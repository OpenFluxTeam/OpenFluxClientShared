package io.openflux.desktop.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

/**
 * A profile handed to another device of the same person, as a line of text (a QR, a link, the clipboard, a file):
 * the node's side to the device that will be the node ([Role.Node]), the client's side to another device that
 * connects through it ([Role.Client]). `openflux://` links cannot carry a script transport (its two ends differ, and
 * its settings hold an account); this can, and keeps each end to what it needs: the client's copy has none of the
 * node account's token, the node's copy none of the client account's.
 *
 * The line holds secrets (the channel's key, an account's token): it is for the person's own devices, not for sharing.
 * A script carrier names the script by its id and author key ([Received.scriptKeys]); the receiving device must
 * have that script installed under that key, which is how it trusts the code that will run.
 */
object ProfileTransfer {
    const val PREFIX = "openflux-profile:1:"

    /** A QR holds about this much and still scans. */
    const val QR_LIMIT = 2200

    enum class Role(val code: String) {
        Client("client"), Node("node");

        companion object {
            fun of(code: String) = entries.firstOrNull { it.code == code }
        }
    }

    /** What arrived: the role it is for, the profile (no id yet) and the author keys of its script carriers by script id. */
    data class Received(val role: Role, val profile: Profile, val scriptKeys: Map<String, String>)

    @Serializable
    private data class Envelope(val v: Int, val role: String, val profile: Profile, val scripts: Map<String, String> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    /** Whether [text] looks like one (a paste of anything else is not told "damaged"). */
    fun isTransfer(text: String): Boolean = text.trim().startsWith("openflux-profile:")

    /** The profile as the client's copy: nothing of the node's side, and no record of how the node is run. */
    fun forClient(p: Profile): Profile = p.copy(
        id = "", createdAt = 0, source = ProfileSource.Link, kitNode = null, phpNode = null,
        nodeSettings = emptyMap(), extras = p.extras.map { it.copy(nodeSettings = emptyMap()) },
    )

    /**
     * The profile as the node's copy: where the ends differ the carrier runs with the node's side (it is the carrier's
     * plain settings there), the client's side is gone, and it is a node profile ([ProfileSource.ThisNode]).
     */
    fun forNode(p: Profile): Profile {
        fun side(settings: Map<String, String>, node: Map<String, String>) = node.ifEmpty { settings }
        return p.copy(
            id = "", createdAt = 0, source = ProfileSource.ThisNode, kitNode = null, phpNode = null,
            settings = side(p.settings, p.nodeSettings), nodeSettings = emptyMap(),
            extras = p.extras.map { it.copy(settings = side(it.settings, it.nodeSettings), nodeSettings = emptyMap()) },
        )
    }

    /** The line for [role]; [scriptKeys] are the author keys of the installed scripts the carriers use, by script id. */
    fun encode(profile: Profile, role: Role, scriptKeys: Map<String, String> = emptyMap()): String {
        val copy = if (role == Role.Node) forNode(profile) else forClient(profile)
        val used = copy.carriers.mapNotNull { it.scriptId.takeIf(String::isNotEmpty) }.toSet()
        val envelope = Envelope(1, role.code, copy, scriptKeys.filterKeys { it in used })
        return PREFIX + base64.encode(json.encodeToString(Envelope.serializer(), envelope).encodeToByteArray())
    }

    /** What [text] holds; throws IllegalArgumentException with what to tell the person. */
    fun decode(text: String): Received {
        val line = text.trim()
        require(line.startsWith("openflux-profile:")) { "Это не профиль OpenFlux для другого устройства" }
        val version = line.removePrefix("openflux-profile:").substringBefore(':').toIntOrNull()
        require(version == 1) { "Профиль сделан более новой версией приложения: обновите OpenFlux" }
        val envelope = try {
            json.decodeFromString(Envelope.serializer(), base64.decode(line.removePrefix(PREFIX)).decodeToString())
        } catch (e: Exception) {
            throw IllegalArgumentException("Профиль повреждён или обрезан: скопируйте его заново")
        }
        require(envelope.v == 1) { "Профиль сделан более новой версией приложения: обновите OpenFlux" }
        val role = Role.of(envelope.role) ?: throw IllegalArgumentException("Профиль для неизвестной роли «${envelope.role}»")
        val p = envelope.profile
        require(p.session && p.secret.length >= Profile.MIN_SECRET) { "В профиле нет ключа ноды" }
        require(p.carriers.isNotEmpty() && p.carriers.none { it.type == TransportType.DIRECT && it.value.isBlank() }) { "В профиле нет транспортов" }
        // Whatever the sender wrote, the copy is of the role it was received as.
        val received = if (role == Role.Node) forNode(p) else forClient(p)
        return Received(role, received, envelope.scripts)
    }
}
