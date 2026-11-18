package dev.resonance.app.ui

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
import androidx.media3.common.Player
import dev.resonance.app.karaoke.LrcParser
import dev.resonance.app.karaoke.LyricLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun KaraokeScreen(player: Player?, trackId: String, trackTitle: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var lyrics by remember(trackId) { mutableStateOf<List<LyricLine>>(emptyList()) }
    var offsetMs by remember(trackId) { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var message by remember(trackId) { mutableStateOf("Import an .lrc file for this track to start karaoke.") }
    val listState = rememberLazyListState()

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
        }
    }

    val activeIndex = LrcParser.activeLineIndex(lyrics, positionMs, offsetMs)
    LaunchedEffect(activeIndex) {
        if (activeIndex >= 0 && lyrics.isNotEmpty()) listState.animateScrollToItem(activeIndex)
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
        Text("Recording, vocal separation and pitch scoring are planned for Karaoke milestone K2.", style = MaterialTheme.typography.bodySmall)
    }
}
