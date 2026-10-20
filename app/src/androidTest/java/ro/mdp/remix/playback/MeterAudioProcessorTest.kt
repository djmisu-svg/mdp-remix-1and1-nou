package ro.mdp.remix.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Media3's actual shared buffers and processor lifecycle on Android. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class MeterAudioProcessorTest {
    @Test
    fun realStereoPcmPublishesFreshLevelsAfterPauseResumeAndSeekFlush() {
        val processor = MeterAudioProcessor()
        AudioMeters.reset()
        processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        try {
            AudioMeters.setPlaybackActive(true)
            processStereo(processor, left = 24_576, right = 1_024)
            val leftHeavy = AudioMeters.levels.value
            assertTrue("RMS must retain the actual stereo channel balance", leftHeavy.left > leftHeavy.right + 0.3f)
            assertTrue("Live LED peaks must retain the same channel balance", leftHeavy.livePeakLeft > leftHeavy.livePeakRight + 0.3f)
            assertTrue("Held peaks must be measured from the actual PCM", leftHeavy.peakLeft > leftHeavy.peakRight + 0.3f)

            AudioMeters.setPlaybackActive(false)
            assertEquals("Pause must immediately clear needles and LEDs", MeterLevels(), AudioMeters.levels.value)
            processStereo(processor, left = 32_767, right = 32_767)
            assertEquals("Decoder data arriving after pause must not revive the meters", MeterLevels(), AudioMeters.levels.value)

            AudioMeters.setPlaybackActive(true)
            processStereo(processor, left = 0, right = 24_576)
            val resumed = AudioMeters.levels.value
            assertEquals("Resume must discard the previous left RMS envelope", 0f, resumed.left, 0f)
            assertEquals("Resume must discard the previous left LED envelope", 0f, resumed.livePeakLeft, 0f)
            assertEquals("Resume must discard the previous left held peak", 0f, resumed.peakLeft, 0f)
            assertTrue("New right-channel PCM must drive the needle", resumed.right > 0.8f)
            assertTrue("New right-channel PCM must drive the LED bar", resumed.livePeakRight > 0.9f)

            // ExoPlayer flushes the real audio pipeline when seeking. A fresh,
            // silent source must not inherit envelopes from before that flush.
            processor.flush()
            assertEquals("Seek flush must clear every meter", MeterLevels(), AudioMeters.levels.value)
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertEquals(0, processor.getOutput().remaining())
            processStereo(processor, left = 0, right = 0)
            assertEquals("Silent PCM after a seek must keep all meters dark", MeterLevels(), AudioMeters.levels.value)
        } finally {
            processor.reset()
            AudioMeters.reset()
        }
    }

    @Test
    fun emptyPipelineProbesAreSafeAndFollowingPcmIsUnchanged() {
        val processor = MeterAudioProcessor()
        processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        try {
            // AudioProcessingPipeline calls this before any real source buffer.
            // It aliases BaseAudioProcessor's initial internal output allocation.
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertEquals(0, processor.getOutput().remaining())
            processor.queueInput(ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()))
            assertEquals(0, processor.getOutput().remaining())

            val source = ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).apply {
                putShort(16384).putShort(-8192)
                putShort(-32768).putShort(32767)
                putShort(0).putShort(0)
                putShort(-4096).putShort(2048)
                flip()
            }
            val expected = ByteArray(source.remaining()).also { source.duplicate().get(it) }
            processor.queueInput(source)
            assertEquals(source.limit(), source.position())
            val output = processor.getOutput()
            val actual = ByteArray(output.remaining()).also { output.get(it) }
            assertArrayEquals(expected, actual)

            // Empty probes also occur while draining between real buffers.
            processor.queueInput(AudioProcessor.EMPTY_BUFFER)
            assertEquals(0, processor.getOutput().remaining())
            processor.queueInput(ByteBuffer.allocateDirect(0))
            assertEquals(0, processor.getOutput().remaining())
            source.rewind()
            processor.queueInput(source)
            val nextOutput = processor.getOutput()
            val nextActual = ByteArray(nextOutput.remaining()).also { nextOutput.get(it) }
            assertArrayEquals(expected, nextActual)
            processor.queueEndOfStream()
            assertEquals(0, processor.getOutput().remaining())
        } finally {
            processor.reset()
        }
    }

    private fun processStereo(processor: MeterAudioProcessor, left: Int, right: Int) {
        // A tenth of a second contains complete RMS blocks and exceeds the
        // processor's 40 ms publication window without advancing a fake timer.
        val input = ByteBuffer.allocateDirect(4_800 * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(4_800) { putShort(left.toShort()).putShort(right.toShort()) }
            flip()
        }
        val expected = ByteArray(input.remaining()).also { input.duplicate().get(it) }
        processor.queueInput(input)
        val output = processor.getOutput()
        val actual = ByteArray(output.remaining()).also { output.get(it) }
        assertArrayEquals("Meter analysis must preserve every audio byte", expected, actual)
        assertEquals(input.limit(), input.position())
    }
}
