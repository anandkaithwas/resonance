package dev.resonance.app.remix

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private local metadata for generated versions; audio never leaves app-private storage. */
class RemixStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("aurix_remixes", Context.MODE_PRIVATE)

    fun outputDirectory(): File = File(appContext.filesDir, "remixes").apply { mkdirs() }

    fun list(): List<RemixJob> {
        val array = runCatching { JSONArray(prefs.getString("jobs", "[]")) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            RemixJob(
                id = item.optString("id"),
                sourceTrackId = item.optString("sourceTrackId"),
                sourceTitle = item.optString("sourceTitle"),
                presetId = item.optString("presetId"),
                prompt = item.optString("prompt"),
                status = RemixStatus.from(item.optString("status")),
                progress = item.optInt("progress"),
                provider = item.optString("provider"),
                resultFileName = item.optString("resultFileName"),
                error = item.optString("error"),
                createdAt = item.optLong("createdAt")
            )
        }.sortedByDescending { it.createdAt }
    }

    fun upsert(job: RemixJob) {
        val jobs = list().filter { it.id != job.id }.toMutableList()
        jobs.add(job)
        val array = JSONArray()
        jobs.forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("sourceTrackId", item.sourceTrackId)
                .put("sourceTitle", item.sourceTitle)
                .put("presetId", item.presetId)
                .put("prompt", item.prompt)
                .put("status", item.status.value)
                .put("progress", item.progress)
                .put("provider", item.provider)
                .put("resultFileName", item.resultFileName)
                .put("error", item.error)
                .put("createdAt", item.createdAt))
        }
        prefs.edit().putString("jobs", array.toString()).apply()
    }

    fun delete(job: RemixJob) {
        if (job.resultFileName.isNotBlank()) {
            runCatching { File(outputDirectory(), job.resultFileName).delete() }
        }
        val array = JSONArray()
        list().filter { it.id != job.id }.forEach { item ->
            array.put(JSONObject()
                .put("id", item.id)
                .put("sourceTrackId", item.sourceTrackId)
                .put("sourceTitle", item.sourceTitle)
                .put("presetId", item.presetId)
                .put("prompt", item.prompt)
                .put("status", item.status.value)
                .put("progress", item.progress)
                .put("provider", item.provider)
                .put("resultFileName", item.resultFileName)
                .put("error", item.error)
                .put("createdAt", item.createdAt))
        }
        prefs.edit().putString("jobs", array.toString()).apply()
    }

    fun resultFile(job: RemixJob): File? =
        job.resultFileName.takeIf { it.isNotBlank() }?.let { File(outputDirectory(), it) }
}
