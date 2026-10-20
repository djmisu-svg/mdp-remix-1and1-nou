package ro.mdp.remix.playback

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMetersTest {
    @After
    fun tearDown() = AudioMeters.reset()

    @Test
    fun pauseClearsReadingsAndRejectsAudioAlreadyInFlight() {
        AudioMeters.reset()
        AudioMeters.setPlaybackActive(true)
        val playingToken = requireNotNull(AudioMeters.activeToken())
        assertTrue(AudioMeters.publish(playingToken, MeterLevels(0.7f, 0.8f, 0.9f, 1f, 0.85f, 0.95f)))
        AudioMeters.setPlaybackActive(false)
        assertEquals(MeterLevels(), AudioMeters.levels.value)
        assertNull(AudioMeters.activeToken())
        assertFalse(AudioMeters.publish(playingToken, MeterLevels(1f, 1f, 1f, 1f, 1f, 1f)))
        assertEquals(MeterLevels(), AudioMeters.levels.value)
    }

    @Test
    fun seekAndResumeRequireFreshGeneration() {
        AudioMeters.reset()
        AudioMeters.setPlaybackActive(true)
        val oldToken = requireNotNull(AudioMeters.activeToken())
        AudioMeters.clear()
        val seekToken = requireNotNull(AudioMeters.activeToken())
        assertNotEquals(oldToken, seekToken)
        assertFalse(AudioMeters.publish(oldToken, MeterLevels(1f, 1f, 1f, 1f, 1f, 1f)))
        assertTrue(AudioMeters.publish(seekToken, MeterLevels(0.3f, 0.4f, 0.5f, 0.6f, 0.4f, 0.5f)))
        AudioMeters.setPlaybackActive(false)
        assertEquals(MeterLevels(), AudioMeters.levels.value)
        AudioMeters.setPlaybackActive(true)
        val resumedToken = requireNotNull(AudioMeters.activeToken())
        assertNotEquals(seekToken, resumedToken)
        assertFalse(AudioMeters.publish(seekToken, MeterLevels(1f, 1f, 1f, 1f, 1f, 1f)))
        assertEquals(MeterLevels(), AudioMeters.levels.value)
        assertTrue(AudioMeters.publish(resumedToken, MeterLevels(0.1f, 0.2f, 0.3f, 0.4f, 0.2f, 0.3f)))
        assertEquals(MeterLevels(0.1f, 0.2f, 0.3f, 0.4f, 0.2f, 0.3f), AudioMeters.levels.value)
    }

    @Test
    fun stopResetClearsAllSixValuesAndReplayCannotReviveStoppedAudio() {
        AudioMeters.reset()
        AudioMeters.setPlaybackActive(true)
        val stoppedToken = requireNotNull(AudioMeters.activeToken())
        assertTrue(AudioMeters.publish(stoppedToken, MeterLevels(0.6f, 0.7f, 0.9f, 1f, 0.8f, 0.9f)))
        AudioMeters.reset()
        assertNull(AudioMeters.activeToken())
        assertEquals(MeterLevels(), AudioMeters.levels.value)
        assertFalse(AudioMeters.publish(stoppedToken, MeterLevels(1f, 1f, 1f, 1f, 1f, 1f)))
        AudioMeters.setPlaybackActive(true)
        val replayToken = requireNotNull(AudioMeters.activeToken())
        assertNotEquals(stoppedToken, replayToken)
        assertFalse(AudioMeters.publish(stoppedToken, MeterLevels(1f, 1f, 1f, 1f, 1f, 1f)))
        assertEquals(MeterLevels(), AudioMeters.levels.value)
        assertTrue(AudioMeters.publish(replayToken, MeterLevels(0.2f, 0.1f, 0.5f, 0.4f, 0.3f, 0.2f)))
        assertEquals(MeterLevels(0.2f, 0.1f, 0.5f, 0.4f, 0.3f, 0.2f), AudioMeters.levels.value)
    }
}
