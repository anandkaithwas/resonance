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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation

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
    var currentTrackId by remember { mutableStateOf("") }
    var shuffleEnabled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(Player.REPEAT_MODE_OFF) }
    var listeningHistory by remember { mutableStateOf(emptyList<ListeningEvent>()) }
    var radioStations by remember { mutableStateOf(emptyList<RadioStation>()) }
    var radioQuery by remember { mutableStateOf("") }
    var radioCountry by remember { mutableStateOf("IN") }
    var radioTag by remember { mutableStateOf("") }
    var radioLoading by remember { mutableStateOf(false) }
    var radioError by remember { mutableStateOf("") }
    val radioFavorites = remember { mutableStateListOf<String>() }
    val scope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        if (granted) { tracks = scan(); message = "${tracks.size} audio files found." }
        else message = "Permission denied. Online playback remains available."
    }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) { shuffleEnabled = shuffleModeEnabled }
            override fun onRepeatModeChanged(repeatModeValue: Int) { repeatMode = repeatModeValue }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                title = item?.mediaMetadata?.title?.toString() ?: "Nothing playing"
                artist = item?.mediaMetadata?.artist?.toString() ?: ""
                currentTrackId = item?.mediaId.orEmpty()
            }
            override fun onPlayerError(error: PlaybackException) { message = "Playback error: ${error.errorCodeName}" }
        }
        controller?.let {
            shuffleEnabled = it.shuffleModeEnabled
            repeatMode = it.repeatMode
            it.addListener(listener)
        }
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
    LaunchedEffect(Unit) {
        val saved = context.getSharedPreferences("resonance_radio", android.content.Context.MODE_PRIVATE).getStringSet("favorites", emptySet()).orEmpty()
        radioFavorites.addAll(saved)
        listeningHistory = loadListeningHistory(context)
    }
    LaunchedEffect(currentTrackId) {
        if (currentTrackId.isBlank()) return@LaunchedEffect
        val startedAt = System.currentTimeMillis()
        try { awaitCancellation() } finally {
            val listened = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
            val current = tracks.firstOrNull { it.id == currentTrackId }
            if (current != null && listened > 0L) {
                val event = ListeningEvent(current.id, current.artist, startedAt, listened, listened < 30_000L)
                listeningHistory = (listeningHistory + event).takeLast(500)
                saveListeningHistory(context, listeningHistory)
            }
        }
    }
    LaunchedEffect(tab) {
        if (tab == "Radio" && radioStations.isEmpty()) {
            radioLoading = true
            runCatching { RadioBrowserRepository.search(countryCode = radioCountry) }
                .onSuccess { radioStations = it }
                .onFailure { radioError = it.message ?: "Could not load radio stations" }
            radioLoading = false
        }
    }

    Scaffold(bottomBar = {
        NavigationBar { listOf("Offline", "Radio", "Online").forEach { item ->
            NavigationBarItem(selected = tab == item, onClick = { tab = item },
                icon = { Icon(when (item) { "Offline" -> Icons.Default.LibraryMusic; "Radio" -> Icons.Default.Favorite; else -> Icons.Default.PlayArrow }, contentDescription = item) },
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
                    item {
                        val seedTrack = tracks.firstOrNull { it.id == currentTrackId }
                            ?: listeningHistory.lastOrNull()?.let { event -> tracks.firstOrNull { it.id == event.trackId } }
                        val suggestions = LocalRecommendationEngine.recommend(seedTrack, tracks, listeningHistory, count = 8)
                        if (suggestions.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Picked for you", style = MaterialTheme.typography.titleMedium)
                                Text(if (seedTrack != null) "Based on your listening around ${seedTrack.title}" else "A rotating mix from your music", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                if (seedTrack != null) {
                                    OutlinedButton(onClick = {
                                        val queue = listOf(seedTrack) + suggestions.map { it.track }
                                        playTrackQueue(controller, queue)
                                    }, modifier = Modifier.fillMaxWidth()) {
                                        Icon(Icons.Default.PlayArrow, null)
                                        Text("  Start a radio from ${seedTrack.title}")
                                    }
                                }
                                suggestions.forEach { recommendation ->
                                    val suggested = recommendation.track
                                    Card(onClick = { playLocalQueue(controller, tracks, suggested.id) }, shape = RoundedCornerShape(12.dp)) {
                                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.LibraryMusic, contentDescription = null)
                                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                                Text(suggested.title, fontWeight = FontWeight.SemiBold)
                                                Text(suggested.artist, style = MaterialTheme.typography.bodySmall)
                                                Text(recommendation.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                            }
                                            Icon(Icons.Default.PlayArrow, contentDescription = "Play recommendation")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    items(tracks, key = { it.id }) { track ->
                        Card(onClick = { playLocalQueue(controller, tracks, track.id) }, shape = RoundedCornerShape(14.dp)) {
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
            } else if (tab == "Radio") {
                Text("Internet Radio", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Free live stations from the Radio Browser directory", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = radioQuery, onValueChange = { radioQuery = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Search stations") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = radioCountry == "IN", onClick = { radioCountry = "IN"; radioTag = ""; radioLoading = true; scope.launch { runCatching { RadioBrowserRepository.search(radioQuery, "IN") }.onSuccess { radioStations = it }.onFailure { radioError = it.message ?: "Search failed" }; radioLoading = false } }, label = { Text("India") })
                    FilterChip(selected = radioCountry.isBlank(), onClick = { radioCountry = ""; radioTag = ""; radioLoading = true; scope.launch { runCatching { RadioBrowserRepository.search(radioQuery) }.onSuccess { radioStations = it }.onFailure { radioError = it.message ?: "Search failed" }; radioLoading = false } }, label = { Text("Worldwide") })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("bollywood", "classical", "devotional", "news").forEach { tag ->
                        FilterChip(selected = radioTag == tag, onClick = { radioTag = if (radioTag == tag) "" else tag; radioLoading = true; scope.launch { runCatching { RadioBrowserRepository.search(radioQuery, radioCountry, radioTag) }.onSuccess { radioStations = it }.onFailure { radioError = it.message ?: "Search failed" }; radioLoading = false } }, label = { Text(tag.replaceFirstChar { it.uppercase() }) })
                    }
                }
                Button(onClick = {
                    radioLoading = true
                    radioError = ""
                    scope.launch {
                        runCatching { RadioBrowserRepository.search(radioQuery, radioCountry, radioTag) }
                            .onSuccess { radioStations = it }
                            .onFailure { radioError = it.message ?: "Could not load stations" }
                        radioLoading = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null); Text("  Find stations") }
                if (radioLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (radioError.isNotBlank()) Text(radioError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    items(radioStations, key = { it.stationUuid }) { station ->
                        Card(shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(station.name, fontWeight = FontWeight.SemiBold)
                                    Text(listOf(station.country, station.language, if (station.bitrate > 0) "${station.bitrate} kbps" else "").filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                    if (station.tags.isNotBlank()) Text(station.tags.take(80), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                }
                                IconButton(onClick = {
                                    if (radioFavorites.contains(station.stationUuid)) radioFavorites.remove(station.stationUuid) else radioFavorites.add(station.stationUuid)
                                    context.getSharedPreferences("resonance_radio", android.content.Context.MODE_PRIVATE).edit().putStringSet("favorites", radioFavorites.toSet()).apply()
                                }) { Icon(if (radioFavorites.contains(station.stationUuid)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, contentDescription = "Toggle favourite") }
                                IconButton(onClick = {
                                    play(controller, Uri.parse(station.streamUrl), station.name, listOf(station.country, station.language).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Live radio" })
                                    scope.launch { RadioBrowserRepository.registerClick(station.stationUuid) }
                                }) { Icon(Icons.Default.PlayArrow, contentDescription = "Play station") }
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
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled } }) {
                            Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { controller?.seekToPrevious() }) { Icon(Icons.Default.SkipPrevious, contentDescription = "Previous") }
                        IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                            Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = "Play or pause")
                        }
                        IconButton(onClick = { controller?.seekToNext() }) { Icon(Icons.Default.SkipNext, contentDescription = "Next") }
                        IconButton(onClick = {
                            controller?.let {
                                it.repeatMode = when (it.repeatMode) {
                                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                    else -> Player.REPEAT_MODE_OFF
                                }
                            }
                        }) {
                            Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                contentDescription = when (repeatMode) { Player.REPEAT_MODE_ALL -> "Repeat all"; Player.REPEAT_MODE_ONE -> "Repeat one"; else -> "Repeat off" },
                                tint = if (repeatMode != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private fun playTrackQueue(controller: MediaController?, tracks: List<AudioTrack>) {
    if (controller == null || tracks.isEmpty()) return
    val items = tracks.map { track ->
        MediaItem.Builder().setMediaId(track.id).setUri(track.uri).setMediaMetadata(
            MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).build()
        ).build()
    }
    controller.setMediaItems(items, 0, 0L)
    controller.prepare()
    controller.play()
}

private fun loadListeningHistory(context: android.content.Context): List<ListeningEvent> {
    val raw = context.getSharedPreferences("resonance_history", android.content.Context.MODE_PRIVATE).getString("events", "[]") ?: "[]"
    return runCatching {
        val array = org.json.JSONArray(raw)
        (0 until array.length()).mapNotNull { i ->
            val item = array.optJSONObject(i) ?: return@mapNotNull null
            ListeningEvent(item.optString("id"), item.optString("artist"), item.optLong("at"), item.optLong("ms"), item.optBoolean("skipped"))
        }
    }.getOrDefault(emptyList())
}

private fun saveListeningHistory(context: android.content.Context, events: List<ListeningEvent>) {
    val array = org.json.JSONArray()
    events.takeLast(500).forEach { event ->
        array.put(org.json.JSONObject().put("id", event.trackId).put("artist", event.artist).put("at", event.playedAt).put("ms", event.listenedMs).put("skipped", event.skipped))
    }
    context.getSharedPreferences("resonance_history", android.content.Context.MODE_PRIVATE).edit().putString("events", array.toString()).apply()
}

private fun playLocalQueue(controller: MediaController?, tracks: List<AudioTrack>, startTrackId: String) {
    if (controller == null || tracks.isEmpty()) return
    val startIndex = tracks.indexOfFirst { it.id == startTrackId }.takeIf { it >= 0 } ?: 0
    val items = tracks.map { track ->
        MediaItem.Builder().setMediaId(track.id).setUri(track.uri).setMediaMetadata(
            MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).build()
        ).build()
    }
    controller.setMediaItems(items, startIndex, 0L)
    controller.prepare()
    controller.play()
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
