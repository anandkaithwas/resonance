package dev.resonance.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class OnlineMusicResult(
    val id: String,
    val title: String,
    val artist: String,
    val provider: String,
    val streamUrl: String?,
    val landingUrl: String,
    val artworkUrl: String?,
    val license: String?,
    val attribution: String?
)

object FreeMusicSourcesRepository {
    private const val AUDIO_APP_NAME = "RESONANCE"

    suspend fun searchAudius(query: String): List<OnlineMusicResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://api.audius.co/v1/tracks/search?query=${encode(query)}&limit=25"
        val root = getJson(url)
        val data = root.optJSONArray("data") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val title = item.optString("title")
                val user = item.optJSONObject("user")
                val artist = user?.optString("name").orEmpty()
                if (id.isBlank() || title.isBlank()) continue
                val landing = item.optString("permalink").takeIf { it.startsWith("https://") }
                    ?: "https://audius.co"
                val stream = "https://api.audius.co/v1/tracks/$id/stream?app_name=$AUDIO_APP_NAME"
                val artwork = item.optJSONObject("artwork")?.optString("150x150")
                add(OnlineMusicResult(
                    id = "audius:$id",
                    title = title,
                    artist = artist.ifBlank { "Audius creator" },
                    provider = "Audius",
                    streamUrl = stream,
                    landingUrl = landing,
                    artworkUrl = artwork?.takeIf { it.startsWith("https://") },
                    license = item.optString("license").takeIf { it.isNotBlank() },
                    attribution = "Listen on Audius · ${artist.ifBlank { "Creator" }}"
                ))
            }
        }
    }

    suspend fun recommendAudius(seedArtist: String, seedTitle: String): List<OnlineMusicResult> {
        val query = if (seedArtist.isNotBlank()) seedArtist else seedTitle
        if (query.isBlank()) return emptyList()
        return searchAudius(query).filterNot { result ->
            result.title.equals(seedTitle, ignoreCase = true) && result.artist.equals(seedArtist, ignoreCase = true)
        }.distinctBy { it.id }.take(15)
    }

    suspend fun searchOpenverse(query: String): List<OnlineMusicResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://api.openverse.org/v1/audio/?q=${encode(query)}&category=music&page_size=25"
        val root = getJson(url)
        val data = root.optJSONArray("results") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val title = item.optString("title")
                val mediaUrl = item.optString("url")
                val landing = item.optString("foreign_landing_url")
                if (id.isBlank() || title.isBlank() || !mediaUrl.startsWith("https://") || !landing.startsWith("https://")) continue
                val creator = item.optString("creator").ifBlank { "Unknown creator" }
                val license = listOf(item.optString("license"), item.optString("license_version"))
                    .filter { it.isNotBlank() }.joinToString(" ").ifBlank { "License details unavailable" }
                add(OnlineMusicResult(
                    id = "openverse:$id",
                    title = title,
                    artist = creator,
                    provider = "Openverse",
                    streamUrl = mediaUrl,
                    landingUrl = landing,
                    artworkUrl = null,
                    license = license,
                    attribution = item.optString("attribution").ifBlank { "$title by $creator · $license" }
                ))
            }
        }
    }

    private fun getJson(address: String): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10000
            readTimeout = 15000
            setRequestProperty("User-Agent", "RESONANCE Android music player")
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Music source returned HTTP ${connection.responseCode}")
            }
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
