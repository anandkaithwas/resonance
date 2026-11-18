package dev.resonance.app.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import dev.resonance.app.AudioTrack
import dev.resonance.app.aidj.LocalAiDjController
import dev.resonance.app.intelligence.SmartMixEngine
import dev.resonance.app.media.ResonanceTrack
import dev.resonance.app.studio.SongGeneratorClient
import kotlinx.coroutines.launch

@Composable
fun LibraryAndStreamTab(
    tracks: List<ResonanceTrack>,
    onRequestLibraryPermission: () -> Unit,
    onPlayTrack: (Int) -> Unit,
    onPlayStreamUrl: (String) -> Unit
) {
    var streamUrl by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Aurix.studio Library", style = MaterialTheme.typography.headlineSmall)
        if (tracks.isEmpty()) {
            Text("Grant audio access to browse music stored on this device.")
            Button(onClick = onRequestLibraryPermission) { Text("Allow music library access") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = streamUrl, onValueChange = { streamUrl = it },
                label = { Text("Direct HTTPS Audio / Radio URL") },
                modifier = Modifier.weight(1f), singleLine = true
            )
            Button(onClick = {
                val uri = runCatching { java.net.URI(streamUrl.trim()) }.getOrNull()
                if (uri?.scheme.equals("https", true) && !uri.host.isNullOrBlank()) onPlayStreamUrl(streamUrl.trim())
            }) { Text("Stream") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                ListItem(
                    headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text("${track.artist} • ${track.album}", maxLines = 1) },
                    trailingContent = { if (track.isAiGenerated) AssistChip(onClick = {}, label = { Text("AI") }) },
                    modifier = Modifier.clickable { onPlayTrack(index) }
                )
            }
        }
    }
}

@Composable
fun AiDjTab(player: Player?, djController: LocalAiDjController, tracks: List<ResonanceTrack>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val smartMix = remember(context) { SmartMixEngine(context) }
    var crossfadeSec by remember { mutableFloatStateOf(3.0f) }
    var voiceIntro by remember { mutableStateOf(true) }
    var shuffleMix by remember { mutableStateOf(false) }
    var transitionRunning by remember { mutableStateOf(false) }
    var mixStatus by remember { mutableStateOf("SmartMix is local-only and uses your library and saved listening history.") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Local-First AI DJ", style = MaterialTheme.typography.headlineSmall)
        Text(
            "SmartMix builds a queue from your library and listening history. The transition control below is a single-deck fade; it is not a beat-matched dual-deck crossfade.",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            enabled = player != null && tracks.size > 1 && !transitionRunning,
            onClick = {
                val active = player ?: return@Button
                val library = tracks.map { AudioTrack(it.id, it.title, it.artist, it.uri, false, it.album) }
                val currentId = active.currentMediaItem?.mediaId.orEmpty()
                val seed = library.firstOrNull { it.id == currentId }
                val current = seed
                if (current != null) smartMix.record(current, active.currentPosition)
                val recommendations = smartMix.recommend(seed, library, 15)
                val items = recommendations.map { recommendation ->
                    MediaItem.Builder().setMediaId(recommendation.track.id)
                        .setUri(recommendation.track.uri)
                        .setMediaMetadata(MediaMetadata.Builder()
                            .setTitle(recommendation.track.title)
                            .setArtist(recommendation.track.artist)
                            .setAlbumTitle(recommendation.track.album).build())
                        .build()
                }
                if (items.isNotEmpty()) {
                    active.addMediaItems(items)
                    mixStatus = "Added ${items.size} recommended tracks to the queue."
                } else {
                    mixStatus = "No suitable tracks found. Add more local music to your library."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Build SmartMix Queue") }
        Text(mixStatus, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Voice Track Announcements", modifier = Modifier.weight(1f))
            Switch(checked = voiceIntro, onCheckedChange = { voiceIntro = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Shuffle Queue", modifier = Modifier.weight(1f))
            Switch(checked = shuffleMix, onCheckedChange = {
                shuffleMix = it
                player?.shuffleModeEnabled = it
            })
        }
        Text("Fade Duration: ${"%.1f".format(crossfadeSec)}s")
        Slider(value = crossfadeSec, onValueChange = { crossfadeSec = it }, valueRange = 1f..8f)
        Button(
            enabled = player != null && !transitionRunning,
            onClick = {
                player?.let { activePlayer ->
                    scope.launch {
                        transitionRunning = true
                        try { djController.transitionToNext(activePlayer, crossfadeSec, voiceIntro) }
                        finally { transitionRunning = false }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (transitionRunning) "Transitioning..." else "Trigger DJ Fade Transition") }
    }
}

@Composable
fun AiSongStudioTab(onTrackCreated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("aurix_studio", Context.MODE_PRIVATE) }
    var prompt by remember { mutableStateOf("") }
    var endpointUrl by remember { mutableStateOf(prefs.getString("endpoint_url", "") ?: "") }
    var apiKey by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var statusMessage by remember {
        mutableStateOf("No endpoint: creates a simple procedural demo tone, not AI-generated music.")
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AI Song Studio (BYOK / Local)", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(value = prompt, onValueChange = { prompt = it },
            label = { Text("Describe mood, tempo, or melody") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = endpointUrl, onValueChange = {
            endpointUrl = it
            prefs.edit().putString("endpoint_url", it).apply()
        }, label = { Text("HTTPS generation endpoint") },
            placeholder = { Text("https://your-server.example/generate") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it },
            label = { Text("Optional Bearer API Key (not saved)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        Button(enabled = !isGenerating && prompt.isNotBlank(), onClick = {
            isGenerating = true
            statusMessage = "Generating audio..."
            scope.launch {
                val result = SongGeneratorClient.generateAndSave(context, prompt, endpointUrl, apiKey)
                isGenerating = false
                result.onSuccess { file ->
                    statusMessage = "Saved to Library: ${file.name}"
                    onTrackCreated()
                }.onFailure { err -> statusMessage = "Error: ${err.message ?: "Unknown error"}" }
            }
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (isGenerating) "Generating..." else "Generate & Save Audio")
        }
        Text(statusMessage, style = MaterialTheme.typography.bodySmall)
    }
}
