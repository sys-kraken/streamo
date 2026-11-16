package com.streamo.app.player

import com.streamo.app.data.SponsorBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerExtrasTest {
    @Test
    fun equalizerCurveFitsAnyBands() {
        val bass = EqPresets.gains("Bass boost", emptyList())
        assertEquals(6f, EqPresets.gainAt(60, bass))
        assertEquals(6f, EqPresets.gainAt(31, bass), "below the lowest band keeps its value")
        val mid = EqPresets.gainAt(120, bass) // between 60 Hz (+6) and 230 Hz (+4)
        assertTrue(mid < 6f && mid > 4f, "$mid")
        assertEquals(0f, EqPresets.gainAt(16000, bass))
        assertTrue(EqPresets.isFlat(EqPresets.gains("Flat", emptyList())))
        assertEquals(listOf(1f, 2f, 0f, 0f, 0f), EqPresets.gains("Custom", listOf(1f, 2f)))
    }

    @Test
    fun sponsorBlockDefaultsKeepOldBehaviour() {
        assertEquals("skip", SponsorBlock.action(emptyMap(), "sponsor"))
        assertEquals("off", SponsorBlock.action(emptyMap(), "intro"))
        assertEquals("ask", SponsorBlock.action(emptyMap(), "poi_highlight"))
        assertEquals("ask", SponsorBlock.action(mapOf("sponsor" to "ask"), "sponsor"))
        assertTrue(SponsorBlock.NAMES.keys.containsAll(SponsorBlock.CATEGORIES))
    }
}
