package dev.resonance.app.djrooms

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DjRoomClient {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    private var credentials: DjCredentials? = null
    private var reconnectAttempts = 0
    private var closedByUser = false
    private var socketGeneration = 0

    var onEvent: ((DjEvent) -> Unit)? = null

    fun requireHttps(baseUrl: String): String {
        val parsed = baseUrl.trim().toHttpUrlOrNull()
        require(parsed != null && parsed.scheme.equals("https", true) && parsed.host.isNotBlank()) {
            "DJ room server must use HTTPS"
        }
        return baseUrl.trim().trimEnd('/')
    }

    suspend fun createRoom(
        baseUrl: String,
        displayName: String,
        track: JSONObject
    ): Result<DjCredentials> = withContext(Dispatchers.IO) {
        runCatching {
            val root = requireHttps(baseUrl)
            val body = JSONObject().put("hostName", displayName).put("track", track)
            val json = executeJson(
                Request.Builder().url("$root/rooms").post(body.toString().toRequestBody(JSON)).build()
            )
            DjCredentials(
                baseUrl = root,
                roomId = json.optString("roomId"),
                token = json.optString("hostToken"),
                participantId = ""
            )
        }
    }

    suspend fun joinRoom(baseUrl: String, joinCode: String, displayName: String): Result<DjCredentials> =
        withContext(Dispatchers.IO) {
            runCatching {
                val root = requireHttps(baseUrl)
                val body = JSONObject().put("joinCode", joinCode.trim().uppercase())
                    .put("displayName", displayName)
                val json = executeJson(
                    Request.Builder().url("$root/rooms/join").post(body.toString().toRequestBody(JSON)).build()
                )
                DjCredentials(
                    baseUrl = root,
                    roomId = json.optString("roomId"),
                    token = json.optString("memberToken"),
                    participantId = json.optString("participantId")
                )
            }
        }

    suspend fun refreshSnapshot(): Result<DjSnapshot> = withContext(Dispatchers.IO) {
        runCatching {
            val creds = credentials ?: error("Not connected to a room")
            val json = executeJson(
                Request.Builder().url("${creds.baseUrl}/rooms/${creds.roomId}/snapshot")
                    .header("Authorization", "Bearer ${creds.token}").get().build()
            )
            DjSnapshot.from(json.optJSONObject("snapshot") ?: error("Invalid room snapshot"))
        }
    }

    suspend fun leave(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val creds = credentials ?: error("Not connected to a room")
            executeJson(
                Request.Builder().url("${creds.baseUrl}/rooms/${creds.roomId}/leave")
                    .header("Authorization", "Bearer ${creds.token}")
                    .post("{}".toRequestBody(JSON)).build()
            )
            Unit
        }
    }

    fun connect(creds: DjCredentials) {
        closedByUser = false
        credentials = creds
        openSocket(creds, immediate = true)
    }

    fun reconnect() {
        credentials?.let { openSocket(it, immediate = true) }
    }

    fun disconnect() {
        closedByUser = true
        socketGeneration += 1
        reconnectAttempts = 0
        webSocket?.close(1000, "Leaving room")
        webSocket = null
        credentials = null
    }

    fun sendCommand(command: JSONObject): Boolean {
        val socket = webSocket ?: return false
        return socket.send(JSONObject().put("type", "command").put("command", command).toString())
    }

    private fun executeJson(request: Request): JSONObject = client.newCall(request).execute().use { response: Response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) error("DJ room server returned HTTP ${response.code}: ${text.take(160)}")
        runCatching { JSONObject(text) }.getOrElse { error("DJ room server returned invalid JSON") }
    }

    private fun openSocket(creds: DjCredentials, immediate: Boolean) {
        if (closedByUser) return
        val generation = ++socketGeneration
        webSocket?.cancel()
        val url = creds.baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?.scheme("wss")
            ?.addPathSegment("ws")
            ?.addQueryParameter("roomId", creds.roomId)
            ?.addQueryParameter("token", creds.token)
            ?.build()
            ?: error("Invalid DJ room server URL")
        webSocket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation != socketGeneration || closedByUser) return
                reconnectAttempts = 0
                emit(DjEvent.Connected)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != socketGeneration || closedByUser) return
                runCatching { parseMessage(text) }.onSuccess { emit(it) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation != socketGeneration || closedByUser) return
                scheduleReconnect(creds)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation == socketGeneration && !closedByUser) scheduleReconnect(creds)
            }
        })
    }

    private fun scheduleReconnect(creds: DjCredentials) {
        if (closedByUser || credentials?.token != creds.token) return
        emit(DjEvent.Reconnecting)
        val delayMs = minOf(1_000L shl minOf(reconnectAttempts, 5), 30_000L)
        reconnectAttempts += 1
        val generation = socketGeneration
        client.dispatcher.executorService.execute {
            Thread.sleep(delayMs)
            if (!closedByUser && generation == socketGeneration && credentials?.token == creds.token) {
                openSocket(creds, immediate = false)
            }
        }
    }

    private fun parseMessage(text: String): DjEvent {
        val json = JSONObject(text)
        return when (json.optString("type")) {
            "snapshot" -> {
                val snapshot = DjSnapshot.from(json.optJSONObject("snapshot") ?: error("Missing snapshot"))
                val you = parseParticipant(json.optJSONObject("you")) ?: snapshot.participants.firstOrNull()
                    ?: error("Missing participant")
                DjEvent.Snapshot(snapshot, you)
            }
            "state" -> DjEvent.State(DjState.from(json.optJSONObject("state") ?: error("Missing state")))
            "presence" -> {
                val array = json.optJSONArray("participants") ?: JSONArray()
                DjEvent.Presence(
                    participants = (0 until array.length()).mapNotNull { parseParticipant(array.optJSONObject(it)) },
                    hostChanged = json.optBoolean("hostChanged")
                )
            }
            "error" -> DjEvent.Error(json.optString("message").ifBlank { "Room command rejected" })
            else -> DjEvent.Error("Unknown room event")
        }
    }

    private fun parseParticipant(json: JSONObject?): DjParticipant? = json?.let {
        DjParticipant(
            id = it.optString("id"),
            name = it.optString("name"),
            isHost = it.optBoolean("isHost"),
            canControlPlayback = it.optBoolean("canControlPlayback"),
            canSuggestQueue = it.optBoolean("canSuggestQueue"),
            connected = it.optBoolean("connected")
        )
    }

    private fun emit(event: DjEvent) {
        mainHandler.post { onEvent?.invoke(event) }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
