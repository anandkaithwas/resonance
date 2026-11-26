package dev.resonance.app.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
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
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import dev.resonance.app.djrooms.DjCredentials
import dev.resonance.app.djrooms.DjEvent
import dev.resonance.app.djrooms.DjParticipant
import dev.resonance.app.djrooms.DjRoomClient
import dev.resonance.app.djrooms.DjSnapshot
import dev.resonance.app.djrooms.DjState
import dev.resonance.app.djrooms.LocalFingerprint
import dev.resonance.app.media.ResonanceTrack
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun DjRoomScreen(
    player: Player?,
    tracks: List<ResonanceTrack>,
    currentTrackId: String,
    currentTrackTitle: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { DjRoomClient() }
    var baseUrl by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("Guest") }
    var joinCode by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Rooms use HTTPS/WSS. A room code syncs playback but does not grant music rights.") }
    var snapshot by remember { mutableStateOf<DjSnapshot?>(null) }
    var you by remember { mutableStateOf<DjParticipant?>(null) }
    var currentState by remember { mutableStateOf<DjState?>(null) }
    var connected by remember { mutableStateOf(false) }
    var seekPosition by remember { mutableFloatStateOf(0f) }
    var clockOffsetMs by remember { mutableStateOf(0L) }
    var localFingerprint by remember { mutableStateOf("") }
    var guestSuggestions by remember { mutableStateOf(false) }
    var grantPlayback by remember { mutableStateOf(false) }
    var lastAppliedSequence by remember { mutableStateOf(0L) }

    val currentTrack = tracks.firstOrNull { it.id == currentTrackId } ?: tracks.firstOrNull()

    LaunchedEffect(currentTrack?.uri) {
        localFingerprint = currentTrack?.let { runCatching { LocalFingerprint.of(context, it.uri) }.getOrNull() }.orEmpty()
    }
    DisposableEffect(client) {
        onDispose {
            client.onEvent = null
            client.disconnect()
            runCatching { player?.playbackParameters = PlaybackParameters.DEFAULT }
        }
    }
    LaunchedEffect(client) {
        client.onEvent = { event ->
            when (event) {
                is DjEvent.Connected -> { connected = true; status = "Connected to room." }
                is DjEvent.Reconnecting -> { connected = false; status = "Reconnecting — latest snapshot will be restored." }
                is DjEvent.Snapshot -> {
                    snapshot = event.snapshot
                    you = event.you
                    currentState = event.snapshot.state
                    clockOffsetMs = event.snapshot.state.serverTimestamp - System.currentTimeMillis()
                    guestSuggestions = event.snapshot.state.guestQueueSuggestions
                    status = "Joined ${event.snapshot.joinCode}. Sequence ${event.snapshot.state.sequence}."
                }
                is DjEvent.State -> currentState = event.state
                is DjEvent.Presence -> snapshot = snapshot?.copy(participants = event.participants)
                is DjEvent.Error -> status = event.message
            }
        }
    }

    fun applyState(state: DjState) {
        val activePlayer = player ?: return
        if (state.sequence <= lastAppliedSequence) return
        lastAppliedSequence = state.sequence
        val serverNow = System.currentTimeMillis() + clockOffsetMs
        val target = (state.positionMs + (serverNow - state.serverTimestamp)).coerceAtLeast(0L)
        seekPosition = target.coerceAtMost(24L * 60L * 60L * 1_000L).toFloat()
        if (!state.isPlaying) {
            activePlayer.pause()
            runCatching { activePlayer.playbackParameters = PlaybackParameters.DEFAULT }
            return
        }
        val drift = target - activePlayer.currentPosition
        when {
            kotlin.math.abs(drift) > 5_000L -> {
                activePlayer.seekTo(target)
                activePlayer.play()
            }
            kotlin.math.abs(drift) > 250L -> {
                val correction = (1f + drift / 12_000f).coerceIn(0.9f, 1.1f)
                runCatching { activePlayer.playbackParameters = PlaybackParameters(correction, 1f) }
                if (!activePlayer.isPlaying) activePlayer.play()
            }
            else -> {
                runCatching { activePlayer.playbackParameters = PlaybackParameters(state.playbackSpeed, 1f) }
                if (!activePlayer.isPlaying) activePlayer.play()
            }
        }
    }

    LaunchedEffect(currentState?.sequence) {
        currentState?.let { applyState(it) }
    }

    suspend fun trackPayload(track: ResonanceTrack?): JSONObject {
        require(track != null) { "Select a track before creating a room" }
        val uri = track.uri.toString()
        return if (uri.startsWith("http", true)) {
            JSONObject().put("sourceType", "licensed_stream").put("provider", "Direct stream")
                .put("providerRef", uri).put("title", track.title).put("artist", track.artist)
                .put("licensed", true)
        } else {
            JSONObject().put("sourceType", "local_fingerprint").put("provider", "Local library")
                .put("title", track.title).put("artist", track.artist)
                .put("fingerprint", LocalFingerprint.of(context, track.uri))
        }
    }

    fun connect(creds: DjCredentials) {
        client.connect(creds)
        status = "Connecting to room ${creds.roomId.take(8)}…"
    }

    fun command(type: String, positionMs: Long = 0L, extra: (JSONObject.() -> Unit)? = null) {
        val payload = JSONObject().put("type", type).put("positionMs", positionMs)
        extra?.invoke(payload)
        val sent = client.sendCommand(payload)
        if (!sent) status = "Not connected to a room."
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Aurix DJ Rooms", style = MaterialTheme.typography.headlineSmall)
        Text("Private, host-controlled listening. Participants must independently have an authorised copy of the track; no local audio is uploaded or relayed.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it },
            label = { Text("HTTPS DJ room server") },
            placeholder = { Text("https://rooms.example.com") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(value = displayName, onValueChange = { displayName = it },
            label = { Text("Display name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    client.createRoom(baseUrl, displayName.ifBlank { "Host" }, trackPayload(currentTrack))
                        .onSuccess { connect(it) }
                        .onFailure { status = it.message ?: "Could not create room" }
                }
            }) { Text("Create") }
            OutlinedTextField(value = joinCode, onValueChange = { joinCode = it },
                label = { Text("Join code") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = {
                scope.launch {
                    client.joinRoom(baseUrl, joinCode, displayName.ifBlank { "Guest" })
                        .onSuccess { connect(it) }
                        .onFailure { status = it.message ?: "Could not join room" }
                }
            }) { Text("Join") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall)

        snapshot?.let { room ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Room ${room.joinCode} ${if (connected) "● Connected" else "○ Reconnecting"}")
                    Text("Host: ${room.participants.firstOrNull { it.isHost }?.name ?: "Unknown"} • Sequence ${currentState?.sequence ?: room.state.sequence}")
                    OutlinedButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Join my Aurix DJ room with code ${room.joinCode}")
                        }
                        runCatching { context.startActivity(Intent.createChooser(share, "Share DJ room")) }
                    }) { Text("Share join code") }
                    val roomTrack = currentState?.track ?: room.state.track
                    Text("Now playing: ${roomTrack?.title ?: currentTrackTitle.ifBlank { "No track" }}")
                    val sourceWarning = when {
                        roomTrack == null -> "No track has been selected by the host."
                        roomTrack.sourceType == "local_fingerprint" &&
                            roomTrack.fingerprint.isNotBlank() && localFingerprint != roomTrack.fingerprint ->
                            "Track unavailable on your device/provider — you do not have a matching local copy."
                        roomTrack.sourceType == "licensed_stream" ->
                            "Resolve this stream through your own authorised provider entitlement."
                        roomTrack.sourceType == "provider_catalogue" ->
                            "Play this track through your own authorised provider account."
                        else -> "Source matched locally. Playback is synchronised only; audio is not shared."
                    }
                    Text(sourceWarning, style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Guests may suggest queue", Modifier.weight(1f))
                        Switch(guestSuggestions, { value ->
                            guestSuggestions = value
                            command("host_policy") {
                                put("guestQueueSuggestions", value)
                                put("playbackPermissionForGuest", grantPlayback)
                            }
                        })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Grant playback to guests", Modifier.weight(1f))
                        Switch(grantPlayback, { value ->
                            grantPlayback = value
                            command("host_policy") {
                                put("guestQueueSuggestions", guestSuggestions)
                                put("playbackPermissionForGuest", value)
                            }
                        })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = you?.canControlPlayback == true, onClick = { command("play", seekPosition.toLong()) }) { Text("Play") }
                        OutlinedButton(enabled = you?.canControlPlayback == true, onClick = { command("pause") }) { Text("Pause") }
                        OutlinedButton(enabled = you?.canControlPlayback == true, onClick = { command("next") }) { Text("Next") }
                    }
                    Text("Target position: ${seekPosition.toLong() / 1000}s")
                    Slider(seekPosition, { seekPosition = it }, enabled = you?.canControlPlayback == true,
                        onValueChangeFinished = { command("seek", seekPosition.toLong()) },
                        valueRange = 0f..600_000f)
                    OutlinedButton(onClick = {
                        scope.launch {
                            client.leave()
                            client.disconnect()
                            snapshot = null
                            you = null
                            currentState = null
                            connected = false
                            status = "Left room."
                        }
                    }) { Text("Leave room") }
                }
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Participants (${room.participants.size})", style = MaterialTheme.typography.titleMedium)
                    LazyColumn {
                        items(room.participants, key = { it.id }) { participant ->
                            Text(
                                "${participant.name}${if (participant.isHost) " (host)" else ""} " +
                                    "${if (participant.canControlPlayback) "controls" else "listener"} " +
                                    "${if (participant.connected) "online" else "offline"}",
                                Modifier.fillMaxWidth().padding(4.dp),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        val current = currentState
        if (current != null && snapshot != null) {
            Text("Drift status: ${kotlin.math.abs(current.positionMs - player?.currentPosition.orZero())} ms " +
                "from authoritative snapshot. Small drift is corrected with seek/speed; large drift re-buffers.",
                style = MaterialTheme.typography.bodySmall)
        }
        Text("Track identity only is sent to the server. Local files, file paths and audio bytes are never relayed.",
            style = MaterialTheme.typography.bodySmall)
    }
}

private fun Long?.orZero(): Long = this ?: 0L
