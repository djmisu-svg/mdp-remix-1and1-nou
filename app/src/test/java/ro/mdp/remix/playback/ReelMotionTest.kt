package ro.mdp.remix.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelMotionTest {
    private class Clock(var now: Long = 0L) {
        fun read(): Long = now
        fun advance(milliseconds: Long) { now += milliseconds * 1_000_000L }
    }

    @Test fun idleClockDoesNotMoveWithoutActualPlayback() {
        val clock = Clock()
        val motion = ReelMotionState(clock::read)
        val initial = motion.sample()
        clock.advance(60_000)
        motion.setPlaying(false)
        assertEquals(initial, motion.snapshot())
        assertFalse(initial.isPlaying)
        assertEquals(0L, initial.elapsedPlayingNanos)
    }

    @Test fun actualPlaybackDrivesBothReelsInOppositeDirections() {
        val clock = Clock()
        val motion = ReelMotionState(clock::read)
        motion.setPlaying(true)
        clock.advance(1_000)
        val frame = motion.sample()
        assertTrue(frame.isPlaying)
        assertEquals(1_000_000_000L, frame.elapsedPlayingNanos)
        assertEquals(48f, frame.leftDegrees, 0.0001f)
        assertEquals(-52.941177f, frame.rightDegrees, 0.0001f)
        assertTrue(frame.leftDegrees > 0f)
        assertTrue(frame.rightDegrees < 0f)
    }

    @Test fun pauseFreezesImmediatelyAndResumeContinuesTheSamePhase() {
        val clock = Clock()
        val motion = ReelMotionState(clock::read)
        motion.setPlaying(true)
        clock.advance(1_250)
        motion.setPlaying(false)
        val paused = motion.sample()
        assertFalse(paused.isPlaying)
        assertEquals(1_250_000_000L, paused.elapsedPlayingNanos)
        clock.advance(120_000)
        assertEquals(paused, motion.sample())
        motion.setPlaying(true)
        val resumed = motion.sample()
        assertTrue(resumed.isPlaying)
        assertEquals(paused.leftDegrees, resumed.leftDegrees, 0f)
        assertEquals(paused.rightDegrees, resumed.rightDegrees, 0f)
        clock.advance(250)
        assertEquals(1_500_000_000L, motion.sample().elapsedPlayingNanos)
        assertEquals(72f, motion.sample().leftDegrees, 0.0001f)
    }

    @Test fun stopOrCompletionFreezeWithoutResettingMechanicalPosition() {
        val clock = Clock()
        val motion = ReelMotionState(clock::read)
        motion.setPlaying(true)
        clock.advance(2_000)
        // The service supplies the same actual isPlaying=false transition for
        // stop, queue completion, buffering, suppression and player errors.
        motion.setPlaying(false)
        val ended = motion.sample()
        repeat(3) {
            clock.advance(5_000)
            motion.setPlaying(false)
            assertEquals(ended, motion.sample())
        }
        motion.setPlaying(true)
        assertEquals(ended.leftDegrees, motion.sample().leftDegrees, 0f)
        assertEquals(ended.rightDegrees, motion.sample().rightDegrees, 0f)
    }

    @Test fun absentUiDoesNotStopOngoingPlaybackClock() {
        val clock = Clock()
        val motion = ReelMotionState(clock::read)
        motion.setPlaying(true)
        clock.advance(61_000)
        // No frame/UI sampling occurred during background playback.
        val frame = motion.sample()
        assertEquals(61_000_000_000L, frame.elapsedPlayingNanos)
        assertEquals(48f, frame.leftDegrees, 0.0001f)
        assertEquals(-349.41177f, frame.rightDegrees, 0.0001f)
    }

    @Test fun resetExplicitlyClearsBothPhasesAndStopsTheClock() {
        val clock = Clock()
        val motion = ReelMotionState(clock::read)
        motion.setPlaying(true)
        clock.advance(5_000)
        assertTrue(motion.sample().elapsedPlayingNanos > 0)
        motion.reset()
        val reset = motion.sample()
        assertFalse(reset.isPlaying)
        assertEquals(0L, reset.elapsedPlayingNanos)
        assertEquals(0f, reset.leftDegrees, 0f)
        assertEquals(0f, reset.rightDegrees, 0f)
        clock.advance(30_000)
        assertEquals(reset, motion.sample())
        motion.setPlaying(true)
        clock.advance(1_000)
        assertEquals(48f, motion.sample().leftDegrees, 0.0001f)
    }

    @Test fun backwardsClockReadingsCannotReverseOrDuplicateElapsedTime() {
        val clock = Clock(1_000_000_000L)
        val motion = ReelMotionState(clock::read)
        motion.setPlaying(true)
        clock.advance(100)
        val beforeRegression = motion.sample()
        clock.now = 900_000_000L
        assertEquals(beforeRegression, motion.sample())
        clock.now = 950_000_000L
        motion.setPlaying(false)
        val paused = motion.sample()
        assertEquals(beforeRegression.elapsedPlayingNanos, paused.elapsedPlayingNanos)
        clock.now = 1_000_000_000L
        motion.setPlaying(true)
        clock.now = 1_200_000_000L
        assertEquals(200_000_000L, motion.sample().elapsedPlayingNanos)
    }

    @Test fun arbitrarySignedNanoTimeOriginDoesNotAffectThePhase() {
        val clock = Clock(-4_000_000_000L)
        val motion = ReelMotionState(clock::read)
        motion.setPlaying(true)
        clock.advance(1_000)
        assertEquals(48f, motion.sample().leftDegrees, 0.0001f)
        motion.setPlaying(false)
        clock.advance(10_000)
        assertEquals(1_000_000_000L, motion.sample().elapsedPlayingNanos)
    }

    @Test fun multipleSamplersAndRepeatedPlayerEventsCannotChangePhase() {
        val clock = Clock()
        val frequentlySampled = ReelMotionState(clock::read)
        val backgroundOnly = ReelMotionState(clock::read)
        frequentlySampled.setPlaying(true)
        backgroundOnly.setPlaying(true)
        repeat(2_000) {
            clock.advance(5)
            val first = frequentlySampled.sample()
            assertEquals(first, frequentlySampled.sample())
            frequentlySampled.setPlaying(true)
            assertEquals(first, frequentlySampled.sample())
        }
        assertEquals(backgroundOnly.sample(), frequentlySampled.sample())
        frequentlySampled.setPlaying(false)
        backgroundOnly.setPlaying(false)
        clock.advance(1_000_000)
        assertEquals(backgroundOnly.sample(), frequentlySampled.sample())
    }

    @Test fun phasesWrapIndependentlyAtTheirMechanicalPeriods() {
        val clock = Clock()
        val motion = ReelMotionState(clock::read)
        motion.setPlaying(true)
        clock.advance(6_800)
        val rightWrapped = motion.sample()
        assertEquals(0f, rightWrapped.rightDegrees, 0f)
        assertEquals(326.4f, rightWrapped.leftDegrees, 0.0001f)
        clock.advance(700)
        val leftWrapped = motion.sample()
        assertEquals(0f, leftWrapped.leftDegrees, 0f)
        assertEquals(-37.058823f, leftWrapped.rightDegrees, 0.0001f)
    }

    @Test fun activityVersionChangesEvenWhenRapidPauseResumeReturnsToSameBoolean() {
        val clock = Clock()
        val motion = ReelMotionState(clock::read)
        assertEquals(ReelMotionActivity(false, 0L), motion.activity())
        motion.setPlaying(true)
        val before = motion.activity()
        assertEquals(ReelMotionActivity(true, 1L), before)
        // A collector can miss the intermediate false value. Its eventual true
        // value must still differ so that an animation effect restarts.
        motion.setPlaying(false)
        motion.setPlaying(true)
        val after = motion.activity()
        assertTrue(after.isPlaying)
        assertEquals(3L, after.version)
        assertTrue(before != after)
        assertEquals(after.version, after.generation)
    }

    @Test fun repeatedPlayerEventsAndSamplersDoNotCreateActivityGenerations() {
        val clock = Clock()
        val motion = ReelMotionState(clock::read)
        motion.setPlaying(true)
        val playing = motion.activity()
        repeat(100) {
            clock.advance(10)
            motion.sample()
            motion.setPlaying(true)
            assertEquals(playing, motion.activity())
        }
        motion.setPlaying(false)
        val paused = motion.activity()
        repeat(100) {
            clock.advance(10)
            motion.sample()
            motion.setPlaying(false)
            assertEquals(paused, motion.activity())
        }
    }

    @Test fun resetPublishesAStopTransitionOnlyIfPlaybackWasActive() {
        val motion = ReelMotionState { 0L }
        motion.reset()
        assertEquals(ReelMotionActivity(false, 0L), motion.activity())
        motion.setPlaying(true)
        motion.reset()
        assertEquals(ReelMotionActivity(false, 2L), motion.activity())
        motion.reset()
        assertEquals(ReelMotionActivity(false, 2L), motion.activity())
    }
}
