package ro.mdp.remix.playback

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmLevelAccumulatorTest {
    @Test
    fun stereoSilenceHasNoRmsOrPeaks() {
        val analyzer = analyzer()
        analyzer.append(constant16(1_000, 0, 0))
        assertEquals(MeterLevels(), analyzer.levels())
        assertEquals(1_000L, analyzer.frameCount)
    }

    @Test
    fun monoHasKnownThirtyMillisecondRmsAttackAndMirroredChannels() {
        val analyzer = PcmLevelAccumulator(1, MeterPcmEncoding.PCM_16_BIT, 1_000)
        val input = ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(30) { putShort(16384) }
            flip()
        }
        analyzer.append(input)
        val expected = db(0.5 * (1.0 - exp(-1.0)))
        val measured = analyzer.levels()
        assertEquals(expected, measured.left, 0.00001f)
        assertEquals(expected, measured.right, 0.00001f)
        assertEquals(db(0.5), measured.peakLeft, 0.00001f)
        assertEquals(db(0.5), measured.peakRight, 0.00001f)
        assertEquals(measured.livePeakLeft, measured.livePeakRight, 0f)
    }

    @Test
    fun stereoRmsUsesMeanSquaresRatherThanAverageAbsoluteAmplitudeOrPeak() {
        val analyzer = analyzer(MeterPcmEncoding.PCM_FLOAT)
        analyzer.append(stereoFloats(1_000) { index ->
            (if (index % 2 == 0) 1f else 0f) to 0.25f
        })
        val measured = analyzer.levels()
        assertEquals(db(sqrt(0.5)), measured.left, 0.00001f)
        assertEquals(db(0.25), measured.right, 0.00001f)
        assertEquals(1f, measured.peakLeft, 0f)
        assertEquals(db(0.25), measured.peakRight, 0.00001f)
        assertTrue(measured.left < measured.peakLeft)
    }

    @Test
    fun fullCycleStereoSinesConvergeToAmplitudeDividedByRootTwo() {
        val analyzer = PcmLevelAccumulator(2, MeterPcmEncoding.PCM_FLOAT, 8_000)
        analyzer.append(stereoFloats(8_000) { frame ->
            (sin(2 * PI * 100 * frame / 8_000) * 0.8).toFloat() to
                (sin(2 * PI * 200 * frame / 8_000) * 0.4).toFloat()
        })
        val measured = analyzer.levels()
        assertEquals(db(0.8 / sqrt(2.0)), measured.left, 0.00001f)
        assertEquals(db(0.4 / sqrt(2.0)), measured.right, 0.00001f)
        assertEquals(db(0.8), measured.peakLeft, 0.00001f)
        assertEquals(db(0.4), measured.peakRight, 0.00001f)
    }

    @Test
    fun positiveAndNegativePcm16FullScaleStayInRange() {
        val analyzer = analyzer()
        analyzer.append(constant16(1_000, 32767, -32768))
        val measured = analyzer.levels()
        assertEquals(1f, measured.left, 0.00001f)
        assertEquals(1f, measured.right, 0.00001f)
        assertEquals(1f, measured.peakLeft, 0.00001f)
        assertEquals(1f, measured.peakRight, 0f)
    }

    @Test
    fun floatClippingAndNonfiniteSamplesCannotPoisonMetersOrChangeAudioBytes() {
        val analyzer = PcmLevelAccumulator(2, MeterPcmEncoding.PCM_FLOAT, 800)
        val pattern = floatArrayOf(2f, -2f, Float.NaN, Float.POSITIVE_INFINITY)
        val input = stereoFloats(800) { index -> pattern[index % 4] to 0.25f }
        val original = bytes(input)
        analyzer.append(input)
        val measured = analyzer.levels()
        assertEquals(db(sqrt(0.5)), measured.left, 0.00001f)
        assertEquals(db(0.25), measured.right, 0.00001f)
        assertEquals(1f, measured.peakLeft, 0f)
        assertEquals(db(0.25), measured.peakRight, 0.00001f)
        assertArrayEquals(original, bytes(input))
        assertEquals(0, input.position())
    }

    @Test
    fun rmsReleaseFollowsThreeHundredMillisecondsOfActualSilentPcm() {
        val analyzer = analyzer(MeterPcmEncoding.PCM_FLOAT)
        analyzer.append(stereoFloats(1_000) { 1f to 0.5f })
        val before = analyzer.levels()
        analyzer.append(stereoFloats(300) { 0f to 0f })
        val after = analyzer.levels()
        // A one-time-constant amplitude release is 20*log10(e), or 8.686 dB.
        val normalizedDrop = (20.0 * log10(exp(1.0)) / 60.0).toFloat()
        assertEquals(before.left - normalizedDrop, after.left, 0.00001f)
        assertEquals(before.right - normalizedDrop, after.right, 0.00001f)
        assertEquals(before.peakLeft, after.peakLeft, 0f)
        assertEquals(before.peakRight, after.peakRight, 0f)
    }

    @Test
    fun liveLedPeaksReleaseWhileMarkerHoldsThenMarkerLosesSixDbInQuarterSecond() {
        val analyzer = analyzer(MeterPcmEncoding.PCM_FLOAT)
        analyzer.append(stereoFloats(1) { 1f to 0.5f })
        analyzer.append(stereoFloats(250) { 0f to 0f })
        assertEquals(0.9f, analyzer.levels().livePeakLeft, 0.00001f)
        assertEquals(db(0.5) - 0.1f, analyzer.levels().livePeakRight, 0.00001f)
        assertEquals(1f, analyzer.levels().peakLeft, 0f)
        analyzer.append(stereoFloats(350) { 0f to 0f })
        assertEquals(1f, analyzer.levels().peakLeft, 0f)
        assertEquals(db(0.5), analyzer.levels().peakRight, 0.00001f)
        analyzer.append(stereoFloats(250) { 0f to 0f })
        assertEquals(0.9f, analyzer.levels().peakLeft, 0.00001f)
        assertEquals(db(0.5) - 0.1f, analyzer.levels().peakRight, 0.00001f)
    }

    @Test
    fun louderPeakAttacksInstantlyAndRestartsOnlyItsOwnChannelHold() {
        val analyzer = analyzer(MeterPcmEncoding.PCM_FLOAT)
        analyzer.append(stereoFloats(1) { 1f to 0.2f })
        analyzer.append(stereoFloats(700) { 0f to 0f })
        val previousLeft = analyzer.levels().peakLeft
        analyzer.append(stereoFloats(1) { 0.5f to 0.9f })
        assertTrue(analyzer.levels().peakLeft < previousLeft)
        assertEquals(db(0.9), analyzer.levels().peakRight, 0.00001f)
        assertEquals(db(0.9), analyzer.levels().livePeakRight, 0.00001f)
        analyzer.append(stereoFloats(600) { 0f to 0f })
        assertEquals(db(0.9), analyzer.levels().peakRight, 0.00001f)
        assertTrue(analyzer.levels().peakLeft < previousLeft - 0.2f)
        assertTrue(analyzer.levels().livePeakRight < analyzer.levels().peakRight)
    }

    @Test
    fun arbitraryByteSplitsAndEmptyInputsProduceExactlyTheSameEnvelopes() {
        val input = bytes(stereoFloats(123) { index ->
            (if (index % 11 == 0) 1f else 0.25f) to (if (index % 7 == 0) -0.75f else 0.1f)
        }) + byteArrayOf(0, 0, 0)
        val whole = analyzer(MeterPcmEncoding.PCM_FLOAT)
        whole.append(ByteBuffer.wrap(input))
        val split = analyzer(MeterPcmEncoding.PCM_FLOAT)
        val sizes = intArrayOf(1, 0, 3, 13, 2, 7, 0, 5)
        var offset = 0
        var step = 0
        while (offset < input.size) {
            val count = sizes[step++ % sizes.size].coerceAtMost(input.size - offset)
            split.append(ByteBuffer.wrap(input, offset, count))
            offset += count
        }
        assertEquals(whole.frameCount, split.frameCount)
        assertEquals(whole.levels(), split.levels())
    }

    @Test
    fun snapshotPublicationDoesNotDropPartialRmsBlocksOrResetEnvelopes() {
        val input = bytes(stereoFloats(100) { 0.5f to 0.25f })
        val whole = analyzer(MeterPcmEncoding.PCM_FLOAT)
        whole.append(ByteBuffer.wrap(input))
        val published = analyzer(MeterPcmEncoding.PCM_FLOAT)
        published.append(ByteBuffer.wrap(input, 0, 23 * 8))
        val beforePublication = published.levels()
        published.clearWindow()
        assertEquals(beforePublication, published.levels())
        published.append(ByteBuffer.wrap(input, 23 * 8, 77 * 8))
        assertEquals(77L, published.frameCount)
        assertEquals(whole.levels(), published.levels())
    }

    @Test
    fun emptyBuffersAndRepeatedReadsCannotAdvanceReleaseOrHold() {
        val analyzer = analyzer(MeterPcmEncoding.PCM_FLOAT)
        analyzer.append(stereoFloats(30) { 1f to 0.5f })
        val measured = analyzer.levels()
        repeat(100) {
            analyzer.append(ByteBuffer.allocate(0))
            analyzer.append(ByteBuffer.allocateDirect(0))
            assertEquals(measured, analyzer.levels())
        }
        assertEquals(30L, analyzer.frameCount)
    }

    @Test
    fun resetDropsEnvelopesHeldPeaksAndPartialSamplesFramesAndRmsBlocks() {
        val analyzer = analyzer(MeterPcmEncoding.PCM_FLOAT)
        analyzer.append(stereoFloats(23) { 1f to 0.5f })
        analyzer.append(ByteBuffer.wrap(byteArrayOf(1, 2, 3)))
        analyzer.reset()
        assertEquals(MeterLevels(), analyzer.levels())
        assertEquals(0L, analyzer.frameCount)
        analyzer.append(stereoFloats(30) { 0f to 0.25f })
        val fresh = analyzer(MeterPcmEncoding.PCM_FLOAT)
        fresh.append(stereoFloats(30) { 0f to 0.25f })
        assertEquals(fresh.levels(), analyzer.levels())
        assertEquals(30L, analyzer.frameCount)
    }

    @Test
    fun analysisPreservesPositionsLimitsByteOrderAndEverySourceByte() {
        val content = byteArrayOf(99, 88) + bytes(constant16(10, 16384, -8192)) + byteArrayOf(77, 66)
        val input = ByteBuffer.wrap(content.copyOf()).order(ByteOrder.BIG_ENDIAN)
        input.position(2)
        input.limit(content.size - 2)
        val analyzer = analyzer()
        analyzer.append(input)
        assertEquals(2, input.position())
        assertEquals(content.size - 2, input.limit())
        assertEquals(ByteOrder.BIG_ENDIAN, input.order())
        assertArrayEquals(content, input.array())
        assertEquals(db(0.5), analyzer.levels().peakLeft, 0.00001f)
        assertEquals(db(0.25), analyzer.levels().peakRight, 0.00001f)
    }

    @Test
    fun multichannelUsesFirstLeftRightPairWithoutMixingSurrounds() {
        val analyzer = PcmLevelAccumulator(4, MeterPcmEncoding.PCM_FLOAT, 1_000)
        val input = ByteBuffer.allocate(1_000 * 16).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(1_000) { putFloat(0.5f).putFloat(0.25f).putFloat(1f).putFloat(1f) }
            flip()
        }
        analyzer.append(input)
        assertEquals(db(0.5), analyzer.levels().left, 0.00001f)
        assertEquals(db(0.25), analyzer.levels().right, 0.00001f)
        assertEquals(1_000L, analyzer.frameCount)
    }

    @Test
    fun silenceEventuallyReleasesEveryMeterToTheMinusSixtyDbFloor() {
        val analyzer = analyzer(MeterPcmEncoding.PCM_FLOAT)
        analyzer.append(stereoFloats(1_000) { 1f to 0.5f })
        analyzer.append(stereoFloats(4_000) { 0f to 0f })
        assertEquals(MeterLevels(), analyzer.levels())
    }

    @Test
    fun physicalAttackAndPeakReleaseDoNotDependOnSampleRate() {
        for (sampleRate in listOf(1_000, 44_100, 48_000)) {
            val analyzer = PcmLevelAccumulator(2, MeterPcmEncoding.PCM_FLOAT, sampleRate)
            analyzer.append(stereoFloats(sampleRate / 100 * 3) { 0.5f to 0.25f })
            assertEquals(db(0.5 * (1.0 - exp(-1.0))), analyzer.levels().left, 0.00001f)
            analyzer.append(stereoFloats(sampleRate / 4) { 0f to 0f })
            assertEquals(db(0.5) - 0.1f, analyzer.levels().livePeakLeft, 0.00001f)
            assertEquals(db(0.5), analyzer.levels().peakLeft, 0.00001f)
        }
    }

    private fun analyzer(encoding: MeterPcmEncoding = MeterPcmEncoding.PCM_16_BIT) =
        PcmLevelAccumulator(2, encoding, 1_000)

    private fun constant16(frames: Int, left: Int, right: Int): ByteBuffer =
        ByteBuffer.allocate(frames * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { putShort(left.toShort()).putShort(right.toShort()) }
            flip()
        }

    private fun stereoFloats(frames: Int, samples: (Int) -> Pair<Float, Float>): ByteBuffer =
        ByteBuffer.allocate(frames * 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { frame ->
                val (left, right) = samples(frame)
                putFloat(left).putFloat(right)
            }
            flip()
        }

    private fun bytes(input: ByteBuffer): ByteArray =
        ByteArray(input.remaining()).also { input.duplicate().get(it) }

    private fun db(value: Double): Float = ((20.0 * log10(value) + 60.0) / 60.0)
        .coerceIn(0.0, 1.0).toFloat()
}
