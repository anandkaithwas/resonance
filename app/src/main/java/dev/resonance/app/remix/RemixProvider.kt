package dev.resonance.app.remix

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

interface RemixProvider {
    val name: String

    suspend fun submitRemix(
        source: File,
        preset: RemixPreset,
        prompt: String,
        durationMs: Long,
        rightsConfirmed: Boolean
    ): Result<RemixJob>

    suspend fun getJobStatus(jobId: String): Result<RemixJob>
    suspend fun downloadResult(jobId: String, destination: File): Result<File>
    suspend fun cancelJob(jobId: String): Result<Unit>
}

/**
 * Adapter for a user-configured HTTPS remix backend.
 *
 * Contract (JSON unless noted):
 * - POST /remixes        multipart: `source` audio file + `metadata` JSON with
 *   preset, prompt, duration_ms and rights_confirmed. Returns job id/status.
 * - GET  /remixes/{id}   returns job status and progress.
 * - GET  /remixes/{id}/result returns a WAV audio body.
 * - POST /remixes/{id}/cancel cancels the job.
 *
 * The APK contains no embedded credential. The key is passed in memory by the caller
 * and must be held by a secure backend token broker or entered by the user.
 */
class HttpRemixProvider(
    private val baseUrl: String,
    private val apiKey: String = "",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()
) : RemixProvider {
    override val name: String = "Configured HTTPS remix provider"

    private val maxSourceBytes = 100L * 1024L * 1024L
    private val maxDurationMs = 15L * 60L * 1000L

    private fun requireHttpsEndpoint(): String {
        val url = baseUrl.trim()
        val parsed = url.toHttpUrlOrNull()
        require(parsed != null && parsed.scheme.equals("https", true) && parsed.host.isNotBlank()) {
            "Remix provider endpoint must use HTTPS"
        }
        return url.trimEnd('/')
    }

    private fun requestBuilder(url: String): Request.Builder {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer ${apiKey.trim()}")
        return builder
    }

    private fun executeJson(request: Request): JSONObject = client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("Remix provider returned HTTP ${response.code} ${body.take(200).trim()}")
        runCatching { JSONObject(body) }.getOrElse { error("Remix provider returned invalid JSON") }
    }

    override suspend fun submitRemix(
        source: File,
        preset: RemixPreset,
        prompt: String,
        durationMs: Long,
        rightsConfirmed: Boolean
    ): Result<RemixJob> = withContext(Dispatchers.IO) {
        runCatching {
            require(rightsConfirmed) { "Confirm that you have the rights to submit this audio" }
            require(source.isFile) { "Select a local source track" }
            require(source.length() in 1L..maxSourceBytes) { "Source audio must be between 1 byte and 100 MB" }
            require(durationMs in 1_000L..maxDurationMs) { "Source duration must be between 1 second and 15 minutes" }
            val text = prompt.trim().ifBlank { preset.stylePrompt }
            require(text.length <= 1_000) { "Style prompt must be 1000 characters or fewer" }
            val metadata = JSONObject()
                .put("preset", preset.id)
                .put("prompt", text)
                .put("duration_ms", durationMs)
                .put("rights_confirmed", true)
                .toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("metadata", "metadata.json", metadata)
                .addFormDataPart("source", source.name, source.asRequestBody("application/octet-stream".toMediaType()))
                .build()
            val json = executeJson(
                requestBuilder("${requireHttpsEndpoint()}/remixes").post(body).build()
            )
            val id = json.optString("id").ifBlank { json.optString("jobId") }
            require(id.isNotBlank()) { "Remix provider did not return a job id" }
            RemixJob(
                id = id,
                presetId = preset.id,
                prompt = text,
                status = RemixStatus.from(json.optString("status")),
                progress = json.optInt("progress"),
                provider = name
            )
        }
    }

    override suspend fun getJobStatus(jobId: String): Result<RemixJob> = withContext(Dispatchers.IO) {
        runCatching {
            require(jobId.isNotBlank()) { "Missing job id" }
            val json = executeJson(
                requestBuilder("${requireHttpsEndpoint()}/remixes/${encodeSegment(jobId)}").get().build()
            )
            RemixJob(
                id = json.optString("id").ifBlank { jobId },
                status = RemixStatus.from(json.optString("status")),
                progress = json.optInt("progress"),
                error = json.optString("error"),
                provider = name
            )
        }
    }

    override suspend fun downloadResult(jobId: String, destination: File): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(jobId.isNotBlank()) { "Missing job id" }
            destination.parentFile?.mkdirs()
            val temp = File(destination.parentFile, "${destination.name}.part")
            val request = requestBuilder("${requireHttpsEndpoint()}/remixes/${encodeSegment(jobId)}/result").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Result download failed with HTTP ${response.code}")
                val stream = response.body?.byteStream() ?: error("Result download returned no audio")
                stream.use { input ->
                    temp.outputStream().buffered().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= maxSourceBytes) { "Result audio exceeds 100 MB limit" }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
            require(isWave(temp)) { "Provider result is not a valid WAV file" }
            if (destination.exists() && !destination.delete()) error("Could not replace an existing result file")
            require(temp.renameTo(destination)) { "Could not finalise downloaded audio" }
            destination
        }.onFailure { runCatching { File(destination.parentFile, "${destination.name}.part").delete() } }
    }

    override suspend fun cancelJob(jobId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(jobId.isNotBlank()) { "Missing job id" }
            val request = requestBuilder("${requireHttpsEndpoint()}/remixes/${encodeSegment(jobId)}/cancel")
                .post(ByteArray(0).toRequestBody(null)).build()
            executeJson(request)
            Unit
        }
    }

    private fun encodeSegment(value: String): String = value.replace("/", "%2F").replace("?", "%3F")

    private fun isWave(file: File): Boolean {
        if (!file.isFile || file.length() < 44) return false
        val header = ByteArray(12)
        val input = file.inputStream()
        val read = input.read(header)
        input.close()
        if (read != header.size) return false
        return String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
    }
}
