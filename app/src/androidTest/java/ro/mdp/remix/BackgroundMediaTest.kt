package ro.mdp.remix

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Looper
import android.os.SystemClock
import android.provider.DocumentsContract
import android.util.Log
import android.view.TextureView
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ro.mdp.remix.background.BACKGROUND_PREFERENCES
import ro.mdp.remix.background.BACKGROUND_SETTINGS_KEY
import ro.mdp.remix.background.BackgroundKind
import ro.mdp.remix.background.BackgroundMediaDiagnostics
import ro.mdp.remix.background.BackgroundScale
import ro.mdp.remix.background.BackgroundSettings
import ro.mdp.remix.background.BackgroundSettingsJson
import ro.mdp.remix.background.resolveOriginalBackgroundAsset
import ro.mdp.remix.playback.AudioMeters
import ro.mdp.remix.playback.PlaybackService
import ro.mdp.remix.playback.ReelMotion

/** Real SAF documents, native decoders and foreground audio on the disposable emulator. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class BackgroundMediaTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var controller: MediaController? = null
    private var connection: ListenableFuture<MediaController>? = null
    private var wave: File? = null
    private var originalPreference: String? = null
    private var resizedDisplay = false
    private var watchdog: ScheduledExecutorService? = null
    private var watchdogTask: ScheduledFuture<*>? = null

    @Before fun launchIsolatedBackgroundAndRealAudio() {
        startWatchdog()
        // DocumentsUI resource IDs provide an unambiguous selection action even
        // when its visible button is rendered as OPEN or SELECT by the platform.
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val prefs = target.getSharedPreferences(BACKGROUND_PREFERENCES, Context.MODE_PRIVATE)
        originalPreference = prefs.getString(BACKGROUND_SETTINGS_KEY, null)
        check(prefs.edit().remove(BACKGROUND_SETTINGS_KEY).commit())
        launchApp()
        waitForTag("local_library")
        lateinit var future: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(target,
                SessionToken(target, ComponentName(target, PlaybackService::class.java))).buildAsync()
        }
        connection = future
        val player = future.get(10, TimeUnit.SECONDS)
        controller = player
        val file = createForegroundWave()
        wave = file
        compose.mainClock.autoAdvance = false
        instrumentation.runOnMainSync {
            player.setMediaItem(MediaItem.Builder().setMediaId("background-test-foreground-audio")
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(MediaMetadata.Builder().setTitle("Background isolation calibration").build())
                .build())
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.prepare()
            player.play()
        }
        await("Foreground calibration must really decode before exercising backgrounds") {
            val levels = AudioMeters.levels.value
            ReelMotion.snapshot().isPlaying && levels.left > levels.right + .2f && levels.right > 0f
        }
    }

    @After fun restoreEmulatorState() {
        try {
            controller?.let { player ->
                instrumentation.runOnMainSync { player.stop(); player.clearMediaItems() }
                await("Cleanup must stop the real foreground clock") { !ReelMotion.snapshot().isPlaying }
            }
            connection?.let { future -> instrumentation.runOnMainSync { MediaController.releaseFuture(future) } }
            compose.mainClock.autoAdvance = true
            scenario?.close()
            val prefs = target.getSharedPreferences(BACKGROUND_PREFERENCES, Context.MODE_PRIVATE)
            val edit = prefs.edit()
            originalPreference?.let { edit.putString(BACKGROUND_SETTINGS_KEY, it) } ?: edit.remove(BACKGROUND_SETTINGS_KEY)
            check(edit.commit())
            wave?.delete()
            if (resizedDisplay) { shell("wm size reset"); shell("wm density reset") }
        } finally {
            // Keep diagnostics alive through ActivityScenario/Compose cleanup,
            // since synchronization there can be the operation that stalls.
            watchdogTask?.cancel(false)
            watchdog?.shutdownNow()
            watchdogTask = null
            watchdog = null
        }
    }

    @Test fun safGifFramesAnimatePauseAndPersistWithoutInterruptingMusic() {
        val priorAudio = ReelMotion.snapshot().elapsedPlayingNanos
        val gif = chooseDocument("mdp-test-animation.gif", BackgroundKind.GIF)
        assertTrue("SAF must provide a persisted read grant", target.contentResolver.persistedUriPermissions.any {
            it.uri == gif && it.isReadPermission
        })
        assertNativeFramesMove(BackgroundKind.GIF)
        assertForegroundContinues(priorAudio)

        setAnimation(false)
        assertBackgroundFrozen()
        val frozenPixels = nativePixelHash()
        realWait(280)
        assertEquals("Disabling GIF animation must freeze actual decoded pixels", frozenPixels, nativePixelHash())
        setAnimation(true)
        assertNativeFramesMove(BackgroundKind.GIF)

        clickSetting("background_fit")
        await("Fit must persist") { settings().scale == BackgroundScale.FIT }
        settleFrames()
        compose.onNodeWithTag("background_fit").assertIsSelected()
        clickSetting("background_fill")
        await("Fill must persist") { settings().scale == BackgroundScale.FILL }
        settleFrames()
        compose.onNodeWithTag("background_fill").assertIsSelected()
        compose.onNodeWithTag("background_dimming").performScrollTo()
        settleFrames()
        compose.onNodeWithTag("background_dimming").performSemanticsAction(SemanticsActions.SetProgress) { change ->
            assertTrue(change(.72f))
        }
        settleFrames()
        await("Dimming must persist as a real preference") { abs(settings().dimming - .72f) < .01f }
        checkNotNull(scenario).recreate()
        settleFrames()
        awaitMedia(BackgroundKind.GIF, gif)
        assertEquals(gif.toString(), settings().uri)
        assertEquals(BackgroundScale.FILL, settings().scale)
        assertTrue(abs(settings().dimming - .72f) < .01f)
        assertTrue(settings().animationEnabled)

        assertLifecyclePausesOnlyBackground()
        capture("phone-gif-background")
        assertForegroundContinues(priorAudio)
    }

    @Test fun videoWithAnAudioTrackLoopsMutedAndPausesWithLifecycle() {
        val priorAudio = ReelMotion.snapshot().elapsedPlayingNanos
        val video = chooseDocument("mdp-test-video.mp4", BackgroundKind.VIDEO)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(target, video)
            assertEquals("The fixture must really contain audio", "yes",
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)?.lowercase())
        } finally { retriever.release() }
        await("Real video frames must loop with their audio disabled") {
            val state = BackgroundMediaDiagnostics.state.value
            state.loaded && state.kind == BackgroundKind.VIDEO && state.playing &&
                state.frameCount >= 4 && state.loopCount >= 1 && state.muted && state.audioTracksDisabled
        }
        assertNativeFramesMove(BackgroundKind.VIDEO)
        assertForegroundContinues(priorAudio)
        setAnimation(false)
        assertBackgroundFrozen()
        setAnimation(true)
        assertNativeFramesMove(BackgroundKind.VIDEO)
        assertLifecyclePausesOnlyBackground()
        assertForegroundContinues(priorAudio)
        capture("phone-muted-video-background")
    }

    @Test fun imageFitsResponsiveLayoutsAndMissingDocumentHasAnHonestFallback() {
        val image = chooseDocument("mdp-test-still.png", BackgroundKind.IMAGE)
        assertEquals(96, BackgroundMediaDiagnostics.state.value.width)
        assertEquals(64, BackgroundMediaDiagnostics.state.value.height)
        compose.onNodeWithTag("nav_studio").performClick()
        settleFrames()
        waitForTag("local_library")
        val phoneContent = compose.onNodeWithTag("local_library").fetchSemanticsNode().boundsInRoot
        val phoneNavigation = compose.onNodeWithTag("nav_studio").fetchSemanticsNode().boundsInRoot
        assertTrue("Phone navigation must remain below its content", phoneNavigation.top >= phoneContent.bottom - 4f)
        capture("phone-image-background")

        resizedDisplay = true
        shell("wm size 1600x1000")
        shell("wm density 160")
        realWait(250)
        settleFrames()
        await("A real landscape tablet configuration must arrive") {
            val configuration = target.resources.configuration
            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && configuration.screenWidthDp >= 840
        }
        settleFrames()
        waitForTag("local_library")
        awaitMedia(BackgroundKind.IMAGE, image)
        val tabletContent = compose.onNodeWithTag("local_library").fetchSemanticsNode().boundsInRoot
        val tabletNavigation = compose.onNodeWithTag("nav_studio").fetchSemanticsNode().boundsInRoot
        assertTrue("Tablet navigation must move to a rail beside the content", tabletNavigation.right <= tabletContent.left + 4f)
        compose.onNodeWithTag("deck_power").assertIsDisplayed()
        capture("tablet-landscape-image-background")

        checkNotNull(scenario).close()
        scenario = null
        val unavailable = DocumentsContract.buildDocumentUri(BackgroundFixtureDocumentsProvider.AUTHORITY, "deleted-background.gif")
        saveSettings(BackgroundSettings(unavailable.toString(), BackgroundKind.GIF))
        launchApp()
        settleFrames()
        openSettings()
        await("An unavailable saved file must report a recoverable error") {
            BackgroundMediaDiagnostics.state.value.error != null ||
                compose.onAllNodesWithTag("background_error").fetchSemanticsNodes().isNotEmpty()
        }
        settleFrames()
        compose.onNodeWithTag("background_error").performScrollTo().assertIsDisplayed()
        assertEquals("An inaccessible URI must stay saved until the user chooses otherwise", unavailable.toString(), settings().uri)
        clickSetting("background_restore")
        await("Restore must select only the real Original source") { settings().kind == BackgroundKind.ORIGINAL && settings().uri == null }
        settleFrames()
        val status = compose.onNodeWithTag("background_status").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertEquals(if (resolveOriginalBackgroundAsset(target) == null) "original_missing" else "original_available", status)
        assertForegroundContinues(0)
    }

    private fun chooseDocument(name: String, kind: BackgroundKind): Uri = try {
        openSettings()
        clickSetting("background_change")
        await("The supported Android document picker must open") { documentsUiVisible() }
        if (!clickSystemText("MdP test media")) {
            await("The document picker must expose its roots drawer") { clickSystemDescription("Show roots", "Show navigation drawer", "Open navigation drawer") }
            await("The isolated test provider must appear in SAF") { clickSystemText("MdP test media") }
        }
        await("The real fixture document must be selectable") { selectDocumentTitle(name) }
        val uri = DocumentsContract.buildDocumentUri(BackgroundFixtureDocumentsProvider.AUTHORITY, name)
        var nextSelectionAttempt = SystemClock.uptimeMillis() + 750
        await("The picker result must be validated and saved") {
            // Some DocumentsUI layouts select the file first and require an
            // explicit toolbar/bottom-bar confirmation before returning its URI.
            if (documentsUiVisible() && !confirmDocumentSelection() &&
                SystemClock.uptimeMillis() >= nextSelectionAttempt) {
                // A provider model refresh can replace the row during the first
                // interaction. Refresh its node and retry without toggling an
                // already selected document or repeatedly opening its preview.
                selectDocumentTitle(name)
                nextSelectionAttempt = SystemClock.uptimeMillis() + 750
            }
            settings().uri == uri.toString() && settings().kind == kind
        }
        settleFrames()
        awaitMedia(kind, uri)
        uri
    } catch (failure: Throwable) {
        dumpPickerFailure(kind)
        throw failure
    }

    private fun openSettings() {
        compose.onNodeWithTag("nav_settings").performClick()
        settleFrames()
        waitForTag("background_change")
    }

    private fun clickSetting(tag: String) {
        compose.onNodeWithTag(tag).performScrollTo()
        settleFrames()
        compose.onNodeWithTag(tag).performClick()
        settleFrames()
    }

    private fun setAnimation(enabled: Boolean) {
        compose.onNodeWithTag("background_animation").performScrollTo()
        settleFrames()
        val current = settings().animationEnabled
        if (current != enabled) compose.onNodeWithTag("background_animation").performClick()
        settleFrames()
        await("Animation state must reach the native renderer") {
            val state = BackgroundMediaDiagnostics.state.value
            state.animationEnabled == enabled && (enabled || !state.playing)
        }
        settleFrames()
        if (enabled) compose.onNodeWithTag("background_animation").assertIsOn() else compose.onNodeWithTag("background_animation").assertIsOff()
    }

    private fun awaitMedia(kind: BackgroundKind, uri: Uri) {
        await("The selected source must decode using its native backend") {
            val state = BackgroundMediaDiagnostics.state.value
            state.source == uri.toString() && state.kind == kind && state.loaded && state.width > 0 && state.height > 0 && state.error == null
        }
    }

    private fun assertNativeFramesMove(kind: BackgroundKind) {
        val first = BackgroundMediaDiagnostics.state.value
        val pixels = nativePixelHash()
        await("Actual $kind decoder frames and pixels must change") {
            val state = BackgroundMediaDiagnostics.state.value
            state.kind == kind && state.playing && state.frameCount > first.frameCount && nativePixelHash() != pixels
        }
    }

    private fun assertBackgroundFrozen() {
        realWait(240)
        val before = BackgroundMediaDiagnostics.state.value
        realWait(320)
        val after = BackgroundMediaDiagnostics.state.value
        assertTrue("Animation must really pause", !after.playing)
        assertEquals("Paused native animation must not submit new frames", before.frameCount, after.frameCount)
        assertEquals("Paused video position must freeze", before.positionMs, after.positionMs)
    }

    private fun assertLifecyclePausesOnlyBackground() {
        val priorAudio = ReelMotion.snapshot().elapsedPlayingNanos
        checkNotNull(scenario).moveToState(Lifecycle.State.CREATED)
        await("A hidden Activity must pause its background renderer") {
            val state = BackgroundMediaDiagnostics.state.value
            !state.lifecycleStarted && !state.playing
        }
        assertBackgroundFrozen()
        assertForegroundContinues(priorAudio)
        checkNotNull(scenario).moveToState(Lifecycle.State.RESUMED)
        settleFrames()
        await("The visible Activity must resume animation") {
            val state = BackgroundMediaDiagnostics.state.value
            state.lifecycleStarted && state.playing && state.loaded
        }
        assertNativeFramesMove(BackgroundMediaDiagnostics.state.value.kind)
    }

    private fun assertForegroundContinues(priorElapsed: Long) {
        var active = false
        instrumentation.runOnMainSync {
            val player = checkNotNull(controller)
            active = player.isPlaying && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                player.currentMediaItem?.mediaId == "background-test-foreground-audio"
        }
        assertTrue("Background media must not take audio focus or replace music", active)
        assertTrue("Background media must not reset the recorder phase", ReelMotion.snapshot().elapsedPlayingNanos > priorElapsed)
        // A repeat boundary flushes the PCM processor briefly. Require the real
        // asymmetric signal to return rather than sampling that legitimate zero.
        await("Only the real foreground stereo signal may drive the meters") {
            val levels = AudioMeters.levels.value
            levels.left > levels.right + .2f && levels.right > 0f
        }
    }

    private fun nativePixelHash(): Int {
        var hash = 0
        checkNotNull(scenario).onActivity { activity ->
            val views = descendants(activity.window.decorView).filter { it.tag == "background_media_native" }
            val texture = views.filterIsInstance<TextureView>().firstOrNull { it.isAvailable }
            val bitmap = if (texture != null) checkNotNull(texture.getBitmap(64, 64)) else {
                val media = views.firstOrNull { it is ImageView || it.javaClass.simpleName == "LegacyGifView" }
                    ?: error("No native image/GIF view exists")
                check(media.width > 0 && media.height > 0)
                Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).also { result ->
                    val canvas = Canvas(result)
                    canvas.scale(64f / media.width, 64f / media.height)
                    media.draw(canvas)
                }
            }
            val pixels = IntArray(64 * 64)
            bitmap.getPixels(pixels, 0, 64, 0, 0, 64, 64)
            hash = pixels.contentHashCode()
            bitmap.recycle()
        }
        return hash
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun documentsUiVisible(): Boolean = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
        ?.contains("documentsui", ignoreCase = true) == true

    private fun clickSystemText(text: String): Boolean = clickSystemNode { node ->
        node.text?.toString()?.trim().equals(text, ignoreCase = true) ||
            node.contentDescription?.toString()?.contains(text, ignoreCase = true) == true
    }

    private fun confirmDocumentSelection(): Boolean {
        // Use actual picker actions, not a generic "Open" substring that could
        // accidentally click "Open navigation drawer" or a preview affordance.
        val confirmationIds = setOf("action_menu_select", "action_menu_open", "button1")
        if (clickSystemNode { node -> node.viewIdResourceName?.substringAfterLast('/') in confirmationIds }) return true
        val labels = setOf("open", "select", "choose", "use this file")
        return clickSystemNode { node ->
            labels.any { label ->
                node.text?.toString()?.trim().equals(label, ignoreCase = true) ||
                    node.contentDescription?.toString()?.trim().equals(label, ignoreCase = true)
            }
        }
    }

    private fun selectDocumentTitle(name: String): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        if (!root.packageName.toString().contains("documentsui", ignoreCase = true)) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            // DocumentsUI's row ACTION_CLICK prefers Quick View. Its supported
            // ACTION_LONG_CLICK explicitly selects the document, allowing the
            // picker confirmation action to return a real persistable URI.
            if (node.isVisibleToUser && node.isEnabled && node.text?.toString()?.trim().equals(name, ignoreCase = true)) {
                val ancestors = mutableListOf<AccessibilityNodeInfo>()
                var ancestor: AccessibilityNodeInfo? = node
                repeat(7) {
                    ancestor?.let { current -> ancestors += current; ancestor = current.parent }
                }
                if (ancestors.any { it.isSelected }) return true
                for (candidate in ancestors) {
                    if (candidate.isEnabled && candidate.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK } &&
                        candidate.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
                }
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.isEmpty) continue
                return injectFingerTap(bounds)
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::add) }
        }
        return false
    }

    private fun injectFingerTap(bounds: Rect): Boolean {
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int): MotionEvent {
            val pointer = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            val coordinates = MotionEvent.PointerCoords().apply {
                x = bounds.exactCenterX()
                y = bounds.exactCenterY()
                pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
                size = 1f
            }
            // Setting SOURCE_TOUCHSCREEN on the short obtain() overload still
            // leaves TOOL_TYPE_UNKNOWN. DocumentsUI routes FINGER/STYLUS only.
            return MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 1,
                arrayOf(pointer), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0,
                InputDevice.SOURCE_TOUCHSCREEN, 0)
        }
        val down = event(MotionEvent.ACTION_DOWN)
        try {
            val pressed = instrumentation.uiAutomation.injectInputEvent(down, true)
            Thread.sleep(60)
            val up = event(MotionEvent.ACTION_UP)
            return try {
                val released = instrumentation.uiAutomation.injectInputEvent(up, true)
                pressed && released
            } finally { up.recycle() }
        } finally { down.recycle() }
    }

    private fun dumpPickerFailure(kind: BackgroundKind) {
        // Text stays bounded; screenshots are files only, never binary/Base64 logs.
        runCatching {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            root?.let(queue::add)
            val lines = mutableListOf<String>()
            var inspected = 0
            while (queue.isNotEmpty() && inspected++ < 500 && lines.size < 45) {
                val node = queue.removeFirst()
                val meaningful = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank() ||
                    node.isClickable || node.isLongClickable || node.isSelected ||
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_LONG_CLICK }
                if (node.isVisibleToUser && meaningful) {
                    fun bounded(value: CharSequence?) = value?.toString()?.replace('\n', ' ')?.take(100).orEmpty()
                    val bounds = Rect().also(node::getBoundsInScreen)
                    val actions = node.actionList.joinToString { "${it.id}:${it.label?.toString().orEmpty()}" }.take(150)
                    lines += "id=${bounded(node.viewIdResourceName)} text=${bounded(node.text)} " +
                        "description=${bounded(node.contentDescription)} selected=${node.isSelected} " +
                        "clickable=${node.isClickable} enabled=${node.isEnabled} bounds=${bounds.toShortString()} actions=$actions"
                }
                repeat(node.childCount) { index -> node.getChild(index)?.let(queue::add) }
            }
            Log.e("MdP.BackgroundMediaTest", "SAF picker failure ($kind), package=${root?.packageName}, inspected=$inspected")
            lines.forEach { Log.e("MdP.BackgroundMediaTest", it) }
        }
        runCatching { capture("picker-failure-${kind.name.lowercase()}") }
    }

    private fun clickSystemDescription(vararg descriptions: String): Boolean = clickSystemNode { node ->
        descriptions.any { node.contentDescription?.toString().equals(it, ignoreCase = true) }
    }

    private fun clickSystemNode(matches: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return false
        if (!root.packageName.toString().contains("documentsui", ignoreCase = true)) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isVisibleToUser && matches(node)) {
                var candidate: AccessibilityNodeInfo? = node
                repeat(5) {
                    val clickable = candidate ?: return@repeat
                    if (clickable.isClickable && clickable.isEnabled && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    candidate = clickable.parent
                }
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(queue::add) }
        }
        return false
    }

    private fun settings(): BackgroundSettings {
        val raw = target.getSharedPreferences(BACKGROUND_PREFERENCES, Context.MODE_PRIVATE).getString(BACKGROUND_SETTINGS_KEY, null)
        return raw?.let(BackgroundSettingsJson::decode) ?: BackgroundSettings()
    }

    private fun saveSettings(settings: BackgroundSettings) {
        check(target.getSharedPreferences(BACKGROUND_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(BACKGROUND_SETTINGS_KEY, BackgroundSettingsJson.encode(settings)).commit())
    }

    private fun launchApp() {
        scenario = ActivityScenario.launch<MainActivity>(Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeBy(32)
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun settleFrames() { compose.mainClock.advanceTimeBy(80); instrumentation.waitForIdleSync() }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (!compose.mainClock.autoAdvance) compose.mainClock.advanceTimeBy(32)
            if (predicate()) return
            Thread.sleep(40)
        }
        assertTrue("$message; background=${BackgroundMediaDiagnostics.state.value}; music=${AudioMeters.levels.value}", predicate())
    }

    private fun realWait(milliseconds: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds)
        while (System.nanoTime() < deadline) Thread.sleep(40)
    }

    private fun startWatchdog() {
        val testThread = Thread.currentThread()
        val executor = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "MdP-background-test-watchdog").apply { isDaemon = true }
        }
        watchdog = executor
        watchdogTask = executor.schedule({
            // Diagnostics only: never mutate playback, unblock a failed check,
            // or call synchronization/UI APIs from this independent thread.
            Log.e("MdP.BackgroundMediaTest", "Background test has exceeded 90 seconds; " +
                "background=${BackgroundMediaDiagnostics.state.value}; music=${AudioMeters.levels.value}")
            for ((label, thread) in listOf("test" to testThread, "main" to Looper.getMainLooper().thread)) {
                Log.e("MdP.BackgroundMediaTest", "$label thread=${thread.name}, state=${thread.state}")
                thread.stackTrace.take(30).forEach { frame ->
                    Log.e("MdP.BackgroundMediaTest", "  at ${frame.toString().take(240)}")
                }
            }
        }, 90, TimeUnit.SECONDS)
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }

    private fun capture(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(target.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        FileOutputStream(File(directory, "$name.png")).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun createForegroundWave(): File {
        val rate = 44_100
        val frames = rate * 4
        val bytes = frames * 4
        val data = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.put("RIFF".toByteArray()).putInt(36 + bytes).put("WAVEfmt ".toByteArray()).putInt(16)
        data.putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
        data.put("data".toByteArray()).putInt(bytes)
        repeat(frames) { index ->
            data.putShort((sin(2 * PI * 440 * index / rate) * 23_000).toInt().toShort())
            data.putShort((sin(2 * PI * 660 * index / rate) * 2_300).toInt().toShort())
        }
        return File(target.cacheDir, "background-foreground.wav").apply { writeBytes(data.array()) }
    }
}
