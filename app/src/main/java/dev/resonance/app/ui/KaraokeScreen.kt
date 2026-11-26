package dev.resonance.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.resonance.app.karaoke.KaraokeRecorder
import dev.resonance.app.karaoke.KaraokeTake
import dev.resonance.app.karaoke.KaraokeTakeStore
import dev.resonance.app.karaoke.LrcParser
import dev.resonance.app.karaoke.LyricLine
import dev.resonance.app.karaoke.PitchDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@Composable
fun KaraokeScreen(player: Player?, trackId: String, trackTitle: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lyrics by remember(trackId) { mutableStateOf<List<LyricLine>>(emptyList()) }
    var offsetMs by remember(trackId) { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var message by remember(trackId) { mutableStateOf("Import an .lrc file for this track to start karaoke.") }
    val listState = rememberLazyListState()

    val takeStore = remember(context) { KaraokeTakeStore(context) }
    val recorder = remember { KaraokeRecorder() }
    var takes by remember(trackId) { mutableStateOf<List<KaraokeTake>>(emptyList()) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingMs by remember { mutableLongStateOf(0L) }
    var pitchFeedback by remember { mutableStateOf("Pitch feedback is optional guidance, not a vocal diagnosis.") }
    var pendingRecord by remember { mutableStateOf(false) }

    fun startRecording() {
        if (isRecording) return
        isRecording = true
        scope.launch {
            val file = File(takeStore.directory(), "take_${System.currentTimeMillis()}.wav")
            val recording = recorder.record(30_000L, file)
            isRecording = false
            recording.onSuccess { result ->
                val take = KaraokeTake(
                    id = UUID.randomUUID().toString(),
                    trackId = trackId.ifBlank { "unknown_track" },
                    fileName = result.file.name,
                    durationMs = result.durationMs,
                    averagePitchHz = result.averagePitchHz,
                    createdAt = System.currentTimeMillis()
                )
                takeStore.save(take)
                takes = takeStore.list(trackId)
                pitchFeedback = result.averagePitchHz?.let { hz ->
                    "Estimated average pitch: ${PitchDetector.noteName(hz)} (${hz.toInt()} Hz)."
                } ?: "No clear pitch detected; try singing closer to the microphone."
                message = "Take saved locally."
            }.onFailure { error ->
                isRecording = false
                message = error.message ?: "Recording failed"
            }
        }
    }

    val micPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && pendingRecord) {
            pendingRecord = false
            startRecording()
        } else {
            pendingRecord = false
            message = "Microphone permission is needed to record a take."
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            message = "Loading lyrics…"
            scope.launch {
                runCatching {
                    val content = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                            ?: error("Could not read selected lyrics file")
                    }
                    val parsed = LrcParser.parse(content)
                    require(parsed.lines.isNotEmpty()) { "No timestamped LRC lyrics were found." }
                    val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
                    val safeId = trackId.ifBlank { "unknown_track" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
                    withContext(Dispatchers.IO) { File(dir, "$safeId.lrc").writeText(content) }
                    lyrics = parsed.lines
                    offsetMs = parsed.offsetMs
                    message = "Loaded ${parsed.lines.size} lyric lines"
                }.onFailure { message = it.message ?: "Unable to load lyrics" }
            }
        }
    }

    LaunchedEffect(player) {
        while (true) {
            positionMs = player?.currentPosition?.coerceAtLeast(0L) ?: 0L
            delay(100L)
        }
    }
    LaunchedEffect(trackId) {
        if (trackId.isNotBlank()) {
            val safeId = trackId.replace(Regex("[^A-Za-z0-9_-]"), "_")
            val file = File(File(context.filesDir, "lyrics"), "$safeId.lrc")
            if (file.isFile) runCatching {
                val parsed = LrcParser.parse(withContext(Dispatchers.IO) { file.readText() })
                lyrics = parsed.lines
                offsetMs = parsed.offsetMs
                message = "Loaded ${parsed.lines.size} saved lyric lines"
            }.onFailure { message = it.message ?: "Unable to load saved lyrics" }
            takes = takeStore.list(trackId)
        }
    }
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingMs = 0L
            while (isRecording && recordingMs < 300_000L) {
                delay(100L)
                recordingMs += 100L
            }
        }
    }

    val activeIndex = LrcParser.activeLineIndex(lyrics, positionMs, offsetMs)
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && lyrics.isNotEmpty()) listState.animateScrollToItem(activeIndex)
    }

    fun requestOrStartRecording() {
        if (!micPermission) {
            pendingRecord = true
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            startRecording()
        }
    }

    fun stopRecording() {
        isRecording = false
        recorder.stop()
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Aurix Karaoke", style = MaterialTheme.typography.headlineSmall)
        Text(trackTitle.ifBlank { "No track selected" }, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(arrayOf("text/plain", "application/octet-stream")) }) { Text("Import LRC") }
            OutlinedButton(enabled = player != null && player.mediaItemCount > 0, onClick = {
                if (player?.isPlaying == true) player.pause() else player?.play()
            }) { Text(if (player?.isPlaying == true) "Pause" else "Play") }
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
        if (lyrics.isEmpty()) {
            Card(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sing along with synchronised lyrics", style = MaterialTheme.typography.titleMedium)
                    Text("Import a timestamped .lrc file supplied by you or obtained from a licensed lyrics source.")
                    Text("Lyrics remain stored locally on this device.")
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                itemsIndexed(lyrics) { index, line ->
                    Text(
                        line.text, Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        style = if (index == activeIndex) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                        fontWeight = if (index == activeIndex) FontWeight.Bold else FontWeight.Normal,
                        color = if (index == activeIndex) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
                    )
                }
            }
            Text("Lyrics timing offset: ${offsetMs} ms")
            Slider(offsetMs.toFloat().coerceIn(-5000f, 5000f), { offsetMs = it.toLong() }, valueRange = -5000f..5000f)
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Record a Take", style = MaterialTheme.typography.titleMedium)
                Text("Microphone access is requested only when you tap Record. Takes are saved privately on this device.",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        if (isRecording) stopRecording() else requestOrStartRecording()
                    }) { Text(if (isRecording) "Stop Recording" else "Record (30s)") }
                    if (isRecording) Text("● Recording ${recordingMs / 1000}s", color = MaterialTheme.colorScheme.error)
                }
                Text(pitchFeedback, style = MaterialTheme.typography.bodySmall)
                if (takes.isNotEmpty()) {
                    Text("Saved takes: ${takes.size}", style = MaterialTheme.typography.bodySmall)
                    takes.take(3).forEach { take ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Take ${take.durationMs / 1000}s", Modifier.weight(1f))
                            OutlinedButton(onClick = {
                                val file = File(takeStore.directory(), take.fileName)
                                if (file.isFile && player != null) {
                                    player.setMediaItem(MediaItem.Builder().setUri(file.toUri()).setMediaId("karaoke_take").build())
                                    player.prepare()
                                    player.play()
                                    message = "Playing your saved take."
                                }
                            }) { Text("Play") }
                            OutlinedButton(onClick = {
                                takeStore.delete(take)
                                takes = takeStore.list(trackId)
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
        Text("Vocal separation and professional pitch scoring remain outside K1 scope.", style = MaterialTheme.typography.bodySmall)
    }
}
