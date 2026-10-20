package ro.mdp.remix.background

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// Bitmap/ImageDecoder reads cannot be interrupted midway. Bound overlapping source changes
// to one decode even when an older activity's cancelled request is still finishing native work.
private val backgroundDecodeMutex = Mutex()

/** Owns only decorative media. It never connects to the music player's session or audio tap. */
@OptIn(UnstableApi::class)
internal class BackgroundMediaView(context: Context) : FrameLayout(context) {
    private data class SourceKey(val kind: BackgroundKind, val uri: String?, val edge: Int)

    private val diagnosticOwner = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loadJob: Job? = null
    private var generation = 0L
    private var sourceKey: SourceKey? = null
    private var settings = BackgroundSettings()
    private var lifecycleStarted = false
    private var onError: (String?) -> Unit = {}
    private var decoded: DecodedBackground? = null
    private var image: ObservedImageView? = null
    private var legacy: LegacyGifView? = null
    private var texture: TextureView? = null
    private var surface: Surface? = null
    private var player: ExoPlayer? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var videoPixelRatio = 1f
    private var released = false
    private var initialized = false
    private var attached = false
    private var aggregatedVisible = true

    init {
        tag = "background_media_host"
        setBackgroundColor(0xFF09090B.toInt())
        clipChildren = true
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        initialized = true
    }

    fun render(settings: BackgroundSettings, started: Boolean, maximumEdge: Int, onError: (String?) -> Unit) {
        if (released) return
        this.settings = settings
        lifecycleStarted = started
        this.onError = onError
        // Dimming, scaling and animation switches never replace the decoder/player.
        val key = SourceKey(settings.kind, settings.uri, if (settings.kind == BackgroundKind.VIDEO) 0 else maximumEdge)
        if (sourceKey != key) {
            sourceKey = key
            load(key)
        }
        image?.scaleType = if (settings.scale == BackgroundScale.FILL) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        legacy?.setScaleMode(settings.scale == BackgroundScale.FILL)
        updateVideoTransform()
        refreshGate()
    }

    fun setLifecycleStarted(started: Boolean) {
        lifecycleStarted = started
        refreshGate()
    }

    private fun load(key: SourceKey) {
        val currentGeneration = ++generation
        loadJob?.cancel()
        releaseCurrent()
        BackgroundMediaDiagnostics.attach(diagnosticOwner, BackgroundRenderState(
            source = key.uri,
            kind = key.kind,
            lifecycleStarted = lifecycleStarted,
            animationEnabled = settings.animationEnabled,
        ))
        onError(null)
        if (key.kind == BackgroundKind.VIDEO) {
            val uri = key.uri
            if (uri.isNullOrBlank()) fail(currentGeneration, "Select a video background again.")
            else createVideo(uri, currentGeneration)
            return
        }
        loadJob = scope.launch {
            var result: DecodedBackground? = null
            try {
                val source = withContext(Dispatchers.IO) {
                    if (key.kind == BackgroundKind.ORIGINAL) {
                        resolveOriginalBackgroundAsset(context)?.let { "asset:///$it" }
                    } else key.uri
                }
                if (generation != currentGeneration || released) return@launch
                if (source == null) {
                    fail(currentGeneration, if (key.kind == BackgroundKind.ORIGINAL) {
                        "The original couple GIF is not included in this build. The background remains matte black."
                    } else "This background is no longer available. Select it again.")
                    return@launch
                }
                BackgroundMediaDiagnostics.update(diagnosticOwner) { it.copy(source = source) }
                withContext(Dispatchers.IO) {
                    backgroundDecodeMutex.withLock {
                        currentCoroutineContext().ensureActive()
                        // Assign before the dispatcher handoff so prompt cancellation cannot
                        // discard a native bitmap/drawable before the cleanup path owns it.
                        result = BackgroundDecoder.decode(context, source, key.kind, key.edge)
                        currentCoroutineContext().ensureActive()
                    }
                }
                if (generation != currentGeneration || released) {
                    discard(result)
                    return@launch
                }
                val media = requireNotNull(result)
                result = null
                applyDecoded(media, currentGeneration)
            } catch (cancelled: CancellationException) {
                discard(result)
                throw cancelled
            } catch (_: OutOfMemoryError) {
                discard(result)
                fail(currentGeneration, "This background needs too much memory. Choose a smaller image or GIF.")
            } catch (error: Exception) {
                discard(result)
                fail(currentGeneration, error.message ?: "This background could not be opened. Select it again.")
            }
        }
    }

    private fun applyDecoded(media: DecodedBackground, currentGeneration: Long) {
        decoded = media
        when (media) {
            is DecodedBackground.Still -> {
                val view = createImageView(currentGeneration)
                view.setImageBitmap(media.bitmap)
                loaded(media.bitmap.width, media.bitmap.height)
            }
            is DecodedBackground.Animated -> {
                val view = createImageView(currentGeneration)
                if (Build.VERSION.SDK_INT >= 28 && media.drawable is AnimatedImageDrawable) {
                    media.drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                }
                view.setImageDrawable(media.drawable)
                loaded(media.drawable.intrinsicWidth, media.drawable.intrinsicHeight)
            }
            is DecodedBackground.Legacy -> {
                val view = LegacyGifView(context).apply {
                    tag = "background_media_native"
                    setScaleMode(settings.scale == BackgroundScale.FILL)
                    setMovie(media.movie) { frame ->
                        if (generation == currentGeneration && mayAnimate()) {
                            BackgroundMediaDiagnostics.update(diagnosticOwner) { it.copy(frameCount = frame.toLong(), playing = true) }
                        }
                    }
                }
                legacy = view
                addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                loaded(media.movie.width(), media.movie.height())
            }
        }
        refreshGate()
    }

    private fun createImageView(currentGeneration: Long): ObservedImageView {
        val view = ObservedImageView(context).apply {
            tag = "background_media_native"
            scaleType = if (settings.scale == BackgroundScale.FILL) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            onAnimatedInvalidation = {
                if (generation == currentGeneration && mayAnimate()) {
                    BackgroundMediaDiagnostics.update(diagnosticOwner) { it.copy(frameCount = it.frameCount + 1, playing = true) }
                }
            }
        }
        image = view
        addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        return view
    }

    private fun createVideo(source: String, currentGeneration: Long) {
        try {
            val video = ExoPlayer.Builder(context).build().apply {
                volume = 0f
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).build(), false)
                trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                repeatMode = Player.REPEAT_MODE_ONE
            }
            player = video
            val view = TextureView(context).apply {
                tag = "background_media_native"
                isOpaque = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(value: SurfaceTexture, width: Int, height: Int) {
                        if (generation != currentGeneration || released) return
                        surface = Surface(value).also { video.setVideoSurface(it) }
                        updateVideoTransform()
                        refreshGate()
                    }
                    override fun onSurfaceTextureSizeChanged(value: SurfaceTexture, width: Int, height: Int) = updateVideoTransform()
                    override fun onSurfaceTextureDestroyed(value: SurfaceTexture): Boolean {
                        if (generation != currentGeneration || released) return true
                        surface?.let { owned -> video.clearVideoSurface(owned); owned.release() }
                        surface = null
                        return true
                    }
                    override fun onSurfaceTextureUpdated(value: SurfaceTexture) {
                        if (generation == currentGeneration && !released) {
                            BackgroundMediaDiagnostics.update(diagnosticOwner) {
                                it.copy(frameCount = it.frameCount + 1, positionMs = video.currentPosition, playing = video.isPlaying,
                                    muted = video.volume == 0f, audioTracksDisabled = video.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO))
                            }
                        }
                    }
                }
            }
            texture = view
            addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            video.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (generation != currentGeneration || released) return
                    videoWidth = videoSize.width
                    videoHeight = videoSize.height
                    videoPixelRatio = videoSize.pixelWidthHeightRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
                    updateVideoTransform()
                    BackgroundMediaDiagnostics.update(diagnosticOwner) { it.copy(width = videoWidth, height = videoHeight) }
                }
                override fun onEvents(player: Player, events: Player.Events) {
                    if (generation == currentGeneration && !released) {
                        BackgroundMediaDiagnostics.update(diagnosticOwner) {
                            it.copy(loaded = player.playbackState == Player.STATE_READY || it.loaded,
                                playing = player.isPlaying, positionMs = player.currentPosition,
                                muted = player.volume == 0f, audioTracksDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_AUDIO))
                        }
                    }
                }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    if (generation == currentGeneration && reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                        BackgroundMediaDiagnostics.update(diagnosticOwner) { it.copy(loopCount = it.loopCount + 1) }
                    }
                }
                override fun onPlayerError(error: PlaybackException) {
                    fail(currentGeneration, "This video could not be played. Choose another supported local video.")
                }
            })
            video.setMediaItem(MediaItem.fromUri(source))
            video.prepare()
            refreshGate()
        } catch (error: Exception) {
            fail(currentGeneration, error.message ?: "This video could not be opened.")
        }
    }

    private fun loaded(width: Int, height: Int) {
        BackgroundMediaDiagnostics.update(diagnosticOwner) { it.copy(loaded = true, width = width, height = height, error = null) }
        onError(null)
    }

    private fun fail(currentGeneration: Long, message: String) {
        if (generation != currentGeneration || released) return
        player?.pause()
        BackgroundMediaDiagnostics.update(diagnosticOwner) { it.copy(loaded = false, playing = false, error = message) }
        onError(message)
    }

    private fun mayAnimate(): Boolean = !released && lifecycleStarted && settings.animationEnabled &&
        attached && aggregatedVisible && isShown && windowVisibility == View.VISIBLE && width > 0 && height > 0

    private fun refreshGate() {
        if (!initialized || released) return
        val animate = mayAnimate()
        val drawable = (decoded as? DecodedBackground.Animated)?.drawable
        if (Build.VERSION.SDK_INT >= 28 && drawable is AnimatedImageDrawable) {
            if (animate && !drawable.isRunning) drawable.start()
            else if (!animate && drawable.isRunning) drawable.stop()
        }
        legacy?.setPlaybackEnabled(animate)
        player?.let { if (it.playWhenReady != animate) it.playWhenReady = animate }
        val running = when {
            player != null -> player?.isPlaying == true
            legacy != null -> animate
            Build.VERSION.SDK_INT >= 28 && drawable is AnimatedImageDrawable -> drawable.isRunning
            else -> false
        }
        BackgroundMediaDiagnostics.update(diagnosticOwner) {
            it.copy(lifecycleStarted = lifecycleStarted, animationEnabled = settings.animationEnabled, playing = running)
        }
    }

    private fun updateVideoTransform() {
        val view = texture ?: return
        if (view.width <= 0 || view.height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val viewRatio = view.width.toFloat() / view.height
        val mediaRatio = videoWidth * videoPixelRatio / videoHeight
        val fill = settings.scale == BackgroundScale.FILL
        val horizontal = if (fill) maxOf(1f, mediaRatio / viewRatio) else minOf(1f, mediaRatio / viewRatio)
        val vertical = if (fill) maxOf(1f, viewRatio / mediaRatio) else minOf(1f, viewRatio / mediaRatio)
        view.setTransform(Matrix().apply { setScale(horizontal, vertical, view.width / 2f, view.height / 2f) })
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); attached = true; refreshGate() }
    override fun onDetachedFromWindow() { attached = false; refreshGate(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) { super.onWindowVisibilityChanged(visibility); refreshGate() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) { super.onVisibilityChanged(changedView, visibility); refreshGate() }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        if (Build.VERSION.SDK_INT >= 24) super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        refreshGate()
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateVideoTransform()
        refreshGate()
    }

    fun release() {
        if (released) return
        released = true
        generation++
        loadJob?.cancel()
        scope.cancel()
        releaseCurrent()
        onError = {}
        BackgroundMediaDiagnostics.detach(diagnosticOwner)
    }

    private fun releaseCurrent() {
        val drawable = (decoded as? DecodedBackground.Animated)?.drawable
        if (Build.VERSION.SDK_INT >= 28 && drawable is AnimatedImageDrawable) drawable.stop()
        image?.onAnimatedInvalidation = null
        image?.setImageDrawable(null)
        drawable?.callback = null
        legacy?.clear()
        player?.let { video ->
            surface?.let { video.clearVideoSurface(it) }
            video.release()
        }
        surface?.release()
        surface = null
        texture?.surfaceTextureListener = null
        player = null
        texture = null
        image = null
        legacy = null
        decoded = null
        videoWidth = 0
        videoHeight = 0
        videoPixelRatio = 1f
        removeAllViews()
    }

    private fun discard(value: DecodedBackground?) {
        if (value is DecodedBackground.Still) value.bitmap.recycle()
        if (value is DecodedBackground.Animated) value.drawable.callback = null
    }
}

/** Native decoder invalidations are observations of GIF work, not synthetic frame ticks. */
private class ObservedImageView(context: Context) : ImageView(context) {
    var onAnimatedInvalidation: (() -> Unit)? = null
    override fun invalidateDrawable(drawable: Drawable) {
        super.invalidateDrawable(drawable)
        if (Build.VERSION.SDK_INT >= 28 && drawable === this.drawable &&
            drawable is AnimatedImageDrawable && drawable.isRunning
        ) onAnimatedInvalidation?.invoke()
    }
}
