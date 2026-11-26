package dev.resonance.app.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import dev.resonance.app.media.ResonanceTrack
import dev.resonance.app.remix.HttpRemixProvider
import dev.resonance.app.remix.InstantEffects
import dev.resonance.app.remix.RemixJob
import dev.resonance.app.remix.RemixPreset
import dev.resonance.app.remix.RemixPresets
import dev.resonance.app.remix.RemixProvider
import dev.resonance.app.remix.RemixStatus
import dev.resonance.app.remix.RemixStore
import dev.resonance.app.remix.SourceAudioResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

@Composable
fun AiRemixScreen(
    player: Player?,
    tracks: List<ResonanceTrack>,
    currentTrackId: String,
    currentTrackTitle: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) { RemixStore(context) }
    val prefs = remember(context) { context.getSharedPreferences("aurix_remix", Context.MODE_PRIVATE) }
    var jobs by remember { mutableStateOf<List<RemixJob>>(store.list()) }
    var selectedTrackId by remember { mutableStateOf(currentTrackId) }
    var endpoint by remember { mutableStateOf(prefs.getString("endpoint_url", "") ?: "") }
    var apiKey by remember { mutableStateOf("") }
    var activePreset by remember { mutableStateOf<RemixPreset>(RemixPresets.presets.first()) }
    var customPrompt by remember { mutableStateOf("") }
    var rightsConfirmed by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Instant effects stay on-device. Generative remixes require an HTTPS provider.") }
    var isSubmitting by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<RemixProvider?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var pitch by remember { mutableFloatStateOf(1f) }
    var pitchLock by remember { mutableStateOf(true) }

    val selectedTrack = tracks.firstOrNull { it.id == selectedTrackId } ?: tracks.firstOrNull()
    val durationMs = if (selectedTrack?.id == currentTrackId && player != null && player.duration > 0L) {
        player.duration
    } else 0L

    LaunchedEffect(currentTrackId, tracks) {
        if (currentTrackId.isNotBlank() && tracks.any { it.id == currentTrackId }) selectedTrackId = currentTrackId
    }
    DisposableEffect(player) {
        onDispose {
            runCatching { player?.playbackParameters = PlaybackParameters.DEFAULT }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000L)
            val currentProvider = provider ?: continue
            val active = store.list().filter {
                it.status == RemixStatus.QUEUED || it.status == RemixStatus.RUNNING
            }
            if (active.isEmpty()) continue
            active.forEach { job ->
                val update = currentProvider.getJobStatus(job.id).getOrNull() ?: return@forEach
                val merged = job.copy(
                    status = update.status,
                    progress = update.progress,
                    error = update.error.ifBlank { job.error }
                )
                if (merged.status == RemixStatus.SUCCEEDED && merged.resultFileName.isBlank()) {
                    val file = File(store.outputDirectory(), "remix_${UUID.randomUUID()}.wav")
                    val downloaded = currentProvider.downloadResult(job.id, file).getOrNull()
                    if (downloaded != null) {
                        store.upsert(merged.copy(resultFileName = downloaded.name))
                    } else {
                        store.upsert(merged.copy(status = RemixStatus.FAILED, error = "Download failed"))
                    }
                } else {
                    store.upsert(merged)
                }
            }
            jobs = store.list()
        }
    }

    fun applyInstantEffect(speedValue: Float, pitchValue: Float) {
        speed = speedValue
        pitch = pitchValue
        val params = PlaybackParameters(speedValue, if (pitchLock) 1f else pitchValue)
        runCatching { player?.playbackParameters = params }
        prefs.edit().putFloat("speed", speedValue).putFloat("pitch", pitchValue)
            .putBoolean("pitch_lock", pitchLock).apply()
    }

    fun submit() {
        val track = selectedTrack
        if (track == null) {
            status = "Select a source track from your library first."
            return
        }
        if (!rightsConfirmed) {
            status = "Confirm that you have the rights to submit this audio before uploading."
            return
        }
        if (endpoint.isBlank()) {
            status = "Enter an HTTPS remix provider endpoint."
            return
        }
        isSubmitting = true
        status = "Copying source audio and submitting to your configured provider…"
        scope.launch {
            val currentProvider = HttpRemixProvider(endpoint, apiKey)
            val copied = SourceAudioResolver.copyToTemp(context, track.uri).getOrNull()
            if (copied == null) {
                isSubmitting = false
                status = "Could not read the selected source audio."
                return@launch
            }
            val resolvedDuration = durationMs.takeIf { it > 0L }
                ?: SourceAudioResolver.durationMs(context, track.uri)
            val result = currentProvider.submitRemix(
                copied,
                activePreset,
                customPrompt,
                resolvedDuration,
                rightsConfirmed
            )
            runCatching { copied.delete() }
            isSubmitting = false
            result.onSuccess { submitted ->
                provider = currentProvider
                prefs.edit().putString("endpoint_url", endpoint.trim()).apply()
                store.upsert(submitted.copy(
                    sourceTrackId = track.id,
                    sourceTitle = track.title,
                    createdAt = System.currentTimeMillis()
                ))
                jobs = store.list()
                status = "Job submitted to ${currentProvider.name}. Poll status below."
            }.onFailure { error ->
                status = error.message ?: "Submission failed"
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Aurix AI Remix", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("A. Instant playback effects — on-device", style = MaterialTheme.typography.titleMedium)
                Text("These presets change playback only. No audio is uploaded and no new master file is created.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    InstantEffects.presets.forEach { preset ->
                        AssistChip(onClick = { applyInstantEffect(preset.speed, preset.pitch) },
                            label = { Text(preset.label) })
                    }
                }
                Text("Speed: ${"%.2f".format(speed)}x   Pitch: ${"%.2f".format(pitch)}")
                Slider(speed, { applyInstantEffect(it, pitch) }, valueRange = 0.5f..2f)
                Slider(pitch, { applyInstantEffect(speed, it) }, enabled = !pitchLock, valueRange = 0.7f..1.5f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep pitch independent of speed", Modifier.weight(1f))
                    Switch(pitchLock, { pitchLock = it; applyInstantEffect(speed, pitch) })
                }
                Text("Saved as a playback preset, not an AI-generated audio file.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("B. AI-generated transformation — provider required", style = MaterialTheme.typography.titleMedium)
                Text("Source audio leaves this device and is processed by the HTTPS provider you configure. " +
                    "Nothing is uploaded until you confirm rights and tap Submit.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Source: ${selectedTrack?.title ?: currentTrackTitle.ifBlank { "No track selected" }}")
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 120.dp)) {
                    items(tracks.take(20), key = { it.id }) { track ->
                        Text(
                            track.title, Modifier.fillMaxWidth().clickable { selectedTrackId = track.id }.padding(6.dp),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (track.id == selectedTrack?.id) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RemixPresets.presets.forEach { preset ->
                        AssistChip(onClick = { activePreset = preset },
                            label = { Text(preset.label) })
                    }
                }
                OutlinedTextField(value = customPrompt, onValueChange = { customPrompt = it },
                    label = { Text(if (activePreset.id == "custom") "Custom style prompt" else "Extra style guidance (optional)") },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3)
                OutlinedTextField(value = endpoint, onValueChange = { endpoint = it },
                    label = { Text("HTTPS remix provider endpoint") },
                    placeholder = { Text("https://your-server.example") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("User-managed API key (memory only, never stored)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(rightsConfirmed, { rightsConfirmed = it })
                    Text("I have the rights to submit this audio for transformation.")
                }
                Button(onClick = { submit() }, enabled = !isSubmitting && rightsConfirmed,
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (isSubmitting) "Submitting…" else "Submit generative remix")
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Generated versions", style = MaterialTheme.typography.titleMedium)
                if (jobs.isEmpty()) {
                    Text("No generative jobs yet.", style = MaterialTheme.typography.bodySmall)
                } else {
                    jobs.take(10).forEach { job ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text("${job.sourceTitle} — ${job.presetId}: ${job.status.value} (${job.progress}%)",
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (job.error.isNotBlank()) Text(job.error,
                                style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.error))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (job.status == RemixStatus.SUCCEEDED && job.resultFileName.isNotBlank()) {
                                    OutlinedButton(onClick = {
                                        val file = File(store.outputDirectory(), job.resultFileName)
                                        if (file.isFile && player != null) {
                                            player.setMediaItem(MediaItem.Builder().setUri(file.toUri())
                                                .setMediaId("remix_${job.id}").build())
                                            player.prepare()
                                            player.play()
                                        }
                                    }) { Text("Play") }
                                }
                                if (job.status == RemixStatus.QUEUED || job.status == RemixStatus.RUNNING) {
                                    OutlinedButton(onClick = {
                                        val currentProvider = provider ?: HttpRemixProvider(endpoint, apiKey)
                                        scope.launch {
                                            currentProvider.cancelJob(job.id).onSuccess {
                                                store.upsert(job.copy(status = RemixStatus.CANCELLED))
                                                jobs = store.list()
                                            }.onFailure {
                                                Toast.makeText(context, it.message ?: "Cancellation failed", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    }) { Text("Cancel") }
                                }
                                OutlinedButton(onClick = {
                                    store.delete(job)
                                    jobs = store.list()
                                }) { Text("Discard") }
                            }
                        }
                    }
                }
            }
        }
    }
}
