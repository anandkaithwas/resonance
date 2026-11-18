package dev.resonance.app.intelligence

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
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

/**
 * Fully offline, deterministic PCM feature extraction and adaptive three-band EQ.
 * This is signal processing + heuristics, not a trained ML model.
 */
class AurixAudioEngine {
    private var lowState = 0f
    private var midState = 0f
    private var smoothedBassGain = 0f
    private var smoothedMidGain = 0f
    private var smoothedTrebleGain = 0f
    var enabled: Boolean = true
    var reality: SoundReality = SoundReality.NATURAL
    var latestInsight: AudioInsight = AudioInsight(0f, 0f, 0f, 0f, AurixMood.BALANCED, reality, floatArrayOf(0f,0f,0f))
        private set

    fun process(input: ShortArray, sampleRate: Int, channels: Int): ShortArray {
        if (input.isEmpty() || sampleRate <= 0 || channels <= 0) return input.copyOf()
        val bassAlpha = (1f - kotlin.math.exp((-2.0 * Math.PI * 180.0 / sampleRate).toFloat()))
        val midAlpha = (1f - kotlin.math.exp((-2.0 * Math.PI * 2600.0 / sampleRate).toFloat()))
        var bassEnergy = 0.0
        var midEnergy = 0.0
        var trebleEnergy = 0.0
        var totalEnergy = 0.0
        val lows = FloatArray(input.size)
        val mids = FloatArray(input.size)
        val highs = FloatArray(input.size)
        for (i in input.indices) {
            val x = input[i] / 32768f
            lowState += bassAlpha * (x - lowState)
            midState += midAlpha * (x - midState)
            val low = lowState
            val mid = midState - lowState
            val high = x - midState
            lows[i] = low; mids[i] = mid; highs[i] = high
            bassEnergy += low.toDouble() * low
            midEnergy += mid.toDouble() * mid
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
            SoundReality.CLUB -> 3.0f
            SoundReality.NIGHT -> -1.5f
            else -> (0.8f - bass * 8f).coerceIn(-1.5f, 2.0f)
        }
        val targetMid = when (reality) {
            SoundReality.CINEMA -> 1.5f
            SoundReality.NIGHT -> 0.8f
            else -> (0.45f - mid * 3f).coerceIn(-1.0f, 1.2f)
        }
        val targetTreble = when (reality) {
            SoundReality.CLUB -> 1.0f
            SoundReality.NIGHT -> -2.0f
            SoundReality.CINEMA -> 1.2f
            else -> (0.35f - treble * 5f).coerceIn(-1.5f, 1.5f)
        }
        // Slow gain movement prevents audible pumping between adjacent PCM buffers.
        smoothedBassGain += (targetBass - smoothedBassGain) * 0.12f
        smoothedMidGain += (targetMid - smoothedMidGain) * 0.12f
        smoothedTrebleGain += (targetTreble - smoothedTrebleGain) * 0.12f
        val gains = floatArrayOf(smoothedBassGain, smoothedMidGain, smoothedTrebleGain)
        latestInsight = AudioInsight(rms, bass, mid, treble, mood, reality, gains)
        if (!enabled) return input.copyOf()
        val bg = dbToGain(smoothedBassGain)
        val mg = dbToGain(smoothedMidGain)
        val tg = dbToGain(smoothedTrebleGain)
        return ShortArray(input.size) { i ->
            val y = lows[i] * bg + mids[i] * mg + highs[i] * tg
            (y.coerceIn(-0.98f, 0.98f) * 32767f).toInt().toShort()
        }
    }

    fun reset() {
        lowState = 0f; midState = 0f
        smoothedBassGain = 0f; smoothedMidGain = 0f; smoothedTrebleGain = 0f
        latestInsight = AudioInsight(0f,0f,0f,0f,AurixMood.BALANCED,reality,floatArrayOf(0f,0f,0f))
    }

    private fun dbToGain(db: Float): Float = 10f.pow(db / 20f)
}

private fun Float.pow(power: Float): Float = kotlin.math.pow(this, power)
