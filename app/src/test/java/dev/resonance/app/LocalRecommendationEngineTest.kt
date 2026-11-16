package dev.resonance.app

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRecommendationEngineTest {
    private fun track(id: String, artist: String) = AudioTrack(id, "Song $id", artist, Uri.parse("file:///music/$id.mp3"), false)

    @Test fun excludesSeedAndRecentlySkippedTracks() {
        val library = listOf(track("a", "A"), track("b", "B"), track("c", "C"))
        val history = listOf(ListeningEvent("c", "C", 1000L, 5000L, true))
        val result = LocalRecommendationEngine.recommend(library[0], library, history, now = 2000L)
        assertTrue(result.none { it.track.id == "a" || it.track.id == "c" })
    }

    @Test fun sameArtistAndCoListeningInfluenceRanking() {
        val library = listOf(track("seed", "A"), track("same", "A"), track("other", "B"), track("third", "C"))
        val history = listOf(
            ListeningEvent("seed", "A", 1_000L, 180_000L, false),
            ListeningEvent("other", "B", 2_000L, 180_000L, false),
            ListeningEvent("seed", "A", 3_000L, 180_000L, false),
            ListeningEvent("other", "B", 4_000L, 180_000L, false)
        )
        val result = LocalRecommendationEngine.recommend(library[0], library, history, now = 100_000L)
        assertEquals("other", result.first().track.id)
    }

    @Test fun noArtistAppearsMoreThanThreeTimes() {
        val library = (1..8).map { track("a$it", "Same Artist") } + (1..3).map { track("b$it", "Other $it") }
        val result = LocalRecommendationEngine.recommend(null, library, emptyList(), now = 1L, count = 12)
        assertTrue(result.groupingBy { it.track.artist }.eachCount().values.all { it <= 3 })
    }
}
