package io.openflux.app.settings

/** Russian services whose package ids have no shared prefix. */
private val russianPackages = setOf(
    "com.allgoritm.youla",
    "com.apegroup.mcdonaldsrussia",
    "com.beru.android",
    "com.deliveryclub",
    "com.idamob.tinkoff.android",
    "com.octopod.russianpost.client.android",
    "com.uma.musicvk",
    "ru.oneme.app",
)

/** Package families that belong to Russian services. `ru.*` covers most domestic apps. */
private val russianPrefixes = listOf(
    "ru.",
    "com.apegroup.mcdonaldsrussia.",
    "com.allgoritm.youla.",
    "com.avito.",
    "com.icemobile.lenta.",
    "com.lamoda.",
    "com.ozon.",
    "com.tele2.",
    "com.vk.",
    "com.vkontakte.",
    "com.wildberries.",
    "com.yandex.",
)

/** Whether [packageName] belongs to a Russian service, for the split-tunnel picker. */
fun isRussianApp(packageName: String): Boolean {
    val normalized = packageName.trim().lowercase()
    return normalized in russianPackages || russianPrefixes.any(normalized::startsWith)
}
