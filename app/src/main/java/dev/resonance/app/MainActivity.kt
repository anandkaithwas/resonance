package dev.resonance.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dev.resonance.app.aidj.LocalAiDjController
import dev.resonance.app.media.LocalAudioScanner
import dev.resonance.app.media.ResonanceTrack
import dev.resonance.app.playback.PlaybackService
import dev.resonance.app.studio.SongGeneratorClient
import dev.resonance.app.ui.VisualiserStudioScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { ResonanceApp() } }
    }
}

@Composable
fun ResonanceApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var tracks by remember { mutableStateOf<List<ResonanceTrack>>(emptyList()) }
    var currentTitle by remember { mutableStateOf("Nothing Playing") }
    var isPlaying by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) }

    val djController = remember { LocalAiDjController(context) }
    DisposableEffect(Unit) { onDispose { djController.release() } }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch { tracks = LocalAudioScanner.scanAllTracks(context) }
    }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            val mc = future.get()
            controller = mc
            mc.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    currentTitle = mediaMetadata.title?.toString() ?: "Streaming Audio"
                }
            })
        }, ContextCompat.getMainExecutor(context))
        onDispose { MediaController.releaseFuture(future) }
    }

    LaunchedEffect(Unit) {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        permissionLauncher.launch(perm)
        tracks = LocalAudioScanner.scanAllTracks(context)
    }

    Scaffold(
        bottomBar = {
            Column {
                Surface(tonalElevation = 4.dp) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(currentTitle, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play or Pause")
                        }
                        IconButton(onClick = { controller?.seekToNextMediaItem() }) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next")
                        }
                    }
                }
                NavigationBar {
                    NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 }, icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Library") })
                    NavigationBarItem(selected = selectedTab == 1, onClick = { selectedTab = 1 }, icon = { Icon(Icons.Default.Equalizer, null) }, label = { Text("Visualiser") })
                    NavigationBarItem(selected = selectedTab == 2, onClick = { selectedTab = 2 }, icon = { Icon(Icons.Default.AutoAwesome, null) }, label = { Text("AI DJ") })
                    NavigationBarItem(selected = selectedTab == 3, onClick = { selectedTab = 3 }, icon = { Icon(Icons.Default.MusicNote, null) }, label = { Text("AI Studio") })
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (selectedTab) {
                0 -> LibraryAndStreamTab(
                    tracks = tracks,
                    onPlayTrack = { idx -> controller?.let { p -> p.setMediaItems(tracks.map { it.toMediaItem() }, idx, 0L); p.prepare(); p.play() } },
                    onPlayStreamUrl = { url ->
                        controller?.let { p ->
                            val item = MediaItem.Builder().setUri(url.trim()).setMediaMetadata(MediaMetadata.Builder().setTitle(url.trim()).build()).build()
                            p.setMediaItem(item); p.prepare(); p.play(); currentTitle = url.trim()
                        }
                    }
                )
                1 -> VisualiserStudioScreen()
                2 -> AiDjTab(player = controller, djController = djController)
                3 -> AiSongStudioTab(onTrackCreated = { scope.launch { tracks = LocalAudioScanner.scanAllTracks(context) } })
            }
        }
    }
}

@Composable
private fun LibraryAndStreamTab(tracks: List<ResonanceTrack>, onPlayTrack: (Int) -> Unit, onPlayStreamUrl: (String) -> Unit) {
    var streamUrl by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("RESONANCE Library", style = MaterialTheme.typography.headlineSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = streamUrl, onValueChange = { streamUrl = it }, label = { Text("Direct HTTPS Audio / Radio URL") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = { if (streamUrl.startsWith("http")) onPlayStreamUrl(streamUrl) }) { Text("Stream") }
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
private fun AiDjTab(player: Player?, djController: LocalAiDjController) {
    val scope = rememberCoroutineScope()
    var crossfadeSec by remember { mutableFloatStateOf(3.0f) }
    var voiceIntro by remember { mutableStateOf(true) }
    var shuffleMix by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Local-First AI DJ", style = MaterialTheme.typography.headlineSmall)
        Text("100% on-device crossfading, shuffle sequencing, and offline Text-to-Speech intros.", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Voice Track Announcements", modifier = Modifier.weight(1f))
            Switch(checked = voiceIntro, onCheckedChange = { voiceIntro = it })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Smart Shuffle Queue", modifier = Modifier.weight(1f))
            Switch(checked = shuffleMix, onCheckedChange = { shuffleMix = it; player?.shuffleModeEnabled = it })
        }
        Text("Crossfade Duration: ${"%.1f".format(crossfadeSec)}s")
        Slider(value = crossfadeSec, onValueChange = { crossfadeSec = it }, valueRange = 1f..8f)
        Button(onClick = { player?.let { scope.launch { djController.transitionToNext(it, crossfadeSec, voiceIntro) } } }, modifier = Modifier.fillMaxWidth()) {
            Text("Trigger AI DJ Crossfade Now")
        }
    }
}

@Composable
private fun AiSongStudioTab(onTrackCreated: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("resonance_byok", Context.MODE_PRIVATE) }
    var prompt by remember { mutableStateOf("") }
    var endpointUrl by remember { mutableStateOf(prefs.getString("endpoint_url", "") ?: "") }
    var apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }
    var isGenerating by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf("Leave endpoint blank for offline synth generation, or enter your local LAN / BYOK server URL.") }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI Song Studio (BYOK / Local)", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(value = prompt, onValueChange = { prompt = it }, label = { Text("Describe mood, tempo, or melody") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = endpointUrl,
            onValueChange = { endpointUrl = it; prefs.edit().putString("endpoint_url", it).apply() },
            label = { Text("Optional Local / BYOK Endpoint URL") },
            placeholder = { Text("http://192.168.1") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; prefs.edit().putString("api_key", it).apply() },
            label = { Text("Optional Bearer API Key (Stored Locally)") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        Button(
            enabled = !isGenerating && prompt.isNotBlank(),
            onClick = {
                isGenerating = true; statusMessage = "Generating audio track..."
                scope.launch {
                    val result = SongGeneratorClient.generateAndSave(context, prompt, endpointUrl, apiKey)
                    isGenerating = false
                    result.onSuccess { file -> statusMessage = "Saved to Library: ${file.name}"; onTrackCreated() }
                        .onFailure { err -> statusMessage = "Error: ${err.message}" }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (isGenerating) "Generating..." else "Generate & Save to Offline Library") }
        Text(statusMessage, style = MaterialTheme.typography.bodySmall)
    }
}
