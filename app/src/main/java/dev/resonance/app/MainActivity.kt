package dev.resonance.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import dev.resonance.app.ui.components.AurixTopBar
import kotlinx.coroutines.launch

private val AurixDarkColorScheme = darkColorScheme(
    primary = Color(0xFF22D3EE),
    secondary = Color(0xFFA855F7),
    background = Color(0xFF11151C),
    surface = Color(0xFF11151C),
    onPrimary = Color(0xFF11151C),
    onSecondary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = AurixDarkColorScheme) {
                ResonanceApp()
            }
        }
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

    val requiredPermissions = remember {
        buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.READ_MEDIA_AUDIO)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            add(Manifest.permission.RECORD_AUDIO)
        }.toTypedArray()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val audioGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            results[Manifest.permission.READ_MEDIA_AUDIO] == true
        } else {
            results[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }
        if (audioGranted) {
            scope.launch { tracks = LocalAudioScanner.scanAllTracks(context) }
        }
    }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            if (future.isDone && !future.isCancelled) {
                val mc = future.get()
                controller = mc
                mc.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }

                    override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                        currentTitle = mediaMetadata.title?.toString() ?: "Streaming Audio"
                    }
                })
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            MediaController.releaseFuture(future)
            controller = null
        }
    }

    LaunchedEffect(Unit) {
        val missing = requiredPermissions.any {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            permissionLauncher.launch(requiredPermissions)
        }
        tracks = LocalAudioScanner.scanAllTracks(context)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AurixTopBar()
        },
        bottomBar = {
            Column {
                Surface(tonalElevation = 4.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = currentTitle,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall
                        )
                        IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play or Pause"
                            )
                        }
                        IconButton(onClick = { controller?.seekToNextMediaItem() }) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next")
                        }
                    }
                }
                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                        label = { Text("Library") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        icon = { Icon(Icons.Default.Equalizer, contentDescription = null) },
                        label = { Text("Visualiser") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null) },
                        label = { Text("AI DJ") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == 3,
                        onClick = { selectedTab = 3 },
                        icon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                        label = { Text("AI Studio") }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
@Composable
private fun LibraryAndStreamTab(
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
private fun AiDjTab(player: Player?, djController: LocalAiDjController) {
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
private fun AiSongStudioTab(onTrackCreated: () -> Unit) {
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
