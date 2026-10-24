package io.openflux.desktop.model

import kotlinx.serialization.Serializable

/**
 * The node's own word on one document's sign-in (node-install.sh show, "signin"): [state] "login" when the
 * service asks the node to sign in, "down" when the document has not connected since [since] (Unix seconds).
 * An older node says nothing: all is taken as well.
 */
@Serializable
data class NodeSignInState(val state: String = "", val since: Long = 0, val doc: String = "")

/**
 * What the app last gave one node for one service ([NodeSignInPolicy.key]): a fingerprint of the sign-in's
 * login cookies (SHA-256, never the values), when, and how the last tries went. A node is kept signed in only
 * once the owner gave it a sign-in from this app (the wizard, or «Передать вход … ноде»): its record says so.
 */
@Serializable
data class NodeSignInRecord(
    val fingerprint: String = "",
    val pushedAt: Long = 0,
    val lastTryAt: Long = 0,
    /** Tries in a row that failed (no access, the node refused); spaces the next one out. */
    val failures: Int = 0,
    /** Pushes in a row of the same sign-in because the node still said it was signed out. */
    val repeats: Int = 0,
)

/** One step of keeping a node's sign-in fresh. */
sealed interface NodeSignInDecision {
    /** Nothing to do now; [why] for the log. */
    data class Skip(val why: String, val problem: Boolean = false) : NodeSignInDecision
    /** The account session was not checked lately: check it with the service first. */
    data object Recheck : NodeSignInDecision
    data class Push(val why: String) : NodeSignInDecision
}

expect fun sha256Hex(text: String): String

/**
 * When the app sends a node the owner's saved sign-in again: only for a node and service it gave one before,
 * only a sign-in that is valid and was checked lately, never a Yandex one to a Mail.ru document or the reverse,
 * and then only when the account's sign-in changed since the last push or the node says its document is signed
 * out (or has been down for [DOWN_GRACE_S]) — not every N minutes.
 */
object NodeSignInPolicy {
    /** An account session checked longer ago is checked again before it goes to a node. */
    const val FRESH_MS = 60 * 60 * 1000L
    /** A document down for less is not a sign-in problem yet (a restart, a reconnect; the node writes its state every 5 min). */
    const val DOWN_GRACE_S = 15 * 60L
    /** The first wait after a failed try; it doubles up to [MAX_RETRY_MS]. */
    const val RETRY_MS = 30 * 60 * 1000L
    const val MAX_RETRY_MS = 12 * 60 * 60 * 1000L
    /** The same sign-in is pushed at most this many times to a node that keeps saying it is signed out. */
    const val MAX_REPEATS = 2
    /** How often the app looks at its nodes while it runs (and at once when a sign-in changes). */
    const val PERIOD_MS = 30 * 60 * 1000L

    fun key(serverId: String, channel: String, kind: AccountKind) = "$serverId|$channel|${kind.name}"

    /** The login cookies of [kind] in [cookies]: the ones that change when the account signs in again. */
    fun loginCookies(kind: AccountKind, cookies: Map<String, String>): Map<String, String> {
        val names = when (kind) {
            AccountKind.Yandex -> setOf("Session_id", "sessionid2", "sessar", "sessguard", "L", "yandex_login")
            AccountKind.Mailru -> setOf("Mpop", "t", "sdcs", "ssdc", "act")
            AccountKind.Max -> emptySet()
        }
        return cookies.filterKeys { it in names }
    }

    /** A fingerprint of [kind]'s login in [cookies]; "" when there is none. Never the values themselves. */
    fun fingerprint(kind: AccountKind, cookies: Map<String, String>): String {
        val login = loginCookies(kind, cookies)
        if (!AccountCookies.signedIn(kind, login)) return ""
        return sha256Hex(login.entries.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" })
    }

    /** The documents of [channel] that [kind]'s sign-in opens. */
    fun documents(channel: InstalledChannel, kind: AccountKind): List<String> =
        channel.transports.filter { t -> TransportType.fromCli(t.type)?.let { it in kind.signedInTransports } == true }.map { it.url }

    fun retryWait(failures: Int): Long {
        var wait = RETRY_MS
        repeat((failures - 1).coerceAtLeast(0)) { wait = (wait * 2).coerceAtMost(MAX_RETRY_MS) }
        return wait
    }

    fun decide(
        kind: AccountKind,
        session: AccountSession?,
        channel: InstalledChannel,
        record: NodeSignInRecord?,
        nowMs: Long,
    ): NodeSignInDecision {
        if (record == null) return NodeSignInDecision.Skip("нода не получала вход в ${kind.label} из этого приложения")
        val docs = documents(channel, kind)
        if (docs.isEmpty()) return NodeSignInDecision.Skip("у ноды нет документа, который открывает вход в ${kind.label}")
        if (session == null || session.expired || !AccountCookies.signedIn(kind, session.cookies)) {
            return NodeSignInDecision.Skip("нет действующего входа в ${kind.label}: войдите в Настройках → Сервисы", problem = true)
        }
        val fp = fingerprint(kind, session.cookies)
        val changed = fp != record.fingerprint
        val state = channel.signin.filter { it.doc in docs }
        val signedOut = state.any { it.state == "login" }
        val longDown = state.any { it.state == "down" && nowMs / 1000 - it.since >= DOWN_GRACE_S }
        if (!changed && !signedOut && !longDown) return NodeSignInDecision.Skip("вход в ${kind.label} на ноде свежий")
        if (record.failures > 0 && nowMs - record.lastTryAt < retryWait(record.failures)) {
            return NodeSignInDecision.Skip("прошлая попытка не удалась, повторю позже")
        }
        if (!changed) {
            // The node's word comes from before the last push: it has not looked again yet.
            val sinceMs = state.minOf { it.since } * 1000
            if (record.lastTryAt > 0 && sinceMs < record.lastTryAt) return NodeSignInDecision.Skip("нода ещё не проверила переданный вход")
            if (record.repeats >= MAX_REPEATS) {
                return NodeSignInDecision.Skip("нода не принимает вход в ${kind.label}: войдите в ${kind.label} заново в Настройках → Сервисы", problem = true)
            }
        }
        if (nowMs - session.checkedAt > FRESH_MS) return NodeSignInDecision.Recheck
        return NodeSignInDecision.Push(
            when {
                changed -> "вход в ${kind.label} в приложении обновился"
                signedOut -> "нода сообщает, что вход в ${kind.label} истёк"
                else -> "документ сервиса ${kind.label} на ноде не работает с ${state.minOf { it.since }}"
            },
        )
    }

    /** The record after a push of [session] went through. */
    fun pushed(record: NodeSignInRecord, kind: AccountKind, session: AccountSession, nowMs: Long): NodeSignInRecord {
        val fp = fingerprint(kind, session.cookies)
        return NodeSignInRecord(
            fingerprint = fp, pushedAt = nowMs, lastTryAt = nowMs, failures = 0,
            repeats = if (fp == record.fingerprint) record.repeats + 1 else 0,
        )
    }

    fun failed(record: NodeSignInRecord, nowMs: Long) = record.copy(lastTryAt = nowMs, failures = record.failures + 1)

    /** A node that reports nothing wrong resets the count of repeated pushes. */
    fun settled(record: NodeSignInRecord) = if (record.repeats == 0) record else record.copy(repeats = 0)
}
