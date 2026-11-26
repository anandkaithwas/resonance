package dev.resonance.app.karaoke

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class PitchDetectorTest {
    @Test
    fun detectsSineWaveFundamental() {
        val sampleRate = 44_100
        val frequency = 220f
        val samples = ShortArray(sampleRate / 4) { i ->
            (sin(2.0 * PI * frequency * i / sampleRate) * 12_000).toInt().toShort()
        }
        val estimated = PitchDetector.estimate(sampleRate, samples)
        assertEquals(true, estimated != null)
        assertEquals(frequency, estimated!!, 8f)
    }

    @Test
    fun silenceHasNoPitch() {
        val estimated = PitchDetector.estimate(44_100, ShortArray(8_192))
        assertEquals(null, estimated)
    }

    @Test
    fun noteNamesUseA4Reference() {
        assertEquals("A4", PitchDetector.noteName(440f))
        assertEquals("C4", PitchDetector.noteName(261.63f))
        assertEquals("A3", PitchDetector.noteName(220f))
    }
}
