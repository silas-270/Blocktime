package com.silas270.blocktime.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.silas270.blocktime.data.model.AchievementUnlock
import com.silas270.blocktime.data.model.Challenge
import com.silas270.blocktime.data.model.FlightLog
import com.silas270.blocktime.data.model.UserProfile

/**
 * Adds the [ChallengeType.STREAK][com.silas270.blocktime.data.model.ChallengeType.STREAK]
 * columns to `challenges`.
 *
 * Defaults matter here: existing rows are all ROUTE/SET_COMPLETION/DISTANCE, and they must come
 * out the far side unchanged. `streak_days` is NOT NULL so it needs an explicit DEFAULT for the
 * rows that already exist; the other two are nullable and default to NULL on their own. Types and
 * nullability have to match schemas/7.json exactly or MigrationTest's validation step fails.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `challenges` ADD COLUMN `target_days` INTEGER")
        db.execSQL("ALTER TABLE `challenges` ADD COLUMN `streak_days` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `challenges` ADD COLUMN `last_flown_day` TEXT")
    }
}

/**
 * Adds the nullable [Challenge.iconName] column to `challenges`.
 */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `challenges` ADD COLUMN `icon_name` TEXT")
    }
}

/**
 * Adds the predefined-route submode columns to `challenges` (see
 * [PredefinedRoute][com.silas270.blocktime.data.model.PredefinedRoute]).
 *
 * Every existing Route row is free-form, and must come out the far side as one: `predefined_route_id`
 * is nullable and defaults to NULL on its own, which is exactly the "not predefined" marker. The
 * NOT NULL `leg_index` needs the explicit DEFAULT for the rows that already exist - and the entity
 * has to declare the same one, or Room's validation rejects a migration that is actually correct
 * (the same trap `streak_days` fell into in MIGRATION_6_7).
 */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `challenges` ADD COLUMN `predefined_route_id` TEXT")
        db.execSQL("ALTER TABLE `challenges` ADD COLUMN `leg_index` INTEGER NOT NULL DEFAULT 0")
    }
}

/**
 * Adds the [Challenge.celebrated] column to `challenges` (see docs/challenges.md).
 *
 * Every existing COMPLETED row predates the completion-presentation animation this backs, and
 * must come out the far side as already-celebrated - not queued up to replay on the pilot's next
 * visit, which is the reason MIGRATION_9_10 backfills existing COMPLETED rows to `1` (celebrated)
 * rather than leaving the column's own DEFAULT 0 stand for them. Existing ACTIVE rows are
 * unaffected either way, since only a COMPLETED row is ever read as "occupying a slot because
 * uncelebrated".
 */
val MIGRATION_9_10 = object : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `challenges` ADD COLUMN `celebrated` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `challenges` SET `celebrated` = 1 WHERE `status` = 'COMPLETED'")
    }
}

/**
 * Adds the shared-challenge columns to `challenges` (docs/shared-challenges.md "Data model"):
 * the room link, the room-state cache, the two sync generations and the shared outcome, plus the
 * unique `(user_id, room_code)` index that keeps one row per room per pilot.
 *
 * Every existing row is a solo challenge and must come out the far side as one: the three
 * nullable columns default to NULL on their own, and NULL `room_code` is exactly the "not shared"
 * marker. The two NOT NULL generation counters need the explicit DEFAULT for the rows that
 * already exist, and the entity has to declare the same one, or Room's validation rejects a
 * migration that is actually correct (the same trap `streak_days` fell into in MIGRATION_6_7).
 * Both start equal, so no existing row reads as pending upload. The index name is the one Room
 * generates for `Index(value = ["user_id", "room_code"])`, which is what the schema validation
 * compares against.
 */
val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `challenges` ADD COLUMN `room_code` TEXT")
        db.execSQL("ALTER TABLE `challenges` ADD COLUMN `room_state` TEXT")
        db.execSQL("ALTER TABLE `challenges` ADD COLUMN `sync_generation` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `challenges` ADD COLUMN `synced_generation` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `challenges` ADD COLUMN `shared_outcome` TEXT")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_challenges_user_id_room_code` " +
                "ON `challenges` (`user_id`, `room_code`)"
        )
    }
}

@Database(
    entities = [UserProfile::class, FlightLog::class, Challenge::class, AchievementUnlock::class],
    version = 11,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userProfileDao(): UserProfileDao
    abstract fun flightLogDao(): FlightLogDao
    abstract fun challengeDao(): ChallengeDao
    abstract fun achievementUnlockDao(): AchievementUnlockDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "user_data.db"
                )
                    // Real migrations, and deliberately no destructive fallback. The fallback
                    // that used to be here silently deleted the entire logbook and every
                    // challenge on any schema bump - survivable while this app existed on one
                    // device, not survivable from the first real user onward.
                    //
                    // The trade this makes: a schema change shipped *without* a matching
                    // migration now crashes on launch instead of quietly wiping. That is the
                    // better failure - it is caught by MigrationTest before it reaches a device,
                    // and a crash can be fixed while deleted flights cannot be recovered.
                    //
                    // Every version bump from here needs a Migration here and a committed
                    // schemas/*.json for the version it migrates from.
                    .addMigrations(
                        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11
                    )
                    .fallbackToDestructiveMigrationFrom(1, 2, 3, 4, 5)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
