package io.openflux.app.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RussianAppsTest {
    @Test
    fun recognizesRussianServicesByPackage() {
        listOf(
            "ru.ozon.app.android",
            "com.wildberries.ru",
            "ru.oneme.app",
            "ru.sberbankmobile",
            "com.idamob.tinkoff.android",
            "ru.vtb24.mobilebanking.android",
            "ru.nspk.mirpay",
            "com.yandex.bank",
            "com.vkontakte.android",
            "com.vk.vkvideo",
            "com.avito.android",
            "com.icemobile.lenta.prod",
            "com.deliveryclub",
            "com.octopod.russianpost.client.android",
            "com.lamoda.lite",
            "com.allgoritm.youla",
            "com.beru.android",
            "com.uma.musicvk",
            "com.apegroup.mcdonaldsrussia",
            "ru.rostel",
            "ru.fns.lkfl",
            "ru.beeline.services",
            "ru.megafon.mlk",
            "ru.rzd.pass",
            "ru.aviasales",
            "ru.dublgis.dgismobile",
            "ru.sportmaster.app",
            "ru.tander.magnit",
            "ru.pyaterochka.app.browser",
            "ru.reksoft.okey",
            "ru.megamarket.marketplace",
            "ru.letu",
            "ru.kfc.kfc_delivery",
            "ru.burgerking",
            "ru.dodopizza.app",
        ).forEach { assertTrue(isRussianApp(it), "$it should be Russian") }
    }

    @Test
    fun ignoresForeignAndUnrelatedApps() {
        listOf(
            "com.google.android.youtube",
            "com.whatsapp",
            "org.telegram.messenger",
            "com.openai.chatgpt",
            "com.deepseek.chat",
            "com.microsoft.office.outlook",
            "com.zhiliaoapp.musically",
            "com.shazam.android",
            "io.openflux.client.dev",
        ).forEach { assertFalse(isRussianApp(it), "$it should not be Russian") }
    }
}
