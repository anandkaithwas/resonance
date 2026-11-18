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
import java.nio.ByteOrder
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
object ResonanceVisualizerTap : BaseAudioProcessor() {
    private val _amplitudes = MutableStateFlow(FloatArray(32) { 0f })
    val amplitudes: StateFlow<FloatArray> = _amplitudes
    private var channelCount = 1

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            // Do not make playback fail for float or other PCM encodings.
            return AudioProcessor.AudioFormat.NOT_SET
        }
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val duplicate = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val totalSamples = duplicate.remaining() / 2
        val frames = totalSamples / channelCount
        val bins = FloatArray(32)
        for (bin in bins.indices) {
            val startFrame = frames * bin / bins.size
            val endFrame = (frames * (bin + 1) / bins.size).coerceAtLeast(startFrame)
            var sumSquares = 0.0
            var count = 0
            for (frame in startFrame until endFrame) {
                repeat(channelCount) {
                    if (duplicate.remaining() >= 2) {
                        val sample = duplicate.short.toDouble() / Short.MAX_VALUE
                        sumSquares += sample * sample
                        count++
                    }
                }
            }
            bins[bin] = if (count == 0) 0f else sqrt(sumSquares / count).toFloat().coerceIn(0f, 1f)
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
            .setContentType(C.A.AUDIO_CONTENT_TYPE_MUSIC)
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
