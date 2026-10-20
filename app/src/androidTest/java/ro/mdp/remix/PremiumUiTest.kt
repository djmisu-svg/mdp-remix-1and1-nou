package ro.mdp.remix

import android.content.ComponentName
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ro.mdp.remix.playback.AudioMeters
import ro.mdp.remix.playback.PlaybackService
import ro.mdp.remix.playback.ReelMotion

/** Exercises the installed, isolated Premium Test application and its real Media3 service. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class PremiumUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext

    @Test
    fun studioNavigationLanguageAndDeckPowerWork() {
        assertEquals("ro.mdp.remix.premiumtest", target.packageName)
        waitForTag("local_library")
        assertNoSystemErrorDialog()
        compose.onNodeWithTag("nav_studio").assertIsSelected()
        compose.onNodeWithTag("deck_power").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("deck_play").assertIsNotEnabled()
        compose.onNodeWithTag("deck_power").performClick().assertIsOn()
        captureScreen("phone-studio")

        selectEnglish()
        compose.onNodeWithTag("nav_settings").assertIsSelected().assertTextContains("Settings")
        compose.onNodeWithTag("language_english").assertIsSelected()
        compose.onNodeWithTag("share_app").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("check_updates").performScrollTo().assertIsEnabled()
        captureScreen("phone-settings")

        compose.onNodeWithTag("language_romanian").performScrollTo().performClick()
        compose.onNodeWithTag("nav_settings").assertTextContains("Setări")
        compose.onNodeWithTag("language_romanian").assertIsSelected()
        compose.onNodeWithTag("language_english").performClick()

        compose.onNodeWithTag("nav_youtube").performClick().assertIsSelected()
        waitForTag("youtube_library")
        compose.onNodeWithTag("add_youtube_link").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("new_link_playlist").assertIsEnabled()
        captureScreen("phone-youtube")
        compose.onNodeWithTag("nav_studio").performClick().assertIsSelected()
        waitForTag("local_library")
    }

    @Test
    fun youtubePlaylistCanBeCreatedRenamedAndRestored() {
        selectEnglish()
        compose.onNodeWithTag("nav_youtube").performClick()
        waitForTag("youtube_library")
        val original = "UI Sessions ${System.currentTimeMillis()}"
        val renamed = "$original edited"
        compose.onNodeWithTag("new_link_playlist").performScrollTo().performClick()
        compose.onNodeWithTag("link_playlist_name").performTextInput(original)
        compose.onNodeWithTag("link_name_save").performClick()
        compose.onNodeWithTag("youtube_search").performScrollTo().performTextReplacement(original)
        scrollToPlaylist(original)
        compose.onNodeWithTag("link_playlist_options").performScrollTo().performClick()
        compose.onNodeWithTag("rename_link_playlist").performClick()
        compose.onNodeWithTag("link_playlist_name").performTextReplacement(renamed)
        compose.onNodeWithTag("link_name_save").performClick()
        compose.onNodeWithTag("youtube_search").performScrollTo().performTextReplacement("")
        scrollToPlaylist(renamed)
        compose.activityRule.scenario.recreate()
        waitForTag("youtube_library")
        scrollToPlaylist(renamed)
        compose.onNodeWithText(renamed).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun realDecodedStereoAudioDrivesMetersAndPauseClearsThem() {
        waitForTag("local_library")
        assertNoSystemErrorDialog()
        val audioFile = createStereoWave(segmented = true)
        val (future, controller) = connectController()
        compose.mainClock.autoAdvance = false
        try {
            startWave(controller, audioFile, repeat = true)
            awaitCondition("Decoded left-heavy PCM must drive distinct stereo meters", { playbackDiagnostics(controller) }) {
                val levels = AudioMeters.levels.value
                levels.right > 0.05f && levels.left > levels.right + 0.2f &&
                    levels.livePeakLeft > levels.livePeakRight + 0.2f
            }
            val measured = AudioMeters.levels.value
            assertTrue("Left level must be normalized", measured.left in 0f..1f)
            assertTrue("Right level must be normalized", measured.right in 0f..1f)
            assertTrue("Decoded left peak must be present", measured.peakLeft > 0f)
            assertTrue("Decoded right peak must be present", measured.peakRight > 0f)
            assertLedBalance(leftDominant = true)
            captureScreen("phone-live-meters")
            instrumentation.runOnMainSync { controller.pause() }
            awaitSilentMeters(controller, "Pause must clear RMS and peak measurements")
            assertLedSilence()

            instrumentation.runOnMainSync { controller.seekTo(5_500) }
            waitRealTime(160)
            assertEquals("Seeking while paused must not revive stale left samples", 0f, AudioMeters.levels.value.left, 0f)
            assertEquals("Seeking while paused must not revive stale right samples", 0f, AudioMeters.levels.value.right, 0f)
            instrumentation.runOnMainSync { controller.play() }
            awaitCondition("Seeking to right-heavy PCM must reverse the measured balance", { playbackDiagnostics(controller) }) {
                val levels = AudioMeters.levels.value
                levels.left > 0.05f && levels.right > levels.left + 0.2f &&
                    levels.livePeakRight > levels.livePeakLeft + 0.2f && levels.peakRight > levels.peakLeft + 0.2f
            }
            assertLedBalance(leftDominant = false)

            instrumentation.runOnMainSync { controller.seekTo(3_300) }
            awaitCondition("Digital silence must clear both channels while playback continues", { playbackDiagnostics(controller) }) {
                val levels = AudioMeters.levels.value
                isPlayingAt(controller, 3_600L..4_400L) &&
                    levels.left == 0f && levels.right == 0f && levels.peakLeft == 0f && levels.peakRight == 0f &&
                    levels.livePeakLeft == 0f && levels.livePeakRight == 0f
            }
            assertLedSilence()
            instrumentation.runOnMainSync { controller.seekTo(500) }
            awaitCondition("Seeking back to sound must restore the original stereo balance", { playbackDiagnostics(controller) }) {
                val levels = AudioMeters.levels.value
                levels.right > 0.05f && levels.left > levels.right + 0.2f
            }
            instrumentation.runOnMainSync { controller.pause() }
            awaitSilentMeters(controller, "Pausing after a seek must clear every meter")
            assertLedSilence()
        } catch (failure: Throwable) {
            runCatching { captureScreen("phone-meter-failure") }
            throw failure
        } finally {
            stopController(future, controller)
            compose.mainClock.autoAdvance = true
            audioFile.delete()
        }
    }

    @Test
    fun reelPhaseSurvivesPauseBackgroundNavigationRecreationStopAndCompletion() {
        waitForTag("local_library")
        assertNoSystemErrorDialog()
        val audioFile = createStereoWave(fileName = "reel-calibration.wav")
        val (future, controller) = connectController()
        compose.mainClock.autoAdvance = false
        try {
            startWave(controller, audioFile, repeat = true)
            awaitClockwiseReelMotion(controller)
            assertVisibleReelMotion()

            val beforeBackground = ReelMotion.snapshot()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            waitRealTime(320)
            val inBackground = ReelMotion.snapshot()
            assertTrue("Foreground audio must keep the reel clock running without a composed screen", inBackground.isPlaying)
            assertTrue("The left reel must keep turning in the background", angleDelta(beforeBackground.leftDegrees, inBackground.leftDegrees) > 3f)
            assertTrue("The right reel must keep turning clockwise in the background", angleDelta(beforeBackground.rightDegrees, inBackground.rightDegrees) > 3f)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

            instrumentation.runOnMainSync { controller.pause() }
            awaitCondition("Pause must freeze the authoritative reel clock", { playbackDiagnostics(controller) }) {
                !ReelMotion.snapshot().isPlaying
            }
            assertFrozenReels("paused")
            val paused = ReelMotion.snapshot()
            assertUiReelAngles(paused.leftDegrees, paused.rightDegrees)

            compose.onNodeWithTag("nav_youtube").performClick()
            settleUiFrame()
            waitForTag("youtube_library")
            compose.onNodeWithTag("nav_studio").performClick()
            settleUiFrame()
            waitForTag("local_library")
            assertUiReelAngles(paused.leftDegrees, paused.rightDegrees)
            compose.activityRule.scenario.recreate()
            settleUiFrame()
            waitForTag("local_library")
            assertUiReelAngles(paused.leftDegrees, paused.rightDegrees)
            assertFrozenReels("after navigation and recreation")

            instrumentation.runOnMainSync { controller.play() }
            awaitClockwiseReelMotion(controller)
            assertVisibleReelMotion()
            instrumentation.runOnMainSync { controller.stop() }
            awaitCondition("Stop must freeze both reels", { playbackDiagnostics(controller) }) { !ReelMotion.snapshot().isPlaying }
            assertFrozenReels("stopped")
            val stopped = ReelMotion.snapshot()
            assertUiReelAngles(stopped.leftDegrees, stopped.rightDegrees)

            startWave(controller, audioFile, repeat = false)
            awaitClockwiseReelMotion(controller)
            instrumentation.runOnMainSync { controller.seekTo(3_650) }
            awaitCondition("The real WAV must reach end of queue", { playbackDiagnostics(controller) }) {
                var ended = false
                instrumentation.runOnMainSync { ended = controller.playbackState == Player.STATE_ENDED }
                ended && !ReelMotion.snapshot().isPlaying
            }
            assertFrozenReels("completed")
            awaitSilentMeters(controller, "Completion must clear source meters")
            val completed = ReelMotion.snapshot()
            assertUiReelAngles(completed.leftDegrees, completed.rightDegrees)
            captureScreen("phone-reels-completed")
        } catch (failure: Throwable) {
            runCatching { captureScreen("phone-reel-failure") }
            throw failure
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            stopController(future, controller)
            compose.mainClock.autoAdvance = true
            audioFile.delete()
        }
    }

    private fun connectController(): Pair<ListenableFuture<MediaController>, MediaController> {
        lateinit var future: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(
                target, SessionToken(target, ComponentName(target, PlaybackService::class.java)),
            ).buildAsync()
        }
        return future to future.get(10, TimeUnit.SECONDS)
    }

    private fun startWave(controller: MediaController, file: File, repeat: Boolean) {
        instrumentation.runOnMainSync {
            controller.setMediaItem(MediaItem.Builder()
                .setMediaId("instrumentation-${file.name}")
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(MediaMetadata.Builder().setTitle("VU calibration").build())
                .build())
            controller.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            controller.prepare()
            controller.play()
        }
    }

    private fun stopController(future: ListenableFuture<MediaController>, controller: MediaController) {
        instrumentation.runOnMainSync {
            controller.stop()
            controller.clearMediaItems()
        }
        try {
            awaitCondition("Cleanup must stop the actual reel clock before restoring automatic UI frames", {
                "ReelMotion=${ReelMotion.snapshot()}"
            }) { !ReelMotion.snapshot().isPlaying }
        } finally {
            instrumentation.runOnMainSync { MediaController.releaseFuture(future) }
        }
    }

    private fun awaitSilentMeters(controller: MediaController, message: String) {
        awaitCondition(message, { playbackDiagnostics(controller) }) {
            val levels = AudioMeters.levels.value
            levels.left == 0f && levels.right == 0f && levels.peakLeft == 0f && levels.peakRight == 0f &&
                levels.livePeakLeft == 0f && levels.livePeakRight == 0f
        }
    }

    private fun isPlayingAt(controller: MediaController, positionRange: LongRange): Boolean {
        var matches = false
        instrumentation.runOnMainSync { matches = controller.isPlaying && controller.currentPosition in positionRange }
        return matches
    }

    private fun awaitClockwiseReelMotion(controller: MediaController) {
        awaitCondition("Actual playback must start the reel clock", { playbackDiagnostics(controller) }) { ReelMotion.snapshot().isPlaying }
        val before = ReelMotion.snapshot()
        awaitCondition("Real playback must turn both reels clockwise", { playbackDiagnostics(controller) }) {
            val after = ReelMotion.snapshot()
            after.isPlaying && angleDelta(before.leftDegrees, after.leftDegrees) > 3f &&
                angleDelta(before.rightDegrees, after.rightDegrees) > 3f
        }
    }

    private fun assertFrozenReels(reason: String) {
        val before = ReelMotion.snapshot()
        assertTrue("Reels must be $reason", !before.isPlaying)
        waitRealTime(240)
        val after = ReelMotion.snapshot()
        assertEquals("Left phase must remain frozen while $reason", before.leftDegrees, after.leftDegrees, 0f)
        assertEquals("Right phase must remain frozen while $reason", before.rightDegrees, after.rightDegrees, 0f)
        assertEquals("Only actual playing time may accrue while $reason", before.elapsedPlayingNanos, after.elapsedPlayingNanos)
    }

    private fun assertVisibleReelMotion() {
        settleUiFrame()
        val before = listOf("reel_left", "reel_right").map { tag ->
            compose.onNodeWithTag(tag).assertIsDisplayed()
            val description = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            assertTrue("Visible playing reel must report running=true", description.contains("running=true"))
            description.substringAfter("angle=").substringBefore(';').toFloat()
        }
        waitRealTime(240)
        settleUiFrame()
        val after = listOf("reel_left", "reel_right").map { tag ->
            compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                .substringAfter("angle=").substringBefore(';').toFloat()
        }
        assertTrue("Visible left reel must change its rendered angle while playing", angleDelta(before[0], after[0]) > 3f)
        assertTrue("Visible right reel must change its rendered angle clockwise", angleDelta(before[1], after[1]) > 3f)
        assertTrue("Both visible clockwise reels must share the same phase", abs(angleDelta(after[0], after[1])) < 0.5f)
    }

    private fun assertUiReelAngles(left: Float, right: Float) {
        settleUiFrame()
        for ((tag, expected) in listOf("reel_left" to left, "reel_right" to right)) {
            val description = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            val actual = description.substringAfter("angle=").substringBefore(';').toFloat()
            assertTrue("$tag must render the preserved service phase ($description, expected $expected)", abs(angleDelta(expected, actual)) < 0.5f)
            assertTrue("Stopped reel accessibility must report running=false", description.contains("running=false"))
        }
    }

    private fun assertLedBalance(leftDominant: Boolean) {
        settleUiFrame()
        val levels = listOf("led_left", "led_right").map { tag ->
            val config = compose.onNodeWithTag(tag).fetchSemanticsNode().config
            val level = config[SemanticsProperties.ProgressBarRangeInfo].current
            val peak = config[SemanticsProperties.StateDescription].substringAfter("peak=").substringBefore(';').toFloat()
            assertTrue("$tag must expose a held peak at or above its live level", peak >= level)
            level
        }
        assertTrue("LED semantics must reflect the decoded stereo balance: $levels",
            if (leftDominant) levels[0] > levels[1] + 0.15f else levels[1] > levels[0] + 0.15f)
    }

    private fun assertLedSilence() {
        settleUiFrame()
        for (tag in listOf("led_left", "led_right")) {
            val value = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
            assertEquals("$tag must expose silence", 0f, value, 0f)
        }
    }

    private fun settleUiFrame() {
        compose.mainClock.advanceTimeBy(80)
        instrumentation.waitForIdleSync()
    }

    private fun angleDelta(from: Float, to: Float): Float = ((to - from + 540f) % 360f) - 180f

    private fun waitRealTime(milliseconds: Long) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds)
        while (System.nanoTime() < deadline) Thread.sleep(40)
    }

    private fun selectEnglish() {
        waitForTag("nav_settings")
        assertNoSystemErrorDialog()
        compose.onNodeWithTag("nav_settings").performClick()
        waitForTag("language_english")
        compose.onNodeWithTag("language_english").performScrollTo().performClick()
    }

    private fun waitForTag(tag: String) {
        try {
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: ComposeTimeoutException) {
            throw AssertionError("The UI tag '$tag' did not appear within 10 seconds", failure)
        }
    }

    private fun scrollToPlaylist(text: String) {
        waitForTag("youtube_grid")
        var lastFailure: AssertionError? = null
        try {
            compose.waitUntil(timeoutMillis = 10_000) {
                try {
                    compose.onNodeWithTag("youtube_grid").performScrollToNode(hasText(text))
                    true
                } catch (failure: AssertionError) {
                    lastFailure = failure
                    false
                }
            }
        } catch (failure: ComposeTimeoutException) {
            runCatching { captureScreen("phone-playlist-failure") }
            throw AssertionError("Could not scroll to playlist '$text': ${lastFailure?.message}", failure)
        }
    }

    private fun awaitCondition(message: String, diagnostics: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(40)
        }
        assertTrue("$message. ${diagnostics()}", condition())
    }

    private fun playbackDiagnostics(controller: MediaController): String {
        var details = ""
        instrumentation.runOnMainSync {
            val formats = controller.currentTracks.groups.flatMap { group ->
                (0 until group.length).map { group.getTrackFormat(it).toString() }
            }
            details = "pid=${android.os.Process.myPid()}, meterIdentity=${System.identityHashCode(AudioMeters)}, " +
                "state=${controller.playbackState}, isPlaying=${controller.isPlaying}, " +
                "playWhenReady=${controller.playWhenReady}, suppression=${controller.playbackSuppressionReason}, " +
                "position=${controller.currentPosition}, duration=${controller.duration}, formats=$formats, " +
                "error=${controller.playerError?.stackTraceToString()}, meters=${AudioMeters.levels.value}"
        }
        return details
    }

    private fun captureScreen(name: String) {
        assertNoSystemErrorDialog()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "A screenshot must be available on the test emulator"
        }
        val directory = File(target.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        FileOutputStream(File(directory, "$name.png")).use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        FileOutputStream(File(directory, "$name.jpg")).use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream))
        }
        bitmap.recycle()
    }

    private fun assertNoSystemErrorDialog() {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
        val errors = listOf("isn't responding", "isn’t responding", "keeps stopping").flatMap { phrase ->
            root.findAccessibilityNodeInfosByText(phrase).filter { node ->
                node.isVisibleToUser && node.packageName?.toString() != target.packageName
            }.map { node -> node.text?.toString() ?: node.contentDescription?.toString() ?: phrase }
        }
        assertTrue("System error dialog is visible: ${errors.joinToString()}", errors.isEmpty())
    }

    private fun createStereoWave(fileName: String = "vu-calibration.wav", segmented: Boolean = false): File {
        val sampleRate = 44_100
        val frames = sampleRate * if (segmented) 8 else 4
        val dataSize = frames * 4
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize)
        buffer.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        buffer.putShort(1).putShort(2).putInt(sampleRate).putInt(sampleRate * 4)
        buffer.putShort(4).putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize)
        repeat(frames) { index ->
            val time = index.toDouble() / sampleRate
            val (leftAmplitude, rightAmplitude) = when {
                !segmented || time < 2.5 -> 23_000 to 2_300
                time < 5.0 -> 0 to 0
                time < 7.5 -> 2_300 to 23_000
                else -> 0 to 0
            }
            buffer.putShort((sin(2 * PI * 440 * time) * leftAmplitude).toInt().toShort())
            buffer.putShort((sin(2 * PI * 660 * time) * rightAmplitude).toInt().toShort())
        }
        return File(target.cacheDir, fileName).apply { writeBytes(buffer.array()) }
    }
}
