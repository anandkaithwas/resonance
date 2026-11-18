package dev.resonance.app.intelligence

import org.junit.Assert.*
import org.junit.Test

class AurixAudioEngineTest {
    @Test fun silenceRemainsFiniteAndSilent() {
        val output = AurixAudioEngine().process(ShortArray(2048), 44100, 2)
        assertTrue(output.all { it == 0.toShort() })
    }

    @Test fun outputNeverClips() {
        val engine = AurixAudioEngine()
        val input = ShortArray(4096) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        val output = engine.process(input, 48000, 2)
        assertEquals(input.size, output.size)
        assertTrue(output.all { it in Short.MIN_VALUE..Short.MAX_VALUE })
    }

    @Test fun bypassPreservesPcmExactly() {
        val engine = AurixAudioEngine().apply { enabled = false }
        val input = ShortArray(1000) { (it * 29).toShort() }
        assertArrayEquals(input, engine.process(input, 44100, 2))
    }

    @Test fun analysisClassifiesLowEnergyAsCalm() {
        val engine = AurixAudioEngine()
        engine.process(ShortArray(4096) { 100 }, 44100, 2)
        assertEquals(AurixMood.CALM, engine.latestInsight.mood)
    }

    @Test fun realityPresetIsReflectedInInsight() {
        val engine = AurixAudioEngine().apply { reality = SoundReality.CLUB }
        engine.process(ShortArray(2048) { 3000 }, 44100, 2)
        assertEquals(SoundReality.CLUB, engine.latestInsight.scene)
    }
}
