package io.openflux.app.servers

import io.openflux.desktop.model.InstalledChannel
import io.openflux.desktop.model.NodeDocuments
import io.openflux.desktop.model.NodeMode
import io.openflux.desktop.model.NodeTransport
import io.openflux.desktop.model.NodeTransports
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ScriptSettingsCodec
import io.openflux.desktop.model.TransportType

/**
 * What the person chose for a node: its name (kept on this device), the transports besides direct (which every
 * node has), the mode, and, for a new node, the server's core updater. Pure data: the editor keeps the
 * original next to it and shows what changed.
 *
 * The Yandex document is one link for both Yandex carriers (Яндекс Документ and the older Yandex Docs).
 * Cups.online and Avito rooms are made when the node is applied, so turning them on needs nothing typed.
 * JS transports are the packages installed on this device ([NodeScript]); the app sends each package to the node.
 */
/**
 * A JS transport on the node: the installed package [id], the value of its primary parameter and its other settings.
 * [settings] are what the CLIENT runs it with (they end up in the person's profile). A transport whose two ends are
 * set up together and differ (MAX: two accounts) also has [nodeSettings], what the NODE runs it with; null means both
 * ends use [settings].
 */
data class NodeScript(
    val id: String,
    val value: String = "",
    val settings: Map<String, String> = emptyMap(),
    val nodeSettings: Map<String, String>? = null,
    /** Its script has a setup wizard of its own: it is not usable before the wizard has handed back its sides. */
    val wizard: Boolean = false,
) {
    /** What the node runs it with. */
    val forNode: Map<String, String> get() = nodeSettings ?: settings

    /** The ends differ: it was set up by the script's own wizard, which handed back both sides. */
    val split: Boolean get() = nodeSettings != null
}

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
    val telemost: Boolean = false,
    val telemostLink: String = "",
    val avito: Boolean = false,
    val avitoRooms: String = "",
    val scripts: List<NodeScript> = emptyList(),
    val mode: NodeMode = NodeMode.L4,
    val autoUpdate: Boolean = true,
) {
    val needsYandexDoc: Boolean get() = volga || yandexDocs

    /** The first thing to fix before applying, in the words the screen shows; null when the draft is fine. */
    fun problem(): String? = when {
        needsYandexDoc && NodeDocuments.clean(yandexDoc) == null -> "Нужна ссылка на документ Яндекса вида https://docs.yandex.ru/edit/d/…"
        mailru && NodeTransports.cleanMailru(mailruLink) == null -> "Нужна публичная ссылка Mail.ru вида https://cloud.mail.ru/public/…/…"
        board && NodeTransports.cleanBoard(boardLink) == null -> "Нужна ссылка на доску вида https://boards.yandex.ru/whiteboard/?hash=…"
        telemost && NodeTransports.cleanTelemost(telemostLink) == null -> "Нужна ссылка на конференцию Телемоста вида https://telemost.yandex.ru/j/…"
        else -> scripts.firstNotNullOfOrNull { s ->
            if (s.wizard && s.settings.isEmpty() && s.nodeSettings == null) "JS-транспорт ${s.id}: пройдите мастер настройки"
            else NodeTransports.scriptValueProblem(s.value)?.let { "JS-транспорт ${s.id}: параметр $it" }
        }
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

    val hasTransport: Boolean get() = volga || yandexDocs || mailru || board || cups || telemost || avito || scripts.isNotEmpty()

    /** The transports in priority order (the first is the main one), cleaned; direct is not listed. */
    fun toTransports(): List<NodeTransport> {
        problem()?.let { throw NodeWizardException(it) }
        val doc = NodeDocuments.clean(yandexDoc).orEmpty()
        return buildList {
            if (volga) add(NodeTransport(TransportType.VYANDEX.cliName, doc))
            if (yandexDocs) add(NodeTransport(TransportType.YANDEX.cliName, doc))
            if (mailru) add(NodeTransport(TransportType.MAILRU.cliName, NodeTransports.cleanMailru(mailruLink)!!))
            if (board) add(NodeTransport(TransportType.BOARDS.cliName, NodeTransports.cleanBoard(boardLink)!!))
            if (telemost) add(NodeTransport(TransportType.TELEMOST.cliName, NodeTransports.cleanTelemost(telemostLink)!!))
            if (avito) add(NodeTransport(TransportType.AVITO.cliName, avitoRooms))
            if (cups) add(NodeTransport(TransportType.CUPSONLINE.cliName, cupsRooms))
            // The package, the key and the settings line come from this device's install (NodeEditorModel).
            for (s in scripts) add(NodeTransport(TransportType.SCRIPT.cliName, s.value.trim(), script = s.id, params = ScriptSettingsCodec.encode(s.forNode).takeIf { s.forNode.isNotEmpty() }.orEmpty()))
        }
    }

    /** How many things differ from [original]: each transport turned on/off, relinked or given other rooms, the mode, the name. */
    fun changes(original: NodeDraft): Int {
        var n = 0
        if (name.trim() != original.name.trim()) n++
        if (mode != original.mode) n++
        if (volga != original.volga) n++
        if (yandexDocs != original.yandexDocs) n++
        if (needsYandexDoc && original.needsYandexDoc && yandexDoc.trim() != original.yandexDoc.trim()) n++
        if (mailru != original.mailru) n++ else if (mailru && mailruLink.trim() != original.mailruLink.trim()) n++
        if (board != original.board) n++ else if (board && boardLink.trim() != original.boardLink.trim()) n++
        if (cups != original.cups) n++ else if (cups && cupsRooms.trim() != original.cupsRooms.trim()) n++
        if (telemost != original.telemost) n++ else if (telemost && telemostLink.trim() != original.telemostLink.trim()) n++
        if (avito != original.avito) n++ else if (avito && avitoRooms.trim() != original.avitoRooms.trim()) n++
        val before = original.scripts.associateBy { it.id }
        val after = scripts.associateBy { it.id }
        n += (before.keys + after.keys).count {
            before[it]?.value?.trim() != after[it]?.value?.trim() || before[it]?.settings != after[it]?.settings ||
                before[it]?.nodeSettings != after[it]?.nodeSettings
        }
        return n
    }

    /**
     * Something the server keeps changed (not just the name). The client's side of a transport whose ends differ stays
     * on this device, so changing only that is no change for the server.
     */
    fun serverChanged(original: NodeDraft): Boolean {
        val keepClientSide = scripts.map { s ->
            val was = original.scripts.firstOrNull { it.id == s.id }
            if (s.split && was != null) s.copy(settings = was.settings) else s
        }
        return copy(name = original.name, scripts = keepClientSide).changes(original) > 0
    }

    companion object {
        /**
         * The draft for a node made for a profile that exists already: its carriers are the start, so what the person
         * set up there (documents, a script's own settings) is not asked for again. [wizardScripts] are the ids of the
         * scripts with a setup wizard of their own; the profile's settings of those are the client's side.
         */
        fun fromProfile(profile: Profile, wizardScripts: Set<String> = emptySet()): NodeDraft {
            var d = NodeDraft(name = profile.name)
            for (c in profile.carriers) {
                val v = c.value.trim()
                d = when (c.type) {
                    TransportType.VYANDEX -> d.copy(volga = true, yandexDoc = d.yandexDoc.ifEmpty { v })
                    TransportType.YANDEX -> d.copy(yandexDocs = true, yandexDoc = d.yandexDoc.ifEmpty { v })
                    TransportType.MAILRU -> d.copy(mailru = true, mailruLink = v)
                    TransportType.BOARDS -> d.copy(board = true, boardLink = v)
                    TransportType.CUPSONLINE -> d.copy(cups = true, cupsRooms = v)
                    TransportType.TELEMOST -> d.copy(telemost = true, telemostLink = v)
                    TransportType.AVITO -> d.copy(avito = true, avitoRooms = v)
                    TransportType.SCRIPT ->
                        if (c.scriptId.isEmpty()) d
                        else d.copy(scripts = d.scripts + NodeScript(c.scriptId, v, c.settings, c.nodeSettings.takeIf { it.isNotEmpty() }, wizard = c.scriptId in wizardScripts))
                    // direct is the node's own, and the token transport is not one a node can run
                    TransportType.DIRECT, TransportType.ONEME -> d
                }
            }
            return d
        }

        /**
         * The draft of an installed node. The server keeps only the node's side of a transport; [clientSides] are what
         * this device kept of the client's side by script id (from the node's profile here), and [splitScripts] the
         * scripts whose ends differ: for those the server's settings are the node's side and the client's come from
         * [clientSides] (none: the person has not set up this device's side yet).
         */
        fun of(
            info: InstalledChannel,
            name: String,
            clientSides: Map<String, Map<String, String>> = emptyMap(),
            splitScripts: Set<String> = emptySet(),
        ): NodeDraft {
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
                telemost = url(TransportType.TELEMOST) != null,
                telemostLink = url(TransportType.TELEMOST).orEmpty(),
                avito = url(TransportType.AVITO) != null,
                avitoRooms = url(TransportType.AVITO).orEmpty(),
                scripts = info.transports.filter { it.type == TransportType.SCRIPT.cliName && it.script.isNotEmpty() }
                    .map {
                        val onServer = ScriptSettingsCodec.decode(it.params)
                        if (it.script in splitScripts) NodeScript(it.script, it.url, clientSides[it.script].orEmpty(), onServer, wizard = true)
                        else NodeScript(it.script, it.url, onServer)
                    },
                mode = info.nodeMode,
            )
        }
    }
}
