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
import dev.resonance.app.intelligence.AurixAudioEngine
import dev.resonance.app.intelligence.AudioInsight
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteOrder
import kotlin.math.sqrt

@OptIn(UnstableApi::class)
object ResonanceVisualizerTap : BaseAudioProcessor() {
    private val _amplitudes = MutableStateFlow(FloatArray(32) { 0f })
    val amplitudes: StateFlow<FloatArray> = _amplitudes
    private val _insight = MutableStateFlow(AurixAudioEngine().latestInsight)
    val insight: StateFlow<AudioInsight> = _insight
    val intelligence = AurixAudioEngine()
    private var channelCount = 1
    private var sampleRate = 44100

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        channelCount = inputAudioFormat.channelCount.coerceAtLeast(1)
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val source = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val samples = ShortArray(source.remaining() / 2)
        for (i in samples.indices) samples[i] = source.short
        val processed = intelligence.process(samples, sampleRate, channelCount)

        val visual = FloatArray(32)
        for (bin in visual.indices) {
            val start = processed.size * bin / visual.size
            val end = processed.size * (bin + 1) / visual.size
            var sum = 0.0
            for (i in start until end) {
                val value = processed[i] / 32768.0
                sum += value * value
            }
            visual[bin] = if (end <= start) 0f else sqrt(sum / (end - start)).toFloat().coerceIn(0f, 1f)
        }
        _amplitudes.value = visual
        _insight.value = intelligence.latestInsight

        val output = replaceOutputBuffer(processed.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        processed.forEach { output.putShort(it) }
        output.flip()
        inputBuffer.position(inputBuffer.limit())
    }

    override fun onFlush() {
        super.onFlush()
        intelligence.reset()
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
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setAudioProcessors(arrayOf(ResonanceVisualizerTap))
                .build()
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
