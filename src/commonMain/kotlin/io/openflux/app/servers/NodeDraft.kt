package io.openflux.app.servers

import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.NodeDocuments
import io.openflux.desktop.model.NodeMode
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.NodeTransports
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.model.TransportType

/**
 * What the person chose for a node: its name (kept on this device), the transports besides direct (which every
 * node has), the mode, and, for a new node, the server's core updater. Pure data: the editor keeps the
 * original next to it and shows what changed.
 *
 * The Yandex document is one link for both Yandex carriers (Яндекс Документ and the older Yandex Docs).
 * Cups.online rooms are made when the node is applied, so turning Cups on needs nothing typed.
 */
data class NodeDraft(
    val name: String = "",
    val volga: Boolean = false,
    val yandexDocs: Boolean = false,
    val yandexDoc: String = "",
    val mailru: Boolean = false,
    val mailruLink: String = "",
    val board: Boolean = false,
    val boardLink: String = "",
    val cups: Boolean = false,
    val cupsRooms: String = "",
    val mode: NodeMode = NodeMode.L4,
    val autoUpdate: Boolean = true,
) {
    val needsYandexDoc: Boolean get() = volga || yandexDocs

    /** The first thing to fix before applying, in the words the screen shows; null when the draft is fine. */
    fun problem(): String? = when {
        needsYandexDoc && NodeDocuments.clean(yandexDoc) == null -> "Нужна ссылка на документ Яндекса вида https://docs.yandex.ru/edit/d/…"
        mailru && NodeTransports.cleanMailru(mailruLink) == null -> "Нужна публичная ссылка Mail.ru вида https://cloud.mail.ru/public/…/…"
        board && NodeTransports.cleanBoard(boardLink) == null -> "Нужна ссылка на доску вида https://boards.yandex.ru/whiteboard/?hash=…"
        else -> null
    }

    /**
     * Why an installed node ([original]) cannot be changed to this draft on the server, or null. The server's
     * set-transports keeps at least one transport besides direct, so a direct-only node changes only by gaining one.
     */
    fun applyProblem(original: NodeDraft): String? = when {
        !serverChanged(original) -> null
        hasTransport -> problem()
        original.hasTransport -> "Нода должна сохранить хотя бы один транспорт кроме прямого"
        else -> "Режим и транспорты меняются, когда у ноды есть транспорт кроме прямого: включите хотя бы один"
    }

    val hasTransport: Boolean get() = volga || yandexDocs || mailru || board || cups

    /** The transports in priority order (the first is the main one), cleaned; direct is not listed. */
    fun toTransports(): List<NodeTransport> {
        problem()?.let { throw NodeWizardException(it) }
        val doc = NodeDocuments.clean(yandexDoc).orEmpty()
        return buildList {
            if (volga) add(NodeTransport(TransportType.VYANDEX.cliName, doc))
            if (yandexDocs) add(NodeTransport(TransportType.YANDEX.cliName, doc))
            if (mailru) add(NodeTransport(TransportType.MAILRU.cliName, NodeTransports.cleanMailru(mailruLink)!!))
            if (board) add(NodeTransport(TransportType.BOARDS.cliName, NodeTransports.cleanBoard(boardLink)!!))
            if (cups) add(NodeTransport(TransportType.CUPSONLINE.cliName, cupsRooms))
        }
    }

    /** How many things differ from [original]: each transport turned on/off or relinked, the mode, the name. */
    fun changes(original: NodeDraft): Int {
        var n = 0
        if (name.trim() != original.name.trim()) n++
        if (mode != original.mode) n++
        if (volga != original.volga) n++
        if (yandexDocs != original.yandexDocs) n++
        if (needsYandexDoc && original.needsYandexDoc && yandexDoc.trim() != original.yandexDoc.trim()) n++
        if (mailru != original.mailru) n++ else if (mailru && mailruLink.trim() != original.mailruLink.trim()) n++
        if (board != original.board) n++ else if (board && boardLink.trim() != original.boardLink.trim()) n++
        if (cups != original.cups) n++
        return n
    }

    /** Something the server keeps changed (not just the name). */
    fun serverChanged(original: NodeDraft): Boolean = copy(name = original.name).changes(original) > 0

    companion object {
        fun of(info: InstalledChannel, name: String): NodeDraft {
            fun url(type: TransportType) = info.transports.firstOrNull { it.type == type.cliName }?.url
            val volga = url(TransportType.VYANDEX)
            val docs = url(TransportType.YANDEX)
            return NodeDraft(
                name = name,
                volga = volga != null,
                yandexDocs = docs != null,
                yandexDoc = volga ?: docs.orEmpty(),
                mailru = url(TransportType.MAILRU) != null,
                mailruLink = url(TransportType.MAILRU).orEmpty(),
                board = url(TransportType.BOARDS) != null,
                boardLink = url(TransportType.BOARDS).orEmpty(),
                cups = url(TransportType.CUPSONLINE) != null,
                cupsRooms = url(TransportType.CUPSONLINE).orEmpty(),
                mode = info.nodeMode,
            )
        }
    }
}
