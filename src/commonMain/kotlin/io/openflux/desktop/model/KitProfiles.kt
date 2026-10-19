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
