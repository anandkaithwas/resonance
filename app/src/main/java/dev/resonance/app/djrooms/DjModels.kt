package dev.resonance.app.djrooms

import org.json.JSONObject

data class DjParticipant(
    val id: String,
    val name: String,
    val isHost: Boolean,
    val canControlPlayback: Boolean,
    val canSuggestQueue: Boolean,
    val connected: Boolean
)

data class DjTrack(
    val sourceType: String,
    val provider: String,
    val providerRef: String,
    val title: String,
    val artist: String,
    val fingerprint: String,
    val licensed: Boolean
)

data class DjState(
    val sequence: Long,
    val eventType: String,
    val serverTimestamp: Long,
    val track: DjTrack?,
    val positionMs: Long,
    val isPlaying: Boolean,
    val playbackSpeed: Float,
    val queueRevision: Int,
    val guestQueueSuggestions: Boolean
) {
    companion object {
        fun from(json: JSONObject): DjState {
            val trackJson = json.optJSONObject("track")
            val track = if (trackJson == null) null else DjTrack(
                sourceType = trackJson.optString("sourceType"),
                provider = trackJson.optString("provider"),
                providerRef = trackJson.optString("providerRef"),
                title = trackJson.optString("title"),
                artist = trackJson.optString("artist"),
                fingerprint = trackJson.optString("fingerprint"),
                licensed = trackJson.optBoolean("licensed")
            )
            return DjState(
                sequence = json.optLong("sequence"),
                eventType = json.optString("eventType"),
                serverTimestamp = json.optLong("serverTimestamp"),
                track = track,
                positionMs = json.optLong("positionMs"),
                isPlaying = json.optBoolean("isPlaying"),
                playbackSpeed = json.optDouble("playbackSpeed", 1.0).toFloat(),
                queueRevision = json.optInt("queueRevision"),
                guestQueueSuggestions = json.optBoolean("guestQueueSuggestions")
            )
        }
    }
}

data class DjSnapshot(
    val roomId: String,
    val joinCode: String,
    val hostId: String,
    val expiresAt: Long,
    val participants: List<DjParticipant>,
    val state: DjState
) {
    companion object {
        fun from(json: JSONObject): DjSnapshot {
            val stateJson = json.optJSONObject("state") ?: JSONObject()
            val participants = json.optJSONArray("participants")
            return DjSnapshot(
                roomId = json.optString("roomId"),
                joinCode = json.optString("joinCode"),
                hostId = json.optString("hostId"),
                expiresAt = json.optLong("expiresAt"),
                participants = if (participants == null) emptyList() else (0 until participants.length()).mapNotNull { i ->
                    val p = participants.optJSONObject(i) ?: return@mapNotNull null
                    DjParticipant(
                        id = p.optString("id"),
                        name = p.optString("name"),
                        isHost = p.optBoolean("isHost"),
                        canControlPlayback = p.optBoolean("canControlPlayback"),
                        canSuggestQueue = p.optBoolean("canSuggestQueue"),
                        connected = p.optBoolean("connected")
                    )
                },
                state = DjState.from(stateJson)
            )
        }
    }
}

data class DjCredentials(
    val baseUrl: String,
    val roomId: String,
    val token: String,
    val participantId: String
)

sealed interface DjEvent {
    data class Snapshot(val snapshot: DjSnapshot, val you: DjParticipant) : DjEvent
    data class State(val state: DjState) : DjEvent
    data class Presence(val participants: List<DjParticipant>, val hostChanged: Boolean) : DjEvent
    data class Error(val message: String) : DjEvent
    data object Connected : DjEvent
    data object Reconnecting : DjEvent
}
