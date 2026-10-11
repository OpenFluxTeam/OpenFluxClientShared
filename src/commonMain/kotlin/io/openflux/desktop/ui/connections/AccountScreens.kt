package io.openflux.desktop.ui.connections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.ui.LocalScrollbars
import io.openflux.desktop.ui.LocalTouchUi
import io.openflux.desktop.ui.PlatformBackHandler
import io.openflux.desktop.ui.nav.DetailScreen
import io.openflux.desktop.ui.nav.Dest
import io.openflux.desktop.ui.nav.LocalActions
import io.openflux.desktop.ui.nav.LocalModels
import io.openflux.desktop.ui.nav.LocalNav
import io.openflux.desktop.ui.subscription.AccountScreen
import io.openflux.desktop.ui.subscription.AuthChoose
import io.openflux.desktop.ui.subscription.AuthDevices
import io.openflux.desktop.ui.subscription.AuthLogin
import io.openflux.desktop.ui.subscription.AuthRecover
import io.openflux.desktop.ui.subscription.AuthRegister
import io.openflux.desktop.ui.subscription.AuthStep
import io.openflux.desktop.ui.subscription.RecoveryKeyScreen
import io.openflux.desktop.ui.subscription.look
import io.openflux.desktop.ui.subscription.rememberNowMillis
import io.openflux.desktop.ui.theme.AppTheme

/** Signing in to the OpenFlux account: choose, register, sign in, recover; one screen with its own steps. */
@Composable
fun AuthScreen() {
    val models = LocalModels.current
    val actions = LocalActions.current
    val nav = LocalNav.current
    val model = models.account
    val container = LocalAppContainer.current
    val touch = LocalTouchUi.current
    val state by model.manager.state.collectAsState()
    val platform = container.platform
    val now = rememberNowMillis(platform)
    val recovery = model.recoveryKey

    // Signed in (and the recovery key, if it was shown, written down): the account takes this screen's place.
    LaunchedEffect(state != null, recovery == null) {
        if (state != null && recovery == null && nav.top == Dest.Auth) nav.replaceTop(Dest.Account)
    }
    // A step back goes up the flow first, then leaves it.
    PlatformBackHandler(enabled = touch && recovery == null) {
        when (model.step) {
            AuthStep.Register, AuthStep.Login, AuthStep.Recover -> model.go(AuthStep.Choose)
            AuthStep.Devices -> model.go(AuthStep.Login)
            else -> actions.back()
        }
    }
    val scroll = rememberScrollState()
    val scrollbars = LocalScrollbars.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = AppTheme.spacing.page, vertical = AppTheme.spacing.xl)) {
            when {
                recovery != null -> RecoveryKeyScreen(model, recovery, trialText(state?.look(now, platform.utcOffsetSeconds)?.line))
                else -> when (model.step) {
                    AuthStep.Add, AuthStep.Choose -> AuthChoose(model, onBack = actions::back)
                    AuthStep.Register -> AuthRegister(model)
                    AuthStep.Login -> AuthLogin(model)
                    AuthStep.Recover -> AuthRecover(model)
                    AuthStep.Devices -> AuthDevices(model, now)
                }
            }
        }
        scrollbars.Vertical(scroll, Modifier.align(Alignment.CenterEnd))
    }
}

/** The signed-in account: the subscription's state and what is done with it. */
@Composable
fun AccountScreenHost() {
    val models = LocalModels.current
    val actions = LocalActions.current
    val container = LocalAppContainer.current
    val model = models.account
    val state by model.manager.state.collectAsState()
    val profiles by container.profiles.profiles.collectAsState()
    val now = rememberNowMillis(container.platform)
    val current = state
    if (current == null) {
        // Signed out (or the account was deleted) while this screen was open: nothing left to show.
        LaunchedEffect(Unit) { actions.back() }
        return
    }
    DetailScreen(title = "Подписка", maxWidth = 640.dp) {
        AccountScreen(model, current, profiles.filter { it.source == ProfileSource.Subscription }.map { it.name }, now)
    }
}

/** "Пробный период: до 14 октября: осталось 3 дня" for the line under the recovery key. */
private fun trialText(line: String?): String? = line?.trimEnd('.')?.replaceFirstChar { it.lowercase() }
