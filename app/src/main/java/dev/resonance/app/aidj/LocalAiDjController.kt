package dev.resonance.app.aidj

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Single-player fade transition. TTS is initialized only when a voice announcement is requested,
 * so Android speech-engine startup is not part of the app's cold-start path.
 */
class LocalAiDjController(context: Context) {
    private val appContext = context.applicationContext
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var ttsInitRequested = false

    @Synchronized
    private fun initializeTtsIfNeeded() {
        if (ttsInitRequested) return
        ttsInitRequested = true
        try {
            tts = TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    runCatching { tts?.language = Locale.getDefault() }
                    ttsReady = true
                }
            }
        } catch (_: Throwable) {
            // Voice announcements are optional; playback and the rest of the app must continue.
            ttsReady = false
        }
    }

    suspend fun transitionToNext(player: Player, fadeSeconds: Float, announceWithVoice: Boolean) {
        require(fadeSeconds.isFinite() && fadeSeconds in 0.5f..15f) { "Fade duration must be 0.5–15 seconds" }
        require(player.mediaItemCount > 0) { "There is no track in the queue" }
        val originalVolume = player.volume
        val steps = 24
        val stepDelay = (fadeSeconds * 1000L / steps).coerceAtLeast(1L)
        try {
            for (step in steps downTo 0) {
                player.volume = originalVolume * step / steps
                delay(stepDelay)
            }
            val nextIndex = if (player.hasNextMediaItem()) player.nextMediaItemIndex else 0
            val nextItem = player.getMediaItemAt(nextIndex)
            if (announceWithVoice) {
                initializeTtsIfNeeded()
                if (ttsReady) {
                    val title = nextItem.mediaMetadata.title?.toString() ?: "the next track"
                    val artist = nextItem.mediaMetadata.artist?.toString().orEmpty()
                    val line = if (artist.isNotBlank()) "Up next, $title by $artist" else "Up next, $title"
                    runCatching { tts?.speak(line, TextToSpeech.QUEUE_FLUSH, null, "aurix_dj") }
                    delay(1500L)
                }
            }
            if (player.hasNextMediaItem()) player.seekToNextMediaItem() else player.seekTo(0, 0L)
            player.play()
            for (step in 0..steps) {
                player.volume = originalVolume * step / steps
                delay(stepDelay)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            player.volume = originalVolume
        }
    }

    @Synchronized
    fun release() {
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
        ttsInitRequested = false
    }
}
