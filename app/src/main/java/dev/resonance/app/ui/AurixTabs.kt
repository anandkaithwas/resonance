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
import androidx.media3.common.Player
import dev.resonance.app.aidj.LocalAiDjController
import dev.resonance.app.media.ResonanceTrack
import dev.resonance.app.studio.SongGeneratorClient
import kotlinx.coroutines.launch

@Composable
fun LibraryAndStreamTab(
    tracks: List<ResonanceTrack>,
    onPlayTrack: (Int) -> Unit,
    onPlayStreamUrl: (String) -> Unit
) {
    var streamUrl by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Aurix.studio Library", style = MaterialTheme.typography.headlineSmall)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = streamUrl,
                onValueChange = { streamUrl = it },
                label = { Text("Direct HTTPS Audio / Radio URL") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(onClick = { if (streamUrl.startsWith("http")) onPlayStreamUrl(streamUrl) }) {
                Text("Stream")
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(tracks) { index, track ->
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
fun AiDjTab(player: Player?, djController: LocalAiDjController) {
    val scope = rememberCoroutineScope()
    var crossfadeSec by remember { mutableFloatStateOf(3.0f) }
    var voiceIntro by remember { mutableStateOf(true) }
    var shuffleMix by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Local-First AI DJ", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "100% on-device crossfading, shuffle sequencing, and offline Text-to-Speech intros.",
            style = MaterialTheme.typography.bodyMedium
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Voice Track Announcements", modifier = Modifier.weight(1f))
            Switch(checked = voiceIntro, onCheckedChange = { voiceIntro = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Smart Shuffle Queue", modifier = Modifier.weight(1f))
            Switch(
                checked = shuffleMix,
                onCheckedChange = {
                    shuffleMix = it
                    player?.shuffleModeEnabled = it
                }
            )
        }
        Text("Crossfade Duration: ${"%.1f".format(crossfadeSec)}s")
        Slider(value = crossfadeSec, onValueChange = { crossfadeSec = it }, valueRange = 1f..8f)
        Button(
            onClick = {
                player?.let {
                    scope.launch { djController.transitionToNext(it, crossfadeSec, voiceIntro) }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Trigger AI DJ Crossfade Now")
        }
    }
}

@Composable
fun AiSongStudioTab(onTrackCreated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("resonance_byok", Context.MODE_PRIVATE) }
    var prompt by remember { mutableStateOf("") }
    var endpointUrl by remember { mutableStateOf(prefs.getString("endpoint_url", "") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var isGenerating by remember { mutableStateOf(false) }
    var statusMessage by remember {
        mutableStateOf("Leave endpoint blank for offline synth generation, or enter your local LAN / BYOK server URL.")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("AI Song Studio (BYOK / Local)", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text("Describe mood, tempo, or melody") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = endpointUrl,
            onValueChange = {
                endpointUrl = it
                prefs.edit().putString("endpoint_url", it).apply()
            },
            label = { Text("Optional Local / BYOK Endpoint URL") },
            placeholder = { Text("http://192.168.1.100:8000") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                prefs.edit().putString("api_key", it).apply()
            },
            label = { Text("Optional Bearer API Key (Stored Locally)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(
            enabled = !isGenerating && prompt.isNotBlank(),
            onClick = {
                isGenerating = true
                statusMessage = "Generating audio track..."
                scope.launch {
                    val result = SongGeneratorClient.generateAndSave(context, prompt, endpointUrl, apiKey)
                    isGenerating = false
                    result.onSuccess { file ->
                        statusMessage = "Saved to Library: ${file.name}"
                        onTrackCreated()
                    }.onFailure { err ->
                        statusMessage = "Error: ${err.message}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isGenerating) "Generating..." else "Generate & Save to Offline Library")
        }
        Text(statusMessage, style = MaterialTheme.typography.bodySmall)
    }
}
