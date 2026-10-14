package io.openflux.app

import io.openflux.app.account.LoginUi
import io.openflux.app.account.TelegramLoginContent
import io.openflux.app.components.ScreenScaffold
import kotlin.test.Test

/** The sign-in screen's states, for review by eye (SHOTS=<dir>). */
class TelegramLoginShotsTest {
    private fun shot(name: String, ui: LoginUi, frame: Shots.Frame = Shots.PHONE) =
        Shots.render("login-$name", frame) {
            ScreenScaffold("Вход", onBack = {}) { TelegramLoginContent(ui, onStart = {}, onCancel = {}, onReopen = {}) }
        }

    @Test fun idle() = shot("idle", LoginUi.Idle)
    @Test fun starting() = shot("starting", LoginUi.Starting)
    @Test fun waiting() = shot("waiting", LoginUi.Waiting())
    @Test fun waitingOffline() = shot("waiting-offline", LoginUi.Waiting(hint = "Бот OpenFlux не отвечает. Проверьте сеть."))
    @Test fun preparing() = shot("preparing", LoginUi.Preparing)
    @Test fun denied() = shot("denied", LoginUi.Denied)
    @Test fun expired() = shot("expired", LoginUi.Expired)
    @Test fun failed() = shot("failed", LoginUi.Failed("Эта версия приложения больше не может войти. Обновите приложение"))
    @Test fun waitingSmall() = shot("waiting", LoginUi.Waiting(), Shots.SMALL)
    @Test fun idleWide() = shot("idle", LoginUi.Idle, Shots.WIDE)
}
