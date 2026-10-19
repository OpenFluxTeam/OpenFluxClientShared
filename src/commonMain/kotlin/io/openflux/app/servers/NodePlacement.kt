package io.openflux.app.servers

/**
 * Where a node lives: on a server the app reaches over SSH, or somewhere it does not reach - a machine the person
 * runs a command on ([Manual]), another device with the app ([Device]), this device ([Here]). The transports, the
 * mode, the wizards are the same editor either way; what differs is how the node gets installed and started.
 * A placement is named in the editor's server id; a real server's id is "host:port:user", never one of these.
 */
enum class NodePlacement(val id: String?, val title: String, val about: String) {
    Ssh(null, "Сервер по SSH", "Приложение зайдёт на сервер, поставит и запустит ноду само."),
    Manual("kit:manual", "Свой компьютер: команда", "Приложение даст команду: выполните её на машине, где будет нода."),
    Device("kit:device", "Другое устройство с OpenFlux", "Профиль ноды перейдёт на другое устройство (QR или строка), и оно станет нодой."),
    Here("kit:here", "Это устройство", "Это устройство станет нодой, а профиль для подключения можно отдать другим.");

    /** Needs no server: made from a kit, not installed over SSH. */
    val kit: Boolean get() = this != Ssh

    /** How the node's default name says where it is. */
    val short: String
        get() = when (this) {
            Ssh -> ""
            Manual -> "команда"
            Device -> "другое устройство"
            Here -> "это устройство"
        }

    /** What the node's profile records about how it is run. */
    val delivery: io.openflux.desktop.model.KitDelivery?
        get() = when (this) {
            Ssh -> null
            Manual -> io.openflux.desktop.model.KitDelivery.Manual
            Device -> io.openflux.desktop.model.KitDelivery.Device
            Here -> io.openflux.desktop.model.KitDelivery.Here
        }

    companion object {
        fun of(serverId: String): NodePlacement = entries.firstOrNull { it.id != null && it.id == serverId } ?: Ssh

        /** The placement of a node whose profile says how it is run. */
        fun of(delivery: io.openflux.desktop.model.KitDelivery): NodePlacement = entries.first { it.delivery == delivery }
    }
}
