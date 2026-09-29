package com.silas270.blocktime.data.network

import com.silas270.blocktime.data.repository.PreferencesRepository
import com.silas270.blocktime.testutil.FakeSharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class ServerReachabilityTest {

    /** A clock the test moves by hand, so the 30 s probe cache can be crossed without waiting. */
    private class ManualClock(var now: Long) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = Instant.ofEpochMilli(now)
    }

    /** A probe that counts its calls and answers what the test tells it to. */
    private class CountingProbe(var answer: Boolean = true, var throwing: Boolean = false) {
        var calls = 0
        suspend fun run(): Boolean {
            calls++
            if (throwing) throw IllegalStateException("probe failed")
            return answer
        }
    }

    private val allProbes = ProbeResult.entries

    @Test
    fun `resolveServerState covers every branch in order`() {
        allProbes.forEach { probe ->
            assertEquals(ServerState.NOT_CONFIGURED, resolveServerState(configured = false, optIn = true, connected = true, lastProbe = probe))
            assertEquals(ServerState.NOT_CONFIGURED, resolveServerState(configured = false, optIn = false, connected = false, lastProbe = probe))
            assertEquals(ServerState.DISABLED, resolveServerState(configured = true, optIn = false, connected = true, lastProbe = probe))
            assertEquals(ServerState.DISABLED, resolveServerState(configured = true, optIn = false, connected = false, lastProbe = probe))
            assertEquals(ServerState.DEVICE_OFFLINE, resolveServerState(configured = true, optIn = true, connected = false, lastProbe = probe))
        }
        assertEquals(ServerState.UNKNOWN, resolveServerState(configured = true, optIn = true, connected = true, lastProbe = ProbeResult.UNKNOWN))
        assertEquals(ServerState.REACHABLE, resolveServerState(configured = true, optIn = true, connected = true, lastProbe = ProbeResult.REACHABLE))
        assertEquals(ServerState.UNREACHABLE, resolveServerState(configured = true, optIn = true, connected = true, lastProbe = ProbeResult.UNREACHABLE))
    }

    @Test
    fun `check does not probe when not configured`() = runTest(UnconfinedTestDispatcher()) {
        val probe = CountingProbe()
        val prefs = PreferencesRepository(FakeSharedPreferences()).apply { setOnlineFeaturesEnabled(true) }
        val reachability = ServerReachability(false, MutableStateFlow(true), prefs, probe::run, backgroundScope, ManualClock(0))

        assertEquals(ServerState.NOT_CONFIGURED, reachability.check())
        assertEquals(ServerState.NOT_CONFIGURED, reachability.check(force = true))
        assertEquals(0, probe.calls)
    }

    @Test
    fun `check does not probe when sharing is off`() = runTest(UnconfinedTestDispatcher()) {
        val probe = CountingProbe()
        val prefs = PreferencesRepository(FakeSharedPreferences())
        val reachability = ServerReachability(true, MutableStateFlow(true), prefs, probe::run, backgroundScope, ManualClock(0))

        assertEquals(ServerState.DISABLED, reachability.state.value)
        assertEquals(ServerState.DISABLED, reachability.check(force = true))
        assertEquals(0, probe.calls)
    }

    @Test
    fun `check does not probe while the device is offline`() = runTest(UnconfinedTestDispatcher()) {
        val probe = CountingProbe()
        val prefs = PreferencesRepository(FakeSharedPreferences()).apply { setOnlineFeaturesEnabled(true) }
        val connected = MutableStateFlow(false)
        val reachability = ServerReachability(true, connected, prefs, probe::run, backgroundScope, ManualClock(0))

        assertEquals(ServerState.DEVICE_OFFLINE, reachability.check(force = true))
        assertEquals(0, probe.calls)

        connected.value = true
        assertEquals(ServerState.UNKNOWN, reachability.state.value)
        assertEquals(ServerState.REACHABLE, reachability.check())
        assertEquals(1, probe.calls)
    }

    @Test
    fun `check caches a probe result for thirty seconds and force ignores the cache`() = runTest(UnconfinedTestDispatcher()) {
        val probe = CountingProbe(answer = true)
        val clock = ManualClock(1_000_000L)
        val prefs = PreferencesRepository(FakeSharedPreferences()).apply { setOnlineFeaturesEnabled(true) }
        val reachability = ServerReachability(true, MutableStateFlow(true), prefs, probe::run, backgroundScope, clock)

        assertEquals(ServerState.REACHABLE, reachability.check())
        assertEquals(1, probe.calls)

        // Inside the window: the cached answer, even though the server would now say otherwise.
        probe.answer = false
        clock.now += ServerReachability.PROBE_TTL_MS - 1
        assertEquals(ServerState.REACHABLE, reachability.check())
        assertEquals(1, probe.calls)

        // Force asks anyway and restarts the window.
        assertEquals(ServerState.UNREACHABLE, reachability.check(force = true))
        assertEquals(2, probe.calls)
        assertEquals(ServerState.UNREACHABLE, reachability.state.value)

        // The window expires.
        probe.answer = true
        clock.now += ServerReachability.PROBE_TTL_MS
        assertEquals(ServerState.REACHABLE, reachability.check())
        assertEquals(3, probe.calls)
    }

    @Test
    fun `a probe that throws counts as unreachable`() = runTest(UnconfinedTestDispatcher()) {
        val probe = CountingProbe(throwing = true)
        val prefs = PreferencesRepository(FakeSharedPreferences()).apply { setOnlineFeaturesEnabled(true) }
        val reachability = ServerReachability(true, MutableStateFlow(true), prefs, probe::run, backgroundScope, ManualClock(0))

        assertEquals(ServerState.UNREACHABLE, reachability.check())
        assertEquals(ServerState.UNREACHABLE, reachability.state.value)
    }

    @Test
    fun `report updates the state and restarts the cache window`() = runTest(UnconfinedTestDispatcher()) {
        val probe = CountingProbe(answer = true)
        val clock = ManualClock(5_000L)
        val prefs = PreferencesRepository(FakeSharedPreferences()).apply { setOnlineFeaturesEnabled(true) }
        val reachability = ServerReachability(true, MutableStateFlow(true), prefs, probe::run, backgroundScope, clock)

        assertEquals(ServerState.UNKNOWN, reachability.state.value)
        reachability.report(success = false)
        assertEquals(ServerState.UNREACHABLE, reachability.state.value)
        reachability.report(success = true)
        assertEquals(ServerState.REACHABLE, reachability.state.value)

        // An API call just answered, so check trusts it and does not probe.
        clock.now += 10_000L
        assertEquals(ServerState.REACHABLE, reachability.check())
        assertEquals(0, probe.calls)
    }

    @Test
    fun `the probe result survives going offline and back`() = runTest(UnconfinedTestDispatcher()) {
        val probe = CountingProbe(answer = true)
        val prefs = PreferencesRepository(FakeSharedPreferences()).apply { setOnlineFeaturesEnabled(true) }
        val connected = MutableStateFlow(true)
        val reachability = ServerReachability(true, connected, prefs, probe::run, backgroundScope, ManualClock(0))

        reachability.report(success = false)
        connected.value = false
        assertEquals(ServerState.DEVICE_OFFLINE, reachability.state.value)
        connected.value = true
        assertEquals(ServerState.UNREACHABLE, reachability.state.value)
    }

    @Test
    fun `switching sharing on creates the secret once and persists both`() = runTest(UnconfinedTestDispatcher()) {
        val sharedPrefs = FakeSharedPreferences()
        val prefs = PreferencesRepository(sharedPrefs)
        val reachability = ServerReachability(true, MutableStateFlow(true), prefs, { true }, backgroundScope, ManualClock(0))

        assertFalse(reachability.optIn.value)
        assertNull(sharedPrefs.getString("room_secret", null))

        reachability.setOnlineFeaturesEnabled(true)
        assertTrue(reachability.optIn.value)
        assertTrue(prefs.isOnlineFeaturesEnabled())
        assertEquals(ServerState.UNKNOWN, reachability.state.value)
        val secret = sharedPrefs.getString("room_secret", null)
        assertNotNull(secret)
        assertEquals(32, secret!!.length)
        assertTrue(secret.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })

        reachability.setOnlineFeaturesEnabled(false)
        assertEquals(ServerState.DISABLED, reachability.state.value)
        assertFalse(prefs.isOnlineFeaturesEnabled())

        // Off and on again keeps the same secret; the server bound it on the first write.
        reachability.setOnlineFeaturesEnabled(true)
        assertEquals(secret, prefs.getOrCreateRoomSecret())
    }

    @Test
    fun `the opt-in is restored from preferences`() = runTest(UnconfinedTestDispatcher()) {
        val prefs = PreferencesRepository(FakeSharedPreferences()).apply { setOnlineFeaturesEnabled(true) }
        val reachability = ServerReachability(true, MutableStateFlow(true), prefs, { true }, backgroundScope, ManualClock(0))

        assertTrue(reachability.optIn.value)
        assertEquals(ServerState.UNKNOWN, reachability.state.value)
    }
}
