package com.silas270.blocktime.data.dev

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.silas270.blocktime.BuildConfig
import com.silas270.blocktime.data.dev.FlightHook.Result
import com.silas270.blocktime.data.local.AppDatabase
import com.silas270.blocktime.data.local.airport.AirportRouteSqliteDataSource
import com.silas270.blocktime.data.model.FlightMode
import com.silas270.blocktime.data.repository.LocalAirportRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Adds flights to the logbook from a computer, in release builds too:
 *
 *     adb shell am broadcast -a com.silas270.blocktime.ADD_FLIGHTS -p com.silas270.blocktime \
 *         --es flights "JFK-HND,LHR-SYD"
 *
 * `--es mode FREE` logs them as Free Mode flights instead of Story Mode ones. The broadcast's
 * result line says what was added, or why nothing was.
 *
 * **Only adb can reach it.** The manifest guards the receiver with `android.permission.DUMP`,
 * which the adb shell holds and an ordinary app cannot obtain, so the phone has to be plugged
 * into a computer it has authorised for USB debugging. There is no password to extract from the
 * APK.
 *
 * **One switch turns it off:** `flightHookEnabled` in app/build.gradle.kts. False disables the
 * receiver in the manifest, so Android delivers nothing to it, and [BuildConfig.FLIGHT_HOOK_ENABLED]
 * stops it here as well; the code stays in place for switching it back on.
 */
class FlightHookReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.FLIGHT_HOOK_ENABLED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val message = try {
                addFlights(context.applicationContext, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Adding flights failed", e)
                "Failed: ${e.message}"
            }
            Log.i(TAG, message)
            pending.resultData = message
            pending.finish()
        }
    }

    private suspend fun addFlights(context: Context, intent: Intent): String {
        val legs = intent.getStringExtra(EXTRA_FLIGHTS)?.let(FlightHook::parse)
            ?: return "Nothing added: pass --es $EXTRA_FLIGHTS \"JFK-HND,LHR-SYD\""
        val mode = intent.getStringExtra(EXTRA_MODE)?.uppercase()?.let { name ->
            FlightMode.entries.firstOrNull { it.name == name } ?: return "Nothing added: unknown mode $name"
        } ?: FlightMode.STORY

        val database = AppDatabase.getInstance(context)
        val userId = database.userProfileDao().getProfile()?.id ?: return "Nothing added: no pilot profile yet"
        val airports = LocalAirportRepository(AirportRouteSqliteDataSource(context)).apply { ensureDatabaseCopied() }

        return when (val result = FlightHook.toFlightLogs(legs, userId, mode, System.currentTimeMillis(), airports::getAirportByIata, airports::findRoute)) {
            is Result.UnknownAirport -> "Nothing added: unknown airport ${result.iata}"
            is Result.Flights -> {
                result.logs.forEach { database.flightLogDao().insertFlightLog(it) }
                "Added ${result.logs.size} ${mode.name} flights: " +
                    result.logs.joinToString { "${it.originIata}-${it.destIata} ${it.distanceKm.toInt()} km" }
            }
        }
    }

    private companion object {
        const val TAG = "FlightHook"
        const val EXTRA_FLIGHTS = "flights"
        const val EXTRA_MODE = "mode"
    }
}
