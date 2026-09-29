package com.silas270.blocktime.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.runtime.Composable
import com.silas270.blocktime.data.network.ServerState

/**
 * "Shared challenges", the online opt-in (docs/shared-challenges.md "Two signals"). Off is the
 * default and means nothing leaves the phone; the subtitle says so, and while on it says whether
 * our server is currently reachable, so a dead server or a missing connection never looks like
 * a broken switch. Not rendered at all when the build has no server ([ServerState.NOT_CONFIGURED]);
 * the screen decides that, the way it decides every other row's presence.
 */
@Composable
internal fun OnlineFeaturesPreferenceRow(
    enabled: Boolean,
    serverState: ServerState,
    onToggle: (Boolean) -> Unit
) {
    PreferenceToggleRow(
        icon = Icons.Outlined.Groups,
        title = "Shared challenges",
        subtitle = when {
            !enabled -> "Off · everything stays on this phone"
            serverState == ServerState.DEVICE_OFFLINE -> "On · no connection"
            serverState == ServerState.UNREACHABLE -> "On · server not reachable"
            else -> "On · your pilot name and progress are shared with your crew"
        },
        checked = enabled,
        onToggle = onToggle
    )
}
