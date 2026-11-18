package dev.resonance.app.studio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.PI
import kotlin.math.floorMod
import kotlin.math.sin

object SongGeneratorClient {
    private const val MAX_AUDIO_BYTES = 100L * 1024L * 1024L

    suspend fun generateAndSave(
        context: Context,
        prompt: String,
        endpointUrl: String,
        apiKey: String,
        durationSeconds: Int = 12
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(prompt.isNotBlank()) { "Describe the audio you want to create" }
            require(durationSeconds in 5..30) { "Duration must be between 5 and 30 seconds" }
            val outDir = File(context.filesDir, "ai_tracks").apply {
                check(isDirectory || mkdirs()) { "Could not create the audio output folder" }
            }
            val safeName = prompt.trim().take(24)
                .replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "aurix_track" }
            val outFile = File(outDir, "${safeName}_${UUID.randomUUID()}.wav")
            val tempFile = File(outDir, "${outFile.name}.part")
            try {
                if (endpointUrl.isNotBlank()) {
                    val uri = URI(endpointUrl.trim())
                    require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()) {
                        "Generation endpoint must use HTTPS"
                    }
                    val conn = (URL(endpointUrl.trim()).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 15_000
                        readTimeout = 120_000
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        setRequestProperty("Accept", "audio/wav, audio/*")
                        if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                        doOutput = true
                    }
                    try {
                        val body = JSONObject().put("prompt", prompt).put("duration", durationSeconds)
                            .toString().toByteArray(Charsets.UTF_8)
                        conn.outputStream.use { it.write(body) }
                        val status = conn.responseCode
                        if (status !in 200..299) {
                            val detail = runCatching {
                                conn.errorStream?.bufferedReader()?.use { it.readText().take(500) }
                            }.getOrNull().orEmpty()
                            error("Generation endpoint returned HTTP $status ${detail.takeIf { it.isNotBlank() } ?: ""}")
                        }
                        val declaredLength = conn.contentLengthLong
                        require(declaredLength <= MAX_AUDIO_BYTES) { "Audio response exceeds 100 MB limit" }
                        BufferedInputStream(conn.inputStream).use { input ->
                            tempFile.outputStream().buffered().use { output ->
                                val buffer = ByteArray(8192)
                                var total = 0L
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    total += read
                                    require(total <= MAX_AUDIO_BYTES) { "Audio response exceeds 100 MB limit" }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                    } finally {
                        conn.disconnect()
                    }
                    require(isWaveFile(tempFile)) {
                        "Endpoint must return a valid WAV file; other audio formats are not supported yet"
                    }
                } else {
                    writeOfflineProceduralWav(tempFile, prompt, durationSeconds)
                }
                check(tempFile.renameTo(outFile)) { "Could not finalise generated audio file" }
                outFile
            } finally {
                if (tempFile.exists()) tempFile.delete()
            }
        }
    }

    private fun isWaveFile(file: File): Boolean {
        if (!file.isFile || file.length() < 44L) return false
        return file.inputStream().use { input ->
            val header = ByteArray(12)
            if (input.read(header) != header.size) return@use false
            String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
        }
    }

    private fun writeOfflineProceduralWav(file: File, seedText: String, seconds: Int) {
        val sampleRate = 22050
        val numSamples = sampleRate * seconds
        val scale = doubleArrayOf(261.63, 293.66, 329.63, 392.00, 440.00, 523.25)
        val hashOffset = floorMod(seedText.hashCode(), scale.size)
        val pcmData = ByteArray(numSamples * 2)
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val freq = scale[((i / (sampleRate / 2)) + hashOffset) % scale.size]
            val env = sin(PI * ((i % (sampleRate / 2)).toDouble() / (sampleRate / 2)))
            val sample = (sin(2.0 * PI * freq * t) * 0.4 + sin(PI * freq * t) * 0.2) * env
            buffer.putShort((sample * Short.MAX_VALUE).toInt().toShort())
        }
        FileOutputStreamCompat.writeWave(file, pcmData, sampleRate)
    }
}

private object FileOutputStreamCompat {
    fun writeWave(file: File, pcmData: ByteArray, sampleRate: Int) {
        file.outputStream().use { out ->
            val dataSize = pcmData.size
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(dataSize)
            }
            out.write(header.array())
            out.write(pcmData)
        }
    }
}
