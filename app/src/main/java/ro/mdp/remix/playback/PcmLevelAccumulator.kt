package ro.mdp.remix.playback

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

internal enum class MeterPcmEncoding(val bytesPerSample: Int) {
    PCM_16_BIT(2),
    PCM_FLOAT(4),
}

/** Pure JVM DSP. Reads Android little-endian PCM without changing the source buffer. */
internal class PcmLevelAccumulator(
    private val channels: Int,
    private val encoding: MeterPcmEncoding,
) {
    init {
        require(channels > 0)
    }

    var frameCount: Long = 0
        private set
    private var leftSquares = 0.0
    private var rightSquares = 0.0
    private var peakLeft = 0.0
    private var peakRight = 0.0
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
                    leftSquares += frameLeft * frameLeft
                    rightSquares += frameRight * frameRight
                    peakLeft = max(peakLeft, abs(frameLeft))
                    peakRight = max(peakRight, abs(frameRight))
                    frameCount++
                }
            }
        }
    }

    fun levels(): MeterLevels = if (frameCount == 0L) MeterLevels() else MeterLevels(
        left = normalizedDb(sqrt(leftSquares / frameCount)),
        right = normalizedDb(sqrt(rightSquares / frameCount)),
        peakLeft = normalizedDb(peakLeft),
        peakRight = normalizedDb(peakRight),
    )

    /** Starts a new measurement window while retaining any split PCM sample/frame. */
    fun clearWindow() {
        frameCount = 0
        leftSquares = 0.0
        rightSquares = 0.0
        peakLeft = 0.0
        peakRight = 0.0
    }

    fun reset() {
        clearWindow()
        sampleByteCount = 0
        channelIndex = 0
        frameLeft = 0.0
        frameRight = 0.0
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
