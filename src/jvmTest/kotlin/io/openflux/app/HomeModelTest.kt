package io.openflux.app

import io.openflux.app.home.HomeBanner
import io.openflux.app.home.HomeModel
import io.openflux.app.home.SubLook2
import io.openflux.app.home.look2
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.AuthStatus
import io.openflux.desktop.model.ConnectionMode
import io.openflux.desktop.model.ConnectionState
import io.openflux.desktop.model.PhpNodeRef
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ServiceEndpoint
import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.model.SubscriptionKind
import io.openflux.desktop.model.SubscriptionState
import io.openflux.desktop.model.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class HomeModelTest {
    private val conn = ScriptedConnection()
    private val c = testContainer(connection = conn)
    private val m = HomeModel(c)
    private val a = Profile(id = "a", name = "Амстердам", source = ProfileSource.Subscription)
    private val b = Profile(id = "b", name = "Нода 203.0.113.7", source = ProfileSource.Node)
    private val h = Profile(id = "h", name = "Хостинг", phpNode = PhpNodeRef("https://x", "t"), stream = true)
    private val d = Profile(id = "d", name = "Друг", source = ProfileSource.Link, transport = TransportType.VYANDEX)

    @Test
    fun theChosenProfileOrTheFirst() {
        listOf(a, b).forEach(c.profiles::upsert)
        assertEquals("a", m.selected()?.id)
        c.settings.update { it.copy(selectedProfileId = "b") }
        assertEquals("b", m.selected()?.id)
        c.settings.update { it.copy(selectedProfileId = "gone") }
        assertEquals("a", m.selected()?.id)
    }

    @Test
    fun choosingWhileConnectedSwitches() {
        listOf(a, b).forEach(c.profiles::upsert)
        m.toggle()
        assertEquals("a", (conn.state.value as ConnectionState.Connected).profile.id)
        m.choose(b)
        assertEquals("b", (conn.state.value as ConnectionState.Connected).profile.id)
        m.toggle()
        assertEquals(ConnectionState.Idle, conn.state.value)
        m.choose(a)
        assertEquals(ConnectionState.Idle, conn.state.value, "choosing while off only chooses")
        assertEquals("a", c.settings.settings.value.selectedProfileId)
    }

    @Test
    fun groupsForTheDrawer() {
        listOf(a, b, h, d).forEach(c.profiles::upsert)
        val g = m.groups(c.profiles.profiles.value)
        assertEquals(listOf("Подписка", "Мои серверы", "Остальные"), g.map { it.first })
        assertEquals(listOf("a"), g[0].second.map { it.id })
        assertEquals(listOf("b", "h"), g[1].second.map { it.id })
        assertEquals(listOf("d"), g[2].second.map { it.id })
    }

    @Test
    fun oneBannerByPriority() {
        listOf(d).forEach(c.profiles::upsert)
        val settings = AppSettings()
        val yandexExpired = mapOf(AccountKind.Yandex to AuthStatus.Expired("ivan"))
        val sub = SubscriptionState(ServiceEndpoint("https://s"), SubscriptionKind.Account, status = SubStatus.REVOKED)
        val failed = ConnectionState.Failed(d, "нода не ответила")
        assertIs<HomeBanner.Failed>(m.banner(failed, settings, d, yandexExpired, sub, 0))
        assertIs<HomeBanner.Account>(m.banner(ConnectionState.Idle, settings, d, yandexExpired, sub, 0))
        assertIs<HomeBanner.Subscription>(m.banner(ConnectionState.Idle, settings, d, emptyMap(), sub, 0))
        assertNull(m.banner(ConnectionState.Idle, settings, d, emptyMap(), null, 0))
        val exit = ConnectionState.Connected(d, ConnectionMode.Exit, 0)
        assertIs<HomeBanner.ThisDevice>(m.banner(exit, settings.copy(mode = ConnectionMode.Exit), d, emptyMap(), null, 0))
    }

    @Test
    fun subscriptionLooks() {
        val day = 86_400L
        val now = 1_791_763_200L
        fun s(status: String, expires: Long) = SubscriptionState(ServiceEndpoint("https://s"), SubscriptionKind.Account, status = status, expires = expires)
        assertEquals(SubLook2("Отключена", "Доступ отключён. Обратитесь в поддержку.", true), s(SubStatus.REVOKED, 0).look2(now * 1000, 0))
        assertEquals(SubLook2("Нет подписки", "Подписка не оформлена.", true), s(SubStatus.EXPIRED, 0).look2(now * 1000, 0))
        assertEquals(true, s(SubStatus.ACTIVE, now + 2 * day).look2(now * 1000, 0).attention)
        assertEquals(false, s(SubStatus.ACTIVE, now + 10 * day).look2(now * 1000, 0).attention)
        assertEquals("До 22 октября 2026: осталось 10 дней.", s(SubStatus.ACTIVE, now + 10 * day).look2(now * 1000, 0).line)
    }
}
