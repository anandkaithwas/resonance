package dev.resonance.app.playback

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import kotlin.math.abs

@OptIn(UnstableApi::class)
object ResonanceVisualizerTap : BaseAudioProcessor() {
    private val _amplitudes = MutableStateFlow(FloatArray(32) { 0.05f })
    val amplitudes: StateFlow<FloatArray> = _amplitudes

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val duplicate = inputBuffer.duplicate()
        val bins = FloatArray(32)
        val totalShorts = duplicate.remaining() / 2
        val step = (totalShorts / 32).coerceAtLeast(1)
        for (i in 0 until 32) {
            if (duplicate.remaining() >= 2) {
                val sample = duplicate.short.toFloat() / Short.MAX_VALUE
                bins[i] = abs(sample).coerceIn(0.04f, 1f)
                val skipBytes = (step - 1) * 2
                if (duplicate.remaining() >= skipBytes) {
                    duplicate.position(duplicate.position() + skipBytes)
                }
            }
        }
        _amplitudes.value = bins
        val outputBuffer = replaceOutputBuffer(inputBuffer.remaining())
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }
}

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(ResonanceVisualizerTap))
                    .build()
            }
        }
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
