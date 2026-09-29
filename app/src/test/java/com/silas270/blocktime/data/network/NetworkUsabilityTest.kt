package com.silas270.blocktime.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The connected rule of [NetworkUsability]: a usable default network over a usable physical one. */
class NetworkUsabilityTest {

    private val usability = NetworkUsability()

    @Test
    fun `wifi without a vpn is connected`() {
        usability.setPhysical("wifi", usable = true)
        assertTrue(usability.setDefault(true))
    }

    @Test
    fun `a validated vpn tunnel with nothing under it is offline`() {
        // ProtonVPN on, Wi-Fi and mobile data off: the tunnel still reads validated.
        assertFalse(usability.setDefault(true))
    }

    @Test
    fun `losing the only physical network under a vpn goes offline, and getting it back reconnects`() {
        usability.setDefault(true)
        assertTrue(usability.setPhysical("wifi", usable = true))

        assertFalse(usability.lostPhysical("wifi"))
        assertTrue(usability.setPhysical("lte", usable = true))
    }

    @Test
    fun `one usable physical network is enough`() {
        usability.setDefault(true)
        usability.setPhysical("wifi", usable = false) // captive portal
        assertTrue(usability.setPhysical("lte", usable = true))
        assertTrue(usability.lostPhysical("wifi"))
    }

    @Test
    fun `a working wifi behind a dead vpn with its kill switch on is offline`() {
        usability.setPhysical("wifi", usable = true)
        assertFalse(usability.setDefault(false))
    }

    @Test
    fun `a captive portal is offline`() {
        usability.setPhysical("wifi", usable = false)
        assertFalse(usability.setDefault(false))
    }
}
