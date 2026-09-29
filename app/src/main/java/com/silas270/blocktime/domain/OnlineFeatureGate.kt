package com.silas270.blocktime.domain

import com.silas270.blocktime.data.network.ServerState

/**
 * What a sharing control may do, derived from [ServerState] by [onlineAvailability]. Every
 * sharing button, field and settings row asks only this, the way the map asks only
 * [resolveEffectiveMapStyle], so "hidden" and "dimmed with a reason" are decided in one place.
 */
enum class OnlineFeatureAvailability {
    /** No server in the build, or sharing switched off: the control does not exist. */
    HIDDEN,

    DISABLED_OFFLINE,
    DISABLED_UNREACHABLE,

    /** Opted in and connected, but the server has not been asked yet. */
    DISABLED_CHECKING,

    ENABLED;
}

/**
 * Not configured and not opted in both hide the control entirely, because in both cases the
 * pilot has not asked for anything online (docs/shared-challenges.md "Why offline first"). The
 * other states dim it and say why; the cached crew stays visible through all of them.
 */
fun onlineAvailability(state: ServerState): OnlineFeatureAvailability = when (state) {
    ServerState.NOT_CONFIGURED, ServerState.DISABLED -> OnlineFeatureAvailability.HIDDEN
    ServerState.DEVICE_OFFLINE -> OnlineFeatureAvailability.DISABLED_OFFLINE
    ServerState.UNREACHABLE -> OnlineFeatureAvailability.DISABLED_UNREACHABLE
    ServerState.UNKNOWN -> OnlineFeatureAvailability.DISABLED_CHECKING
    ServerState.REACHABLE -> OnlineFeatureAvailability.ENABLED
}

/** The short line under a dimmed sharing control, or null when there is nothing to explain. */
fun onlineHint(availability: OnlineFeatureAvailability): String? = when (availability) {
    OnlineFeatureAvailability.DISABLED_OFFLINE -> "No connection"
    OnlineFeatureAvailability.DISABLED_UNREACHABLE -> "Server not reachable"
    OnlineFeatureAvailability.DISABLED_CHECKING -> "Checking…"
    OnlineFeatureAvailability.HIDDEN, OnlineFeatureAvailability.ENABLED -> null
}
