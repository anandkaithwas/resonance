package dev.resonance.app.aidj

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.media3.common.Player
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Single-player fade transition. A true crossfade needs two independently-playing decks.
 */
class LocalAiDjController(context: Context) {
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
            }
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
            if (announceWithVoice && ttsReady) {
                val title = nextItem.mediaMetadata.title?.toString() ?: "the next track"
                val artist = nextItem.mediaMetadata.artist?.toString().orEmpty()
                val line = if (artist.isNotBlank()) "Up next, $title by $artist" else "Up next, $title"
                tts?.speak(line, TextToSpeech.QUEUE_FLUSH, null, "aurix_dj")
                delay(1500L)
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

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }
}
