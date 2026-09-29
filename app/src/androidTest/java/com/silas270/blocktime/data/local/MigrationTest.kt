package com.silas270.blocktime.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The regression test for the thing that used to be a policy: `AppDatabase` ran
 * `fallbackToDestructiveMigration(dropAllTables = true)`, so every schema bump silently deleted
 * the pilot's entire logbook and every challenge. That was survivable while this app existed on
 * exactly one device and is not survivable from the first real user onward.
 *
 * This is an instrumented test rather than a JVM one because [MigrationTestHelper] needs a real
 * SQLite implementation, not the JDBC one - it opens an actual database at the old version, runs
 * the migration, and then validates the result against the committed `schemas/7.json`. That
 * validation step is the part that catches a migration which runs without error but produces a
 * subtly wrong column - a missing default, a nullable that should not be.
 *
 * Run with `./gradlew connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private companion object {
        const val TEST_DB = "migration-test.db"
    }

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate6To7_preservesFlightsAndChallenges() {
        helper.createDatabase(TEST_DB, 6).apply {
            execSQL(
                """
                INSERT INTO user_profile
                    (id, username, user_code, home_airport_iata, created_at, updated_at)
                VALUES (1, 'testpilot', 'FF-TEST', 'LHR', 1700000000000, 1700000000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO flight_log
                    (user_id, flight_number, origin_iata, dest_iata, duration_min, distance_km,
                     completed_at, created_at, mode)
                VALUES (1, 'FF001', 'LHR', 'CDG', 45, 350.0, 1700000000000, 1700000000000, 'STORY')
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO challenges
                    (user_id, type, source, status, name, description, route_progress_fraction,
                     set_total_members, set_visited_members, cumulative_distance_km, started_at)
                VALUES (1, 'DISTANCE', 'CUSTOM', 'ACTIVE', 'Custom Distance', '', 0.0,
                        0, '', 4200.0, 1700000000000)
                """.trimIndent()
            )
            close()
        }

        // validateDroppedTables = true: this migration adds columns and must not drop anything.
        val db = helper.runMigrationsAndValidate(TEST_DB, 7, true, MIGRATION_6_7)

        db.query("SELECT flight_number, distance_km FROM flight_log").use { cursor ->
            assertTrue("the flight log must survive the migration", cursor.moveToFirst())
            assertEquals(1, cursor.count)
            assertEquals("FF001", cursor.getString(0))
            assertEquals(350.0, cursor.getDouble(1), 0.001)
        }

        db.query(
            "SELECT cumulative_distance_km, target_days, streak_days, last_flown_day FROM challenges"
        ).use { cursor ->
            assertTrue("the challenge must survive the migration", cursor.moveToFirst())
            assertEquals(4200.0, cursor.getDouble(0), 0.001)
            // A pre-existing DISTANCE challenge has no streak state, and the new columns must not
            // invent any: nullable ones stay null, and the NOT NULL one takes its declared default
            // rather than tripping the ALTER.
            assertTrue(cursor.isNull(1))
            assertEquals(0, cursor.getInt(2))
            assertTrue(cursor.isNull(3))
        }
    }

    @Test
    fun migrate7To8_preservesChallengesWithIconName() {
        helper.createDatabase(TEST_DB, 7).apply {
            execSQL(
                """
                INSERT INTO user_profile
                    (id, username, user_code, home_airport_iata, created_at, updated_at)
                VALUES (1, 'testpilot', 'FF-TEST', 'LHR', 1700000000000, 1700000000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO challenges
                    (user_id, type, source, status, name, description, route_progress_fraction,
                     set_total_members, set_visited_members, cumulative_distance_km, started_at,
                     streak_days)
                VALUES (1, 'DISTANCE', 'CUSTOM', 'ACTIVE', 'Custom Distance', '', 0.0,
                        0, '', 4200.0, 1700000000000, 0)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 8, true, MIGRATION_7_8)

        db.query("SELECT cumulative_distance_km, icon_name FROM challenges").use { cursor ->
            assertTrue("the challenge must survive the migration", cursor.moveToFirst())
            assertEquals(4200.0, cursor.getDouble(0), 0.001)
            assertTrue(cursor.isNull(1))
        }
    }

    /**
     * The predefined-route submode's columns. The row inserted here is a *free-form* Route
     * challenge, which is the case that matters: every Route challenge that exists before this
     * migration is free-form, and has to come out the far side still free-form. A
     * `predefined_route_id` that arrived non-null - or a `leg_index` that failed to default -
     * would silently reinterpret an existing challenge as an itinerary it never chose.
     */
    @Test
    fun migrate8To9_leavesExistingRouteChallengesFreeForm() {
        helper.createDatabase(TEST_DB, 8).apply {
            execSQL(
                """
                INSERT INTO user_profile
                    (id, username, user_code, home_airport_iata, created_at, updated_at)
                VALUES (1, 'testpilot', 'FF-TEST', 'LHR', 1700000000000, 1700000000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO challenges
                    (user_id, type, source, status, name, description, origin_iata, dest_iata,
                     position_iata, route_progress_fraction, set_total_members, set_visited_members,
                     cumulative_distance_km, started_at, streak_days)
                VALUES (1, 'ROUTE', 'CUSTOM', 'ACTIVE', 'Stuttgart to Beijing', '', 'STR', 'PEK',
                        'IST', 0.4, 0, '', 0.0, 1700000000000, 0)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 9, true, MIGRATION_8_9)

        db.query(
            "SELECT position_iata, route_progress_fraction, predefined_route_id, leg_index FROM challenges"
        ).use { cursor ->
            assertTrue("the challenge must survive the migration", cursor.moveToFirst())
            assertEquals("IST", cursor.getString(0))
            assertEquals(0.4f, cursor.getFloat(1), 0.001f)
            assertTrue("an existing route challenge must stay free-form", cursor.isNull(2))
            assertEquals(0, cursor.getInt(3))
        }
    }

    /**
     * The `celebrated` column backing the completion-presentation animation (docs/challenges.md).
     * A COMPLETED row that predates the feature must come out backfilled to celebrated - not
     * queued up to replay its completion the next time the pilot opens Challenges - while an
     * existing ACTIVE row must come out at the column's own default (0), since it isn't COMPLETED
     * at all and the backfill only touches COMPLETED rows.
     */
    @Test
    fun migrate9To10_backfillsExistingCompletionsAsCelebrated() {
        helper.createDatabase(TEST_DB, 9).apply {
            execSQL(
                """
                INSERT INTO user_profile
                    (id, username, user_code, home_airport_iata, created_at, updated_at)
                VALUES (1, 'testpilot', 'FF-TEST', 'LHR', 1700000000000, 1700000000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO challenges
                    (id, user_id, type, source, status, name, description, route_progress_fraction,
                     target_distance_km, cumulative_distance_km, set_total_members,
                     set_visited_members, started_at, completed_at, streak_days, leg_index)
                VALUES (1, 1, 'DISTANCE', 'CUSTOM', 'COMPLETED', 'Already done', '', 0.0, 1000.0,
                        1000.0, 0, '', 1700000000000, 1700000001000, 0, 0)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO challenges
                    (id, user_id, type, source, status, name, description, route_progress_fraction,
                     target_distance_km, cumulative_distance_km, set_total_members,
                     set_visited_members, started_at, streak_days, leg_index)
                VALUES (2, 1, 'DISTANCE', 'CUSTOM', 'ACTIVE', 'Still going', '', 0.0, 1000.0,
                        200.0, 0, '', 1700000000000, 0, 0)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 10, true, MIGRATION_9_10)

        db.query("SELECT id, celebrated FROM challenges ORDER BY id").use { cursor ->
            assertTrue("the completed challenge must survive the migration", cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals("a pre-existing completion must not replay its celebration", 1, cursor.getInt(1))

            assertTrue("the active challenge must survive the migration", cursor.moveToNext())
            assertEquals(2, cursor.getInt(0))
            assertEquals("an active challenge is untouched by the backfill", 0, cursor.getInt(1))
        }
    }

    /**
     * The shared-challenge columns (docs/shared-challenges.md "Data model"). Every row that
     * exists before this migration is a solo challenge and must come out the far side as one:
     * NULL room code (which is the "not shared" marker), no cache, no outcome, and both
     * generations at zero and equal, so nothing reads as pending upload. The unique
     * `(user_id, room_code)` index is asserted by name and by behaviour, because the join path
     * relies on it to refuse a second row for a finished room (situation J6b), while NULL room
     * codes must keep not colliding or a pilot could only ever have one solo challenge.
     */
    @Test
    fun migrate10To11_leavesExistingChallengesSoloAndAddsTheRoomIndex() {
        helper.createDatabase(TEST_DB, 10).apply {
            execSQL(
                """
                INSERT INTO user_profile
                    (id, username, user_code, home_airport_iata, created_at, updated_at)
                VALUES (1, 'testpilot', 'FF-TEST', 'LHR', 1700000000000, 1700000000000)
                """.trimIndent()
            )
            execSQL(
                """
                INSERT INTO challenges
                    (id, user_id, type, source, status, name, description, route_progress_fraction,
                     target_distance_km, cumulative_distance_km, set_total_members,
                     set_visited_members, started_at, streak_days, leg_index, celebrated)
                VALUES (1, 1, 'DISTANCE', 'CUSTOM', 'ACTIVE', 'Still going', '', 0.0, 1000.0,
                        200.0, 0, '', 1700000000000, 0, 0, 0)
                """.trimIndent()
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(TEST_DB, 11, true, MIGRATION_10_11)

        db.query(
            "SELECT room_code, room_state, sync_generation, synced_generation, shared_outcome FROM challenges"
        ).use { cursor ->
            assertTrue("the challenge must survive the migration", cursor.moveToFirst())
            assertTrue("an existing challenge must stay unshared", cursor.isNull(0))
            assertTrue("an existing challenge has no room cache", cursor.isNull(1))
            assertEquals(0L, cursor.getLong(2))
            assertEquals("nothing pre-existing may read as pending upload", 0L, cursor.getLong(3))
            assertTrue("an existing challenge has no shared outcome", cursor.isNull(4))
        }

        db.query("PRAGMA index_list('challenges')").use { cursor ->
            var found = false
            var unique = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "index_challenges_user_id_room_code") {
                    found = true
                    unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique")) == 1
                }
            }
            assertTrue("the room index must exist under Room's generated name", found)
            assertTrue("the room index must be unique", unique)
        }

        val sharedRow = { id: Int, roomCode: String? ->
            """
            INSERT INTO challenges
                (id, user_id, type, source, status, name, description, route_progress_fraction,
                 target_distance_km, cumulative_distance_km, set_total_members,
                 set_visited_members, started_at, room_code)
            VALUES ($id, 1, 'DISTANCE', 'CUSTOM', 'ACTIVE', 'Shared', '', 0.0, 1000.0, 0.0, 0, '',
                    1700000000000, ${roomCode?.let { "'$it'" } ?: "NULL"})
            """.trimIndent()
        }
        db.execSQL(sharedRow(2, "ABC234"))
        val duplicate = runCatching { db.execSQL(sharedRow(3, "ABC234")) }
        assertTrue("a second row for the same room and pilot must be refused", duplicate.isFailure)
        // NULL is distinct from NULL in a SQLite unique index: solo rows keep not colliding.
        db.execSQL(sharedRow(4, null))
        db.query("SELECT COUNT(*) FROM challenges").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
        }
    }
}
