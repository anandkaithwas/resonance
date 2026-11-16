package dev.resonance.app

import kotlin.math.ln

data class ListeningEvent(
    val trackId: String,
    val artist: String,
    val playedAt: Long,
    val listenedMs: Long,
    val skipped: Boolean
)

data class TrackRecommendation(
    val track: AudioTrack,
    val score: Double,
    val reason: String
)

/**
 * On-device recommendation engine. It uses only the user's local library and local play history.
 * It deliberately does not copy Prism's implementation.
 */
object LocalRecommendationEngine {
    private const val SESSION_GAP_MS = 30L * 60_000L
    private const val CO_LISTEN_WINDOW = 3
    private const val RECENTLY_HEARD_MS = 4L * 60 * 60_000L
    private const val SKIP_COOLDOWN_MS = 60L * 24 * 60 * 60_000L

    fun recommend(
        seed: AudioTrack?,
        library: List<AudioTrack>,
        history: List<ListeningEvent>,
        now: Long = System.currentTimeMillis(),
        count: Int = 12
    ): List<TrackRecommendation> {
        if (library.isEmpty() || count <= 0) return emptyList()
        val recent = history.sortedBy { it.playedAt }
        val latestSeed = seed ?: recent.lastOrNull()?.let { event ->
            library.firstOrNull { it.id == event.trackId }
        }
        val seedId = latestSeed?.id
        val seedArtist = latestSeed?.artist.orEmpty()
        val skippedRecently = recent.filter { it.skipped && now - it.playedAt < SKIP_COOLDOWN_MS }.map { it.trackId }.toSet()
        val heardRecently = recent.filter { now - it.playedAt < RECENTLY_HEARD_MS }.map { it.trackId }.toSet()
        val playCounts = recent.groupingBy { it.trackId }.eachCount()
        val coListen = coListenWeights(recent)
        val seedCo = seedId?.let { coListen[it].orEmpty() }.orEmpty()

        val scored = library.asSequence()
            .filter { it.id != seedId && it.id !in skippedRecently }
            .distinctBy { it.id }
            .map { candidate ->
                val sameArtist = seedArtist.isNotBlank() && candidate.artist.equals(seedArtist, true)
                val coScore = seedCo[candidate.id] ?: 0.0
                val artistHistory = recent.count { it.artist.equals(candidate.artist, true) }
                val familiarity = ln(1.0 + (playCounts[candidate.id] ?: 0)) / 4.0
                val recencyPenalty = if (candidate.id in heardRecently) 0.15 else 1.0
                val score = (coScore * 2.4 + if (sameArtist) 1.8 else 0.0 +
                    ln(1.0 + artistHistory) * 0.22 + familiarity * 0.25 + 0.05) * recencyPenalty
                val reason = when {
                    coScore >= 1.0 -> "Often played alongside your recent music"
                    sameArtist -> "More by ${candidate.artist}"
                    artistHistory > 0 -> "An artist you've listened to"
                    else -> "From your music library"
                }
                TrackRecommendation(candidate, score, reason)
            }
            .sortedByDescending { it.score }
            .toList()

        val output = mutableListOf<TrackRecommendation>()
        val artistCounts = mutableMapOf<String, Int>()
        var lastArtist = seedArtist.lowercase()
        val remaining = scored.toMutableList()
        while (output.size < count && remaining.isNotEmpty()) {
            val index = remaining.indexOfFirst { item ->
                val artist = item.track.artist.lowercase()
                artist != lastArtist && (artistCounts[artist] ?: 0) < 3
            }.takeIf { it >= 0 } ?: remaining.indexOfFirst {
                (artistCounts[it.track.artist.lowercase()] ?: 0) < 3
            }.takeIf { it >= 0 } ?: break
            val next = remaining.removeAt(index)
            output += next
            val artist = next.track.artist.lowercase()
            artistCounts[artist] = (artistCounts[artist] ?: 0) + 1
            lastArtist = artist
        }
        return output
    }

    fun coListenWeights(events: List<ListeningEvent>): Map<String, Map<String, Double>> {
        val result = mutableMapOf<String, MutableMap<String, Double>>()
        val sorted = events.sortedBy { it.playedAt }
        var sessionStart = 0
        for (i in sorted.indices) {
            if (i > 0 && sorted[i].playedAt - sorted[i - 1].playedAt > SESSION_GAP_MS) sessionStart = i
            for (j in maxOf(sessionStart, i - CO_LISTEN_WINDOW) until i) {
                val a = sorted[i].trackId
                val b = sorted[j].trackId
                if (a == b) continue
                val weight = 1.0 / (i - j)
                result.getOrPut(a) { mutableMapOf() }[b] = (result[a]?.get(b) ?: 0.0) + weight
                result.getOrPut(b) { mutableMapOf() }[a] = (result[b]?.get(a) ?: 0.0) + weight
            }
        }
        return result
    }
}
