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
import org.junit.Test
import org.junit.runner.RunWith

/** Uses Media3's actual shared buffers and processor lifecycle on Android. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class MeterAudioProcessorTest {
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
}
