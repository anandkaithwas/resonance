package dev.resonance.app.karaoke

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class KaraokeRecording(
    val file: File,
    val durationMs: Long,
    val averagePitchHz: Float?,
    val sampleCount: Int
)

/** Records microphone audio to a local WAV file. Permission is checked by the caller before start. */
class KaraokeRecorder(private val sampleRate: Int = 44_100) {
    @Volatile var isRecording: Boolean = false
        private set

    private var recorder: AudioRecord? = null

    suspend fun record(durationMs: Long, outputFile: File): Result<KaraokeRecording> = withContext(Dispatchers.IO) {
        runCatching {
            require(durationMs in 1_000L..300_000L) { "Recording duration must be between 1 and 300 seconds" }
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            require(minBuffer > 0) { "Microphone recording is not available on this device" }
            val bufferSize = maxOf(minBuffer, sampleRate / 2)
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            recorder = record
            require(record.state == AudioRecord.STATE_INITIALIZED) { "Could not initialise the microphone" }
            record.startRecording()
            isRecording = true
            val pcm = ByteArray(bufferSize)
            val allSamples = mutableListOf<Short>()
            val pitches = mutableListOf<Float>()
            val deadline = System.currentTimeMillis() + durationMs
            try {
                while (System.currentTimeMillis() < deadline && isRecording) {
                    val read = record.read(pcm, 0, pcm.size)
                    if (read > 0) {
                        val window = ShortArray(read / 2)
                        for (i in window.indices) {
                            val value = (pcm[i * 2].toInt() and 0xFF) or
                                ((pcm[i * 2 + 1].toInt() and 0xFF) shl 8)
                            window[i] = value.toShort()
                            allSamples += window[i]
                        }
                        PitchDetector.estimate(sampleRate, window)?.let { pitches += it }
                    }
                }
            } finally {
                isRecording = false
                runCatching { record.stop() }
                record.release()
                recorder = null
            }
            outputFile.parentFile?.mkdirs()
            writeWave(outputFile, allSamples.toShortArray(), sampleRate)
            val averagePitch = if (pitches.isEmpty()) null else pitches.sum() / pitches.size
            KaraokeRecording(
                file = outputFile,
                durationMs = durationMs.coerceAtMost(totalMs(allSamples.size, sampleRate)),
                averagePitchHz = averagePitch,
                sampleCount = allSamples.size
            )
        }
    }

    fun stop() {
        isRecording = false
    }

    private fun totalMs(sampleCount: Int, sampleRate: Int): Long =
        (sampleCount.toLong() * 1_000L / sampleRate.coerceAtLeast(1))

    private fun writeWave(file: File, samples: ShortArray, sampleRate: Int) {
        val dataSize = samples.size * 2
        file.outputStream().buffered().use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataSize)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
                putShort(1); putShort(1)
                putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
                put("data".toByteArray(Charsets.US_ASCII)); putInt(dataSize)
            }
            out.write(header.array())
            val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
            samples.forEach { body.putShort(it) }
            out.write(body.array())
        }
    }
}
