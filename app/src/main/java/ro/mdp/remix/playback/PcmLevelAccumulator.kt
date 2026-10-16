package ro.mdp.remix.playback

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

internal enum class MeterPcmEncoding(val bytesPerSample: Int) {
    PCM_16_BIT(2),
    PCM_FLOAT(4),
}

/**
 * Pure JVM, frame-driven meter DSP. Reads little-endian PCM without changing its bytes.
 *
 * True RMS is calculated in approximately 10 ms blocks, then follows a 30 ms attack /
 * 300 ms release amplitude envelope. Peaks attack on the actual sample, hold for 600 ms,
 * and fall at 24 dB/s. All time is completed PCM frames / sample rate, never wall time.
 * Partial samples, channel frames and RMS blocks survive arbitrary decoder buffer splits.
 */
internal class PcmLevelAccumulator(
    private val channels: Int,
    private val encoding: MeterPcmEncoding,
    sampleRate: Int,
) {
    init {
        require(channels > 0)
        require(sampleRate > 0)
    }

    // Publication cadence is independent of the signal envelopes and measurement blocks.
    var frameCount: Long = 0
        private set
    private val rmsBlockFrames = (sampleRate / 100).coerceAtLeast(1)
    private val blockDurationSeconds = rmsBlockFrames.toDouble() / sampleRate
    private val rmsAttackDecay = exp(-blockDurationSeconds / 0.030)
    private val rmsReleaseDecay = exp(-blockDurationSeconds / 0.300)
    private val peakHoldFrames = ceil(sampleRate * 0.600).toInt()
    private val peakReleasePerFrame = 10.0.pow(-24.0 / (20.0 * sampleRate))
    private val rmsAmplitudes = DoubleArray(2)
    private val blockSquares = DoubleArray(2)
    private var blockFrameCount = 0
    private val peakAmplitudes = DoubleArray(2)
    private val livePeakAmplitudes = DoubleArray(2)
    private val remainingHoldFrames = IntArray(2)
    private val sampleBytes = ByteArray(4)
    private var sampleByteCount = 0
    private var channelIndex = 0
    private var frameLeft = 0.0
    private var frameRight = 0.0

    fun append(buffer: ByteBuffer) {
        for (index in buffer.position() until buffer.limit()) {
            sampleBytes[sampleByteCount++] = buffer.get(index)
            if (sampleByteCount == encoding.bytesPerSample) {
                sampleByteCount = 0
                val sample = readSample()
                when (channelIndex) {
                    0 -> {
                        frameLeft = sample
                        if (channels == 1) frameRight = sample
                    }
                    1 -> frameRight = sample
                }
                channelIndex++
                if (channelIndex == channels) {
                    channelIndex = 0
                    blockSquares[0] += frameLeft * frameLeft
                    blockSquares[1] += frameRight * frameRight
                    updatePeak(0, abs(frameLeft))
                    updatePeak(1, abs(frameRight))
                    blockFrameCount++
                    if (blockFrameCount == rmsBlockFrames) finishRmsBlock()
                    frameCount++
                }
            }
        }
    }

    fun levels(): MeterLevels = MeterLevels(
        left = normalizedDb(rmsAmplitudes[0]),
        right = normalizedDb(rmsAmplitudes[1]),
        peakLeft = normalizedDb(peakAmplitudes[0]),
        peakRight = normalizedDb(peakAmplitudes[1]),
        livePeakLeft = normalizedDb(livePeakAmplitudes[0]),
        livePeakRight = normalizedDb(livePeakAmplitudes[1]),
    )

    /** Starts a new UI snapshot interval; no measured audio or ballistic state is discarded. */
    fun clearWindow() {
        frameCount = 0
    }

    fun reset() {
        clearWindow()
        rmsAmplitudes.fill(0.0)
        blockSquares.fill(0.0)
        blockFrameCount = 0
        peakAmplitudes.fill(0.0)
        livePeakAmplitudes.fill(0.0)
        remainingHoldFrames.fill(0)
        sampleByteCount = 0
        channelIndex = 0
        frameLeft = 0.0
        frameRight = 0.0
    }

    private fun finishRmsBlock() {
        for (channel in 0..1) {
            val targetRms = sqrt(blockSquares[channel] / blockFrameCount)
            val previous = rmsAmplitudes[channel]
            val decay = if (targetRms > previous) rmsAttackDecay else rmsReleaseDecay
            rmsAmplitudes[channel] = targetRms + (previous - targetRms) * decay
            blockSquares[channel] = 0.0
        }
        blockFrameCount = 0
    }

    private fun updatePeak(channel: Int, amplitude: Double) {
        livePeakAmplitudes[channel] = maxOf(amplitude, livePeakAmplitudes[channel] * peakReleasePerFrame)
        if (amplitude >= peakAmplitudes[channel]) {
            peakAmplitudes[channel] = amplitude
            remainingHoldFrames[channel] = peakHoldFrames
        } else if (remainingHoldFrames[channel] > 0) {
            remainingHoldFrames[channel]--
        } else {
            val released = peakAmplitudes[channel] * peakReleasePerFrame
            if (amplitude >= released) {
                peakAmplitudes[channel] = amplitude
                remainingHoldFrames[channel] = peakHoldFrames
            } else {
                peakAmplitudes[channel] = released
            }
        }
    }

    private fun readSample(): Double {
        val low = sampleBytes[0].toInt() and 0xff
        val second = sampleBytes[1].toInt() and 0xff
        return when (encoding) {
            MeterPcmEncoding.PCM_16_BIT ->
                (low or (second shl 8)).toShort().toDouble() / 32768.0
            MeterPcmEncoding.PCM_FLOAT -> {
                val bits = low or (second shl 8) or
                    ((sampleBytes[2].toInt() and 0xff) shl 16) or
                    ((sampleBytes[3].toInt() and 0xff) shl 24)
                val value = Float.fromBits(bits)
                // Invalid floating PCM must not poison the UI; the audio bytes remain unchanged.
                if (value.isFinite()) value.toDouble().coerceIn(-1.0, 1.0) else 0.0
            }
        }
    }

    private fun normalizedDb(amplitude: Double): Float {
        if (amplitude <= 0.001 || !amplitude.isFinite()) return 0f
        return ((20.0 * log10(amplitude.coerceAtMost(1.0)) + 60.0) / 60.0)
            .coerceIn(0.0, 1.0).toFloat()
    }
}
