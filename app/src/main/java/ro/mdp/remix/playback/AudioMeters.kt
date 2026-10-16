package ro.mdp.remix.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decoded source PCM levels, before the player's volume and the device audio mixer.
 * RMS and sample peaks use the same scale: 0 = silence / <= -60 dBFS, 1 = 0 dBFS.
 * Mono is mirrored to both channels; multichannel files use their first L/R pair.
 */
data class MeterLevels(
    val left: Float = 0f,
    val right: Float = 0f,
    val peakLeft: Float = 0f,
    val peakRight: Float = 0f,
)

/** A thread-safe bridge from Media3's audio thread to Compose; no microphone is used. */
object AudioMeters {
    private const val UPDATE_INTERVAL_NS = 40_000_000L // At most 25 PCM updates per second.
    private val lock = Any()
    private val mutableLevels = MutableStateFlow(MeterLevels())
    val levels: StateFlow<MeterLevels> = mutableLevels.asStateFlow()

    private var active = false
    private var generation = 0L
    private var lastPublicationNs: Long? = null

    internal fun setPlaybackActive(value: Boolean) = synchronized(lock) {
        if (active != value) {
            active = value
            clearLocked()
        }
    }

    internal fun activeToken(): Long? = synchronized(lock) {
        if (active) generation else null
    }

    internal fun publish(token: Long, value: MeterLevels): Boolean = synchronized(lock) {
        // A sample measured before pause/seek must never revive cleared meters afterward.
        if (!active || token != generation) return@synchronized false
        val now = System.nanoTime()
        val previous = lastPublicationNs
        if (previous != null && now - previous < UPDATE_INTERVAL_NS) return@synchronized false
        lastPublicationNs = now
        mutableLevels.value = value
        true
    }

    internal fun clear() = synchronized(lock) { clearLocked() }

    internal fun reset() = synchronized(lock) {
        active = false
        clearLocked()
    }

    private fun clearLocked() {
        generation++
        lastPublicationNs = null
        mutableLevels.value = MeterLevels()
    }
}
