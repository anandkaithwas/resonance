package dev.resonance.app

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.*
import androidx.media3.session.*
import dev.resonance.app.aidj.LocalAiDjController
import dev.resonance.app.media.*
import dev.resonance.app.playback.PlaybackService
import dev.resonance.app.ui.*
import dev.resonance.app.ui.components.AurixTopBar
import kotlinx.coroutines.launch

private val AurixDarkColors = darkColorScheme(
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
        setContent { MaterialTheme(colorScheme = AurixDarkColors) { AurixApp() } }
    }
}

@Composable
fun AurixApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var tracks by remember { mutableStateOf<List<ResonanceTrack>>(emptyList()) }
    var title by remember { mutableStateOf("Nothing Playing") }
    var isPlaying by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    val currentTrackId = controller?.currentMediaItem?.mediaId.orEmpty()
    val currentTrackTitle = controller?.currentMediaItem?.mediaMetadata?.title?.toString() ?: title
    var libraryPermissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    Manifest.permission.READ_MEDIA_AUDIO
                else Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val dj = remember { LocalAiDjController(context) }
    DisposableEffect(Unit) { onDispose { dj.release() } }

    val audioPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        libraryPermissionGranted = granted
        if (granted) scope.launch { tracks = LocalAudioScanner.scanAllTracks(context) }
    }

    DisposableEffect(context) {
        val future = MediaController.Builder(
            context,
            SessionToken(context, ComponentName(context, PlaybackService::class.java))
        ).buildAsync()
        val listener = Runnable {
            if (future.isDone && !future.isCancelled) {
                runCatching { future.get() }.onSuccess { mediaController ->
                    controller = mediaController
                    mediaController.addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
                        override fun onMediaMetadataChanged(meta: MediaMetadata) {
                            title = meta.title?.toString() ?: "Streaming Audio"
                        }
                        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                            title = item?.mediaMetadata?.title?.toString() ?: "Streaming Audio"
                        }
                    })
                    isPlaying = mediaController.isPlaying
                    title = mediaController.currentMediaItem?.mediaMetadata?.title?.toString()
                        ?: "Nothing Playing"
                }
            }
        }
        future.addListener(listener, ContextCompat.getMainExecutor(context))
        onDispose {
            controller?.release()
            MediaController.releaseFuture(future)
            controller = null
        }
    }

    LaunchedEffect(libraryPermissionGranted) {
        if (libraryPermissionGranted) tracks = LocalAudioScanner.scanAllTracks(context)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AurixTopBar() },
        bottomBar = {
            Column {
                Surface(tonalElevation = 4.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(title, Modifier.weight(1f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = {
                            controller?.let { if (it.isPlaying) it.pause() else it.play() }
                        }) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or Pause")
                        }
                        IconButton(onClick = { controller?.seekToNextMediaItem() }) {
                            Icon(Icons.Default.SkipNext, "Next")
                        }
                    }
                }
                NavigationBar {
                    NavigationBarItem(selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(Icons.Default.LibraryMusic, null) }, label = { Text("Library") })
                    NavigationBarItem(selected = tab == 1, onClick = { tab = 1 },
                        icon = { Icon(Icons.Default.Equalizer, null) }, label = { Text("Visualiser") })
                    NavigationBarItem(selected = tab == 2, onClick = { tab = 2 },
                        icon = { Icon(Icons.Default.AutoAwesome, null) }, label = { Text("AI DJ") })
                    NavigationBarItem(selected = tab == 3, onClick = { tab = 3 },
                        icon = { Icon(Icons.Default.Mic, null) }, label = { Text("Karaoke") })
                    NavigationBarItem(selected = tab == 4, onClick = { tab = 4 },
                        icon = { Icon(Icons.Default.MusicNote, null) }, label = { Text("AI Studio") })
                    NavigationBarItem(selected = tab == 5, onClick = { tab = 5 },
                        icon = { Icon(Icons.Default.Tune, null) }, label = { Text("Aurix AI") })
                }
            }
        }
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> LibraryAndStreamTab(
                    tracks = tracks,
                    onRequestLibraryPermission = {
                        if (!libraryPermissionGranted) permissionLauncher.launch(audioPermission)
                    },
                    onPlayTrack = { i ->
                        controller?.apply {
                            setMediaItems(tracks.map { it.toMediaItem() }, i, 0L)
                            prepare()
                            play()
                        }
                    },
                    onPlayStreamUrl = { url ->
                        controller?.apply {
                            val clean = url.trim()
                            setMediaItem(MediaItem.Builder().setUri(clean)
                                .setMediaMetadata(MediaMetadata.Builder().setTitle(clean).build()).build())
                            prepare()
                            play()
                            title = clean
                        }
                    }
                )
                1 -> VisualiserStudioScreen()
                2 -> AiDjTab(player = controller, djController = dj, tracks = tracks)
                3 -> KaraokeScreen(player = controller, trackId = currentTrackId, trackTitle = currentTrackTitle)
                4 -> AiSongStudioTab(onTrackCreated = {
                    if (libraryPermissionGranted) scope.launch {
                        tracks = LocalAudioScanner.scanAllTracks(context)
                    }
                })
                5 -> AurixIntelligenceScreen()
            }
        }
    }
}
