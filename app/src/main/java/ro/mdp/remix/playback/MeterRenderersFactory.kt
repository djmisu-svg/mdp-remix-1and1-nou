package ro.mdp.remix.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

@OptIn(UnstableApi::class)
internal class MeterRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        val sink = DefaultAudioSink.Builder(context)
            // Media3's float-output shortcut bypasses custom processors. Its normal pipeline
            // converts higher-resolution decoder output to PCM16 before our transparent tap.
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(MeterAudioProcessor()))
            .build()
        return object : ForwardingAudioSink(sink) {
            // Encoded passthrough/offload also bypass PCM processors. Let MediaCodec decode
            // supported local audio so the meter always measures the stream actually queued.
            override fun supportsFormat(format: Format): Boolean =
                format.sampleMimeType == MimeTypes.AUDIO_RAW && super.supportsFormat(format)

            override fun getFormatSupport(format: Format): Int =
                if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format)
                else AudioSink.SINK_FORMAT_UNSUPPORTED

            override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
                AudioOffloadSupport.DEFAULT_UNSUPPORTED
        }
    }
}
