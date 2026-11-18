package dev.resonance.app.intelligence

import android.content.Context
import dev.resonance.app.AudioTrack
import dev.resonance.app.ListeningEvent
import dev.resonance.app.LocalRecommendationEngine
import dev.resonance.app.TrackRecommendation
import org.json.JSONArray
import org.json.JSONObject

/** Local-only SmartMix history and queue builder. */
class SmartMixEngine(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("aurix_smart_mix", Context.MODE_PRIVATE)

    fun record(track: AudioTrack, listenedMs: Long, skipped: Boolean = false) {
        val events = readEvents().toMutableList()
        events += ListeningEvent(track.id, track.artist, System.currentTimeMillis(), listenedMs.coerceAtLeast(0), skipped)
        val recent = events.takeLast(500)
        val array = JSONArray()
        recent.forEach { event ->
            array.put(JSONObject().put("id", event.trackId).put("artist", event.artist)
                .put("at", event.playedAt).put("ms", event.listenedMs).put("skip", event.skipped))
        }
        prefs.edit().putString("history", array.toString()).apply()
    }

    fun recommend(seed: AudioTrack?, library: List<AudioTrack>, count: Int = 15): List<TrackRecommendation> =
        LocalRecommendationEngine.recommend(seed, library, readEvents(), count = count)

    private fun readEvents(): List<ListeningEvent> {
        val array = runCatching { JSONArray(prefs.getString("history", "[]")) }.getOrNull() ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                add(ListeningEvent(item.optString("id"), item.optString("artist"),
                    item.optLong("at"), item.optLong("ms"), item.optBoolean("skip")))
            }
        }
    }
}
