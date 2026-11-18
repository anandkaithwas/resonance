package dev.resonance.app.intelligence

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class MusicDnaProfile(
    val trackId: String,
    val title: String,
    val artist: String,
    val bass: Float,
    val mid: Float,
    val treble: Float,
    val rms: Float,
    val mood: String,
    val updatedAt: Long
)

/** Stores a compact, local-only signal fingerprint; it does not claim BPM or musical-key detection. */
class MusicDnaStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("aurix_music_dna", Context.MODE_PRIVATE)

    fun save(trackId: String, title: String, artist: String, insight: AudioInsight) {
        if (trackId.isBlank()) return
        val profile = JSONObject()
            .put("id", trackId).put("title", title).put("artist", artist)
            .put("bass", insight.bass).put("mid", insight.mid).put("treble", insight.treble)
            .put("rms", insight.rms).put("mood", insight.mood.name)
            .put("updated", System.currentTimeMillis())
        val all = JSONArray(prefs.getString("profiles", "[]"))
        val next = JSONArray()
        for (i in 0 until all.length()) {
            val old = all.optJSONObject(i) ?: continue
            if (old.optString("id") != trackId) next.put(old)
        }
        next.put(profile)
        prefs.edit().putString("profiles", next.toString()).apply()
    }

    fun profiles(): List<MusicDnaProfile> {
        val all = runCatching { JSONArray(prefs.getString("profiles", "[]")) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until all.length()) {
                val p = all.optJSONObject(i) ?: continue
                add(MusicDnaProfile(p.optString("id"), p.optString("title"), p.optString("artist"),
                    p.optDouble("bass").toFloat(), p.optDouble("mid").toFloat(),
                    p.optDouble("treble").toFloat(), p.optDouble("rms").toFloat(),
                    p.optString("mood"), p.optLong("updated")))
            }
        }.sortedByDescending { it.updatedAt }
    }
}
