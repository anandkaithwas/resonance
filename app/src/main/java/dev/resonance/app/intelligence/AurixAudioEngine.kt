package dev.resonance.app.intelligence

import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

enum class AurixMood { CALM, BALANCED, ENERGETIC }
enum class SoundReality { NATURAL, CLUB, CINEMA, NIGHT }

data class AudioInsight(
    val rms: Float,
    val bass: Float,
    val mid: Float,
    val treble: Float,
    val mood: AurixMood,
    val scene: SoundReality,
    val gainsDb: FloatArray
)

/** Offline PCM feature extraction and adaptive three-band EQ; deterministic DSP, not a trained ML model. */
class AurixAudioEngine {
    private var lowState = FloatArray(0)
    private var midState = FloatArray(0)
    private var smoothedBassGain = 0f
    private var smoothedMidGain = 0f
    private var smoothedTrebleGain = 0f
    var enabled: Boolean = true
    var reality: SoundReality = SoundReality.NATURAL
    var latestInsight: AudioInsight = AudioInsight(0f, 0f, 0f, 0f, AurixMood.BALANCED, reality, floatArrayOf(0f, 0f, 0f))
        private set

    fun process(input: ShortArray, sampleRate: Int, channels: Int): ShortArray {
        if (input.isEmpty() || sampleRate <= 0 || channels <= 0) return input.copyOf()
        if (lowState.size != channels) {
            lowState = FloatArray(channels)
            midState = FloatArray(channels)
        }
        val bassAlpha = 1f - exp((-2.0 * Math.PI * 180.0 / sampleRate).toFloat())
        val midAlpha = 1f - exp((-2.0 * Math.PI * 2600.0 / sampleRate).toFloat())
        var bassEnergy = 0.0
        var midEnergy = 0.0
        var trebleEnergy = 0.0
        var totalEnergy = 0.0
        val lows = FloatArray(input.size)
        val mids = FloatArray(input.size)
        val highs = FloatArray(input.size)
        for (i in input.indices) {
            val channel = i % channels
            val x = input[i] / 32768f
            lowState[channel] += bassAlpha * (x - lowState[channel])
            midState[channel] += midAlpha * (x - midState[channel])
            val low = lowState[channel]
            val midBand = midState[channel] - lowState[channel]
            val high = x - midState[channel]
            lows[i] = low
            mids[i] = midBand
            highs[i] = high
            bassEnergy += low.toDouble() * low
            midEnergy += midBand.toDouble() * midBand
            trebleEnergy += high.toDouble() * high
            totalEnergy += x.toDouble() * x
        }
        val count = input.size.coerceAtLeast(1)
        val bass = sqrt(bassEnergy / count).toFloat()
        val mid = sqrt(midEnergy / count).toFloat()
        val treble = sqrt(trebleEnergy / count).toFloat()
        val rms = sqrt(totalEnergy / count).toFloat()
        val energy = (rms * 5f).coerceIn(0f, 1f)
        val mood = when {
            energy < 0.12f -> AurixMood.CALM
            energy > 0.32f -> AurixMood.ENERGETIC
            else -> AurixMood.BALANCED
        }
        val targetBass = when (reality) {
            SoundReality.CLUB -> 3f
            SoundReality.NIGHT -> -1.5f
            else -> (0.8f - bass * 8f).coerceIn(-1.5f, 2f)
        }
        val targetMid = when (reality) {
            SoundReality.CINEMA -> 1.5f
            SoundReality.NIGHT -> 0.8f
            else -> (0.45f - mid * 3f).coerceIn(-1f, 1.2f)
        }
        val targetTreble = when (reality) {
            SoundReality.CLUB -> 1f
            SoundReality.NIGHT -> -2f
            SoundReality.CINEMA -> 1.2f
            else -> (0.35f - treble * 5f).coerceIn(-1.5f, 1.5f)
        }
        // Per-buffer gain smoothing reduces abrupt changes and audible pumping.
        smoothedBassGain += (targetBass - smoothedBassGain) * 0.12f
        smoothedMidGain += (targetMid - smoothedMidGain) * 0.12f
        smoothedTrebleGain += (targetTreble - smoothedTrebleGain) * 0.12f
        val gains = floatArrayOf(smoothedBassGain, smoothedMidGain, smoothedTrebleGain)
        latestInsight = AudioInsight(rms, bass, mid, treble, mood, reality, gains)
        if (!enabled) return input.copyOf()
        val bg = 10f.pow(smoothedBassGain / 20f)
        val mg = 10f.pow(smoothedMidGain / 20f)
        val tg = 10f.pow(smoothedTrebleGain / 20f)
        return ShortArray(input.size) { i ->
            val y = lows[i] * bg + mids[i] * mg + highs[i] * tg
            (y.coerceIn(-0.98f, 0.98f) * 32767f).toInt().toShort()
        }
    }

    fun reset() {
        lowState.fill(0f)
        midState.fill(0f)
        smoothedBassGain = 0f
        smoothedMidGain = 0f
        smoothedTrebleGain = 0f
        latestInsight = AudioInsight(0f, 0f, 0f, 0f, AurixMood.BALANCED, reality, floatArrayOf(0f, 0f, 0f))
    }
}
