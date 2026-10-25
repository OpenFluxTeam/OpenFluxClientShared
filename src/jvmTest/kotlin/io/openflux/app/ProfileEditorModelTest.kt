package io.openflux.app

import io.openflux.app.profiles.EditMode
import io.openflux.app.profiles.ProfileEditorModel
import io.openflux.app.profiles.blurb
import io.openflux.app.profiles.transportChoices
import io.openflux.app.servers.title
import io.openflux.desktop.model.ExtraTransport
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.TransportType
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileEditorModelTest {
    private val c = testContainer()
    private val doc = "https://docs.yandex.ru/edit/d/" + "a".repeat(20)

    @Test
    fun aNewProfileNeedsANameAndAValidTransport() = runTest {
        val m = ProfileEditorModel(c, null, this)
        assertNull(m.save())
        assertTrue(m.showProblems)
        assertTrue(m.problems.any { it == "Укажите название" })
        m.edit { it.copy(name = "Нода в Нидерландах", value = doc, secret = "k".repeat(32)) }
        val saved = m.save()!!
        assertEquals("Нода в Нидерландах", saved.name)
        assertEquals(ProfileSource.Manual, saved.source)
        assertEquals(listOf(saved), c.profiles.profiles.value)
    }

    @Test
    fun fluxFastIsOfferedOnlyInASession() {
        assertTrue(TransportType.FLUXFAST in transportChoices(stream = false, session = true))
        assertFalse(TransportType.FLUXFAST in transportChoices(stream = false, session = false))
        assertEquals("FluxFast (UDP)", TransportType.FLUXFAST.title)
        assertEquals("UDP до ноды: переживает смену сети", TransportType.FLUXFAST.blurb())
        val classic = Profile(
            id = "c", name = "C", transport = TransportType.FLUXFAST,
            value = "203.0.113.10:8446", secret = "k".repeat(32),
        )
        assertTrue(classic.problems().any { "Session" in it }, "a classic FluxFast profile must be refused")
    }

    @Test
    fun aSubscriptionsLocationCannotBeEdited() = runTest {
        c.profiles.upsert(Profile(id = "s", name = "Амстердам", source = ProfileSource.Subscription))
        val m = ProfileEditorModel(c, "s", this)
        assertTrue(m.readOnly)
        assertNull(m.save())
    }

    @Test
    fun switchingToNoServerDropsTheKeyAndPicksAHostingTransport() = runTest {
        val m = ProfileEditorModel(c, null, this)
        m.edit { it.copy(name = "x", secret = "k".repeat(32), transport = TransportType.VYANDEX) }
        m.setMode(EditMode.Stream)
        assertEquals("", m.draft.secret)
        assertEquals(TransportType.CUPSONLINE, m.draft.transport)
        assertTrue(m.draft.stream)
    }

    @Test
    fun sessionKeepsExtrasClassicDropsThemOnSave() = runTest {
        val m = ProfileEditorModel(c, null, this)
        m.edit { it.copy(name = "x", value = doc, secret = "k".repeat(32)) }
        m.setMode(EditMode.Session)
        m.addTransport()
        assertEquals(listOf(ExtraTransport(TransportType.DIRECT, priority = 110)), m.draft.extras)
        m.editTransport(1) { it.copy(value = "203.0.113.7:8445") }
        m.setMode(EditMode.Classic)
        assertTrue(m.draft.extras.isEmpty())
        assertEquals(TransportType.VYANDEX, m.draft.transport)
    }

    @Test
    fun directMovesOffClassic() = runTest {
        val m = ProfileEditorModel(c, null, this)
        m.setMode(EditMode.Session)
        m.editTransport(0) { it.copy(type = TransportType.DIRECT) }
        m.setMode(EditMode.Classic)
        assertEquals(TransportType.VYANDEX, m.draft.transport, "direct works only in Session")
    }

    @Test
    fun roomsAndKeysAreMadeForThePerson() = runTest {
        val m = ProfileEditorModel(c, null, this)
        m.edit { it.copy(transport = TransportType.CUPSONLINE) }
        m.makeRooms(0); advanceUntilIdle()
        assertEquals("packed-rooms", m.draft.value)
        m.newKey()
        assertEquals(64, m.draft.secret.length)
    }

    @Test
    fun editingCountsAsUnsavedUntilSaved() = runTest {
        c.profiles.upsert(Profile(id = "p", name = "Друг", value = doc, secret = "k".repeat(32)))
        val m = ProfileEditorModel(c, "p", this)
        assertFalse(m.hasUnsaved)
        m.edit { it.copy(name = "Друг 2") }
        assertTrue(m.hasUnsaved)
        m.save()
        assertFalse(m.hasUnsaved)
        assertEquals("Друг 2", c.profiles.profiles.value.single().name)
    }

    @Test
    fun anUntouchedNewProfileLeavesWithoutAQuestion() = runTest {
        val m = ProfileEditorModel(c, null, this)
        assertFalse(m.hasUnsaved)
        m.edit { it.copy(name = "x") }
        assertTrue(m.hasUnsaved)
    }
}
