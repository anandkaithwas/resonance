package dev.resonance.app

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, android.content.ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        controllerFuture?.addListener({
            runCatching { controller = controllerFuture?.get() }
        }, ContextCompat.getMainExecutor(this))

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF9A78FF), secondary = androidx.compose.ui.graphics.Color(0xFF55D6E8))) {
                PlayerApp(controller = controller, scan = { scanAudio() })
            }
        }
    }

    private fun scanAudio(): List<AudioTrack> {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        val result = mutableListOf<AudioTrack>()
        contentResolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                result += AudioTrack(id.toString(), c.getString(titleCol) ?: "Unknown title",
                    c.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: "Unknown artist",
                    ContentUris.withAppendedId(collection, id), online = false)
            }
        }
        return result
    }

    override fun onDestroy() {
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        controller = null
        super.onDestroy()
    }
}

@Composable
private fun PlayerApp(controller: MediaController?, scan: () -> List<AudioTrack>) {
    val context = LocalContext.current
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) }
    var tracks by remember { mutableStateOf(emptyList<AudioTrack>()) }
    var tab by remember { mutableStateOf("Offline") }
    var stream by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("Scan your device for local music.") }
    var title by remember { mutableStateOf("Nothing playing") }
    var artist by remember { mutableStateOf("Choose a track") }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) { tracks = scan(); message = "${tracks.size} audio files found." }
        else message = "Permission denied. Online playback remains available."
    }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                title = item?.mediaMetadata?.title?.toString() ?: "Nothing playing"
                artist = item?.mediaMetadata?.artist?.toString() ?: ""
            }
            override fun onPlayerError(error: PlaybackException) { message = "Playback error: ${error.errorCodeName}" }
        }
        controller?.addListener(listener)
        onDispose { controller?.removeListener(listener) }
    }
    LaunchedEffect(controller) {
        while (true) {
            position = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
            duration = controller?.duration?.takeIf { it > 0 } ?: 0L
            delay(500)
        }
    }
    LaunchedEffect(hasPermission) {
        if (hasPermission && tracks.isEmpty()) { tracks = scan(); message = "${tracks.size} audio files found." }
    }

    Scaffold(bottomBar = {
        NavigationBar { listOf("Offline", "Online").forEach { item ->
            NavigationBarItem(selected = tab == item, onClick = { tab = item },
                icon = { Icon(if (item == "Offline") Icons.Default.LibraryMusic else Icons.Default.PlayArrow, contentDescription = item) },
                label = { Text(item) })
        } }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(18.dp)) {
            Text("RESONANCE", fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Text("LOCAL MUSIC · ONLINE STREAMING", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp)
            Spacer(Modifier.height(18.dp))
            if (tab == "Offline") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Music on this device", style = MaterialTheme.typography.titleLarge)
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { if (hasPermission) { tracks = scan(); message = "${tracks.size} audio files found." } else permissionLauncher.launch(permission) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Rescan")
                    }
                }
                if (!hasPermission) Button(onClick = { permissionLauncher.launch(permission) }, modifier = Modifier.fillMaxWidth()) { Text("Allow access and scan") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(tracks, key = { it.id }) { track ->
                        Card(onClick = { play(controller, track.uri, track.title, track.artist) }, shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LibraryMusic, contentDescription = null)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(track.title, fontWeight = FontWeight.SemiBold)
                                    Text(track.artist, style = MaterialTheme.typography.bodySmall)
                                }
                                Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                            }
                        }
                    }
                }
            } else {
                Text("Play an online stream", style = MaterialTheme.typography.titleLarge)
                Text("Paste a direct HTTPS audio URL or authorised internet-radio stream. Webpage links are not necessarily playable media.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = stream, onValueChange = { stream = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("HTTPS audio stream URL") }, singleLine = true)
                Button(onClick = {
                    val uri = Uri.parse(stream.trim())
                    if (uri.scheme != "https" || uri.host.isNullOrBlank()) message = "Enter a valid HTTPS URL."
                    else play(controller, uri, uri.lastPathSegment ?: "Online stream", uri.host ?: "Internet stream")
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.PlayArrow, null); Text("  Play stream") }
                Text(message, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
            }
            Card(shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("NOW PLAYING", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(title, fontWeight = FontWeight.Bold)
                    Text(artist, style = MaterialTheme.typography.bodySmall)
                    Slider(value = if (duration > 0) position.coerceIn(0L, duration).toFloat() else 0f,
                        onValueChange = { if (duration > 0) controller?.seekTo(it.toLong()) },
                        valueRange = 0f..duration.coerceAtLeast(1L).toFloat(), enabled = duration > 0)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play or pause")
                        }
                        IconButton(onClick = { controller?.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, contentDescription = "Next") }
                    }
                }
            }
        }
    }
}

private fun play(controller: MediaController?, uri: Uri, title: String, artist: String) {
    if (controller == null) return
    val item = MediaItem.Builder().setUri(uri).setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setArtist(artist).build()
    ).build()
    controller.setMediaItem(item)
    controller.prepare()
    controller.play()
}
