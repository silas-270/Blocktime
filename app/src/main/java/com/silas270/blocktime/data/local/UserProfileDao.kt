package com.silas270.blocktime.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.silas270.blocktime.data.model.UserProfile
import kotlinx.coroutines.flow.Flow

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile LIMIT 1")
    fun getProfileFlow(): Flow<UserProfile?>

    @Query("SELECT * FROM user_profile LIMIT 1")
    suspend fun getProfile(): UserProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: UserProfile): Long

    @Update
    suspend fun updateProfile(profile: UserProfile)

    @Query("UPDATE user_profile SET username = :username, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateUsername(id: Int, username: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE user_profile SET home_airport_iata = :iata, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateHomeAirport(id: Int, iata: String, updatedAt: Long = System.currentTimeMillis())
}

/**
 * The user id every `Local*Repository` needs before it can scope a query - previously a
 * byte-for-byte-identical private helper in `LocalChallengeRepository`, `LocalFlightLogRepository`,
 * and `LocalAchievementsRepository`. Throws rather than returning null since every repository
 * call site is unreachable before onboarding creates the one profile row.
 */
suspend fun UserProfileDao.requireProfileId(): Int = requireProfile().id

/** The whole profile, for the callers that also need the pilot's name and code (sharing). Same
 *  contract as [requireProfileId]: onboarding has created the row before any of them can run. */
suspend fun UserProfileDao.requireProfile(): UserProfile =
    getProfile() ?: throw IllegalStateException("No user profile found. Create a profile first.")
