package com.silas270.blocktime.data.repository

import com.silas270.blocktime.data.model.ThemeMode
import com.silas270.blocktime.testutil.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesRepositoryTest {

    private val fakePrefs = FakeSharedPreferences()
    private val repository = PreferencesRepository(fakePrefs)

    @Test
    fun `route line mode defaults to 0 (Full)`() {
        assertEquals(0, repository.getRouteLineMode())
    }

    @Test
    fun `route line mode persists updates`() {
        repository.setRouteLineMode(1) // Window
        assertEquals(1, repository.getRouteLineMode())

        repository.setRouteLineMode(2) // Hidden
        assertEquals(2, repository.getRouteLineMode())

        repository.setRouteLineMode(0) // Full
        assertEquals(0, repository.getRouteLineMode())
    }

    @Test
    fun `map style defaults to 0 (Standard) and persists updates`() {
        assertEquals(0, repository.getMapStyle())
        repository.setMapStyle(2) // Offline
        assertEquals(2, repository.getMapStyle())
        repository.setMapStyle(1) // Satellite + Terrain
        assertEquals(1, repository.getMapStyle())
    }

    @Test
    fun `offline data saver defaults to false and persists updates`() {
        assertFalse(repository.isOfflineDataSaverEnabled())
        repository.setOfflineDataSaverEnabled(true)
        assertTrue(repository.isOfflineDataSaverEnabled())
        repository.setOfflineDataSaverEnabled(false)
        assertFalse(repository.isOfflineDataSaverEnabled())
    }

    @Test
    fun `engine sound defaults to false and persists updates`() {
        assertFalse(repository.getEngineSoundEnabled())
        repository.setEngineSoundEnabled(true)
        assertTrue(repository.getEngineSoundEnabled())
        repository.setEngineSoundEnabled(false)
        assertFalse(repository.getEngineSoundEnabled())
    }

    @Test
    fun `theme mode defaults to SYSTEM and persists updates`() {
        assertEquals(ThemeMode.SYSTEM, repository.getThemeMode())
        repository.setThemeMode(ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, repository.getThemeMode())
        repository.setThemeMode(ThemeMode.LIGHT)
        assertEquals(ThemeMode.LIGHT, repository.getThemeMode())
    }

    @Test
    fun `onboarding completed defaults to false and persists updates`() {
        assertFalse(repository.isOnboardingCompleted())
        repository.setOnboardingCompleted(true)
        assertTrue(repository.isOnboardingCompleted())
    }

    @Test
    fun `online features default to false and persist updates`() {
        assertFalse(repository.isOnlineFeaturesEnabled())
        repository.setOnlineFeaturesEnabled(true)
        assertTrue(repository.isOnlineFeaturesEnabled())
        repository.setOnlineFeaturesEnabled(false)
        assertFalse(repository.isOnlineFeaturesEnabled())
    }

    @Test
    fun `room secret is 32 characters from the code alphabet and created once`() {
        assertNull(fakePrefs.getString("room_secret", null))

        val secret = repository.getOrCreateRoomSecret()

        assertEquals(32, secret.length)
        assertTrue(secret.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" })
        assertEquals(secret, repository.getOrCreateRoomSecret())
        assertEquals(secret, PreferencesRepository(fakePrefs).getOrCreateRoomSecret())
    }

    @Test
    fun `pending room leaves are a set that survives adds and removes`() {
        assertEquals(emptySet<String>(), repository.getPendingRoomLeaves())

        repository.addPendingRoomLeave("ABC234")
        repository.addPendingRoomLeave("XYZ789")
        repository.addPendingRoomLeave("ABC234")
        assertEquals(setOf("ABC234", "XYZ789"), repository.getPendingRoomLeaves())

        repository.removePendingRoomLeave("ABC234")
        assertEquals(setOf("XYZ789"), repository.getPendingRoomLeaves())
        repository.removePendingRoomLeave("XYZ789")
        assertEquals(emptySet<String>(), repository.getPendingRoomLeaves())
        assertFalse(fakePrefs.contains("pending_room_leaves"))
    }

    @Test
    fun `last room sync defaults to null and persists updates`() {
        assertNull(repository.getLastRoomSyncAt())
        repository.setLastRoomSyncAt(42L)
        assertEquals(42L, repository.getLastRoomSyncAt())
    }
}
