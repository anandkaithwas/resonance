package dev.resonance.app.karaoke

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lightweight autocorrelation pitch estimator for monophonic microphone input.
 *
 * This is guidance only: it estimates the dominant fundamental frequency of a short
 * PCM window. It is not a professional vocal diagnosis and it does not identify notes.
 */
object PitchDetector {
    private const val MIN_HZ = 70f
    private const val MAX_HZ = 1100f

    /** Returns the estimated fundamental frequency in Hz, or null when the window is silence/unvoiced. */
    fun estimate(sampleRate: Int, pcm: ShortArray, offset: Int = 0, length: Int = pcm.size - offset): Float? {
        if (sampleRate <= 0 || length < 512 || offset < 0 || offset + length > pcm.size) return null

        var energy = 0.0
        var zeroCrossings = 0
        for (i in offset until offset + length) {
            val s = pcm[i] / 32768.0
            energy += s * s
            if (i > offset && (s >= 0) != (pcm[i - 1] >= 0)) zeroCrossings++
        }
        val rms = kotlin.math.sqrt(energy / length)
        if (rms < 0.012) return null // near silence

        // Too many zero crossings indicates noise/unvoiced audio rather than a tonal voice.
        val crossingHz = zeroCrossings * sampleRate / (2.0 * length)
        if (crossingHz > 3000.0) return null

        val minLag = max(1, (sampleRate / MAX_HZ).roundToInt())
        val maxLag = min(length / 2, (sampleRate / MIN_HZ).roundToInt())
        if (maxLag <= minLag) return null

        // Normalised square difference (a compact YIN-style score): lower is a better match.
        fun scoreAt(lag: Int): Double {
            if (lag < minLag || lag > maxLag) return Double.MAX_VALUE
            var diff = 0.0
            var norm = 0.0
            var i = offset
            while (i + lag < offset + length) {
                val a = pcm[i] / 32768.0
                val b = pcm[i + lag] / 32768.0
                diff += (a - b) * (a - b)
                norm += a * a + b * b
                i++
            }
            return if (norm <= 1e-9) Double.MAX_VALUE else diff / norm
        }

        var bestScore = Double.MAX_VALUE
        for (lag in minLag..maxLag) bestScore = minOf(bestScore, scoreAt(lag))
        if (bestScore == Double.MAX_VALUE || bestScore > 0.72) return null

        // Periodic waveforms match at multiples of the true period. Prefer the shortest
        // lag among near-equal minima so a 220 Hz tone is not reported as 110 Hz.
        val tolerance = bestScore + 0.01
        var bestLag = -1
        for (lag in minLag..maxLag) {
            if (scoreAt(lag) <= tolerance) {
                bestLag = lag
                break
            }
        }
        if (bestLag < 0) return null

        // Parabolic interpolation around the best lag improves sub-sample resolution.
        val left = scoreAt(bestLag - 1)
        val right = scoreAt(bestLag + 1)
        val denom = left - 2 * bestScore + right
        val correctedLag = if (abs(denom) > 1e-9) bestLag + 0.5 * (left - right) / denom else bestLag.toDouble()
        if (correctedLag <= 0.0) return null
        return (sampleRate / correctedLag).toFloat().coerceIn(MIN_HZ, MAX_HZ)
    }

    /** Human-readable nearest note name (A4 = 440 Hz), for gentle guidance only. */
    fun noteName(frequencyHz: Float): String {
        if (frequencyHz <= 0f) return "—"
        val midi = (69 + 12 * kotlin.math.log2(frequencyHz / 440f)).roundToInt()
        val names = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        return "${names[((midi % 12) + 12) % 12]}${midi / 12 - 1}"
    }
}
