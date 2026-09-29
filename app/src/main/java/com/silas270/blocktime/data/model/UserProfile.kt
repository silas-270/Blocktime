package com.silas270.blocktime.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "user_profile",
    indices = [Index(value = ["user_code"], unique = true)]
)
data class UserProfile(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val username: String,
    @ColumnInfo(name = "user_code") val userCode: String,
    @ColumnInfo(name = "home_airport_iata") val homeAirportIata: String,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        private const val CODE_LENGTH = 6

        private val ADJECTIVES = listOf(
            "Swift", "Brave", "Silver", "Golden", "Metallic", "Stealthy", "Rapid", "High",
            "Infinite", "Solar", "Midnight", "Oceanic", "Alpine", "Crimson", "Radiant",
            "Nimbus", "Stratos", "Aero", "Mach", "Sonic", "Global", "Arctic", "Blue"
        )

        private val NOUNS = listOf(
            "Pilot", "Aviator", "Captain", "Navigator", "Eagle", "Falcon", "Hawk",
            "Albatross", "Airbus", "Boeing", "Propeller", "Wing", "Turbine", "Jet",
            "Flare", "Skyward", "Horizon", "Runway", "Altitude", "Vector", "Cessna",
            "Piper", "Concorde"
        )

        /** Six characters from [ROOM_CODE_ALPHABET], the alphabet shared with room codes. */
        fun generateUserCode(): String = generateCode(CODE_LENGTH)

        fun generateRandomName(): String {
            val adj = ADJECTIVES.random()
            val noun = NOUNS.random()
            return "$adj $noun"
        }
    }
}
