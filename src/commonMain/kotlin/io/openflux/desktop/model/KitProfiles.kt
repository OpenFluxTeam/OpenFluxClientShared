package io.openflux.desktop.model

/**
 * A script carrier of a node made without SSH, as the person set it up: [client] is what the client runs it with
 * (the primary parameter, which is the carrier's value, not among them), [node] what the node does when the two
 * ends differ (null: both use [client]).
 */
data class KitScriptSides(val client: Map<String, String>, val node: Map<String, String>?)

/**
 * The owner's profile of a node made without SSH: the key the app made, the carriers the core listed ([kit]) with both
 * sides of each script, and the record of how the node is run ([KitNode]). Session mode, the carriers best first
 * (the first is the main one), no direct carrier: there is no address to dial, and the node needs none.
 */
fun Profile.Companion.ownerOfKit(
    id: String,
    name: String,
    icon: String,
    createdAt: Long,
    channel: NewChannel,
    kit: NodeKit,
    delivery: KitDelivery,
    mode: NodeMode,
    autoUpdate: Boolean,
    scripts: Map<String, KitScriptSides>,
): Profile {
    require(kit.transports.isNotEmpty()) { "У ноды нет ни одного транспорта" }
    val carriers = kit.transports.map { t ->
        val type = TransportType.fromCli(t.type) ?: throw IllegalArgumentException("Неизвестный транспорт ${t.type}")
        if (type == TransportType.SCRIPT) {
            val sides = scripts[t.script]
            ExtraTransport(
                TransportType.SCRIPT, t.url, priority = t.priority, scriptId = t.script,
                settings = sides?.client.orEmpty(), nodeSettings = sides?.node.orEmpty(),
            )
        } else {
            ExtraTransport(type, t.url, priority = t.priority)
        }
    }
    val main = carriers.first()
    return Profile(
        id = id, name = name, icon = icon, createdAt = createdAt,
        transport = main.type, value = main.value, priority = main.priority, scriptId = main.scriptId,
        settings = main.settings, nodeSettings = main.nodeSettings,
        extras = carriers.drop(1),
        secret = channel.key, session = true, context = kit.context,
        source = ProfileSource.Node,
        kitNode = KitNode(channel.id, delivery, mode.cli, autoUpdate),
    )
}

/**
 * What this profile's node runs, as the core's node transports: documents by their links, scripts with the node's side
 * of their settings (the settings both ends share when the ends do not differ). The package and the author's key are
 * the device's own and are filled in by whoever sends it.
 */
fun Profile.nodeTransports(): List<NodeTransport> = carriers.filter { it.type != TransportType.DIRECT && it.type != TransportType.ONEME }.map { c ->
    if (c.type == TransportType.SCRIPT) {
        val side = c.nodeSettings.ifEmpty { c.settings }
        NodeTransport(TransportType.SCRIPT.cliName, c.value, script = c.scriptId, params = if (side.isEmpty()) "" else ScriptSettingsCodec.encode(side))
    } else {
        NodeTransport(c.type.cliName, c.value)
    }
}

/** The installed channel as this profile's node record describes it, for the editor of a node that has no server to read it from. */
fun Profile.installedKit(): InstalledChannel? {
    val kit = kitNode ?: return null
    return InstalledChannel(kit.channel, state = "", port = 0, context = context, transports = nodeTransports(), mode = kit.mode, key = secret)
}

/**
 * Whether [carrier], a carrier of this script, has what the script declares it cannot run without: every required
 * parameter filled, in its settings or, for the profile's own input, in its value. A script that declares nothing
 * required is set up when it has any settings or value at all.
 */
fun InstalledScript.configured(carrier: ExtraTransport): Boolean {
    val required = params.filter { it.required }
    if (required.isEmpty()) return carrier.settings.isNotEmpty() || carrier.value.isNotBlank()
    val primary = primaryParam?.key
    return required.all { p -> (carrier.settings[p.key] ?: if (p.key == primary) carrier.value else null).orEmpty().isNotBlank() }
}

/**
 * A profile made by hand of a script transport whose two ends are set up together (MAX: two accounts) that is not set
 * up: nothing on the other end waits for it, and what the script's wizard asks is asked together with the node, not
 * at connect time. It cannot connect until a node is made for it (the profile editor has given it a key already, so a
 * key says nothing: the script's own settings do). A profile someone handed over, a node's own and one the person
 * filled in themselves (they run the node) are not this.
 */
fun Profile.needsNode(scripts: List<InstalledScript>): Boolean =
    source == ProfileSource.Manual && !stream && carriers.any { c ->
        val script = if (c.type == TransportType.SCRIPT) scripts.firstOrNull { it.id == c.scriptId } else null
        script != null && script.setupPage && !script.configured(c)
    }

/** What the connection says when [needsNode] stops it. */
const val NEEDS_NODE_MESSAGE = "Этому подключению нужна нода: без неё не с кем соединяться. Создайте её в «Серверах» или нажмите «Создать ноду» на карточке профиля."

/** What the connection says of a node's own profile ([ProfileSource.ThisNode]) when it is asked to connect as a client. */
const val NODE_PROFILE_MESSAGE = "Это профиль ноды, подключаться им нельзя: запустите его на экране «Это устройство»."
