package ro.mdp.remix.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/** A pass-through tap: the output PCM is byte-for-byte identical to the input. */
@OptIn(UnstableApi::class)
internal class MeterAudioProcessor : BaseAudioProcessor() {
    private var accumulator: PcmLevelAccumulator? = null
    private var activeGeneration: Long? = null
    private var minimumFrames = 1L

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.channelCount <= 0 || inputAudioFormat.sampleRate <= 0 ||
            inputAudioFormat.encoding !in setOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)
        ) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        // BaseAudioProcessor applies pending formats at flush, not at configure.
        accumulator = if (inputAudioFormat.channelCount > 0) {
            PcmLevelAccumulator(
                inputAudioFormat.channelCount,
                if (inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT) {
                    MeterPcmEncoding.PCM_FLOAT
                } else {
                    MeterPcmEncoding.PCM_16_BIT
                },
            )
        } else null
        minimumFrames = (inputAudioFormat.sampleRate / 25).coerceAtLeast(1).toLong()
        activeGeneration = null
        AudioMeters.clear()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val token = AudioMeters.activeToken()
        val analyzer = accumulator
        if (token != activeGeneration || token == null) {
            analyzer?.reset()
            activeGeneration = token
        }
        if (token != null && analyzer != null) {
            analyzer.append(inputBuffer)
            if (analyzer.frameCount >= minimumFrames && AudioMeters.publish(token, analyzer.levels())) {
                analyzer.clearWindow()
            }
        }
        val output = replaceOutputBuffer(inputBuffer.remaining())
        output.put(inputBuffer)
        output.flip()
    }

    override fun onReset() {
        accumulator = null
        activeGeneration = null
        AudioMeters.clear()
    }
}
