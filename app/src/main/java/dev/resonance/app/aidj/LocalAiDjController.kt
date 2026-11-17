package dev.resonance.app.aidj

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import java.util.Locale

class LocalAiDjController(context: Context) {
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                ttsReady = true
            }
        }
    }

    suspend fun transitionToNext(player: Player, crossfadeSeconds: Float, announceWithVoice: Boolean) {
        val totalMs = (crossfadeSeconds * 1000).toLong().coerceAtLeast(500L)
        val steps = 16
        val stepDelay = totalMs / steps
        for (i in steps downTo 2) {
            player.volume = i / steps.toFloat()
            delay(stepDelay)
        }
        val nextIndex = if (player.hasNextMediaItem()) player.nextMediaItemIndex else 0
        val nextItem = if (player.mediaItemCount > nextIndex) player.getMediaItemAt(nextIndex) else null
        val title = nextItem?.mediaMetadata?.title?.toString() ?: "next track"
        val artist = nextItem?.mediaMetadata?.artist?.toString() ?: ""
        if (announceWithVoice && ttsReady) {
            val line = if (artist.isNotBlank()) "Up next, $title by $artist" else "Up next, $title"
            tts?.speak(line, TextToSpeech.QUEUE_FLUSH, null, "resonance_dj")
            delay(1500L)
        }
        if (player.hasNextMediaItem()) player.seekToNextMediaItem() else if (player.mediaItemCount > 0) player.seekTo(0, 0L)
        player.play()
        for (i in 2..steps) {
            player.volume = i / steps.toFloat()
            delay(stepDelay)
        }
    }

    fun release() { tts?.shutdown() }
}
