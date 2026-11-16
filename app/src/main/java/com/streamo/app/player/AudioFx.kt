package com.streamo.app.player

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import android.util.Log
import com.streamo.app.data.Settings

/**
 * Streamo's sound effects on the player's audio: the equalizer (presets are curves, applied to
 * whatever bands this phone's equalizer has) and volume leveling (a gentle compressor plus a
 * limiter, so quiet videos come up and loud ones don't blast).
 */
class AudioFx {
    private var sessionId = 0
    private var equalizer: Equalizer? = null
    private var leveler: DynamicsProcessing? = null

    /** Attach to the player's audio session (call again when it changes), then apply [settings]. */
    fun attach(audioSessionId: Int, settings: Settings) {
        if (audioSessionId == 0 || audioSessionId == sessionId) return apply(settings)
        release()
        sessionId = audioSessionId
        apply(settings)
    }

    fun apply(settings: Settings) {
        if (sessionId == 0) return
        applyEqualizer(settings)
        applyLeveling(settings)
    }

    private fun applyEqualizer(settings: Settings) {
        val gains = EqPresets.gains(settings.eqPreset, settings.eqCustom)
        val wanted = settings.eqEnabled && !EqPresets.isFlat(gains)
        if (!wanted) {
            equalizer?.enabled = false
            return
        }
        runCatching {
            val eq = equalizer ?: Equalizer(0, sessionId).also { equalizer = it }
            val (min, max) = eq.bandLevelRange.let { it[0] to it[1] }
            for (band in 0 until eq.numberOfBands) {
                val centerHz = eq.getCenterFreq(band.toShort()) / 1000
                val millibels = (EqPresets.gainAt(centerHz, gains) * 100).toInt().coerceIn(min.toInt(), max.toInt())
                eq.setBandLevel(band.toShort(), millibels.toShort())
            }
            eq.enabled = true
        }.onFailure { Log.w("Streamo", "Equalizer unavailable", it) }
    }

    private fun applyLeveling(settings: Settings) {
        if (Build.VERSION.SDK_INT < 28) return
        if (!settings.volumeLeveling) {
            leveler?.enabled = false
            return
        }
        runCatching {
            val dp = leveler ?: createLeveler().also { leveler = it }
            dp.enabled = true
        }.onFailure { Log.w("Streamo", "Volume leveling unavailable", it) }
    }

    @androidx.annotation.RequiresApi(28)
    private fun createLeveler(): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            /* channelCount = */ 2,
            /* preEqInUse = */ false, 0,
            /* mbcInUse = */ true, 1,
            /* postEqInUse = */ false, 0,
            /* limiterInUse = */ true,
        ).build()
        val dp = DynamicsProcessing(0, sessionId, config)
        // One wide band: above -26 dB, squeeze 3:1, then make up 7 dB, so levels even out.
        val band = DynamicsProcessing.MbcBand(
            /* inUse = */ true, /* cutoffFrequency = */ 20_000f, /* attackTime = */ 20f, /* releaseTime = */ 300f,
            /* ratio = */ 3f, /* threshold = */ -26f, /* kneeWidth = */ 6f, /* noiseGateThreshold = */ -90f,
            /* expanderRatio = */ 1f, /* preGain = */ 0f, /* postGain = */ 7f,
        )
        dp.setMbcBandAllChannelsTo(0, band)
        // Never clip: hold peaks under -1 dB.
        dp.setLimiterAllChannelsTo(
            DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f),
        )
        return dp
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { leveler?.release() }
        equalizer = null
        leveler = null
        sessionId = 0
    }
}
