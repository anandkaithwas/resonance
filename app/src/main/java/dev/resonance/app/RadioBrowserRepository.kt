package dev.resonance.app

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

data class RadioStation(
    val stationUuid: String,
    val name: String,
    val streamUrl: String,
    val country: String,
    val countryCode: String,
    val language: String,
    val tags: String,
    val bitrate: Int
)

object RadioBrowserRepository {
    private const val USER_AGENT = "RESONANCE/0.2.0 (Android music player)"
    private const val DNS_NAME = "all.api.radio-browser.info"

    suspend fun search(query: String = "", countryCode: String = "", tag: String = ""): List<RadioStation> =
        withContext(Dispatchers.IO) {
            val params = mutableListOf("hidebroken=true", "limit=60", "order=votes", "reverse=true")
            if (query.isNotBlank()) params += "name=${encode(query)}"
            if (countryCode.isNotBlank()) params += "countrycode=${encode(countryCode)}"
            if (tag.isNotBlank()) params += "tag=${encode(tag)}"
            requestWithServerFallback("/json/stations/search?${params.joinToString("&")}")
        }

    suspend fun registerClick(stationUuid: String) = withContext(Dispatchers.IO) {
        runCatching { requestWithServerFallback("/json/url/${encode(stationUuid)}") }
    }

    private fun requestWithServerFallback(path: String): List<RadioStation> {
        val hosts = InetAddress.getAllByName(DNS_NAME).mapNotNull { address ->
            address.canonicalHostName.lowercase().takeIf {
                it.endsWith(".api.radio-browser.info") && it != DNS_NAME
            }
        }.distinct().shuffled()
        if (hosts.isEmpty()) throw IllegalStateException("Could not discover an available Radio Browser server")
        var lastError: Exception? = null
        for (host in hosts) {
            try {
                val connection = (URL("https://$host$path").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "application/json")
                }
                try {
                    if (connection.responseCode !in 200..299) throw IllegalStateException("Radio directory returned HTTP ${connection.responseCode}")
                    return parseStations(JSONArray(connection.inputStream.bufferedReader().use { it.readText() }))
                } finally {
                    connection.disconnect()
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("Radio directory is temporarily unavailable", lastError)
    }

    private fun parseStations(array: JSONArray): List<RadioStation> = buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val uuid = item.optString("stationuuid")
            val stream = item.optString("url_resolved").ifBlank { item.optString("url") }
            val uri = runCatching { Uri.parse(stream) }.getOrNull()
            if (uuid.isBlank() || item.optString("name").isBlank() || uri?.scheme != "https" || uri.host.isNullOrBlank()) continue
            add(RadioStation(
                stationUuid = uuid,
                name = item.optString("name"),
                streamUrl = stream,
                country = item.optString("country"),
                countryCode = item.optString("countrycode"),
                language = item.optString("language"),
                tags = item.optString("tags"),
                bitrate = item.optInt("bitrate")
            ))
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
