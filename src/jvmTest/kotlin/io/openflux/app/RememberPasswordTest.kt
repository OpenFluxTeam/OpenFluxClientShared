package io.openflux.app

import io.openflux.app.servers.LoginForm
import io.openflux.app.servers.ServerLink
import io.openflux.desktop.model.NodeWizardException
import io.openflux.desktop.model.SshTarget
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RememberPasswordTest {
    private val a = FakeServer("a.example", password = "pw")
    private val fake = FakeNodeService(a)
    private val platform = TestPlatform()
    private val c = testContainer(node = fake, platform = platform)
    private val link = ServerLink(c)
    private val id = "a.example:22:root"

    init {
        // The server was trusted on the first visit, as the host key dialog does.
        c.settings.update { it.copy(knownHostKeys = mapOf(io.openflux.desktop.model.NodeServers.hostKeyId("a.example", 22) to "SHA256:a.example")) }
    }

    private fun target(pw: String = "pw") = SshTarget("a.example", 22, "root", password = pw, hostKey = "SHA256:a.example")

    @Test
    fun tickedPasswordGoesToTheSecretStoreNotTheSettings() = runTest {
        link.connect(target(), remember = true)
        assertEquals("pw", platform.vault["ssh:$id"])
        assertTrue(link.hasSavedPassword(id))
        assertTrue(c.settings.settings.value.knownServers.single().rememberPassword)
        assertFalse(c.settings.settings.value.toString().contains("pw"), "no password in the settings")
    }

    @Test
    fun untickedStoresNothingAndDeletesTheOldOne() = runTest {
        link.connect(target(), remember = true)
        link.closeAccess(id)
        link.connect(target(), remember = false)
        assertTrue(platform.vault.isEmpty())
        assertFalse(link.hasSavedPassword(id))
    }

    @Test
    fun aWrongPasswordIsNotSaved() = runTest {
        assertFailsWith<NodeWizardException> { link.connect(target("bad"), remember = true) }
        assertTrue(platform.vault.isEmpty())
    }

    @Test
    fun savedPasswordConnectsWithoutTyping() = runTest {
        link.connect(target(), remember = true)
        link.closeAccess(id)
        assertFalse(link.hasAccess(id))
        assertTrue(link.connectSaved(id))
        assertTrue(link.hasAccess(id))
    }

    @Test
    fun aRejectedSavedPasswordIsDeletedAndAskedAgain() = runTest {
        link.connect(target(), remember = true)
        link.closeAccess(id)
        a.password = "changed"
        val e = assertFailsWith<NodeWizardException> { link.connectSaved(id) }
        assertTrue("удалён" in e.message.orEmpty())
        assertTrue(platform.vault.isEmpty())
        assertFalse(link.hasSavedPassword(id))
        assertFalse(link.connectSaved(id), "nothing left to try")
    }

    @Test
    fun forgettingThePasswordKeepsTheServer() = runTest {
        link.connect(target(), remember = true)
        link.forgetPassword(id)
        assertTrue(platform.vault.isEmpty())
        assertEquals(1, c.settings.settings.value.knownServers.size)
        assertFalse(c.settings.settings.value.knownServers.single().rememberPassword)
    }

    @Test
    fun forgettingTheServerForgetsItsPassword() = runTest {
        link.connect(target(), remember = true)
        link.forget(id)
        assertTrue(platform.vault.isEmpty())
    }

    @Test
    fun theFormUsesTheSavedPasswordAndKeepsTheTickPerServer() = runTest {
        link.connect(target(), remember = true)
        link.closeAccess(id)
        val form = LoginForm(link)
        form.prefill(c.settings.settings.value.knownServers.single())
        assertTrue(form.remember)
        assertEquals(id, form.savedServerId())
        assertEquals(id, form.connect())
        assertNull(form.error)
        assertTrue(link.hasAccess(id))
    }

    @Test
    fun theFormUntickedAsksForThePasswordAgain() = runTest {
        link.connect(target(), remember = true)
        link.closeAccess(id)
        val form = LoginForm(link)
        form.prefill(c.settings.settings.value.knownServers.single())
        form.remember = false
        assertNull(form.connect())
        assertEquals("Введите пароль", form.error)
        form.password = "pw"
        assertNotNull(form.connect())
        assertTrue(platform.vault.isEmpty(), "unticked: the saved one is deleted")
    }
}
