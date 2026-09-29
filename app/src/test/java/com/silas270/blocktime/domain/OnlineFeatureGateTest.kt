package com.silas270.blocktime.domain

import com.silas270.blocktime.data.network.ServerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineFeatureGateTest {

    @Test
    fun `not configured and switched off both hide the control`() {
        assertEquals(OnlineFeatureAvailability.HIDDEN, onlineAvailability(ServerState.NOT_CONFIGURED))
        assertEquals(OnlineFeatureAvailability.HIDDEN, onlineAvailability(ServerState.DISABLED))
    }

    @Test
    fun `offline, unreachable and unknown dim the control with their reason`() {
        assertEquals(OnlineFeatureAvailability.DISABLED_OFFLINE, onlineAvailability(ServerState.DEVICE_OFFLINE))
        assertEquals(OnlineFeatureAvailability.DISABLED_UNREACHABLE, onlineAvailability(ServerState.UNREACHABLE))
        assertEquals(OnlineFeatureAvailability.DISABLED_CHECKING, onlineAvailability(ServerState.UNKNOWN))
    }

    @Test
    fun `reachable enables the control`() {
        assertEquals(OnlineFeatureAvailability.ENABLED, onlineAvailability(ServerState.REACHABLE))
    }

    @Test
    fun `every server state has an availability`() {
        ServerState.entries.forEach { onlineAvailability(it) }
    }

    @Test
    fun `hints explain the dimmed states and nothing else`() {
        assertEquals("No connection", onlineHint(OnlineFeatureAvailability.DISABLED_OFFLINE))
        assertEquals("Server not reachable", onlineHint(OnlineFeatureAvailability.DISABLED_UNREACHABLE))
        assertEquals("Checking…", onlineHint(OnlineFeatureAvailability.DISABLED_CHECKING))
        assertNull(onlineHint(OnlineFeatureAvailability.HIDDEN))
        assertNull(onlineHint(OnlineFeatureAvailability.ENABLED))
    }
}
