package io.nekohasekai.sagernet.simple.home

import io.nekohasekai.sagernet.bg.BaseService
import java.util.Locale

/** What the connect button and the status line of the home screen show (simple-ui spec "Кнопка подключения"). */
enum class ConnectionPhase {
    /** «Выключен». */
    OFF,

    /** «Подключение»: the button cancels. */
    CONNECTING,

    /** «Защищено», with the connection time and the speed. */
    PROTECTED,

    /** «Переподключение»: the service replaces its core in place (a switch or a reload); the button disconnects. */
    RECONNECTING,

    /** The user asked to disconnect and the service is still stopping; the button waits. */
    DISCONNECTING,
}

/** What the body of the home screen shows. */
enum class HomeContent {
    /** The profiles are not read yet. */
    LOADING,

    /** «Серверов пока нет» with the way to add one. */
    NO_SERVERS,

    /** The connect button, the status and the cards. */
    CONTROLS,
}

/** The problem banner of the home screen (simple-ui spec "Баннер проблемы на главной"). */
sealed interface HomeProblem {
    /** The service stopped by itself (a failed start, a crash of `:bg`); [detail] is the service's message, if any. */
    data class ConnectionLost(val detail: String?) : HomeProblem

    /** Connected, but the current server (or every server of the auto-selector) does not answer. */
    data object ServerNotResponding : HomeProblem
}

/**
 * The pure rules of the home screen, kept apart from Android so that JVM unit tests cover them (design.md D10).
 */
object HomeStatus {

    /** Failed URL tests in a row before the banner says the server does not answer (one failure can be a blip). */
    const val FAILURES_FOR_BANNER = 2

    /**
     * The phase for the service [state].
     *
     * [userStopping]: this screen asked the service to stop and it has not stopped yet.
     * [restartPending]: the connection outlives this core (the service keeps the connection start between a Stopped
     * of a restart and the next Connected, see SimpleHooks), so Stopped/Connecting are a reconnect, not a fresh start.
     * [previous]: the phase shown so far; a Stopping nobody here asked for keeps it until the next state tells a
     * restart (Connecting) from a stop (Stopped).
     */
    fun phase(
        state: BaseService.State,
        userStopping: Boolean,
        restartPending: Boolean,
        previous: ConnectionPhase,
    ): ConnectionPhase = when (state) {
        BaseService.State.Connected -> ConnectionPhase.PROTECTED
        BaseService.State.Connecting -> if (restartPending && !userStopping) {
            ConnectionPhase.RECONNECTING
        } else {
            ConnectionPhase.CONNECTING
        }

        BaseService.State.Stopping -> when {
            userStopping -> ConnectionPhase.DISCONNECTING
            previous == ConnectionPhase.PROTECTED || previous == ConnectionPhase.RECONNECTING -> previous
            else -> ConnectionPhase.OFF
        }

        BaseService.State.Stopped -> if (restartPending && !userStopping) ConnectionPhase.RECONNECTING else ConnectionPhase.OFF
        BaseService.State.Idle -> ConnectionPhase.OFF
    }

    /**
     * The body for [hasServers] (null until read) and [phase]: the connection controls stay while the service runs
     * even without servers (the running profile may have been deleted), so it can still be stopped from here.
     */
    fun content(hasServers: Boolean?, phase: ConnectionPhase): HomeContent = when {
        hasServers == true || phase != ConnectionPhase.OFF -> HomeContent.CONTROLS
        hasServers == false -> HomeContent.NO_SERVERS
        else -> HomeContent.LOADING
    }

    /** The button stops in these phases; in the others it connects (or, while disconnecting, waits). */
    fun buttonStops(phase: ConnectionPhase): Boolean =
        phase == ConnectionPhase.CONNECTING || phase == ConnectionPhase.PROTECTED || phase == ConnectionPhase.RECONNECTING

    /**
     * The banner after the latest observations, or null.
     *
     * [lost]: the service stopped by itself and the user has not dismissed it (cleared by the next start).
     * [connected]: the service is Connected; the server checks only count then.
     * [urlTestFailures]: failed URL tests of the running connection in a row.
     * [autoSelectorExhausted]: the running auto-selector reports that none of its servers works.
     * [serverProblemHidden]: the user hid «Сервер не отвечает» and nothing changed since.
     */
    fun problem(
        lost: HomeProblem.ConnectionLost?,
        connected: Boolean,
        urlTestFailures: Int,
        autoSelectorExhausted: Boolean,
        serverProblemHidden: Boolean = false,
    ): HomeProblem? = when {
        connected && !serverProblemHidden && (urlTestFailures >= FAILURES_FOR_BANNER || autoSelectorExhausted) ->
            HomeProblem.ServerNotResponding

        connected -> null
        else -> lost
    }

    /**
     * Whether the death of the service process is a loss the banner reports: it ran ([previous] was a started state)
     * and no stop was asked for here.
     */
    fun isLossOnServiceDeath(previous: BaseService.State, userStopping: Boolean): Boolean =
        previous.started && !userStopping

    /** Whether a Stopped carrying [msg] is a loss the banner reports: not asked for here, and with a reason. */
    fun isLoss(msg: String?, userStopping: Boolean): Boolean = !userStopping && !msg.isNullOrBlank()

    /** What [working] needs of one member of the running auto-selector (AutoSelectorStatus.Member). */
    data class MemberHealth(val usable: Boolean, val lastOkMs: Long, val lastProbeMs: Long)

    /**
     * «Работают в этой сети: N из M» (unix ms throughout): N = members that answered since the physical network was
     * first seen by this session ([since]), M = all members. Health from before [since] — another network, an earlier
     * session, or health the auto-selector restored from storage at its start — does not count. Nothing (null) until a
     * round of checks has finished in this network ([lastRoundMs]) and at least one member was probed in it, while the
     * selector is [suspended], or when [since] is unknown.
     */
    fun working(members: List<MemberHealth>, since: Long?, lastRoundMs: Long, suspended: Boolean): Pair<Int, Int>? {
        if (since == null || suspended || members.isEmpty()) return null
        if (lastRoundMs < since || members.none { it.lastProbeMs >= since }) return null
        val alive = members.count { it.usable && it.lastOkMs >= since }
        return alive to members.size
    }

    /** `h:mm:ss`, or `m:ss` under an hour, for the connection time. */
    fun formatDuration(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }
}
