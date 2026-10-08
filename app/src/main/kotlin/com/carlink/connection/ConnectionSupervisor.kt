package com.carlink.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Outcome of a single connection attempt. */
enum class ConnectResult {
    /** Adapter opened and protocol started. Streaming may still be pending (phone not in range yet). */
    CONNECTED,

    /** No Carlinkit device is enumerated on the USB bus (adapter still booting, or unplugged). */
    NO_DEVICE,

    /** The user dismissed the USB permission dialog (or it timed out). */
    PERMISSION_DENIED,

    /** Anything else (open/claim failed, surface not ready, adapter error). */
    FAILED,
}

/** What the watchdog should do about the current connection. */
enum class WatchdogAction { NONE, RESTART, CONNECT }

/** Point-in-time view of the connection that the watchdog evaluates. Pure data, easy to test. */
data class WatchdogSnapshot(
    val wanted: Boolean,
    val attemptActive: Boolean,
    val reconnectPending: Boolean,
    val disconnected: Boolean,
    val streaming: Boolean,
    /** Uptime (ms) when the adapter protocol was started, or 0 if it is not running. */
    val adapterStartedAtMs: Long,
    /** Uptime (ms) of the last message of any kind received from the adapter, or 0. */
    val lastRxMs: Long,
    val nowMs: Long,
)

/** Reconnect backoff. Never gives up: a car can sit asleep for hours and the adapter boots slowly. */
object ReconnectPolicy {
    const val INITIAL_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 30_000L

    /** Re-prompting for USB permission every 30 s is hostile; wait longer after a refusal. */
    const val PERMISSION_DENIED_DELAY_MS = 60_000L

    /** Settle time between tearing a session down and opening it again (adapter re-enumerates). */
    const val RESTART_SETTLE_MS = 2_000L

    fun delayMs(
        attempt: Int,
        result: ConnectResult,
    ): Long {
        if (result == ConnectResult.PERMISSION_DENIED) return PERMISSION_DENIED_DELAY_MS
        val shift = attempt.coerceIn(0, 4)
        return minOf(INITIAL_DELAY_MS * (1L shl shift), MAX_DELAY_MS)
    }
}

/** Decides when a connection that has not failed loudly is nevertheless dead. */
object WatchdogPolicy {
    /** The adapter must say *something* within this long after the protocol starts. */
    const val STARTUP_SILENCE_MS = 25_000L

    /** While streaming, total silence for this long means the link is stalled. */
    const val STREAM_STALL_MS = 20_000L

    fun evaluate(s: WatchdogSnapshot): WatchdogAction {
        if (!s.wanted || s.attemptActive) return WatchdogAction.NONE

        // Safety net: we want a connection but nothing is running and nothing is scheduled.
        if (s.disconnected && !s.reconnectPending) return WatchdogAction.CONNECT

        if (s.adapterStartedAtMs > 0L) {
            val silentSinceStart = s.lastRxMs < s.adapterStartedAtMs
            if (silentSinceStart && s.nowMs - s.adapterStartedAtMs > STARTUP_SILENCE_MS) {
                return WatchdogAction.RESTART
            }
            if (s.streaming && s.nowMs - s.lastRxMs > STREAM_STALL_MS) {
                return WatchdogAction.RESTART
            }
        }
        return WatchdogAction.NONE
    }
}

/** What the supervisor needs from its owner. All methods may be called from background threads. */
interface ConnectionHooks {
    /** Open the adapter and start the protocol. Blocking work is fine; called on [scope]. */
    suspend fun connect(): ConnectResult

    /** Release adapter/USB/audio/mic. Must be idempotent and safe to call at any time. */
    fun teardown()

    /** Publish DISCONNECTED to the UI/media session. */
    fun markDisconnected()

    fun snapshot(nowMs: Long): WatchdogSnapshot

    fun log(message: String)
}

/**
 * Owns the lifecycle of the adapter connection.
 *
 * Fixes a class of "stuck on Connecting" bugs in the previous design where connecting was a
 * suspend function run inside UI scopes: a recomposition, a second caller, or a cancelled
 * LaunchedEffect could abandon a half-open connection with no one left to retry.
 *
 * Guarantees:
 *  - **Single flight**: at most one attempt runs; concurrent requests coalesce.
 *  - **Cancellation safe**: attempts live in [scope], never in a caller's scope, and an interrupted
 *    attempt always tears down and reports DISCONNECTED.
 *  - **Self-healing**: failures schedule capped exponential retries forever until [stop].
 *  - **Watchdog**: a silent adapter or stalled stream is restarted without user action.
 */
class ConnectionSupervisor(
    private val scope: CoroutineScope,
    private val hooks: ConnectionHooks,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val watchdogIntervalMs: Long = 5_000L,
) {
    private val mutex = Mutex()

    @Volatile private var wanted = false

    @Volatile private var attemptJob: Job? = null

    @Volatile private var reconnectJob: Job? = null
    private var watchdogJob: Job? = null

    @Volatile private var attempts = 0

    @Volatile private var lastRestartRequestMs = 0L

    val isWanted: Boolean get() = wanted
    val isAttemptActive: Boolean get() = attemptJob?.isActive == true
    val isReconnectPending: Boolean get() = reconnectJob?.isActive == true

    /** Begin supervising: starts the watchdog. Idempotent. */
    fun start() {
        if (watchdogJob?.isActive == true) return
        watchdogJob =
            scope.launch {
                while (isActive) {
                    delay(watchdogIntervalMs)
                    runWatchdog()
                }
            }
    }

    /** Stop supervising and cancel everything. */
    fun close() {
        stop()
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * Ask for a connection. Safe to call repeatedly, from any thread. Returns the active attempt,
     * or null when nothing needed doing.
     *
     * @param skipIfHealthy leave a running session alone (a UI re-entering the screen must not
     *   kill a working stream). Pass false when the hardware itself changed (USB re-attached).
     */
    fun connect(
        reason: String,
        skipIfHealthy: Boolean = true,
    ): Job? {
        wanted = true
        attemptJob?.let {
            if (it.isActive) {
                hooks.log("[SUPERVISOR] connect($reason): attempt already running")
                return it
            }
        }
        if (skipIfHealthy) {
            val snap = hooks.snapshot(clock())
            if (!snap.disconnected && snap.adapterStartedAtMs > 0L) {
                hooks.log("[SUPERVISOR] connect($reason): session already running")
                return null
            }
        }
        cancelReconnect()
        return launchAttempt(reason, fresh = false)
    }

    /**
     * Tear the session down and connect again. Concurrent restart requests inside the settle window
     * coalesce so a burst of "unplugged" events cannot thrash the adapter.
     */
    fun restart(reason: String): Job? {
        wanted = true
        val now = clock()
        val active = attemptJob
        if (active?.isActive == true && now - lastRestartRequestMs < ReconnectPolicy.RESTART_SETTLE_MS * 2) {
            hooks.log("[SUPERVISOR] restart($reason): coalesced into running restart")
            return active
        }
        lastRestartRequestMs = now
        cancelReconnect()
        return launchAttempt(reason, fresh = true)
    }

    /** Intentional disconnect: no automatic reconnect until [connect]/[restart] is called again. */
    fun stop() {
        wanted = false
        cancelReconnect()
        attemptJob?.cancel()
        attemptJob = null
        attempts = 0
    }

    /** The owner observed a hard failure (USB detach, transfer error). Retries with backoff. */
    fun onConnectionLost(reason: String) {
        if (!wanted) return
        if (isAttemptActive || isReconnectPending) return // already being handled
        hooks.log("[SUPERVISOR] connection lost: $reason")
        hooks.markDisconnected()
        scheduleReconnect(ConnectResult.FAILED)
    }

    /** The owner confirmed a healthy session (phone plugged / video flowing): reset backoff. */
    fun onHealthy() {
        attempts = 0
    }

    // ---------------------------------------------------------------------------------------

    private fun launchAttempt(
        reason: String,
        fresh: Boolean,
    ): Job {
        val previous = attemptJob
        previous?.cancel()
        val job =
            scope.launch {
                mutex.withLock {
                    var result = ConnectResult.FAILED
                    try {
                        hooks.log("[SUPERVISOR] attempt start (reason=$reason, fresh=$fresh)")
                        hooks.teardown()
                        if (fresh) {
                            hooks.markDisconnected()
                            delay(ReconnectPolicy.RESTART_SETTLE_MS)
                        }
                        result = hooks.connect()
                    } catch (ce: CancellationException) {
                        withContext(NonCancellable) {
                            hooks.teardown()
                            hooks.markDisconnected()
                        }
                        throw ce
                    } catch (t: Throwable) {
                        hooks.log("[SUPERVISOR] attempt crashed: ${t.javaClass.simpleName}: ${t.message}")
                        hooks.teardown()
                        hooks.markDisconnected()
                    }
                    if (result == ConnectResult.CONNECTED) {
                        hooks.log("[SUPERVISOR] attempt connected")
                    } else {
                        hooks.log("[SUPERVISOR] attempt failed: $result")
                        hooks.teardown()
                        hooks.markDisconnected()
                        if (wanted) scheduleReconnect(result)
                    }
                }
            }
        attemptJob = job
        return job
    }

    private fun scheduleReconnect(result: ConnectResult) {
        cancelReconnect()
        val delayMs = ReconnectPolicy.delayMs(attempts, result)
        attempts++
        hooks.log("[SUPERVISOR] reconnect #$attempts in ${delayMs}ms (after $result)")
        reconnectJob =
            scope.launch {
                delay(delayMs)
                if (wanted && !isAttemptActive) {
                    launchAttempt("backoff", fresh = false)
                }
            }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun runWatchdog() {
        val snap = hooks.snapshot(clock())
        when (WatchdogPolicy.evaluate(snap)) {
            WatchdogAction.NONE -> {}
            WatchdogAction.CONNECT -> {
                hooks.log("[WATCHDOG] wanted but idle with no retry scheduled - connecting")
                connect("watchdog-idle")
            }
            WatchdogAction.RESTART -> {
                hooks.log("[WATCHDOG] adapter silent/stalled - restarting connection")
                restart("watchdog-stall")
            }
        }
    }
}
