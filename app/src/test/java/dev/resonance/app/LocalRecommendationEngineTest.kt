package dev.resonance.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRecommendationEngineTest {
    @Test fun coListeningBuildsSymmetricDistanceWeightedLinks() {
        val events = listOf(
            ListeningEvent("a", "A", 1_000L, 180_000L, false),
            ListeningEvent("b", "B", 2_000L, 180_000L, false),
            ListeningEvent("c", "C", 3_000L, 180_000L, false)
        )
        val links = LocalRecommendationEngine.coListenWeights(events)
        assertEquals(1.0, links["a"]?.get("b") ?: 0.0, 0.0001)
        assertEquals(0.5, links["a"]?.get("c") ?: 0.0, 0.0001)
        assertEquals(links["a"]?.get("b"), links["b"]?.get("a"))
    }

    @Test fun coListeningDoesNotCrossThirtyMinuteSessions() {
        val events = listOf(
            ListeningEvent("a", "A", 1_000L, 180_000L, false),
            ListeningEvent("b", "B", 2_000L, 180_000L, false),
            ListeningEvent("c", "C", 2_000_000L, 180_000L, false)
        )
        val links = LocalRecommendationEngine.coListenWeights(events)
        assertTrue(links["a"]?.containsKey("c") != true)
        assertTrue(links["b"]?.containsKey("c") != true)
    }
}
