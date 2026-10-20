package ro.mdp.remix

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ro.mdp.remix.background.BACKGROUND_PREFERENCES
import ro.mdp.remix.background.BACKGROUND_SETTINGS_KEY
import ro.mdp.remix.background.BackgroundKind
import ro.mdp.remix.background.BackgroundMediaDiagnostics
import ro.mdp.remix.background.BackgroundScale
import ro.mdp.remix.background.BackgroundSettings
import ro.mdp.remix.background.BackgroundSettingsJson
import ro.mdp.remix.data.LibrarySnapshot
import ro.mdp.remix.data.MusicRepository
import ro.mdp.remix.data.Playlist
import ro.mdp.remix.data.Track
import ro.mdp.remix.links.LinkKind
import ro.mdp.remix.links.LinkLibrary
import ro.mdp.remix.links.LinkPlaylist
import ro.mdp.remix.links.LinkRepository
import ro.mdp.remix.links.SavedMusicLink

/**
 * Opt-in production-signer regression, excluded from the normal Premium Test source set.
 * CI invokes each method separately around a real `adb install -r` of a higher version.
 * Every write is restricted to a fresh, disposable emulator installation.
 */
@RunWith(AndroidJUnit4::class)
class ProductionUpgradeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val marker get() = File(target.filesDir, "production-upgrade-fixture.json")

    @Test fun seedOldInstallation() {
        val identity = verifyEnvironment()
        assertFalse("The production fixture must not replace an existing fixture", marker.exists())
        assertFalse("Refuse to replace an existing local library", File(target.filesDir, MUSIC_FILE).exists())
        assertFalse("Refuse to replace an existing YouTube library", File(target.filesDir, LINKS_FILE).exists())
        assertTrue("Refuse to replace saved user preferences", preferences().all.isEmpty())
        assertTrue("Refuse to replace a saved background", backgroundPreferences().all.isEmpty())
        val mediaDirectory = File(target.filesDir, "shared-media")
        assertTrue("Refuse to replace existing imported media", mediaDirectory.listFiles().orEmpty().isEmpty())
        check(mediaDirectory.isDirectory || mediaDirectory.mkdirs())

        val firstWave = writeMedia(mediaDirectory, stereoWave(8000, 2000))
        val secondWave = writeMedia(mediaDirectory, stereoWave(4000, 1000))
        val gif = writeMedia(mediaDirectory, instrumentation.context.assets.open(
            "background_test/mdp-test-animation.gif").use { it.readBytes() })
        val firstUri = mediaUri(firstWave)
        val secondUri = mediaUri(secondWave)
        val gifUri = mediaUri(gif)
        writeDurable(File(target.filesDir, MUSIC_FILE), musicJson(firstUri, secondUri).toByteArray())
        writeDurable(File(target.filesDir, LINKS_FILE), youtubeJson().toByteArray())
        assertTrue(preferences().edit().putBoolean("english", true)
            .putString("recorder_design", "studio_pro").putInt("panel_transparency", 37)
            .putBoolean("brand_sync_enabled", false).commit())
        assertTrue(backgroundPreferences().edit().putString(BACKGROUND_SETTINGS_KEY,
            BackgroundSettingsJson.encode(expectedBackground(gifUri))).commit())

        val saved = JSONObject().apply {
            put("schemaVersion", 1)
            put("seedVersionCode", identity.versionCode)
            put("uid", identity.uid)
            put("certificateSha256", identity.certificate)
            put("privateMarker", UUID.randomUUID().toString())
            put("firstUri", firstUri)
            put("secondUri", secondUri)
            put("gifUri", gifUri)
            put("files", JSONObject().apply {
                listOf(File(target.filesDir, MUSIC_FILE), File(target.filesDir, LINKS_FILE),
                    firstWave, secondWave, gif).forEach { file ->
                    put(file.relativeTo(target.filesDir).invariantSeparatorsPath, sha256(file.readBytes()))
                }
            })
        }
        writeDurable(marker, saved.toString().toByteArray())
        launchAndVerify(saved)
        println("PASS: fresh production installation launched with the pinned release signer; durable local/YouTube, preferences and real audio/GIF fixtures were verified.")
    }

    @Test fun verifyUpdatedInstallation() {
        val identity = verifyEnvironment()
        assertTrue("The first installation's private fixture must survive the update", marker.isFile)
        val markerBytes = marker.readBytes()
        val saved = JSONObject(markerBytes.toString(Charsets.UTF_8))
        assertEquals(1, saved.getInt("schemaVersion"))
        assertTrue("The actual installed version must increase", identity.versionCode > saved.getLong("seedVersionCode"))
        assertEquals("An in-place update must preserve the Android UID", saved.getInt("uid"), identity.uid)
        assertEquals("The same real signing certificate must survive the update", saved.getString("certificateSha256"), identity.certificate)
        assertTrue("The private marker must be retained", saved.getString("privateMarker").isNotBlank())
        launchAndVerify(saved)
        assertTrue("Activity startup must not replace the private upgrade marker", markerBytes.contentEquals(marker.readBytes()))
        println("PASS: production in-place update ${saved.getLong("seedVersionCode")} -> ${identity.versionCode} retained package, actual release certificate, UID, playlists/favorites/order/history, language, AKAI skin, transparency, GIF selection and audio/GIF bytes.")
    }

    private data class Identity(val versionCode: Long, val uid: Int, val certificate: String)

    @Suppress("DEPRECATION")
    private fun verifyEnvironment(): Identity {
        // This check precedes every fixture read/write, so a supplied runner argument
        // cannot turn the regression into a data operation on a physical device.
        assertEquals("The production regression is restricted to the disposable CI emulator",
            "1", shell("getprop ro.kernel.qemu").trim())
        assertEquals("Only the final production package is exercised", PACKAGE, target.packageName)
        assertEquals("The target must be a non-debuggable release APK", 0,
            target.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val arguments = InstrumentationRegistry.getArguments()
        val expectedCode = arguments.getString("expectedVersionCode")?.toLongOrNull()
        assertNotNull("Pass the actual expected installed versionCode to the runner", expectedCode)
        assertTrue("A valid expected versionCode is required", checkNotNull(expectedCode) > 0L)
        val expectedCertificate = arguments.getString("expectedCertificateSha256").orEmpty()
            .replace(":", "").lowercase()
        assertTrue("Pass the public release certificate's SHA-256 to the runner",
            Regex("[0-9a-f]{64}").matches(expectedCertificate))
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info: PackageInfo = target.packageManager.getPackageInfo(PACKAGE, flags)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        assertEquals("The expected version must really be installed", checkNotNull(expectedCode), code)
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        assertEquals("Only one original signing certificate is accepted", 1, signatures?.size ?: 0)
        val certificate = sha256(checkNotNull(signatures).single().toByteArray())
        assertEquals("The actual installed APK must use the protected permanent release key", expectedCertificate, certificate)
        return Identity(code, target.applicationInfo.uid, certificate)
    }

    private fun launchAndVerify(saved: JSONObject) {
        var originalFailure: Throwable? = null
        try {
            verifySavedData(saved)
            val output = shell("am start -W -n ro.mdp.remix/ro.mdp.remix.MainActivity")
            assertTrue("The production launcher Activity must start successfully", output.contains("Status: ok"))
            val deadline = SystemClock.uptimeMillis() + 10_000
            var resumed = false
            while (!resumed && SystemClock.uptimeMillis() < deadline) {
                instrumentation.runOnMainSync {
                    resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>().any { it.window.decorView.isShown }
                }
                if (!resumed) SystemClock.sleep(40)
            }
            assertTrue("The real production UI must become visible", resumed)
            // No global-idle wait is used while the native GIF may keep submitting frames.
            verifySavedData(saved)
            runBlocking {
                withTimeout(10_000) {
                    assertEquals("The actual music repository must load the retained library", expectedMusic(saved), MusicRepository(target).sharedSnapshot())
                    val links = LinkRepository(target)
                    try { assertEquals("The actual YouTube repository must load the retained library", expectedYoutube(), links.sharedSnapshot()) }
                    finally { links.close() }
                }
            }
        } catch (failure: Throwable) {
            originalFailure = failure
            throw failure
        } finally {
            try {
                // The runner's final cleanup may wait for global idle. Hide the
                // live native GIF through Android before it enters that path.
                // HOME never stops the instrumented process or clears data.
                shell("input keyevent KEYCODE_HOME")
                val deadline = SystemClock.uptimeMillis() + 10_000
                var paused = false
                while (!paused && SystemClock.uptimeMillis() < deadline) {
                    val state = BackgroundMediaDiagnostics.state.value
                    paused = !state.lifecycleStarted && !state.playing
                    if (!paused) SystemClock.sleep(40)
                }
                assertTrue("The hidden production Activity must pause decorative media before runner cleanup", paused)
            } catch (cleanup: Throwable) {
                if (originalFailure == null) throw cleanup else originalFailure.addSuppressed(cleanup)
            }
        }
    }

    private fun verifySavedData(saved: JSONObject) {
        val files = saved.getJSONObject("files")
        for (relative in files.keys()) {
            assertTrue("Fixture paths must stay inside private files", relative in setOf(MUSIC_FILE, LINKS_FILE) ||
                Regex("shared-media/[0-9a-f]{64}").matches(relative))
            val file = File(target.filesDir, relative)
            assertTrue("Saved media/library must still exist: $relative", file.isFile)
            assertEquals("Saved bytes must remain unchanged: $relative", files.getString(relative), sha256(file.readBytes()))
        }
        val preferences = preferences()
        assertTrue("English selection must survive", preferences.getBoolean("english", false))
        assertEquals("AKAI Studio Pro selection must survive", "studio_pro", preferences.getString("recorder_design", null))
        assertEquals("Panel transparency must survive", 37, preferences.getInt("panel_transparency", -1))
        assertFalse("The saved brand-sync setting must survive", preferences.getBoolean("brand_sync_enabled", true))
        val background = BackgroundSettingsJson.decode(checkNotNull(backgroundPreferences().getString(BACKGROUND_SETTINGS_KEY, null)))
        assertEquals("Custom GIF and display settings must survive", expectedBackground(saved.getString("gifUri")), background)
        listOf("firstUri", "secondUri", "gifUri").forEach { name ->
            val uri = android.net.Uri.parse(saved.getString(name))
            assertEquals("Media must remain owned by this installation", "$PACKAGE.mdpfiles", uri.authority)
            target.contentResolver.openInputStream(uri)!!.use { input ->
                assertEquals("FileProvider must still serve the exact saved media", checkNotNull(uri.lastPathSegment), sha256(input.readBytes()))
            }
        }
    }

    private fun expectedBackground(gifUri: String) =
        BackgroundSettings(gifUri, BackgroundKind.GIF, BackgroundScale.FIT, .27f, true)

    private fun expectedMusic(saved: JSONObject) = LibrarySnapshot(
        tracks = listOf(
            Track("release-track-a", "Release stereo A", "MdP", "Permanent release", 1000, saved.getString("firstUri")),
            Track("release-track-b", "Release stereo B", "MdP", "Permanent release", 1000, saved.getString("secondUri")),
        ),
        favorites = setOf("release-track-b"),
        playlists = listOf(Playlist("release-local-playlist", "Retained local playlist", listOf("release-track-b", "release-track-a"))),
    )

    private fun expectedYoutube() = LinkLibrary(
        links = listOf(
            SavedMusicLink("release-youtube-video", "Retained YouTube video", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", LinkKind.YOUTUBE_VIDEO, true, 1000, 2000),
            SavedMusicLink("release-youtube-playlist", "Retained YouTube playlist", "https://www.youtube.com/playlist?list=PLreleaseRetention12345", LinkKind.YOUTUBE_PLAYLIST, false, 3000, 4000),
        ),
        playlists = listOf(LinkPlaylist("release-online-playlist", "Retained YouTube collection", listOf("release-youtube-playlist", "release-youtube-video"), true, 5000, 6000)),
    )

    private fun musicJson(firstUri: String, secondUri: String): String {
        val fixture = expectedMusic(JSONObject().put("firstUri", firstUri).put("secondUri", secondUri))
        return JSONObject().put("version", 1).put("tracks", JSONArray().apply {
            fixture.tracks.forEach { track -> put(JSONObject().put("id", track.id).put("title", track.title)
                .put("artist", track.artist).put("album", track.album).put("durationMs", track.durationMs).put("uri", track.uri)) }
        }).put("favorites", JSONArray(fixture.favorites.toList())).put("playlists", JSONArray().apply {
            fixture.playlists.forEach { playlist -> put(JSONObject().put("id", playlist.id).put("name", playlist.name).put("trackIds", JSONArray(playlist.trackIds))) }
        }).toString()
    }

    private fun youtubeJson(): String {
        val fixture = expectedYoutube()
        return JSONObject().put("version", 1).put("links", JSONArray().apply {
            fixture.links.forEach { link -> put(JSONObject().put("id", link.id).put("title", link.title).put("url", link.url)
                .put("kind", link.kind.name).put("favorite", link.favorite).put("addedAt", link.addedAt).put("lastOpenedAt", link.lastOpenedAt)) }
        }).put("playlists", JSONArray().apply {
            fixture.playlists.forEach { playlist -> put(JSONObject().put("id", playlist.id).put("name", playlist.name)
                .put("linkIds", JSONArray(playlist.linkIds)).put("favorite", playlist.favorite)
                .put("createdAt", playlist.createdAt).put("lastOpenedAt", playlist.lastOpenedAt)) }
        }).toString()
    }

    private fun writeMedia(directory: File, bytes: ByteArray): File = File(directory, sha256(bytes)).apply {
        assertFalse("The fresh fixture must not overwrite an existing media file", exists())
        writeDurable(this, bytes)
    }

    private fun mediaUri(file: File) = FileProvider.getUriForFile(target, "$PACKAGE.mdpfiles", file).toString()
    private fun preferences() = target.getSharedPreferences("mdp_settings", Context.MODE_PRIVATE)
    private fun backgroundPreferences() = target.getSharedPreferences(BACKGROUND_PREFERENCES, Context.MODE_PRIVATE)

    private fun writeDurable(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun stereoWave(left: Int, right: Int): ByteArray {
        val frames = 16_000
        val dataBytes = frames * 4
        return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(2)
            putInt(16_000); putInt(64_000); putShort(4); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes)
            repeat(frames) { index ->
                putShort((if (index % 32 < 16) left else -left).toShort())
                putShort((if (index % 32 < 16) right else -right).toShort())
            }
        }.array()
    }

    private companion object {
        const val PACKAGE = "ro.mdp.remix"
        const val MUSIC_FILE = "music-library.json"
        const val LINKS_FILE = "youtube-link-library.json"
    }
}
