package com.streamo.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.streamo.app.data.LibraryStore
import com.streamo.app.player.EqPresets
import kotlin.math.roundToInt

/** Equalizer: presets (Bass boost, Vocal, …) or your own five bands. Applied to everything Streamo plays. */
@Composable
fun EqualizerScreen(nav: Nav, contentPadding: PaddingValues) {
    val settings by LibraryStore.settings.collectAsState()
    val c = MaterialTheme.colorScheme
    val gains = EqPresets.gains(settings.eqPreset, settings.eqCustom)

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Equalizer", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Switch(
                    checked = settings.eqEnabled,
                    onCheckedChange = { on -> LibraryStore.updateSettings { it.copy(eqEnabled = on) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = c.background, checkedTrackColor = c.onBackground,
                        uncheckedThumbColor = c.onSurfaceVariant, uncheckedTrackColor = c.surfaceVariant, uncheckedBorderColor = c.outline,
                    ),
                    modifier = Modifier.padding(end = 12.dp),
                )
            }
        }
        item {
            Text(
                if (settings.eqEnabled) "Shapes the sound of every video, in the background too."
                else "Turn on to shape the sound. Works with headphones, Bluetooth and the phone's speaker.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
            )
        }
        item { SectionHeader("Presets") }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (EqPresets.PRESETS.keys + "Custom").forEach { name ->
                    Chip(name, settings.eqPreset == name) {
                        LibraryStore.updateSettings { s ->
                            // Starting "Custom" from the preset you had, so you can tweak it.
                            val custom = if (name == "Custom" && s.eqCustom.isEmpty()) EqPresets.gains(s.eqPreset, emptyList()) else s.eqCustom
                            s.copy(eqPreset = name, eqCustom = custom, eqEnabled = true)
                        }
                    }
                }
            }
        }
        item { SectionHeader("Bands") }
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                EqPresets.FREQUENCIES.forEachIndexed { i, hz ->
                    val value = gains.getOrElse(i) { 0f }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${EqPresets.label(hz)} Hz", Modifier.width(64.dp), style = MaterialTheme.typography.labelLarge)
                        Slider(
                            value = value,
                            onValueChange = { v ->
                                LibraryStore.updateSettings { s ->
                                    val base = EqPresets.gains(s.eqPreset, s.eqCustom).toMutableList()
                                    base[i] = (v * 2).roundToInt() / 2f // half-decibel steps
                                    s.copy(eqPreset = "Custom", eqCustom = base, eqEnabled = true)
                                }
                            },
                            valueRange = -12f..12f,
                            colors = SliderDefaults.colors(
                                thumbColor = c.onBackground, activeTrackColor = c.onBackground,
                                inactiveTrackColor = c.surfaceVariant,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            (if (value > 0) "+" else "") + ((value * 2).roundToInt() / 2f).toString().removeSuffix(".0") + " dB",
                            Modifier.width(64.dp).padding(start = 8.dp),
                            style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Text(
                "Tip: \"Volume leveling\" in Settings keeps loud and quiet videos at a similar volume.",
                Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
            )
        }
    }
}
