package dev.resonance.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRecommendationEngineTest {
    @Test
    fun coListenWeightsAreSymmetricWithinSession() {
        val events = listOf(
            ListeningEvent("a", "Artist A", 1_000L, 60_000L, false),
            ListeningEvent("b", "Artist B", 2_000L, 60_000L, false)
        )

        val weights = LocalRecommendationEngine.coListenWeights(events)

        assertEquals(weights["a"]?.get("b"), weights["b"]?.get("a"))
        assertTrue((weights["a"]?.get("b") ?: 0.0) > 0.0)
    }

    @Test
    fun coListenDoesNotCrossSessionGap() {
        val events = listOf(
            ListeningEvent("a", "Artist A", 1_000L, 60_000L, false),
            ListeningEvent("b", "Artist B", 31L * 60_000L + 1_000L, 60_000L, false)
        )

        val weights = LocalRecommendationEngine.coListenWeights(events)

        assertTrue(weights["a"]?.containsKey("b") != true)
        assertTrue(weights["b"]?.containsKey("a") != true)
    }

    @Test
    fun emptyHistoryProducesNoCoListenWeights() {
        assertTrue(LocalRecommendationEngine.coListenWeights(emptyList()).isEmpty())
    }
}
