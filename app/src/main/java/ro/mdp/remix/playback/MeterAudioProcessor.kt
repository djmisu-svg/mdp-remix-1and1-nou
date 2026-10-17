package ro.mdp.remix.playback

import android.util.Log
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
    private var loggedFirstInput = false
    private var loggedPublication = false

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        Log.d("MdP.AudioMeters", "Configure PCM: $inputAudioFormat")
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
                inputAudioFormat.sampleRate,
            )
        } else null
        minimumFrames = (inputAudioFormat.sampleRate / 25).coerceAtLeast(1).toLong()
        activeGeneration = null
        loggedFirstInput = false
        loggedPublication = false
        AudioMeters.clear()
        Log.d("MdP.AudioMeters", "Flush PCM: $inputAudioFormat; analyzer=${accumulator != null}")
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // AudioProcessingPipeline probes processors with its shared EMPTY_BUFFER.
        // BaseAudioProcessor initially uses that same instance internally, so copying
        // a zero-length input via replaceOutputBuffer(0).put(input) would self-copy.
        if (!inputBuffer.hasRemaining()) return

        val token = AudioMeters.activeToken()
        val analyzer = accumulator
        if (!loggedFirstInput && inputBuffer.hasRemaining()) {
            loggedFirstInput = true
            Log.d("MdP.AudioMeters", "First PCM buffer: bytes=${inputBuffer.remaining()}; token=$token; analyzer=${analyzer != null}")
        }
        if (token != activeGeneration || token == null) {
            analyzer?.reset()
            activeGeneration = token
            loggedPublication = false
        }
        if (token != null && analyzer != null) {
            // Every PCM frame advances RMS/peak ballistics even when this UI snapshot
            // is throttled. Decoder buffer sizes therefore cannot skip envelope steps.
            analyzer.append(inputBuffer)
            if (analyzer.frameCount >= minimumFrames && AudioMeters.publish(token, analyzer.levels())) {
                if (!loggedPublication) {
                    loggedPublication = true
                    Log.d("MdP.AudioMeters", "Published decoded PCM: ${analyzer.levels()}")
                }
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
