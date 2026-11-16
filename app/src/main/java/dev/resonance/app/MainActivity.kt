package dev.resonance.app

import android.Manifest
import android.content.ComponentName
import android.content.ContentUris
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import java.util.concurrent.ExecutionException

private val Night = Color(0xFF090B14)
private val Panel = Color(0xFF171B2B)
private val Purple = Color(0xFF9A78FF)
private val Cyan = Color(0xFF55D6E8)
private val Muted = Color(0xFFB5B6C7)

class MainActivity : ComponentActivity() {
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                try { controller = future.get() } catch (_: ExecutionException) { }
            }, ContextCompat.getMainExecutor(this))
        }
        setContent { ResonancePlayer(controller) }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controller = null
        super.onDestroy()
    }
}

@Composable
private fun ResonancePlayer(controller: MediaController?) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf("Offline") }
    var tracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var message by remember { mutableStateOf("Scan your device for music.") }
    var streamUrl by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("Nothing playing") }
    var artist by remember { mutableStateOf("Choose local music or add an online stream") }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }

    val audioPermission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    fun scan() {
        if (ContextCompat.checkSelfPermission(context, audioPermission) != PackageManager.PERMISSION_GRANTED) {
            message = "Audio permission is needed to scan local files."
            return
        }
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST)
        val found = mutableListOf<AudioTrack>()
        context.contentResolver.query(
            collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
            "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                found += AudioTrack(
                    id.toString(),
                    cursor.getString(titleCol) ?: "Unknown title",
                    cursor.getString(artistCol)?.takeIf { it != "<unknown>" } ?: "Unknown artist",
                    ContentUris.withAppendedId(collection, id),
                    false
                )
            }
        }
        tracks = found
        message = "${found.size} tracks found on this device."
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scan() else message = "Permission denied. Online streams remain available."
    }

    fun play(track: AudioTrack) {
        val player = controller ?: run { error = "Player is still starting. Try again in a moment."; return }
        val metadata = MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).build()
        player.setMediaItem(MediaItem.Builder().setUri(track.uri).setMediaMetadata(metadata).build())
        player.prepare()
        player.play()
        title = track.title
        artist = track.artist
        error = ""
    }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                title = item?.mediaMetadata?.title?.toString() ?: "Nothing playing"
                artist = item?.mediaMetadata?.artist?.toString() ?: ""
            }
            override fun onPlaybackStateChanged(state: Int) {
                duration = controller?.duration?.takeIf { it > 0 } ?: 0L
            }
            override fun onPlayerError(playbackError: androidx.media3.common.PlaybackException) {
                error = "Playback failed: ${playbackError.errorCodeName}. Check the URL, connection or audio format."
            }
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

    MaterialTheme(colorScheme = darkColorScheme(primary = Purple, secondary = Cyan, background = Night, surface = Panel)) {
        Scaffold(
            containerColor = Night,
            bottomBar = {
                NavigationBar(containerColor = Panel) {
                    listOf("Offline", "Online").forEach { item ->
                        NavigationBarItem(
                            selected = tab == item, onClick = { tab = item },
                            icon = { Icon(if (item == "Offline") Icons.Default.LibraryMusic else Icons.Default.PlayArrow, contentDescription = item) },
                            label = { Text(item) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().background(Night).padding(padding).padding(18.dp)) {
                Text("RESONANCE", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                Text("MUSIC WITHOUT BOUNDARIES", color = Cyan, fontSize = 10.sp, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(18.dp))

                if (tab == "Offline") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("On this device", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Text(message, color = Muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = {
                            if (ContextCompat.checkSelfPermission(context, audioPermission) == PackageManager.PERMISSION_GRANTED) scan()
                            else permissionLauncher.launch(audioPermission)
                        }) { Icon(Icons.Default.Refresh, contentDescription = "Scan", tint = Cyan) }
                    }
                    if (ContextCompat.checkSelfPermission(context, audioPermission) != PackageManager.PERMISSION_GRANTED) {
                        Button(onClick = { permissionLauncher.launch(audioPermission) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Allow access and scan music")
                        }
                    } else if (tracks.isEmpty()) {
                        Button(onClick = { scan() }, modifier = Modifier.fillMaxWidth()) { Text("Scan music") }
                    }
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(tracks, key = { it.id }) { track ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { play(track) },
                                colors = CardDefaults.cardColors(containerColor = Panel),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.LibraryMusic, null, tint = Purple)
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(track.title, color = Color.White, fontWeight = FontWeight.SemiBold)
                                        Text(track.artist, color = Muted, fontSize = 12.sp)
                                    }
                                    Icon(Icons.Default.PlayArrow, null, tint = Cyan)
                                }
                            }
                        }
                    }
                } else {
                    Text("Online audio", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Paste a direct HTTPS audio URL or compatible internet-radio stream.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = streamUrl, onValueChange = { streamUrl = it; error = "" },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("HTTPS audio stream URL") },
                        placeholder = { Text("https://example.com/audio.mp3") }
                    )
                    Button(onClick = {
                        val uri = android.net.Uri.parse(streamUrl.trim())
                        if (uri.scheme != "https" || uri.host.isNullOrBlank()) error = "Enter a valid HTTPS URL."
                        else play(AudioTrack("online:${streamUrl.hashCode()}", uri.lastPathSegment ?: "Online stream", uri.host ?: "Stream", uri, true))
                    }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.PlayArrow, null); Text("  Play stream")
                    }
                    Text("Supported when the server provides a Media3-compatible audio stream. Webpage URLs are not necessarily playable audio URLs.", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                }

                if (error.isNotBlank()) Text(error, color = Color(0xFFFF8A8A), fontSize = 12.sp)
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("NOW PLAYING", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                        Text(title, color = Color.White, fontWeight = FontWeight.Bold)
                        Text(artist, color = Muted, fontSize = 12.sp)
                        Slider(
                            value = if (duration > 0) position.coerceIn(0L, duration).toFloat() else 0f,
                            onValueChange = { if (duration > 0) controller?.seekTo(it.toLong()) },
                            valueRange = 0f..duration.coerceAtLeast(1L).toFloat(),
                            enabled = duration > 0
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Play or pause", tint = Color.White)
                            }
                            IconButton(onClick = { controller?.seekToNextMediaItem() }) {
                                Icon(Icons.Default.SkipNext, "Next", tint = Cyan)
                            }
                        }
                    }
                }
            }
        }
    }
}
