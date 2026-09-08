package com.meditation.stopwatch.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meditation.stopwatch.SettingsRepository
import kotlin.math.roundToInt

/** Breath guide, labels, screen and render-quality preferences. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(settings: SettingsRepository, onDismiss: () -> Unit) {
    val prefs by settings.settings.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetColour,
        contentColor = Mist,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            SheetTitle("Settings")
            Spacer(Modifier.height(12.dp))
            SwitchRow(
                title = "Breathing guide",
                subtitle = "The ring around the time inflates and deflates with a coherent breath",
                checked = prefs.breathGuide,
                onChange = settings::setBreathGuide,
            )
            SwitchRow(
                title = "Breath labels",
                subtitle = "Show inhale, hold and exhale under the time",
                checked = prefs.breathLabels,
                onChange = settings::setBreathLabels,
            )
            SwitchRow(
                title = "Keep screen on",
                subtitle = "Never let the display sleep during a session",
                checked = prefs.keepScreenOn,
                onChange = settings::setKeepScreenOn,
            )
            HorizontalDivider(
                Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            )
            SliderRow(
                title = "Render quality",
                subtitle = "${(prefs.renderScale * 100).roundToInt()} % of screen resolution. " +
                    "Lower saves battery and keeps older phones cool.",
                value = prefs.renderScale,
                onChange = settings::setRenderScale,
                valueRange = 0.3f..1f,
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, color = Mist, fontSize = 14.sp, fontWeight = FontWeight.Normal)
            Text(subtitle, color = Mist.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.Light)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink,
                checkedTrackColor = Glow,
                uncheckedThumbColor = Mist.copy(alpha = 0.6f),
                uncheckedTrackColor = Mist.copy(alpha = 0.12f),
                uncheckedBorderColor = Mist.copy(alpha = 0.3f),
            ),
        )
    }
}
