package com.streamo.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.streamo.app.Platform
import com.streamo.app.data.LibraryStore
import com.streamo.app.data.SponsorBlock
import com.streamo.app.player.NowPlaying
import com.streamo.app.player.PlayerConnection
import com.streamo.app.player.SponsorState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A short message in the middle of the video after a gesture: an icon and a value. */
private data class Feedback(val icon: ImageVector, val text: String, val side: Int = 0, val id: Long = 0)

/**
 * Streamo's own player controls, drawn over the video: tap to show or hide, double-tap a side to
 * skip 10 s, hold for 2x, and in fullscreen swipe up/down for brightness (left) and volume (right).
 */
@Composable
fun BoxScope.PlayerControls(
    now: NowPlaying,
    fullscreen: Boolean,
    onFullscreen: (() -> Unit)?,
    onSettings: (() -> Unit)?,
    onCollapse: (() -> Unit)?,
    controlsVisible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    // The gesture handlers are set up once; these always hold the latest values.
    val current by rememberUpdatedState(now)
    val visibleNow by rememberUpdatedState(controlsVisible)
    val setVisible by rememberUpdatedState(onVisibleChange)
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var touched by remember { mutableLongStateOf(0L) } // last interaction, keeps controls up
    var feedback by remember { mutableStateOf<Feedback?>(null) }
    var seekRun by remember { mutableIntStateOf(0) }
    val segments by SponsorState.segments.collectAsState()
    val prompt by SponsorState.prompt.collectAsState()

    LaunchedEffect(Unit) {
        while (true) {
            position = PlayerConnection.positionMs()
            duration = PlayerConnection.durationMs()
            buffered = PlayerConnection.bufferedMs()
            delay(250)
        }
    }
    // Hide the controls 3 s after the last touch while playing, like YouTube.
    LaunchedEffect(controlsVisible, touched, now.isPlaying, scrub) {
        if (controlsVisible && now.isPlaying && scrub == null) {
            delay(3_000)
            onVisibleChange(false)
        }
    }
    // Messages fade after a moment, except while still holding (2x) or swiping.
    LaunchedEffect(feedback?.id) {
        val id = feedback?.id ?: return@LaunchedEffect
        if (id >= Long.MAX_VALUE - 1) return@LaunchedEffect
        delay(700); feedback = null; seekRun = 0
    }

    fun poke() { touched = com.streamo.app.nowMillis() }

    // ---------- Gestures (whole video area) ----------
    var holdJob by remember { mutableStateOf<Job?>(null) }
    var holding by remember { mutableStateOf(false) }
    var speedBefore by remember { mutableFloatStateOf(1f) }
    var swipeSide by remember { mutableIntStateOf(0) }
    var swipeValue by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { setVisible(!visibleNow); poke() },
                    onDoubleTap = { at ->
                        val forward = at.x > size.width / 2f
                        seekRun = if ((seekRun > 0) == forward && seekRun != 0) seekRun + (if (forward) 10 else -10) else if (forward) 10 else -10
                        PlayerConnection.seekTo((PlayerConnection.positionMs() + if (forward) 10_000 else -10_000).coerceAtLeast(0))
                        feedback = Feedback(
                            if (forward) Icons.Default.FastForward else Icons.Default.FastRewind,
                            "${kotlin.math.abs(seekRun)} seconds", side = if (forward) 1 else -1, id = com.streamo.app.nowMillis(),
                        )
                    },
                    onLongPress = {}, // so letting go after holding doesn't count as a tap
                    onPress = {
                        holdJob = scope.launch {
                            delay(450)
                            if (!current.isPlaying) return@launch
                            holding = true
                            speedBefore = current.speed
                            PlayerConnection.setSpeed(2f)
                            feedback = Feedback(Icons.Default.FastForward, "2x speed", id = Long.MAX_VALUE)
                        }
                        tryAwaitRelease()
                        holdJob?.cancel()
                        if (holding) {
                            holding = false
                            PlayerConnection.setSpeed(speedBefore)
                            feedback = null
                        }
                    },
                )
            }
            .then(
                if (!fullscreen) Modifier else Modifier.pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { at ->
                            swipeSide = if (at.x < size.width / 2f) -1 else 1
                            swipeValue = (if (swipeSide < 0) Platform.ui.brightness() else Platform.ui.volume())
                                .takeIf { it >= 0 } ?: 0.5f
                        },
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            swipeValue = (swipeValue - dy / (size.height * 0.8f)).coerceIn(0f, 1f)
                            if (swipeSide < 0) Platform.ui.setBrightness(swipeValue) else Platform.ui.setVolume(swipeValue)
                            feedback = Feedback(
                                if (swipeSide < 0) Icons.Default.Brightness6 else Icons.Default.VolumeUp,
                                "${(swipeValue * 100).toInt()}%", id = Long.MAX_VALUE - 1,
                            )
                        },
                        onDragEnd = { feedback = feedback?.copy(id = com.streamo.app.nowMillis()) },
                    )
                },
            ),
    )

    // ---------- Controls ----------
    AnimatedVisibility(
        visible = controlsVisible || scrub != null,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(250)),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0x59000000))) {
            // Top: minimize, title (fullscreen), captions, settings.
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent)))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onCollapse != null && !fullscreen) ControlButton(Icons.Default.KeyboardArrowDown, "Minimize", size = 28.dp, onClick = onCollapse)
                if (fullscreen) {
                    Text(
                        now.video.title, Modifier.weight(1f).padding(start = 12.dp), color = Color.White,
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                } else Spacer(Modifier.weight(1f))
                if (now.captions.isNotEmpty()) {
                    ControlButton(
                        if (now.captionIndex != null) Icons.Default.ClosedCaption else Icons.Outlined.ClosedCaption,
                        if (now.captionIndex != null) "Captions off" else "Captions on",
                    ) { PlayerConnection.toggleCaptions(); poke() }
                }
                if (onSettings != null) ControlButton(Icons.Default.Settings, "Settings") { onSettings(); poke() }
            }

            // Middle: previous, play/pause, next.
            Row(
                Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(if (fullscreen) 64.dp else 40.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlButton(Icons.Default.SkipPrevious, "Previous", size = 30.dp) { PlayerConnection.previous(); poke() }
                Box(
                    Modifier.size(if (fullscreen) 72.dp else 60.dp).clip(CircleShape).background(Color(0x66000000))
                        .clickable { PlayerConnection.togglePlay(); poke() },
                    contentAlignment = Alignment.Center,
                ) {
                    val ended = duration > 0 && position >= duration - 500 && !now.isPlaying
                    when {
                        now.isBuffering && !now.isPlaying && now.started ->
                            CircularProgressIndicator(Modifier.size(36.dp), color = Color.White, strokeWidth = 3.dp)
                        else -> Icon(
                            when { ended -> Icons.Default.Replay; now.isPlaying -> Icons.Default.Pause; else -> Icons.Default.PlayArrow },
                            if (now.isPlaying) "Pause" else "Play", tint = Color.White,
                            modifier = Modifier.size(if (fullscreen) 44.dp else 38.dp),
                        )
                    }
                }
                ControlButton(Icons.Default.SkipNext, "Next", size = 30.dp) { PlayerConnection.next(); poke() }
            }

            // Bottom: time, fullscreen, seek bar.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000)))),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    val shown = scrub?.let { (it * duration).toLong() } ?: position
                    Text(
                        if (now.video.isLive) "LIVE" else "${formatTime(shown)}  /  ${formatTime(duration)}",
                        color = Color.White, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    )
                    Spacer(Modifier.weight(1f))
                    if (onFullscreen != null) {
                        ControlButton(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen, "Fullscreen") {
                            onFullscreen(); poke()
                        }
                    }
                }
                SeekBar(
                    position = position, duration = duration, buffered = buffered,
                    segments = segments.filter { !it.isHighlight }.map { it.startMs to it.endMs },
                    scrub = scrub,
                    onScrub = { scrub = it; poke() },
                    onSeek = { f -> PlayerConnection.seekTo((f * duration).toLong()); scrub = null; poke() },
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = if (fullscreen) 12.dp else 4.dp),
                )
            }
        }
    }

    // Thin progress line while the controls are hidden (not in fullscreen).
    if (!controlsVisible && scrub == null && !fullscreen && duration > 0) {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(2.dp).background(Color(0x33FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((position.toFloat() / duration).coerceIn(0f, 1f)).background(Color.White))
        }
    }

    // SponsorBlock: "Skip sponsor" for segments set to Ask, "Jump to highlight" near the start.
    val p = prompt
    if (p != null) {
        val name = SponsorBlock.NAMES[p.category] ?: "segment"
        Row(
            Modifier.align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = if (controlsVisible) 64.dp else 16.dp)
                .clip(RoundedCornerShape(50))
                .background(Color(0xF2FFFFFF))
                .clickable { PlayerConnection.seekTo(if (p.isHighlight) p.startMs else p.endMs); SponsorState.setPrompt(null) }
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (p.isHighlight) "Jump to highlight" else "Skip ${name.lowercase()}", color = Color.Black,
                style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.SkipNext, null, Modifier.size(18.dp), tint = Color.Black)
        }
    }

    // Gesture feedback: double-tap seeks show on their side, the rest in the middle.
    val f = feedback
    if (f != null) {
        val alignment = when (f.side) { -1 -> Alignment.CenterStart; 1 -> Alignment.CenterEnd; else -> Alignment.Center }
        Column(
            Modifier.align(alignment).padding(horizontal = 36.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color(0x8C000000))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(f.icon, null, Modifier.size(28.dp), tint = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(f.text, color = Color.White, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium))
        }
    }
}

@Composable
private fun ControlButton(icon: ImageVector, label: String, size: Dp = 24.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, Modifier.size(size), tint = Color.White) }
}

/**
 * Slim seek bar: white for what's played, light grey for what's loaded, and SponsorBlock segments
 * as brighter blocks. Tap or drag anywhere on it; it thickens and shows a thumb while dragging.
 */
@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    buffered: Long,
    segments: List<Pair<Long, Long>>,
    scrub: Float?,
    onScrub: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = scrub != null
    val thickness by animateDpAsState(if (active) 5.dp else 3.dp, label = "seekbar")
    val thumb by animateDpAsState(if (active) 16.dp else 12.dp, label = "thumb")
    val played = scrub ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val loaded = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    var latest by remember { mutableFloatStateOf(0f) }
    Canvas(
        modifier.fillMaxWidth().height(24.dp)
            .pointerInput(duration) {
                detectTapGestures { at -> if (duration > 0) onSeek((at.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { at -> latest = (at.x / size.width).coerceIn(0f, 1f); onScrub(latest) },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        latest = (change.position.x / size.width).coerceIn(0f, 1f)
                        onScrub(latest)
                    },
                    onDragEnd = { onSeek(latest) },
                    onDragCancel = { onSeek(latest) },
                )
            },
    ) {
        val h = thickness.toPx()
        val y = size.height / 2 - h / 2
        val r = CornerRadius(h / 2, h / 2)
        drawRoundRect(Color(0x40FFFFFF), Offset(0f, y), Size(size.width, h), r)
        drawRoundRect(Color(0x73FFFFFF), Offset(0f, y), Size(size.width * loaded, h), r)
        drawRoundRect(Color.White, Offset(0f, y), Size(size.width * played, h), r)
        if (duration > 0) segments.forEach { (start, end) ->
            val x0 = size.width * (start.toFloat() / duration).coerceIn(0f, 1f)
            val x1 = size.width * (end.toFloat() / duration).coerceIn(0f, 1f)
            drawRect(Color(0xFFBDBDBD), Offset(x0, y), Size((x1 - x0).coerceAtLeast(2f), h))
        }
        drawCircle(Color.White, thumb.toPx() / 2, Offset(size.width * played, size.height / 2))
    }
}

/** Captions drawn by Streamo: centered, white with a soft shadow (no box), never doubled. */
@Composable
fun BoxScope.CaptionsLayer(fullscreen: Boolean, controlsVisible: Boolean) {
    val text by PlayerConnection.captionText.collectAsState()
    val settings by LibraryStore.settings.collectAsState()
    if (text.isBlank()) return
    val scale = when (settings.captionSize) { "small" -> 0.82f; "large" -> 1.3f; else -> 1f }
    val base = if (fullscreen) 20f else 15f
    val lift by animateDpAsState(if (controlsVisible) (if (fullscreen) 72.dp else 52.dp) else 14.dp, label = "captions")
    Text(
        text,
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 28.dp).offset(y = -lift),
        color = Color.White,
        textAlign = TextAlign.Center,
        style = TextStyle(
            fontSize = (base * scale).sp,
            lineHeight = (base * scale * 1.3f).sp,
            fontWeight = FontWeight.Medium,
            shadow = Shadow(Color(0xE6000000), Offset(0f, 2f), blurRadius = 8f),
        ),
    )
}

/** Audio only: the video's picture, dimmed, with a label and a way back to the video. */
@Composable
fun BoxScope.AudioOnlyCover(now: NowPlaying, full: Boolean) {
    AsyncImage(now.video.thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    Box(Modifier.matchParentSize().background(Color(0x8C000000)))
    if (!full) return
    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Headphones, null, Modifier.size(36.dp), tint = Color.White)
        Spacer(Modifier.height(6.dp))
        Text("Audio only", color = Color.White, style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}

/** The "Show video" pill shown with the audio-only cover (above the controls so it's always tappable). */
@Composable
fun BoxScope.ShowVideoButton() {
    Row(
        Modifier.align(Alignment.TopEnd).padding(top = 52.dp, end = 12.dp)
            .clip(RoundedCornerShape(50)).background(Color.White)
            .clickable { PlayerConnection.setAudioOnly(false) }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Videocam, null, Modifier.size(18.dp), tint = Color.Black)
        Spacer(Modifier.width(6.dp))
        Text("Show video", color = Color.Black, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold))
    }
}

/** 1:05 or 1:02:03. */
fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}
