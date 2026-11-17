package dev.resonance.app.studio

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

object SongGeneratorClient {
    suspend fun generateAndSave(
        context: Context,
        prompt: String,
        endpointUrl: String,
        apiKey: String,
        durationSeconds: Int = 12
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val outDir = File(context.filesDir, "ai_tracks").apply { mkdirs() }
            val safeName = prompt.trim().take(24).replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "synth_track" }
            val outFile = File(outDir, "${safeName}_${System.currentTimeMillis() % 10000}.wav")
            if (endpointUrl.isNotBlank()) {
                val conn = (URL(endpointUrl.trim()).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    setRequestProperty("Content-Type", "application/json")
                    if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                    doOutput = true
                }
                val body = """{"prompt":"${prompt.replace("\"", "\\\"")}","duration":$durationSeconds}"""
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode !in 200..299) error("Endpoint returned HTTP ${conn.responseCode}")
                conn.inputStream.use { input -> outFile.outputStream().use { output -> input.copyTo(output) } }
            } else {
                writeOfflineProceduralWav(outFile, prompt, durationSeconds.coerceIn(5, 30))
            }
            outFile
        }
    }

    private fun writeOfflineProceduralWav(file: File, seedText: String, seconds: Int) {
        val sampleRate = 22050
        val numSamples = sampleRate * seconds
        val scale = doubleArrayOf(261.63, 293.66, 329.63, 392.00, 440.00, 523.25)
        val hashOffset = seedText.hashCode().let { if (it < 0) -it else it }
        val pcmData = ByteArray(numSamples * 2)
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            val freq = scale[((i / (sampleRate / 2)) + hashOffset) % scale.size]
            val env = sin(PI * ((i % (sampleRate / 2)).toDouble() / (sampleRate / 2)))
            val sample = (sin(2.0 * PI * freq * t) * 0.4 + sin(PI * freq * t) * 0.2) * env
            buffer.putShort((sample * Short.MAX_VALUE).toInt().toShort())
        }
        FileOutputStream(file).use { out ->
            val dataSize = pcmData.size
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + dataSize); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(sampleRate); putInt(sampleRate * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(dataSize)
            }
            out.write(header.array()); out.write(pcmData)
        }
    }
}
