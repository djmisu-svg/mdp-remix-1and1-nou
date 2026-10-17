package ro.mdp.remix

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
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
import ro.mdp.remix.ui.RecorderDesign
import ro.mdp.remix.ui.RecorderDesignPreferences

/** Owns Activity lifetimes explicitly so a fresh launch cannot restore a saved Compose bundle. */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class RecorderDesignTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext

    @Test
    fun allFourDesignsKeepRealPlaybackAndPersistAcrossRecreationAndFreshLaunch() {
        val originalDesign = RecorderDesignPreferences.load(target)
        val audio = createLongStereoWave()
        var scenario: ActivityScenario<MainActivity>? = null
        var future: ListenableFuture<MediaController>? = null
        var controller: MediaController? = null
        try {
            scenario = launchFreshActivity()
            waitForTag("local_library")
            val connection = connectController()
            future = connection.first
            val player = connection.second
            controller = player
            compose.mainClock.autoAdvance = false
            instrumentation.runOnMainSync {
                player.setMediaItem(MediaItem.Builder().setMediaId("design-calibration")
                    .setUri(Uri.fromFile(audio))
                    .setMediaMetadata(MediaMetadata.Builder().setTitle("Recorder design calibration").build())
                    .build())
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.prepare()
                player.play()
            }
            await("The design test requires real asymmetric stereo playback") {
                val levels = AudioMeters.levels.value
                ReelMotion.snapshot().isPlaying && levels.left > levels.right + 0.2f && levels.right > 0f
            }
            for (design in RecorderDesign.entries) {
                val before = ReelMotion.snapshot()
                val activityVersion = ReelMotion.activity.value.version
                chooseDesign(design)
                assertDesignAndSharedControls(design)
                assertContinuousPlayback(player, before.elapsedPlayingNanos, activityVersion)
                assertPersistedOnDisk(design)
                assertSelectedRadio(design)
                captureDesign(design)
            }

            val persisted = RecorderDesign.STUDIO_PRO
            val beforeRecreation = ReelMotion.snapshot()
            val recreationVersion = ReelMotion.activity.value.version
            checkNotNull(scenario).recreate()
            settleFrames()
            waitForTag("local_library")
            assertDesignAndSharedControls(persisted)
            assertSelectedRadio(persisted)
            assertContinuousPlayback(player, beforeRecreation.elapsedPlayingNanos, recreationVersion)

            assertPersistedOnDisk(persisted)
            val beforeFreshLaunch = ReelMotion.snapshot()
            val freshVersion = ReelMotion.activity.value.version
            checkNotNull(scenario).close()
            scenario = null
            // A new Activity receives no saved instance state. This simulates an app relaunch;
            // the instrumented process itself remains alive so the real Media3 service can play.
            scenario = launchFreshActivity()
            settleFrames()
            waitForTag("local_library")
            assertEquals(persisted, RecorderDesignPreferences.load(target))
            assertDesignAndSharedControls(persisted)
            assertSelectedRadio(persisted)
            assertContinuousPlayback(player, beforeFreshLaunch.elapsedPlayingNanos, freshVersion)
        } finally {
            RecorderDesignPreferences.save(target, originalDesign)
            val player = controller
            if (player != null) {
                instrumentation.runOnMainSync { player.stop(); player.clearMediaItems() }
                await("Test cleanup must stop the service-owned reel clock") { !ReelMotion.snapshot().isPlaying }
            }
            future?.let { connection -> instrumentation.runOnMainSync { MediaController.releaseFuture(connection) } }
            compose.mainClock.autoAdvance = true
            scenario?.close()
            audio.delete()
        }
    }

    private fun launchFreshActivity(): ActivityScenario<MainActivity> = ActivityScenario.launch<MainActivity>(
        Intent(target, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )

    private fun chooseDesign(design: RecorderDesign) {
        openPickerAt(design)
        compose.onNodeWithTag("recorder_design_${design.id}").performClick()
        settleFrames()
    }

    private fun assertSelectedRadio(design: RecorderDesign) {
        openPickerAt(design)
        compose.onNodeWithTag("recorder_design_${design.id}").assertIsSelected().performClick()
        settleFrames()
    }

    private fun openPickerAt(design: RecorderDesign) {
        compose.onNodeWithTag("recorder_design_selector").performScrollTo()
        settleFrames()
        compose.onNodeWithTag("recorder_design_selector").performClick()
        settleFrames()
        waitForTag("recorder_design_list")
        compose.onNodeWithTag("recorder_design_list")
            .performScrollToNode(hasTestTag("recorder_design_${design.id}"))
        settleFrames()
        waitForTag("recorder_design_${design.id}")
    }

    private fun assertDesignAndSharedControls(design: RecorderDesign) {
        settleFrames()
        assertNoSystemErrorDialog()
        assertEquals(design.id, compose.onNodeWithTag("deck_design").fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription])
        assertEquals(design.label, compose.onNodeWithTag("recorder_design_selector").fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription])
        for (tag in listOf("tape_deck", "reel_left", "reel_right", "deck_power", "deck_play",
            "deck_previous", "deck_next", "deck_seek", "deck_volume", "vu_left", "vu_right", "led_left", "led_right")) {
            compose.onNodeWithTag(tag).assertExists()
        }
        compose.onNodeWithTag("deck_power").assertIsOn()
        compose.onNodeWithTag("deck_play").assertIsEnabled()
        val left = compose.onNodeWithTag("led_left").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        val right = compose.onNodeWithTag("led_right").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertTrue("${design.label} must retain the real asymmetric stereo meters", left > right + 0.15f && right > 0f)
        val angles = listOf("reel_left", "reel_right").map { tag ->
            val description = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.StateDescription]
            assertTrue("${design.label} must keep both reel displays running", description.contains("running=true"))
            description.substringAfter("angle=").substringBefore(';').toFloat()
        }
        assertTrue("Both clockwise reels must stay synchronized in ${design.label}", abs(angleDelta(angles[0], angles[1])) < 0.5f)
        val source = ReelMotion.snapshot()
        assertTrue("Changing recorder design must render the existing left phase", abs(angleDelta(source.leftDegrees, angles[0])) < 10f)
        assertTrue("Changing recorder design must render the existing right phase", abs(angleDelta(source.rightDegrees, angles[1])) < 10f)
    }

    private fun assertContinuousPlayback(player: MediaController, priorElapsed: Long, priorVersion: Long) {
        var playing = false
        var mediaId: String? = null
        instrumentation.runOnMainSync { playing = player.isPlaying; mediaId = player.currentMediaItem?.mediaId }
        assertTrue("Selecting a recorder design must not pause the actual Media3 player", playing)
        assertEquals("design-calibration", mediaId)
        val motion = ReelMotion.snapshot()
        assertTrue("Selecting or reloading a design must not reset accumulated reel phase", motion.elapsedPlayingNanos > priorElapsed)
        assertEquals("Design changes must not restart playback activity", priorVersion, ReelMotion.activity.value.version)
        val levels = AudioMeters.levels.value
        assertTrue("Decoded stereo audio must continue through design changes", levels.left > levels.right + 0.2f && levels.right > 0f)
    }

    private fun assertPersistedOnDisk(design: RecorderDesign) {
        await("The selected recorder design must reach private durable storage") {
            val file = File(target.applicationInfo.dataDir, "shared_prefs/${RecorderDesignPreferences.FILE_NAME}.xml")
            RecorderDesignPreferences.load(target) == design && runCatching {
                file.readText().contains("name=\"${RecorderDesignPreferences.KEY}\">${design.id}</string>")
            }.getOrDefault(false)
        }
    }

    private fun connectController(): Pair<ListenableFuture<MediaController>, MediaController> {
        lateinit var future: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(target,
                SessionToken(target, ComponentName(target, PlaybackService::class.java))).buildAsync()
        }
        return future to future.get(10, TimeUnit.SECONDS)
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun settleFrames() {
        compose.mainClock.advanceTimeBy(80)
        instrumentation.waitForIdleSync()
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (predicate()) return
            Thread.sleep(40)
        }
        assertTrue("$message; motion=${ReelMotion.snapshot()}; meters=${AudioMeters.levels.value}", predicate())
    }

    private fun captureDesign(design: RecorderDesign) {
        assertNoSystemErrorDialog()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val directory = File(target.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        FileOutputStream(File(directory, "phone-design-${design.id}.png")).use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        bitmap.recycle()
    }

    private fun assertNoSystemErrorDialog() {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
        val errors = listOf("isn't responding", "isn’t responding", "keeps stopping").flatMap { phrase ->
            root.findAccessibilityNodeInfosByText(phrase).filter { node ->
                node.isVisibleToUser && node.packageName?.toString() != target.packageName
            }.map { node -> node.text?.toString() ?: phrase }
        }
        assertTrue("System error dialog is visible: ${errors.joinToString()}", errors.isEmpty())
    }

    private fun angleDelta(from: Float, to: Float): Float = ((to - from + 540f) % 360f) - 180f

    private fun createLongStereoWave(): File {
        val rate = 44_100
        val frames = rate * 30
        val bytes = frames * 4
        val buffer = ByteBuffer.allocate(44 + bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + bytes)
        buffer.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        buffer.putShort(1).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(bytes)
        repeat(frames) { index ->
            val seconds = index.toDouble() / rate
            buffer.putShort((sin(2 * PI * 440 * seconds) * 23_000).toInt().toShort())
            buffer.putShort((sin(2 * PI * 660 * seconds) * 2_300).toInt().toShort())
        }
        return File(target.cacheDir, "design-calibration.wav").apply { writeBytes(buffer.array()) }
    }
}
