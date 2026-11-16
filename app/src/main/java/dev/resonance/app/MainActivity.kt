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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Newspaper
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import coil.compose.AsyncImage
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
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.ALBUM_ID)
        val result = mutableListOf<AudioTrack>()
        contentResolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                result += AudioTrack(id.toString(), c.getString(titleCol) ?: "Unknown title",
                    c.getString(artistCol)?.takeUnless { it == "<unknown>" } ?: "Unknown artist",
                    ContentUris.withAppendedId(collection, id), online = false,
                    album = c.getString(albumCol).orEmpty(),
                    artworkUri = c.getLong(albumIdCol).takeIf { it > 0L }?.let { Uri.parse("content://media/external/audio/albumart/$it") })
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
    var tab by remember { mutableStateOf("Discover") }
    var sourceQuery by remember { mutableStateOf("") }
    var sourceProvider by remember { mutableStateOf("Audius") }
    var sourceResults by remember { mutableStateOf(emptyList<OnlineMusicResult>()) }
    var sourceLoading by remember { mutableStateOf(false) }
    var sourceError by remember { mutableStateOf("") }
    var discoveryOnline by remember { mutableStateOf(emptyList<OnlineMusicResult>()) }
    var discoveryLoading by remember { mutableStateOf(false) }
    var discoveryError by remember { mutableStateOf("") }
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
        NavigationBar { listOf("Discover", "Offline", "Radio", "Sources").forEach { item ->
            NavigationBarItem(selected = tab == item, onClick = { tab = item },
                icon = { Icon(when (item) { "Discover" -> Icons.Default.AutoAwesome; "Offline" -> Icons.Default.LibraryMusic; "Radio" -> Icons.Default.Radio; else -> Icons.Default.Cloud }, contentDescription = item) },
                label = { Text(item) })
        } }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(18.dp)) {
            Text("RESONANCE", fontSize = 24.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
            Text("LOCAL MUSIC · ONLINE STREAMING", color = MaterialTheme.colorScheme.secondary, fontSize = 10.sp)
            Spacer(Modifier.height(18.dp))
            if (tab == "Discover") {
                val seedTrack = tracks.firstOrNull { it.id == currentTrackId }
                    ?: listeningHistory.lastOrNull()?.let { event -> tracks.firstOrNull { it.id == event.trackId } }
                val suggestions = LocalRecommendationEngine.recommend(seedTrack, tracks, listeningHistory, count = 20)
                val seedKey = seedTrack?.id.orEmpty()
                LaunchedEffect(seedKey) { discoveryOnline = emptyList(); discoveryError = "" }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("For you", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                        Text("Music that learns your taste, on your device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text("Explore by mood & occasion", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                val moods = listOf("Happy", "Chill", "Focus", "Workout", "Romantic", "Party", "Travel", "Devotional", "Sleep", "Sad")
                moods.chunked(5).forEach { rowMoods ->
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        rowMoods.forEach { mood ->
                            FilterChip(selected = false, onClick = {
                                discoveryLoading = true
                                discoveryError = ""
                                scope.launch {
                                    runCatching { FreeMusicSourcesRepository.searchAudius(mood.lowercase()) }
                                        .onSuccess { discoveryOnline = it }
                                        .onFailure { discoveryError = it.message ?: "Could not load mood" }
                                    discoveryLoading = false
                                }
                            }, label = { Text(mood) })
                        }
                    }
                }
                if (discoveryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (discoveryError.isNotBlank()) Text(discoveryError, color = MaterialTheme.colorScheme.error)
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.GraphicEq, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("  Your listening mix", fontWeight = FontWeight.Bold)
                        }
                        Text(if (seedTrack != null) "Built around ${seedTrack.title} by ${seedTrack.artist}" else "A fresh starting mix from your local music library", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            val queue = (listOfNotNull(seedTrack) + suggestions.map { it.track }).distinctBy { it.id }
                            if (queue.isNotEmpty()) playTrackQueue(controller, queue)
                        }, enabled = suggestions.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Text("  Play my mix")
                        }
                    }
                }
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.Cloud, null); Text("  Discover beyond your library", fontWeight = FontWeight.Bold) }
                        Text(if (seedTrack != null) "Find independent music related to " + seedTrack.artist + " on Audius." else "Play a track to discover related independent music.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            val seed = seedTrack
                            if (seed != null) { discoveryLoading = true; discoveryError = ""; scope.launch {
                                runCatching { FreeMusicSourcesRepository.recommendAudius(seed.artist, seed.title) }
                                    .onSuccess { discoveryOnline = it }.onFailure { discoveryError = it.message ?: "Discovery failed" }
                                discoveryLoading = false
                            } }
                        }, enabled = seedTrack != null && !discoveryLoading, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.AutoAwesome, null); Text("  Find related music") }
                        if (discoveryLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        if (discoveryError.isNotBlank()) Text(discoveryError, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (discoveryOnline.isNotEmpty()) {
                    Text("Artists", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    discoveryOnline.distinctBy { it.artist }.take(6).forEach { artistPick ->
                        Card(onClick = {
                            discoveryLoading = true
                            scope.launch {
                                runCatching { FreeMusicSourcesRepository.searchAudius(artistPick.artist) }
                                    .onSuccess { discoveryOnline = it }
                                    .onFailure { discoveryError = it.message ?: "Artist lookup failed" }
                                discoveryLoading = false
                            }
                        }, shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                MusicCover(artistPick.artworkUrl, Modifier.size(48.dp))
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(artistPick.artist, fontWeight = FontWeight.SemiBold)
                                    Text("Explore artist", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                    val albums = discoveryOnline.filter { it.album.isNotBlank() }.distinctBy { it.album + it.artist }
                    if (albums.isNotEmpty()) {
                        Text("Albums", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        albums.forEach { albumPick ->
                            Card(onClick = {
                                val albumQueue = discoveryOnline.filter { it.album == albumPick.album && it.artist == albumPick.artist }
                                playOnlineQueue(controller, albumQueue, albumPick.id)
                            }, shape = RoundedCornerShape(14.dp)) {
                                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    MusicCover(albumPick.artworkUrl, Modifier.size(52.dp))
                                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                        Text(albumPick.album, fontWeight = FontWeight.SemiBold)
                                        Text(albumPick.artist, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                    Text("Tracks · Audius", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    discoveryOnline.forEach { result ->
                        Card(shape = RoundedCornerShape(14.dp)) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                MusicCover(result.artworkUrl, Modifier.size(56.dp))
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(result.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    Text(result.artist, style = MaterialTheme.typography.bodySmall)
                                    if (result.album.isNotBlank()) Text(result.album, style = MaterialTheme.typography.labelSmall)
                                }
                                IconButton(onClick = { playOnlineQueue(controller, discoveryOnline, result.id) }, enabled = result.streamUrl != null) { Icon(Icons.Default.PlayArrow, "Play discovery") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Picked for you", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text("${suggestions.size} tracks", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
                if (suggestions.isEmpty()) {
                    Card(shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                            Text(if (tracks.isEmpty()) "Your recommendations are waiting" else "Not enough tracks to recommend yet", fontWeight = FontWeight.Bold)
                            Text(if (tracks.isEmpty()) "Scan your device to let RESONANCE build recommendations from your music. No account or cloud history required." else "Add more music to your library. Suggestions update as you listen.", style = MaterialTheme.typography.bodySmall)
                            Button(onClick = { tab = "Offline"; if (hasPermission) { tracks = scan(); message = "${tracks.size} audio files found." } else permissionLauncher.launch(permission) }) {
                                Icon(Icons.Default.LibraryMusic, null)
                                Text("  Scan music library")
                            }
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.weight(1f)) {
                        items(suggestions, key = { it.track.id }) { recommendation ->
                            Card(onClick = { playLocalQueue(controller, tracks, recommendation.track.id) }, shape = RoundedCornerShape(16.dp)) {
                                Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                                    MusicCover(recommendation.track.artworkUri?.toString(), Modifier.size(54.dp))
                                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                        Text(recommendation.track.title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                        Text(recommendation.track.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                        Text(recommendation.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
                                    }
                                    IconButton(onClick = { playLocalQueue(controller, tracks, recommendation.track.id) }) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = "Play recommendation")
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (tab == "Offline") {

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
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf("bollywood", "classical", "devotional", "news").forEach { tag ->
                        FilterChip(
                            selected = radioTag == tag,
                            onClick = { radioTag = if (radioTag == tag) "" else tag; radioLoading = true; scope.launch { runCatching { RadioBrowserRepository.search(radioQuery, radioCountry, radioTag) }.onSuccess { radioStations = it }.onFailure { radioError = it.message ?: "Search failed" }; radioLoading = false } },
                            label = { Text(tag.replaceFirstChar { it.uppercase() }) },
                            leadingIcon = { Icon(radioCategoryIcon(tag), contentDescription = null, modifier = Modifier.size(16.dp)) }
                        )
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
                                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Icon(radioCategoryIcon(station.tags), contentDescription = "Station category", modifier = Modifier.padding(11.dp).size(25.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                }
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(station.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                    Text(listOf(station.country, station.language, if (station.bitrate > 0) "${station.bitrate} kbps" else "").filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                    if (station.tags.isNotBlank()) Text(station.tags.take(80), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
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
            } else if (tab == "Sources") {
                Text("Music Sources", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Text("Search independent and openly licensed music catalogues", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = sourceQuery, onValueChange = { sourceQuery = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Search songs, artists or genres") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = sourceProvider == "Audius", onClick = { sourceProvider = "Audius" }, label = { Text("Audius") })
                    FilterChip(selected = sourceProvider == "Openverse", onClick = { sourceProvider = "Openverse" }, label = { Text("Openly licensed") })
                }
                Button(
                    onClick = {
                        sourceLoading = true
                        sourceError = ""
                        scope.launch {
                            runCatching {
                                if (sourceProvider == "Audius") FreeMusicSourcesRepository.searchAudius(sourceQuery)
                                else FreeMusicSourcesRepository.searchOpenverse(sourceQuery)
                            }.onSuccess { sourceResults = it }
                             .onFailure { sourceError = it.message ?: "Search failed" }
                            sourceLoading = false
                        }
                    },
                    enabled = sourceQuery.isNotBlank() && !sourceLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Search, null)
                    Text("  Search ${sourceProvider}")
                }
                if (sourceLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (sourceError.isNotBlank()) Text(sourceError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (sourceResults.isEmpty() && !sourceLoading) {
                    Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.Cloud, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                            Text("Explore independent music", fontWeight = FontWeight.Bold)
                            Text("Audius offers an open music catalogue. Openverse searches openly licensed audio and displays its licence and attribution details.", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                        items(sourceResults, key = { it.id }) { result ->
                            Card(shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        MusicCover(result.artworkUrl, Modifier.size(64.dp))
                                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                            Text(result.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                                            Text("${result.artist} · ${result.provider}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                                        }
                                        IconButton(onClick = {
                                            val uri = Uri.parse(result.landingUrl)
                                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
                                        }) { Icon(Icons.Default.OpenInNew, contentDescription = "Open source page") }
                                        IconButton(onClick = {
                                            val queue = if (result.album.isNotBlank()) {
                                                sourceResults.filter { it.provider == result.provider && it.album == result.album && it.artist == result.artist }
                                            } else {
                                                sourceResults.filter { it.provider == result.provider && it.artist.equals(result.artist, true) }
                                            }
                                            playOnlineQueue(controller, queue.ifEmpty { listOf(result) }, result.id)
                                        }, enabled = result.streamUrl != null) { Icon(Icons.Default.PlayArrow, contentDescription = "Play artist or album queue") }
                                    }
                                    if (!result.license.isNullOrBlank()) Text("Licence: ${result.license}", style = MaterialTheme.typography.labelSmall)
                                    if (!result.attribution.isNullOrBlank()) Text(result.attribution, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 3)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("More services: Spotify and YouTube use official app hand-off; SoundCloud and Jamendo require provider-approved app credentials and attribution before direct playback can be enabled.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
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

private fun radioCategoryIcon(tags: String): ImageVector {
    val value = tags.lowercase()
    return when {
        listOf("news", "talk", "spoken", "politic").any { it in value } -> Icons.Default.Newspaper
        listOf("classical", "piano", "orchestra", "instrumental").any { it in value } -> Icons.Default.Piano
        listOf("devotional", "religious", "spiritual", "bhajan", "gospel").any { it in value } -> Icons.Default.SelfImprovement
        listOf("bollywood", "hindi", "indian", "filmi").any { it in value } -> Icons.Default.Language
        listOf("electronic", "dance", "edm", "techno", "house").any { it in value } -> Icons.Default.GraphicEq
        listOf("rock", "metal", "pop", "hits").any { it in value } -> Icons.Default.Headphones
        else -> Icons.Default.MusicNote
    }
}

@Composable
private fun MusicCover(imageUrl: String?, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(contentAlignment = Alignment.Center) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(model = imageUrl, contentDescription = "Album cover", modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Default.MusicNote, contentDescription = "Cover artwork unavailable", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            }
        }
    }
}

private fun playOnlineQueue(controller: MediaController?, results: List<OnlineMusicResult>, selectedId: String) {
    if (controller == null || results.isEmpty()) return
    val playable = results.filter { !it.streamUrl.isNullOrBlank() }
    if (playable.isEmpty()) return
    val start = playable.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val items = playable.map { result ->
        val metadata = MediaMetadata.Builder().setTitle(result.title).setArtist(result.artist)
            .setAlbumTitle(result.album).setArtworkUri(result.artworkUrl?.let { Uri.parse(it) }).build()
        MediaItem.Builder().setMediaId(result.id).setUri(result.streamUrl!!).setMediaMetadata(metadata).build()
    }
    controller.stop()
    controller.setMediaItems(items, start, 0L)
    controller.prepare()
    controller.play()
}

private fun playTrackQueue(controller: MediaController?, tracks: List<AudioTrack>) {
    if (controller == null || tracks.isEmpty()) return
    val items = tracks.map { track ->
        MediaItem.Builder().setMediaId(track.id).setUri(track.uri).setMediaMetadata(
            MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).setArtworkUri(track.artworkUri).build()
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
