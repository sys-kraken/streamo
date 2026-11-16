package com.streamo.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.streamo.app.Platform
import com.streamo.app.data.LibraryStore
import com.streamo.app.player.EqPresets
import com.streamo.app.player.NowPlaying
import com.streamo.app.player.PlayerConnection
import kotlin.math.roundToInt

private enum class SheetPage(val title: String) {
    MAIN(""), QUALITY("Quality"), SPEED("Playback speed"), CAPTIONS("Captions"), SOUND("Sound"), SLEEP("Sleep timer")
}

/** The gear on the video: quality, speed, captions, sound (equalizer), audio only and the sleep timer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerSettingsSheet(now: NowPlaying, onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(SheetPage.MAIN) }
    val settings by LibraryStore.settings.collectAsState()
    val c = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surfaceContainer,
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(c.outline))
        },
    ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val forward = targetState != SheetPage.MAIN
                (slideInHorizontally(tween(220)) { if (forward) it / 4 else -it / 4 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(180)) { if (forward) -it / 4 else it / 4 } + fadeOut(tween(150)))
            },
            label = "sheet",
        ) { shown ->
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
                    .navigationBarsPadding().padding(bottom = 12.dp),
            ) {
                if (shown != SheetPage.MAIN) PageHeader(shown.title) { page = SheetPage.MAIN }
                when (shown) {
                    SheetPage.MAIN -> {
                        Text(
                            now.video.title, Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = c.onSurfaceVariant,
                        )
                        Group {
                            NavRow(Icons.Default.HighQuality, "Quality", if (now.height > 0) "${now.height}p" else "Auto") { page = SheetPage.QUALITY }
                            NavRow(Icons.Default.Speed, "Playback speed", speedLabel(now.speed)) { page = SheetPage.SPEED }
                            NavRow(
                                Icons.Default.ClosedCaption, "Captions",
                                now.captionIndex?.let { now.captions.getOrNull(it) } ?: if (now.captions.isEmpty()) "Unavailable" else "Off",
                            ) { page = SheetPage.CAPTIONS }
                        }
                        Group {
                            NavRow(
                                Icons.Default.Equalizer, "Sound",
                                listOfNotNull(
                                    if (settings.eqEnabled) settings.eqPreset else null,
                                    if (settings.volumeLeveling) "Leveling" else null,
                                ).joinToString(" · ").ifEmpty { "Standard" },
                            ) { page = SheetPage.SOUND }
                            SwitchRow(Icons.Default.Headphones, "Audio only", "Play just the sound to save data", now.audioOnly) {
                                PlayerConnection.setAudioOnly(it)
                            }
                            NavRow(Icons.Default.Bedtime, "Sleep timer", sleepLabel(now)) { page = SheetPage.SLEEP }
                        }
                    }
                    SheetPage.QUALITY -> Group {
                        if (now.heights.isEmpty()) Hint("Only one quality is available for this video.")
                        now.heights.forEach { h ->
                            val tag = when { h >= 2160 -> "4K"; h >= 1440 -> "QHD"; h >= 1080 -> "Full HD"; h >= 720 -> "HD"; else -> null }
                            ChoiceRow("${h}p", tag, h == now.height) { PlayerConnection.setQuality(h); onDismiss() }
                        }
                    }
                    SheetPage.SPEED -> {
                        Text(
                            speedLabel(now.speed), Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f).forEach { sp ->
                                Chip(if (sp == 1f) "Normal" else "${trimSpeed(sp)}x", sp == now.speed) { PlayerConnection.setSpeed(sp) }
                            }
                        }
                        Slider(
                            value = now.speed, onValueChange = { PlayerConnection.setSpeed(((it * 20).roundToInt() / 20f).coerceIn(0.25f, 3f)) },
                            valueRange = 0.25f..3f,
                            colors = SliderDefaults.colors(thumbColor = c.onBackground, activeTrackColor = c.onBackground, inactiveTrackColor = c.surfaceVariant),
                            modifier = Modifier.padding(horizontal = 20.dp),
                        )
                    }
                    SheetPage.CAPTIONS -> {
                        Group {
                            if (now.captions.isEmpty()) {
                                Hint("No captions for this video right now. YouTube sometimes holds them back; try again later.")
                            } else {
                                ChoiceRow("Off", null, now.captionIndex == null) { PlayerConnection.setCaption(null) }
                                now.captions.forEachIndexed { i, label ->
                                    ChoiceRow(label, null, now.captionIndex == i) { PlayerConnection.setCaption(i) }
                                }
                            }
                        }
                        SectionLabel("Text size")
                        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("small" to "Small", "normal" to "Normal", "large" to "Large").forEach { (value, label) ->
                                Chip(label, settings.captionSize == value) { LibraryStore.updateSettings { it.copy(captionSize = value) } }
                            }
                        }
                    }
                    SheetPage.SOUND -> SoundControls()
                    SheetPage.SLEEP -> Group {
                        ChoiceRow("Off", null, now.sleepAtMs == null && !now.sleepAtEnd) { PlayerConnection.setSleepTimer(null); onDismiss() }
                        listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
                            val label = when { m < 60 -> "$m minutes"; m == 60 -> "1 hour"; else -> "1 hour 30 minutes" }
                            ChoiceRow(label, null, false) {
                                PlayerConnection.setSleepTimer(m); onDismiss()
                                Platform.ui.toast("Playback will stop in ${label.replace("1 hour 30 minutes", "1.5 hours")}.")
                            }
                        }
                        ChoiceRow("End of this video", null, now.sleepAtEnd) { PlayerConnection.setSleepTimer(null, endOfVideo = true); onDismiss() }
                    }
                }
            }
        }
    }
}

/** Equalizer (on/off, presets, five bands) and volume leveling, applied to everything Streamo plays. */
@Composable
private fun SoundControls() {
    val settings by LibraryStore.settings.collectAsState()
    val c = MaterialTheme.colorScheme
    val gains = EqPresets.gains(settings.eqPreset, settings.eqCustom)
    Group {
        SwitchRow(Icons.Default.Equalizer, "Equalizer", "Shape the sound with a preset or your own", settings.eqEnabled) { on ->
            LibraryStore.updateSettings { it.copy(eqEnabled = on) }
        }
        SwitchRow(Icons.Default.Speed, "Volume leveling", "Keep loud and quiet videos at a similar volume", settings.volumeLeveling) { on ->
            LibraryStore.updateSettings { it.copy(volumeLeveling = on) }
        }
    }
    SectionLabel("Presets")
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (EqPresets.PRESETS.keys + "Custom").forEach { name ->
            Chip(name, settings.eqEnabled && settings.eqPreset == name) {
                LibraryStore.updateSettings { s ->
                    val custom = if (name == "Custom" && s.eqCustom.isEmpty()) EqPresets.gains(s.eqPreset, emptyList()) else s.eqCustom
                    s.copy(eqPreset = name, eqCustom = custom, eqEnabled = true)
                }
            }
        }
    }
    SectionLabel("Bands")
    Column(Modifier.padding(horizontal = 16.dp)) {
        EqPresets.FREQUENCIES.forEachIndexed { i, hz ->
            val value = gains.getOrElse(i) { 0f }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${EqPresets.label(hz)} Hz", Modifier.width(60.dp), style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                Slider(
                    value = value,
                    onValueChange = { v ->
                        LibraryStore.updateSettings { s ->
                            val base = EqPresets.gains(s.eqPreset, s.eqCustom).toMutableList()
                            base[i] = (v * 2).roundToInt() / 2f
                            s.copy(eqPreset = "Custom", eqCustom = base, eqEnabled = true)
                        }
                    },
                    valueRange = -12f..12f,
                    colors = SliderDefaults.colors(thumbColor = c.onBackground, activeTrackColor = c.onBackground, inactiveTrackColor = c.surfaceVariant),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    (if (value > 0) "+" else "") + ((value * 2).roundToInt() / 2f).toString().removeSuffix(".0") + " dB",
                    Modifier.width(56.dp).padding(start = 6.dp), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------- Building blocks ----------

/** Rows grouped on a slightly lighter card, like a premium settings list. */
@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) { content() }
}

@Composable
private fun PageHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RowIcon(icon: ImageVector) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, Modifier.size(20.dp)) }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(
            value, Modifier.padding(start = 8.dp).widthIn(max = 150.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowIcon(icon)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(body, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = c.background, checkedTrackColor = c.onBackground,
                uncheckedThumbColor = c.onSurfaceVariant, uncheckedTrackColor = c.surfaceVariant, uncheckedBorderColor = c.outline,
            ),
        )
    }
}

@Composable
private fun ChoiceRow(label: String, tag: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
        if (tag != null) {
            Text(
                tag, Modifier.padding(end = 12.dp).clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 6.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { if (selected) Icon(Icons.Default.Check, "Selected") }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, Modifier.padding(start = 20.dp, top = 14.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, Modifier.padding(18.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun trimSpeed(v: Float) = v.toString().removeSuffix(".0")

private fun speedLabel(speed: Float) = if (speed == 1f) "Normal" else "${trimSpeed(speed)}x"

/** "Off", "12 min left" or "End of video", for the sleep timer row. */
private fun sleepLabel(now: NowPlaying): String = when {
    now.sleepAtEnd -> "End of video"
    now.sleepAtMs != null -> "${((now.sleepAtMs - com.streamo.app.nowMillis()) / 60_000 + 1).coerceAtLeast(1)} min left"
    else -> "Off"
}
