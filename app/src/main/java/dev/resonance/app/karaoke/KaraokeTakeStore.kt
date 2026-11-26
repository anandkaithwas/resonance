package dev.resonance.app.karaoke

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class KaraokeTake(
    val id: String,
    val trackId: String,
    val fileName: String,
    val durationMs: Long,
    val averagePitchHz: Float?,
    val createdAt: Long
)

/** Stores take metadata locally; audio files stay in app-private storage. */
class KaraokeTakeStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("aurix_karaoke_takes", Context.MODE_PRIVATE)

    fun directory(): File = File(appContext.filesDir, "karaoke_takes").apply { mkdirs() }

    fun save(take: KaraokeTake) {
        val all = JSONArray(prefs.getString("takes", "[]"))
        all.put(JSONObject()
            .put("id", take.id)
            .put("trackId", take.trackId)
            .put("file", take.fileName)
            .put("duration", take.durationMs)
            .put("pitch", take.averagePitchHz?.toDouble() ?: JSONObject.NULL)
            .put("created", take.createdAt))
        prefs.edit().putString("takes", all.toString()).apply()
    }

    fun list(trackId: String): List<KaraokeTake> {
        val all = runCatching { JSONArray(prefs.getString("takes", "[]")) }.getOrNull() ?: return emptyList()
        return (0 until all.length()).mapNotNull { i ->
            val item = all.optJSONObject(i) ?: return@mapNotNull null
            if (item.optString("trackId") != trackId) return@mapNotNull null
            val pitch = item.opt("pitch")
            KaraokeTake(
                id = item.optString("id"),
                trackId = item.optString("trackId"),
                fileName = item.optString("file"),
                durationMs = item.optLong("duration"),
                averagePitchHz = if (pitch is Number) pitch.toFloat() else null,
                createdAt = item.optLong("created")
            )
        }.sortedByDescending { it.createdAt }
    }

    fun delete(take: KaraokeTake) {
        File(directory(), take.fileName).let { if (it.isFile) it.delete() }
        val all = runCatching { JSONArray(prefs.getString("takes", "[]")) }.getOrNull() ?: JSONArray()
        val next = JSONArray()
        for (i in 0 until all.length()) {
            val item = all.optJSONObject(i) ?: continue
            if (item.optString("id") != take.id) next.put(item)
        }
        prefs.edit().putString("takes", next.toString()).apply()
    }
}
