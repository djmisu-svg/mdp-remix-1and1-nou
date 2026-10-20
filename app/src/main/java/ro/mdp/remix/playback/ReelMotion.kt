package ro.mdp.remix.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A version survives StateFlow conflation when playback briefly stops/restarts. */
data class ReelMotionActivity(val isPlaying: Boolean, val version: Long) {
    val generation: Long get() = version
}

/** Angles are ready for Canvas.rotate: both clockwise from one shared mechanical phase. */
data class ReelMotionFrame(
    val leftDegrees: Float,
    val rightDegrees: Float,
    val isPlaying: Boolean,
    val elapsedPlayingNanos: Long,
)

/**
 * A mechanical clock driven only by the player's actual isPlaying transitions.
 *
 * A sample never advances an animation accumulator: it derives both angles from
 * the same accumulated playing time. Two screens sampling at different frame
 * rates therefore see the same phase. While the UI is absent, ongoing playback
 * still advances the clock; pause, buffering, stop, errors and completion freeze
 * it at the instant the service calls setPlaying(false).
 *
 * The injected clock is monotonic, independent of wall time and animation scale.
 * Regressing readings are clamped, so a faulty clock cannot reverse a reel.
 */
class ReelMotionState(
    private val clockNanos: () -> Long = System::nanoTime,
) {
    private var lastObservedNanos: Long? = null
    private var startedAtNanos = 0L
    private var accumulatedNanos = 0L
    private var playing = false
    private var activityVersion = 0L

    /** Called by the service with Player.isPlaying, never playWhenReady. */
    @Synchronized
    fun setPlaying(isPlaying: Boolean) {
        val now = monotonicNow()
        if (playing == isPlaying) return
        if (playing) accumulatedNanos = elapsedAt(now)
        playing = isPlaying
        activityVersion++
        if (playing) startedAtNanos = now
    }

    @Synchronized
    fun activity(): ReelMotionActivity = ReelMotionActivity(playing, activityVersion)

    /** Safe for multiple UI samplers; sampling frequency cannot change phase. */
    @Synchronized
    fun sample(): ReelMotionFrame {
        val elapsed = elapsedAt(monotonicNow())
        val phase = degrees(elapsed, REEL_PERIOD_NANOS)
        return ReelMotionFrame(
            leftDegrees = phase,
            rightDegrees = phase,
            isPlaying = playing,
            elapsedPlayingNanos = elapsed,
        )
    }

    fun snapshot(): ReelMotionFrame = sample()

    /** Explicitly starts a fresh, stopped phase; ordinary pause/seek must not reset. */
    @Synchronized
    fun reset() {
        startedAtNanos = monotonicNow()
        accumulatedNanos = 0L
        if (playing) activityVersion++
        playing = false
    }

    private fun monotonicNow(): Long {
        val now = clockNanos()
        val previous = lastObservedNanos
        return if (previous == null || now >= previous) {
            lastObservedNanos = now
            now
        } else previous
    }

    private fun elapsedAt(now: Long): Long {
        if (!playing) return accumulatedNanos
        // A signed nanoTime origin may be negative. Subtraction is valid for
        // practical intervals; saturate rather than wrap an extreme interval.
        val difference = now - startedAtNanos
        val delta = if (difference < 0L) Long.MAX_VALUE else difference
        return if (delta > Long.MAX_VALUE - accumulatedNanos) Long.MAX_VALUE
        else accumulatedNanos + delta
    }

    private fun degrees(elapsed: Long, period: Long): Float =
        ((elapsed % period).toDouble() * 360.0 / period.toDouble()).toFloat()

    private companion object {
        const val REEL_PERIOD_NANOS = 7_500_000_000L
    }
}

/**
 * Process-local motion owned by PlaybackService, retained across UI recreation.
 * Only the service mutates playback activity; composables only sample it.
 */
object ReelMotion {
    private val state = ReelMotionState()
    private val mutableActivity = MutableStateFlow(state.activity())
    val activity: StateFlow<ReelMotionActivity> = mutableActivity.asStateFlow()

    @Synchronized
    fun setPlaying(isPlaying: Boolean) {
        state.setPlaying(isPlaying)
        mutableActivity.value = state.activity()
    }

    fun sample(): ReelMotionFrame = state.sample()
    fun snapshot(): ReelMotionFrame = state.sample()

    @Synchronized
    fun reset() {
        state.reset()
        mutableActivity.value = state.activity()
    }
}
