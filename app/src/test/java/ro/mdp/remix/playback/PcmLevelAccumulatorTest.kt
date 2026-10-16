package ro.mdp.remix.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmLevelAccumulatorTest {
    @Test
    fun silenceHasNoRmsOrPeaks() {
        val analyzer = PcmLevelAccumulator(2, MeterPcmEncoding.PCM_16_BIT)
        analyzer.append(pcm16(0, 0, 0, 0))
        assertEquals(MeterLevels(), analyzer.levels())
        assertEquals(2L, analyzer.frameCount)
    }

    @Test
    fun monoIsMirroredIntoBothMeters() {
        val analyzer = PcmLevelAccumulator(1, MeterPcmEncoding.PCM_16_BIT)
        analyzer.append(pcm16(16384, -16384))
        val expected = db(0.5)
        assertEquals(MeterLevels(expected, expected, expected, expected), analyzer.levels())
    }

    @Test
    fun stereoRmsAndSamplePeaksStayIndependent() {
        val analyzer = PcmLevelAccumulator(2, MeterPcmEncoding.PCM_16_BIT)
        analyzer.append(pcm16(-32768, 0, 0, 16384, 0, 0, 0, 0))
        val levels = analyzer.levels()
        assertEquals(db(0.5), levels.left, 0.00001f)
        assertEquals(db(0.25), levels.right, 0.00001f)
        assertEquals(1f, levels.peakLeft, 0f)
        assertEquals(db(0.5), levels.peakRight, 0.00001f)
    }

    @Test
    fun positiveAndNegativePcm16FullScaleStayInRange() {
        val analyzer = PcmLevelAccumulator(2, MeterPcmEncoding.PCM_16_BIT)
        analyzer.append(pcm16(32767, -32768))
        val levels = analyzer.levels()
        assertEquals(1f, levels.left, 0.00001f)
        assertEquals(1f, levels.right, 0f)
        assertEquals(1f, levels.peakLeft, 0.00001f)
        assertEquals(1f, levels.peakRight, 0f)
    }

    @Test
    fun floatClippingAndNonfiniteSamplesCannotPoisonMeters() {
        val analyzer = PcmLevelAccumulator(2, MeterPcmEncoding.PCM_FLOAT)
        val input = floats(2f, 0.25f, -2f, -0.25f, Float.NaN, 0.25f, Float.POSITIVE_INFINITY, -0.25f)
        val original = bytes(input)
        analyzer.append(input)
        val levels = analyzer.levels()
        assertEquals(db(sqrt(0.5)), levels.left, 0.00001f)
        assertEquals(db(0.25), levels.right, 0.00001f)
        assertEquals(1f, levels.peakLeft, 0f)
        assertEquals(db(0.25), levels.peakRight, 0.00001f)
        assertArrayEquals(original, bytes(input))
        assertEquals(0, input.position())
    }

    @Test
    fun splitSamplesAndFramesSurviveBufferAndWindowBoundaries() {
        val analyzer = PcmLevelAccumulator(2, MeterPcmEncoding.PCM_16_BIT)
        val input = bytes(pcm16(16384, 8192, -8192, -16384))
        analyzer.append(ByteBuffer.wrap(input, 0, 3))
        assertEquals(0L, analyzer.frameCount)
        analyzer.append(ByteBuffer.wrap(input, 3, 3))
        assertEquals(1L, analyzer.frameCount)
        assertEquals(db(0.5), analyzer.levels().left, 0.00001f)
        assertEquals(db(0.25), analyzer.levels().right, 0.00001f)
        analyzer.clearWindow()
        analyzer.append(ByteBuffer.wrap(input, 6, 2))
        assertEquals(1L, analyzer.frameCount)
        assertEquals(db(0.25), analyzer.levels().left, 0.00001f)
        assertEquals(db(0.5), analyzer.levels().right, 0.00001f)
    }

    @Test
    fun resetDropsPeaksAndIncompleteFrames() {
        val analyzer = PcmLevelAccumulator(2, MeterPcmEncoding.PCM_16_BIT)
        analyzer.append(ByteBuffer.wrap(bytes(pcm16(-32768, 16384)), 0, 3))
        analyzer.reset()
        assertEquals(MeterLevels(), analyzer.levels())
        analyzer.append(pcm16(0, -32768))
        assertEquals(MeterLevels(0f, 1f, 0f, 1f), analyzer.levels())
    }

    @Test
    fun analysisPreservesPositionsLimitsByteOrderAndEverySourceByte() {
        val content = byteArrayOf(99, 88) + bytes(pcm16(16384, -8192)) + byteArrayOf(77, 66)
        val input = ByteBuffer.wrap(content.copyOf()).order(ByteOrder.BIG_ENDIAN)
        input.position(2)
        input.limit(6)
        val analyzer = PcmLevelAccumulator(2, MeterPcmEncoding.PCM_16_BIT)
        analyzer.append(input)
        assertEquals(2, input.position())
        assertEquals(6, input.limit())
        assertEquals(ByteOrder.BIG_ENDIAN, input.order())
        assertArrayEquals(content, input.array())
        assertEquals(db(0.5), analyzer.levels().left, 0.00001f)
        assertEquals(db(0.25), analyzer.levels().right, 0.00001f)
    }

    @Test
    fun multichannelUsesFirstLeftRightPairWithoutMixingSurrounds() {
        val analyzer = PcmLevelAccumulator(4, MeterPcmEncoding.PCM_FLOAT)
        analyzer.append(floats(0.5f, 0.25f, 1f, 1f))
        assertEquals(db(0.5), analyzer.levels().left, 0.00001f)
        assertEquals(db(0.25), analyzer.levels().right, 0.00001f)
        assertEquals(1L, analyzer.frameCount)
    }

    @Test
    fun logarithmicMeterFloorIsMinusSixtyDbfs() {
        val analyzer = PcmLevelAccumulator(2, MeterPcmEncoding.PCM_FLOAT)
        analyzer.append(floats(0.0005f, 0.1f))
        assertEquals(0f, analyzer.levels().left, 0f)
        assertEquals(2f / 3f, analyzer.levels().right, 0.00001f)
    }

    private fun pcm16(vararg samples: Int): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putShort(it.toShort()) }
            flip()
        }

    private fun floats(vararg samples: Float): ByteBuffer =
        ByteBuffer.allocate(samples.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putFloat(it) }
            flip()
        }

    private fun bytes(input: ByteBuffer): ByteArray =
        ByteArray(input.remaining()).also { input.duplicate().get(it) }

    private fun db(value: Double): Float = ((20.0 * log10(value) + 60.0) / 60.0).toFloat()
}
