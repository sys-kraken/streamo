package com.streamo.app.player

import com.streamo.app.data.SkipSegment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs
import kotlin.math.ln

/**
 * SponsorBlock for the video playing now, shared by the player (which skips) and the screen
 * (which shows "Skip" and "Jump to highlight" buttons and marks segments on the seek bar).
 */
object SponsorState {
    private val _segments = MutableStateFlow<List<SkipSegment>>(emptyList())
    /** All segments of the current video that aren't turned off, highlight included. */
    val segments: StateFlow<List<SkipSegment>> = _segments.asStateFlow()

    private val _prompt = MutableStateFlow<SkipSegment?>(null)
    /** A segment set to "ask" that is playing right now: show a Skip button for it. */
    val prompt: StateFlow<SkipSegment?> = _prompt.asStateFlow()

    fun setSegments(list: List<SkipSegment>) { _segments.value = list; _prompt.value = null }
    fun setPrompt(segment: SkipSegment?) { if (_prompt.value != segment) _prompt.value = segment }
}

/**
 * Equalizer presets as a curve (decibels at a few frequencies), so they work whatever bands the
 * phone's equalizer has: each band takes the curve's value at its own centre frequency.
 */
object EqPresets {
    /** The bands Streamo's own equalizer screen shows (Hz). */
    val FREQUENCIES = listOf(60, 230, 910, 3600, 14000)

    val PRESETS: Map<String, List<Float>> = linkedMapOf(
        "Flat" to listOf(0f, 0f, 0f, 0f, 0f),
        "Bass boost" to listOf(6f, 4f, 0f, 0f, 0f),
        "Bass reducer" to listOf(-6f, -3f, 0f, 0f, 0f),
        "Vocal" to listOf(-2f, -1f, 3f, 4f, 1f),
        "Treble boost" to listOf(0f, 0f, 0f, 3f, 6f),
        "Rock" to listOf(5f, 2f, -2f, 2f, 5f),
        "Pop" to listOf(-1f, 2f, 4f, 2f, -1f),
        "Hip-hop" to listOf(5f, 3f, 0f, 1f, 3f),
        "Classical" to listOf(4f, 2f, -1f, 2f, 4f),
        "Podcast" to listOf(-4f, 0f, 4f, 3f, -2f),
        "Late night" to listOf(-3f, 0f, 2f, 1f, -3f),
    )

    /** The gains for [preset] (or the custom ones), one per [FREQUENCIES] entry. */
    fun gains(preset: String, custom: List<Float>): List<Float> =
        if (preset == "Custom") FREQUENCIES.indices.map { custom.getOrElse(it) { 0f } }
        else PRESETS[preset] ?: PRESETS.getValue("Flat")

    /** The gain at [hz], read off the curve through [gains] (straight lines on a log-frequency scale). */
    fun gainAt(hz: Int, gains: List<Float>): Float {
        val x = ln(hz.coerceAtLeast(20).toDouble())
        val xs = FREQUENCIES.map { ln(it.toDouble()) }
        if (x <= xs.first()) return gains.first()
        if (x >= xs.last()) return gains.last()
        val i = xs.indexOfLast { it <= x }
        val t = (x - xs[i]) / (xs[i + 1] - xs[i])
        return (gains[i] + (gains[i + 1] - gains[i]) * t).toFloat()
    }

    fun label(hz: Int) = if (hz >= 1000) "${if (hz % 1000 == 0) hz / 1000 else (hz / 100) / 10.0}k" else "$hz"

    /** True when the preset's gains are all (nearly) zero. */
    fun isFlat(gains: List<Float>) = gains.all { abs(it) < 0.05f }
}

/**
 * YouTube's automatic captions repeat each line in the next cue (they "roll up"), which shows the
 * same words twice. Keeps each line once, and at most the last two lines.
 */
fun cleanCaptionLines(texts: List<String>): String =
    texts.flatMap { it.split('\n') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .takeLast(2)
        .joinToString("\n")
