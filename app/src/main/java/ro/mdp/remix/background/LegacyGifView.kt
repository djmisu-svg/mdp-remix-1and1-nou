package ro.mdp.remix.background

import android.content.Context
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import kotlin.math.max
import kotlin.math.min

/** Software-rendered GIF playback for devices without AnimatedImageDrawable. */
@Suppress("DEPRECATION")
internal class LegacyGifView(context: Context) : View(context) {
    private var movie: Movie? = null
    private var onFrame: ((Int) -> Unit)? = null
    private var playbackEnabled = false
    private var fill = true
    private var attached = false
    private var aggregatedVisible = Build.VERSION.SDK_INT < Build.VERSION_CODES.N
    private val visibleBounds = Rect()

    private var running = false
    private var positionMs = 0L
    private var startedAtMs: Long? = null
    private var firstFramePending = true
    private var frameCounter = 0
    private var initialized = false
    private val scrollListener = ViewTreeObserver.OnScrollChangedListener { refreshPlayback() }
    private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener { refreshPlayback() }

    init {
        // Movie draws through the software canvas on the Android versions using this view.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        initialized = true
    }

    fun setMovie(movie: Movie, onFrame: (Int) -> Unit) {
        running = false
        startedAtMs = null
        positionMs = 0L
        firstFramePending = true
        frameCounter = 0
        this.movie = movie
        this.onFrame = onFrame
        refreshPlayback()
    }

    fun setPlaybackEnabled(enabled: Boolean) {
        if (playbackEnabled == enabled) return
        playbackEnabled = enabled
        refreshPlayback()
    }

    fun setScaleMode(fill: Boolean) {
        if (this.fill == fill) return
        this.fill = fill
        // Refit the same frozen frame too; this one redraw never advances the GIF clock.
        if (visibleToUser()) invalidate()
    }

    fun clear() {
        val redraw = playbackEnabled && visibleToUser()
        movie = null
        onFrame = null
        running = false
        startedAtMs = null
        positionMs = 0L
        firstFramePending = true
        frameCounter = 0
        if (redraw) invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        // The framework subsequently supplies the aggregate value on API 24+.
        aggregatedVisible = isShown && windowVisibility == VISIBLE
        viewTreeObserver.addOnScrollChangedListener(scrollListener)
        viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        refreshPlayback()
    }

    override fun onDetachedFromWindow() {
        attached = false
        refreshPlayback()
        if (viewTreeObserver.isAlive) {
            viewTreeObserver.removeOnScrollChangedListener(scrollListener)
            viewTreeObserver.removeOnGlobalLayoutListener(layoutListener)
        }
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (initialized) refreshPlayback()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (initialized) refreshPlayback()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            super.onVisibilityAggregated(isVisible)
        }
        aggregatedVisible = isVisible
        if (initialized) refreshPlayback()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        refreshPlayback()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // A scroll or clipping change can hide the view without a visibility callback.
        refreshPlayback(requestDraw = false)
        val currentMovie = movie ?: return
        if (!visibleToUser() || currentMovie.width() <= 0 || currentMovie.height() <= 0) return

        val active = running
        val now = if (active) SystemClock.uptimeMillis() else null
        val initialDraw = active && firstFramePending
        val changed = if (active) {
            val elapsed = if (initialDraw) 0L else positionAt(requireNotNull(now))
            currentMovie.setTime(elapsed.toInt())
        } else {
            false
        }

        val horizontalScale = width.toFloat() / currentMovie.width()
        val verticalScale = height.toFloat() / currentMovie.height()
        val scale = if (fill) max(horizontalScale, verticalScale) else min(horizontalScale, verticalScale)
        val saved = canvas.save()
        try {
            canvas.clipRect(0, 0, width, height)
            canvas.translate(
                (width - currentMovie.width() * scale) / 2f,
                (height - currentMovie.height() * scale) / 2f,
            )
            canvas.scale(scale, scale)
            currentMovie.draw(canvas, 0f, 0f)
        } finally {
            canvas.restoreToCount(saved)
        }

        if (!active) return
        // Start the clock only once the first (or resumed) frame actually reaches a canvas.
        if (startedAtMs == null) startedAtMs = now
        firstFramePending = false
        if (changed || initialDraw) {
            frameCounter += 1
            onFrame?.invoke(frameCounter)
        }

        // Callbacks can replace, pause or release the movie. Never schedule their old source.
        if (movie === currentMovie && running && visibleToUser()) {
            postInvalidateOnAnimation()
        } else {
            refreshPlayback(requestDraw = false)
        }
    }

    private fun refreshPlayback(requestDraw: Boolean = true) {
        val shouldRun = playbackEnabled && movie != null && visibleToUser()
        if (running == shouldRun) return
        if (running) {
            startedAtMs?.let { positionMs = positionAt(SystemClock.uptimeMillis()) }
            startedAtMs = null
        }
        running = shouldRun
        if (running && requestDraw) invalidate()
    }

    private fun positionAt(now: Long): Long {
        val elapsed = startedAtMs?.let { (now - it).coerceAtLeast(0L) } ?: 0L
        val duration = movie?.duration()?.takeIf { it > 0 } ?: DEFAULT_DURATION_MS
        return (positionMs + elapsed) % duration.toLong()
    }

    private fun visibleToUser(): Boolean =
        attached && windowVisibility == VISIBLE && isShown && aggregatedVisible &&
            width > 0 && height > 0 && getGlobalVisibleRect(visibleBounds)

    private companion object {
        const val DEFAULT_DURATION_MS = 1_000
    }
}
