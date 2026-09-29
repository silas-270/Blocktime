package com.silas270.blocktime.ui.screens.settings

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import com.silas270.blocktime.ui.theme.Amber
import com.silas270.blocktime.ui.theme.Haze
import com.silas270.blocktime.ui.theme.Midnight

/**
 * The Settings screen's switch row. The whole row toggles, not just the switch: the row is a
 * large card, and a tap on its text used to do nothing.
 */
@Composable
internal fun PreferenceToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit
) {
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        interaction = if (enabled) {
            Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onToggle)
        } else {
            Modifier
        }
    ) {
        Switch(
            checked = checked && enabled,
            enabled = enabled,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Amber,
                checkedTrackColor = Amber.copy(alpha = 0.4f),
                uncheckedThumbColor = Haze,
                uncheckedTrackColor = Midnight
            )
        )
    }
}
