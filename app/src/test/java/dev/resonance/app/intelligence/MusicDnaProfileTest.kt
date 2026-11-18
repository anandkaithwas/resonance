package dev.resonance.app.intelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicDnaProfileTest {
    @Test fun profileFieldsRemainNormalizedFractions() {
        val profile = MusicDnaProfile("1", "Track", "Artist", .2f, .4f, .1f, .3f, "BALANCED", 1L)
        assertTrue(profile.bass in 0f..1f)
        assertTrue(profile.mid in 0f..1f)
        assertTrue(profile.treble in 0f..1f)
        assertEquals("BALANCED", profile.mood)
    }
}
