package com.meditation.stopwatch.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meditation.stopwatch.SettingsRepository
import com.meditation.stopwatch.audio.SoundId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop

/** Sheet background: the theme surface, slightly translucent so the visuals glow through. */
internal val SheetColour = Color(0xF20E0B1C)

/** Sounds play whenever their slider is up, so the sheet doubles as an audition panel. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundSheet(settings: SettingsRepository, onDismiss: () -> Unit) {
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SheetTitle("Sounds", Modifier.weight(1f))
                TextButton(onClick = { settings.muteAll() }) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeOff, contentDescription = null, tint = Mist.copy(alpha = 0.7f))
                    Spacer(Modifier.width(6.dp))
                    Text("Mute all", color = Mist.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(8.dp))
            SliderRow(
                title = "Master volume",
                subtitle = null,
                value = prefs.masterVolume,
                onChange = settings::setMasterVolume,
            )
            HorizontalDivider(
                Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
            )
            for (id in SoundId.entries) {
                SliderRow(
                    title = id.title,
                    subtitle = id.subtitle,
                    value = prefs.volume(id),
                    onChange = { settings.setVolume(id, it) },
                )
            }
        }
    }
}

@Composable
internal fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = Mist,
        fontSize = 15.sp,
        fontWeight = FontWeight.Light,
        letterSpacing = 0.3.em,
    )
}

/**
 * A labelled slider whose knob follows the finger immediately and whose writes to the settings
 * store are conflated to at most ~25 per second, so the audio engine hears every drag live without
 * the DataStore being hammered on every frame.  The persisted value is only re-applied to the
 * knob when the finger is not on it (e.g. after "Mute all").
 */
@Composable
internal fun SliderRow(
    title: String,
    subtitle: String?,
    value: Float,
    onChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    var local by remember { mutableFloatStateOf(value) }
    var dragging by remember { mutableStateOf(false) }
    val latestOnChange by rememberUpdatedState(onChange)

    LaunchedEffect(value) { if (!dragging) local = value }
    LaunchedEffect(Unit) {
        snapshotFlow { local }.drop(1).conflate().collect {
            latestOnChange(it)
            delay(40)
        }
    }

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Mist, fontSize = 14.sp, fontWeight = FontWeight.Normal)
                if (subtitle != null) {
                    Text(subtitle, color = Mist.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.Light)
                }
            }
        }
        Slider(
            value = local,
            onValueChange = { local = it; dragging = true },
            onValueChangeFinished = { dragging = false },
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Glow,
                activeTrackColor = Glow.copy(alpha = 0.8f),
                inactiveTrackColor = Mist.copy(alpha = 0.15f),
            ),
        )
    }
}
