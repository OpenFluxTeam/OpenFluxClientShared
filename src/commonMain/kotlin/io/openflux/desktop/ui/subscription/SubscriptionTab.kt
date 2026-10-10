package io.openflux.desktop.ui.subscription

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
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import io.openflux.desktop.service.LocalAppContainer
import io.openflux.desktop.ui.LocalScrollbars
import io.openflux.desktop.ui.LocalTouchUi
import io.openflux.desktop.ui.PlatformBackHandler
import io.openflux.desktop.ui.components.AppIcons
import io.openflux.desktop.ui.profiles.ProfilesTab
import io.openflux.desktop.ui.shell.LocalShell
import io.openflux.desktop.ui.theme.AppTheme
import org.jetbrains.compose.resources.painterResource

/** "Подписка": adding a connection (a key, an account) and, once there is one, the account. */
object SubscriptionTab : Tab {
    override val options: TabOptions
        @Composable get() = TabOptions(index = 7u, title = "Подписка", icon = painterResource(AppIcons.Person))

    @Composable
    override fun Content() {
        val container = LocalAppContainer.current
        val model = rememberScreenModel { SubscriptionScreenModel(container) }
        SubscriptionScreen(model)
    }
}

@Composable
private fun SubscriptionScreen(model: SubscriptionScreenModel) {
    val container = LocalAppContainer.current
    val shell = LocalShell.current
    val touch = LocalTouchUi.current
    val state by model.manager.state.collectAsState()
    val profiles by container.profiles.profiles.collectAsState()
    val pendingKey by container.pendingKey.collectAsState()
    val platform = container.platform

    val now = rememberNowMillis(platform)
    // "Войти или создать аккаунт" on Home opens the sign-in choice at once.
    LaunchedEffect(shell.accountRequested) {
        if (shell.accountRequested) { shell.accountRequested = false; if (state == null) model.go(AuthStep.Choose) }
    }
    // A key that came from outside (a link opened in the app): the field takes it.
    LaunchedEffect(pendingKey) {
        val key = pendingKey ?: return@LaunchedEffect
        container.pendingKey.value = null
        model.keyChanged(key)
    }
    // A profile link pasted in the key field goes to the Profiles screen's import.
    val toProfiles: (String) -> Unit = { link ->
        container.incomingLink.value = link
        shell.open(ProfilesTab)
    }

    val signedOut = state == null
    PlatformBackHandler(enabled = touch && signedOut && model.step != AuthStep.Add && model.recoveryKey == null) { model.go(AuthStep.Add) }

    val scroll = rememberScrollState()
    val scrollbars = LocalScrollbars.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = AppTheme.spacing.page, vertical = AppTheme.spacing.xl)) {
            val recovery = model.recoveryKey
            when {
                recovery != null -> RecoveryKeyScreen(model, recovery, trialText(state?.let { it.look(now, platform.utcOffsetSeconds).line }))
                state != null -> AccountScreen(model, state!!, profiles.filter { it.source == io.openflux.desktop.model.ProfileSource.Subscription }.map { it.name }, now)
                else -> when (model.step) {
                    AuthStep.Add -> AddConnectionPage(
                        model,
                        AddConnectionActions(
                            onAccount = { model.go(AuthStep.Choose) },
                            onRestore = { model.go(AuthStep.Recover) },
                            onOwnNode = { shell.nodeWizardRequested = true; shell.open(ProfilesTab) },
                            onNoServer = { shell.phpWizardRequested = true; shell.open(ProfilesTab) },
                            onManual = { shell.newProfileRequested = true; shell.open(ProfilesTab) },
                            onProfileLink = toProfiles,
                        ),
                    )
                    AuthStep.Choose -> AuthChoose(model)
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

/** "Пробный период: до 14 октября: осталось 3 дня" for the line under the recovery key. */
private fun trialText(line: String?): String? = line?.trimEnd('.')?.replaceFirstChar { it.lowercase() }
