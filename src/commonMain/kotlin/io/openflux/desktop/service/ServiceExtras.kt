package io.openflux.desktop.service

import io.openflux.desktop.model.ServiceDefaults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The services only a full build has: the OpenFlux service's subscription and bot, the script transports'
 * updates, the app's own updates. [AppContainer] makes them through [serviceExtras]; where that is null (iOS)
 * nothing refers to their code and the compiler leaves it out of the binary. See io.openflux.app.shell.BuildExtras.
 */
interface ServiceExtras {
    /** What stands for the service's API where the platform gave none. */
    val noSubscriptionApi: SubscriptionApi
    val noBotApi: BotApi
    val noSignIn: SignInSupport
    fun newSubscriptionRepo(): SubscriptionRepository
    fun newBotRepo(): BotRepository

    fun subscription(c: AppContainer): SubscriptionManager
    fun bot(c: AppContainer): BotAccountManager
    fun scriptUpdater(c: AppContainer): ScriptUpdater
    fun updates(c: AppContainer): AppUpdates
}

/** The full build's services; null in a build without them. */
expect val serviceExtras: ServiceExtras?

/** Everything [ServiceExtras] promises. Named only by the builds that have it (the `actual` of [serviceExtras]). */
object FullServiceExtras : ServiceExtras {
    override val noSubscriptionApi: SubscriptionApi get() = NoSubscriptionApi
    override val noBotApi: BotApi get() = NoBotApi
    override val noSignIn: SignInSupport get() = NoSignIn
    override fun newSubscriptionRepo(): SubscriptionRepository = InMemorySubscriptionRepository()
    override fun newBotRepo(): BotRepository = InMemoryBotRepository()

    override fun subscription(c: AppContainer) =
        SubscriptionManager(c.subscriptionApi, c.subscriptionRepo, c.profiles, c.shareCodec, c.platform, c.connection, ServiceDefaults.endpoint)

    override fun bot(c: AppContainer) = BotAccountManager(c.botApi, c.botRepo, c.profiles, c.shareCodec, c.platform, c.connection, c.accounts)

    override fun scriptUpdater(c: AppContainer) = ScriptUpdater(c.scripts, c.platform, c.settings)

    override fun updates(c: AppContainer) = AppUpdates(c.platform, c.settings, CoroutineScope(SupervisorJob() + Dispatchers.Default))
}
