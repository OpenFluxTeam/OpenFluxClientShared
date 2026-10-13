package io.openflux.app

import io.openflux.app.account.AuthModel
import io.openflux.app.account.AuthStep
import io.openflux.app.account.SubscriptionModel
import io.openflux.desktop.model.DeviceInfo
import io.openflux.desktop.model.PlansResult
import io.openflux.desktop.model.SubPlan
import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.model.SubscriptionDocument
import io.openflux.desktop.model.SubscriptionException
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountModelsTest {
    private val api = FakeSubApi()
    private val c = testContainer(subscriptions = api)

    @Test
    fun registeringChecksTheFormFirst() = runTest {
        val m = AuthModel(c, this)
        m.step = AuthStep.Register
        m.login = "ab"
        m.register()
        assertEquals("Логин: не короче 3 символов (почта или имя)", m.error)
        m.login = "ivan"; m.password = "short"; m.password2 = "short"
        m.register()
        assertEquals("Пароль: не короче 10 символов", m.error)
        m.password = "longenough1"; m.password2 = "longenough2"
        m.register()
        assertEquals("Пароли не совпадают", m.error)
        m.password = "ivanivanivan"; m.password2 = "ivanivanivan"; m.login = "ivanivanivan"
        m.register()
        assertEquals("Пароль не должен совпадать с логином", m.error)
    }

    @Test
    fun aNewAccountShowsItsRecoveryKeyUntilItIsSaved() = runTest {
        val m = AuthModel(c, this)
        m.step = AuthStep.Register
        m.login = "ivan"; m.password = "longenough1"; m.password2 = "longenough1"
        m.register(); advanceUntilIdle()
        assertEquals("RECOVERY-KEY", m.recoveryKey)
        assertNotNull(c.subscription.state.value)
        assertEquals(false, m.done, "not done until the key is saved")
        m.recoveryKeySaved()
        assertTrue(m.done)
        assertEquals("", m.password, "the form forgets the password")
    }

    @Test
    fun aFullAccountOffersToReplaceADevice() = runTest {
        api.loginFailure = SubscriptionException("device_limit", "Слишком много устройств", listOf(DeviceInfo("d1", "Телефон")))
        val m = AuthModel(c, this)
        m.step = AuthStep.Login
        m.login = "ivan"; m.password = "longenough1"
        m.signIn(); advanceUntilIdle()
        assertEquals(AuthStep.Devices, m.step)
        assertEquals(listOf("d1"), m.devices.map { it.id })
        api.loginFailure = null
        m.signIn(replaceDeviceId = "d1"); advanceUntilIdle()
        assertTrue(api.calls.any { it.startsWith("login:ivan|replace=d1|") })
        assertTrue(m.done)
    }

    @Test
    fun theWholeFlowOfBuying() = runTest {
        api.plans = PlansResult(listOf(SubPlan("month", "Месяц", 30, 2, "299 ₽")))
        val a = AuthModel(c, this).apply { step = AuthStep.Login; login = "ivan"; password = "longenough1" }
        a.signIn(); advanceUntilIdle()
        val s = SubscriptionModel(c, this)
        s.openPlans(); advanceUntilIdle()
        assertEquals("month", s.selectedPlan)
        s.checkout(); runCurrent()
        assertNotNull(s.order)
        api.orderStatus = "paid"
        advanceTimeBy(9_000); runCurrent()
        assertTrue(s.paid, "the poll noticed the payment")
        s.closePlans()
    }

    @Test
    fun changingThePasswordAsksForTheOldOne() = runTest {
        AuthModel(c, this).apply { step = AuthStep.Login; login = "ivan"; password = "longenough1"; signIn() }
        advanceUntilIdle()
        val s = SubscriptionModel(c, this)
        s.changePassword("", "newpassword1", "newpassword1")
        assertEquals("Введите текущий пароль", s.dialogError)
        s.changePassword("longenough1", "newpassword1", "newpassword1"); advanceUntilIdle()
        assertNull(s.dialogError)
        assertEquals("Пароль изменён. На других устройствах войдите заново", s.notice)
    }

    @Test
    fun deletingTheAccountNeedsThePasswordAndSignsOut() = runTest {
        AuthModel(c, this).apply { step = AuthStep.Login; login = "ivan"; password = "longenough1"; signIn() }
        advanceUntilIdle()
        val s = SubscriptionModel(c, this)
        s.deleteAccount(""); advanceUntilIdle()
        assertEquals("Введите пароль", s.dialogError)
        s.deleteAccount("longenough1"); advanceUntilIdle()
        assertNull(c.subscription.state.value)
    }

    @Test
    fun aTrialStarts() = runTest {
        api.document = SubscriptionDocument(status = SubStatus.EXPIRED)
        AuthModel(c, this).apply { step = AuthStep.Login; login = "ivan"; password = "longenough1"; signIn() }
        advanceUntilIdle()
        val s = SubscriptionModel(c, this)
        s.startTrial(); advanceUntilIdle()
        assertNull(s.error)
    }
}
