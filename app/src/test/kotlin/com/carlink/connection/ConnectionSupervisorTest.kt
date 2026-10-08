package com.carlink.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSupervisorTest {
    private class FakeHooks(
        private val now: () -> Long,
    ) : ConnectionHooks {
        val results = ArrayDeque<ConnectResult>()
        var connectTakesMs = 0L
        val connectTimes = mutableListOf<Long>()
        var teardowns = 0
        var disconnects = 0
        val logs = mutableListOf<String>()

        // Snapshot knobs for watchdog / healthy-session tests
        var disconnected = true
        var streaming = false
        var adapterStartedAtMs = 0L
        var lastRxMs = 0L
        lateinit var supervisor: ConnectionSupervisor

        override suspend fun connect(): ConnectResult {
            connectTimes += now()
            if (connectTakesMs > 0) delay(connectTakesMs)
            return results.removeFirstOrNull() ?: ConnectResult.CONNECTED
        }

        override fun teardown() {
            teardowns++
        }

        override fun markDisconnected() {
            disconnects++
        }

        override fun snapshot(nowMs: Long) =
            WatchdogSnapshot(
                wanted = supervisor.isWanted,
                attemptActive = supervisor.isAttemptActive,
                reconnectPending = supervisor.isReconnectPending,
                disconnected = disconnected,
                streaming = streaming,
                adapterStartedAtMs = adapterStartedAtMs,
                lastRxMs = lastRxMs,
                nowMs = nowMs,
            )

        override fun log(message: String) {
            logs += message
        }
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(): FakeHooks {
        val hooks = FakeHooks { currentTime }
        hooks.supervisor =
            ConnectionSupervisor(backgroundScope, hooks, clock = { currentTime }, watchdogIntervalMs = 5_000L)
        return hooks
    }

    @Test
    fun concurrentConnectRequestsCoalesce() =
        runTest {
            val h = fixture()
            h.connectTakesMs = 1_000
            val a = h.supervisor.connect("a")
            val b = h.supervisor.connect("b")
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(1, h.connectTimes.size)
            assertTrue(a === b)
        }

    @Test
    fun failuresRetryForeverWithCappedBackoff() =
        runTest {
            val h = fixture()
            repeat(7) { h.results += ConnectResult.NO_DEVICE }
            h.supervisor.connect("go")
            advanceTimeBy(200_000)
            runCurrent()
            // 7 failures then a success: never gives up (old code stopped after 5)
            assertEquals(8, h.connectTimes.size)
            val gaps = h.connectTimes.zipWithNext { a, b -> b - a }
            assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L), gaps)
        }

    @Test
    fun permissionRefusalBacksOffLonger() =
        runTest {
            val h = fixture()
            h.results += ConnectResult.PERMISSION_DENIED
            h.supervisor.connect("go")
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(60_000L, h.connectTimes[1] - h.connectTimes[0])
        }

    @Test
    fun stopCancelsInFlightAttemptCleansUpAndNeverRetries() =
        runTest {
            val h = fixture()
            h.connectTakesMs = 10_000
            h.supervisor.connect("go")
            advanceTimeBy(1_000)
            val teardownsBefore = h.teardowns
            h.supervisor.stop()
            advanceTimeBy(300_000)
            runCurrent()
            assertEquals(1, h.connectTimes.size)
            assertTrue("cancelled attempt must tear down", h.teardowns > teardownsBefore)
            assertTrue("and report disconnected (never stuck on CONNECTING)", h.disconnects >= 1)
            assertFalse(h.supervisor.isReconnectPending)
        }

    @Test
    fun cancellingTheCallerDoesNotCancelTheConnection() =
        runTest {
            val h = fixture()
            h.connectTakesMs = 5_000
            val job = h.supervisor.connect("ui")!!
            // A UI-scoped waiter being cancelled (recomposition) must not touch the attempt
            val waiter = launch { job.join() }
            advanceTimeBy(1_000)
            waiter.cancel()
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, h.connectTimes.size)
            assertTrue(job.isCompleted && !job.isCancelled)
        }

    @Test
    fun connectLeavesAHealthySessionAlone() =
        runTest {
            val h = fixture()
            h.disconnected = false
            h.adapterStartedAtMs = 1
            assertNull(h.supervisor.connect("ui-reenter"))
            runCurrent()
            assertEquals(0, h.connectTimes.size)
            assertEquals(0, h.teardowns)
        }

    @Test
    fun hardwareChangeForcesReconnectEvenIfHealthy() =
        runTest {
            val h = fixture()
            h.disconnected = false
            h.adapterStartedAtMs = 1
            h.supervisor.connect("usb-attach", skipIfHealthy = false)
            runCurrent()
            assertEquals(1, h.connectTimes.size)
        }

    @Test
    fun connectionLostSchedulesOneReconnectEvenIfReportedManyTimes() =
        runTest {
            val h = fixture()
            h.supervisor.connect("go")
            runCurrent()
            repeat(50) { h.supervisor.onConnectionLost("send error") }
            advanceTimeBy(2_500)
            runCurrent()
            assertEquals(2, h.connectTimes.size)
        }

    @Test
    fun connectionLostAfterIntentionalStopDoesNothing() =
        runTest {
            val h = fixture()
            h.supervisor.connect("go")
            runCurrent()
            h.supervisor.stop()
            h.supervisor.onConnectionLost("late error")
            advanceTimeBy(120_000)
            runCurrent()
            assertEquals(1, h.connectTimes.size)
        }

    @Test
    fun restartTearsDownSettlesAndReconnects() =
        runTest {
            val h = fixture()
            h.supervisor.connect("go")
            runCurrent()
            h.supervisor.restart("manual")
            advanceTimeBy(ReconnectPolicy.RESTART_SETTLE_MS + 100)
            runCurrent()
            assertEquals(2, h.connectTimes.size)
            assertTrue(h.connectTimes[1] - h.connectTimes[0] >= ReconnectPolicy.RESTART_SETTLE_MS)
        }

    @Test
    fun burstOfRestartsCoalesces() =
        runTest {
            val h = fixture()
            h.supervisor.restart("a")
            h.supervisor.restart("b")
            h.supervisor.restart("c")
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, h.connectTimes.size)
        }

    @Test
    fun watchdogRestartsSilentAdapter() =
        runTest {
            val h = fixture()
            h.supervisor.connect("go")
            runCurrent()
            // Connected from the app's view, but the adapter never said a word
            h.disconnected = false
            h.adapterStartedAtMs = currentTime + 1 // 0 means "not started"
            h.lastRxMs = 0
            h.supervisor.start()
            advanceTimeBy(WatchdogPolicy.STARTUP_SILENCE_MS + 15_000)
            runCurrent()
            assertTrue(h.logs.any { it.contains("[WATCHDOG] adapter silent") })
            assertTrue(h.connectTimes.size >= 2)
        }

    @Test
    fun watchdogReconnectsWhenIdleWithNothingScheduled() =
        runTest {
            val h = fixture()
            h.supervisor.connect("go")
            runCurrent()
            h.disconnected = true // connected earlier, then something dropped it silently
            h.supervisor.start()
            // Make the supervisor believe nothing is pending: attempt finished, no reconnect job
            advanceTimeBy(6_000)
            runCurrent()
            assertTrue(h.connectTimes.size >= 2)
        }

    // ---- pure policy ------------------------------------------------------------------------

    private fun snap(
        wanted: Boolean = true,
        attemptActive: Boolean = false,
        reconnectPending: Boolean = false,
        disconnected: Boolean = false,
        streaming: Boolean = false,
        started: Long = 1_000,
        lastRx: Long = 0,
        now: Long = 1_000,
    ) = WatchdogSnapshot(wanted, attemptActive, reconnectPending, disconnected, streaming, started, lastRx, now)

    @Test
    fun watchdogIgnoresUnwantedOrBusyConnections() {
        assertEquals(WatchdogAction.NONE, WatchdogPolicy.evaluate(snap(wanted = false, now = 999_999)))
        assertEquals(WatchdogAction.NONE, WatchdogPolicy.evaluate(snap(attemptActive = true, now = 999_999)))
    }

    @Test
    fun watchdogGivesAdapterTimeToBoot() {
        assertEquals(WatchdogAction.NONE, WatchdogPolicy.evaluate(snap(now = 1_000 + WatchdogPolicy.STARTUP_SILENCE_MS - 1)))
        assertEquals(WatchdogAction.RESTART, WatchdogPolicy.evaluate(snap(now = 1_000 + WatchdogPolicy.STARTUP_SILENCE_MS + 1)))
    }

    @Test
    fun watchdogLeavesAChattyAdapterAlone() {
        assertEquals(
            WatchdogAction.NONE,
            WatchdogPolicy.evaluate(snap(lastRx = 2_000, streaming = true, now = 2_000 + WatchdogPolicy.STREAM_STALL_MS - 1)),
        )
    }

    @Test
    fun watchdogCatchesStalledStream() {
        assertEquals(
            WatchdogAction.RESTART,
            WatchdogPolicy.evaluate(snap(lastRx = 2_000, streaming = true, now = 2_000 + WatchdogPolicy.STREAM_STALL_MS + 1)),
        )
    }

    @Test
    fun idleStallIsNotTreatedAsFailureWhenNotStreaming() {
        // No phone in range: adapter legitimately quiet after its first messages
        assertEquals(WatchdogAction.NONE, WatchdogPolicy.evaluate(snap(lastRx = 2_000, streaming = false, now = 900_000)))
    }

    @Test
    fun watchdogReconnectsWhenDisconnectedWithNoRetryPending() {
        assertEquals(WatchdogAction.CONNECT, WatchdogPolicy.evaluate(snap(disconnected = true, started = 0)))
        assertEquals(WatchdogAction.NONE, WatchdogPolicy.evaluate(snap(disconnected = true, reconnectPending = true, started = 0)))
    }

    @Test
    fun backoffSequenceIsCapped() {
        val d = (0..8).map { ReconnectPolicy.delayMs(it, ConnectResult.FAILED) }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L, 30_000L, 30_000L), d)
        assertEquals(60_000L, ReconnectPolicy.delayMs(0, ConnectResult.PERMISSION_DENIED))
    }
}
