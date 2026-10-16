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
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ro.mdp.remix.playback.AudioMeters
import ro.mdp.remix.playback.PlaybackService

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
        val audioFile = createStereoWave()
        lateinit var future: ListenableFuture<MediaController>
        instrumentation.runOnMainSync {
            future = MediaController.Builder(
                target,
                SessionToken(target, ComponentName(target, PlaybackService::class.java)),
            ).buildAsync()
        }
        val controller = future.get(10, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                controller.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId("instrumentation-calibration")
                        .setUri(Uri.fromFile(audioFile))
                        .setMediaMetadata(MediaMetadata.Builder().setTitle("VU calibration").build())
                        .build(),
                )
                controller.repeatMode = Player.REPEAT_MODE_ONE
                controller.prepare()
                controller.play()
            }
            awaitCondition("Decoded stereo audio must drive both VU channels") {
                val levels = AudioMeters.levels.value
                levels.left > 0.05f && levels.right > 0.05f
            }
            val measured = AudioMeters.levels.value
            assertTrue("Left level must be normalized", measured.left in 0f..1f)
            assertTrue("Right level must be normalized", measured.right in 0f..1f)
            assertTrue("Decoded left peak must be present", measured.peakLeft > 0f)
            assertTrue("Decoded right peak must be present", measured.peakRight > 0f)
            captureScreen("phone-live-meters")
            instrumentation.runOnMainSync { controller.pause() }
            awaitCondition("Pausing playback must reset both source meters") {
                val levels = AudioMeters.levels.value
                levels.left == 0f && levels.right == 0f
            }
        } finally {
            instrumentation.runOnMainSync {
                controller.stop()
                controller.clearMediaItems()
                MediaController.releaseFuture(future)
            }
            audioFile.delete()
        }
    }

    private fun selectEnglish() {
        waitForTag("nav_settings")
        compose.onNodeWithTag("nav_settings").performClick()
        waitForTag("language_english")
        compose.onNodeWithTag("language_english").performScrollTo().performClick()
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun scrollToPlaylist(text: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            try {
                compose.onNodeWithTag("youtube_grid").performScrollToNode(hasText(text))
                true
            } catch (_: AssertionError) {
                false
            }
        }
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(40)
        }
        assertTrue(message, condition())
    }

    private fun captureScreen(name: String) {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "A screenshot must be available on the test emulator"
        }
        val directory = File(target.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        FileOutputStream(File(directory, "$name.png")).use { stream ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        if (name == "phone-studio" || name == "phone-youtube") {
            FileOutputStream(File(directory, "$name.jpg")).use { stream ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream))
            }
        }
        bitmap.recycle()
    }

    private fun createStereoWave(): File {
        val sampleRate = 44_100
        val frames = sampleRate * 4
        val dataSize = frames * 4
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + dataSize)
        buffer.put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16)
        buffer.putShort(1).putShort(2).putInt(sampleRate).putInt(sampleRate * 4)
        buffer.putShort(4).putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII)).putInt(dataSize)
        repeat(frames) { index ->
            val time = index.toDouble() / sampleRate
            buffer.putShort((sin(2 * PI * 440 * time) * 23_000).toInt().toShort())
            buffer.putShort((sin(2 * PI * 660 * time) * 14_000).toInt().toShort())
        }
        return File(target.cacheDir, "vu-calibration.wav").apply { writeBytes(buffer.array()) }
    }
}
