package ro.mdp.remix.transfer

import android.content.Intent
import android.content.ComponentName
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ro.mdp.remix.background.BackgroundKind
import ro.mdp.remix.background.BackgroundScale
import ro.mdp.remix.background.BackgroundSettings
import ro.mdp.remix.data.LibrarySnapshot
import ro.mdp.remix.data.Playlist
import ro.mdp.remix.data.Track
import ro.mdp.remix.links.LinkKind
import ro.mdp.remix.links.LinkLibrary
import ro.mdp.remix.links.LinkPlaylist
import ro.mdp.remix.links.SavedMusicLink

/** Real installed APK, FileProvider streams and durable imported media; no Activity or UI idle waits. */
@RunWith(AndroidJUnit4::class)
class CollectionSharingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext

    @Test fun sharesUnmodifiedInstalledApkAndRestoresCompletePortableCollection() = runBlocking {
        assertTrue("Sharing regression must run in the isolated test application", target.packageName.endsWith(".premiumtest"))
        val mediaDirectory = File(target.filesDir, "shared-media").apply { mkdirs() }
        val originalNames = mediaDirectory.list().orEmpty().toSet()
        val sources = File(mediaDirectory, "test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        var prepared: PreparedCollectionShare? = null
        try {
            val waveBytes = createWave()
            val wave = File(sources, "local.wav").apply { writeBytes(waveBytes) }
            val duplicate = File(sources, "same-audio.wav").apply { writeBytes(waveBytes) }
            val gifBytes = instrumentation.context.assets.open("background_test/mdp-test-animation.gif").use { it.readBytes() }
            val gif = File(sources, "background.gif").apply { writeBytes(gifBytes) }
            val mp4Bytes = instrumentation.context.assets.open("background_test/mdp-test-video.mp4").use { it.readBytes() }
            val video = File(sources, "local.mp4").apply { writeBytes(mp4Bytes) }
            fun provider(file: File) = FileProvider.getUriForFile(target, "${target.packageName}.mdpfiles", file).toString()
            val tracks = listOf(
                Track("wav", "Local stereo", "MdP", "Transfer", 16, provider(wave)),
                Track("duplicate", "Same bytes, distinct track", "MdP", "Transfer", 16, provider(duplicate)),
                Track("mp4", "Real AAC fixture", "MdP", "Transfer", 1500, provider(video)),
            )
            val data = CollectionData(
                music = LibrarySnapshot(
                    tracks = tracks,
                    favorites = setOf("wav", "mp4"),
                    playlists = listOf(Playlist("local-list", "Muzica mea", listOf("mp4", "duplicate", "wav"))),
                ),
                youtube = LinkLibrary(
                    links = listOf(SavedMusicLink("youtube", "Official link", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", LinkKind.YOUTUBE_VIDEO, true, 10, 20)),
                    playlists = listOf(LinkPlaylist("youtube-list", "YouTube", listOf("youtube"), true, 10, 20)),
                ),
                english = true,
                recorderDesignId = "studio_pro",
                background = BackgroundSettings(provider(gif), BackgroundKind.GIF, BackgroundScale.FIT, 0.27f, true),
                panelTransparency = 73,
                brandSyncEnabled = false,
            )
            val sharing = CollectionSharing(target)
            val exported = sharing.prepareShare(data)
            prepared = exported
            val intent = sharing.shareIntent(exported, english = true)
            assertEquals(Intent.ACTION_SEND_MULTIPLE, intent.action)
            assertEquals("*/*", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            @Suppress("DEPRECATION")
            val streams = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            assertEquals(listOf(exported.apkUri, exported.collectionUri), streams)
            assertEquals(2, intent.clipData?.itemCount)
            assertEquals(exported.apkUri, intent.clipData?.getItemAt(0)?.uri)
            assertEquals(exported.collectionUri, intent.clipData?.getItemAt(1)?.uri)
            val installedApk = File(target.applicationInfo.sourceDir)
            val sharedApkHash = target.contentResolver.openInputStream(exported.apkUri)!!.use { sha256(it) }
            val installedApkHash = installedApk.inputStream().use { sha256(it) }
            assertEquals("The signed installed APK must be shared byte-for-byte", installedApkHash, sharedApkHash)
            target.contentResolver.openAssetFileDescriptor(exported.apkUri, "r")!!.use { descriptor ->
                assertEquals(installedApk.length(), descriptor.length)
            }
            target.contentResolver.openInputStream(exported.collectionUri)!!.use { input ->
                assertEquals('P'.code, input.read())
                assertEquals('K'.code, input.read())
            }
            val received = sharing.receive(exported.collectionUri)
            assertTrue(received.id.isNotBlank())
            assertEquals(data.music.playlists, received.data.music.playlists)
            assertEquals(data.music.favorites, received.data.music.favorites)
            assertEquals(data.youtube, received.data.youtube)
            assertEquals(data.english, received.data.english)
            assertEquals(data.recorderDesignId, received.data.recorderDesignId)
            assertEquals(data.panelTransparency, received.data.panelTransparency)
            assertEquals(data.brandSyncEnabled, received.data.brandSyncEnabled)
            assertEquals(data.background.copy(uri = received.data.background.uri), received.data.background)
            assertEquals(data.music.tracks.map { it.copy(uri = "") }, received.data.music.tracks.map { it.copy(uri = "") })
            assertNotEquals(received.data.music.tracks[0].uri, received.data.music.tracks[1].uri)
            assertEquals(Uri.parse(received.data.music.tracks[0].uri).path, Uri.parse(received.data.music.tracks[1].uri).path)
            for (track in received.data.music.tracks) {
                val uri = Uri.parse(track.uri)
                assertEquals("content", uri.scheme)
                assertEquals("${target.packageName}.mdpfiles", uri.authority)
                assertTrue("Distinct source aliases must retain their identity", uri.getQueryParameter("source") != null)
            }
            assertTrue(sources.deleteRecursively())
            assertFalse(wave.exists())
            fun read(uri: String): ByteArray = target.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() }
            assertArrayEquals(waveBytes, read(received.data.music.tracks[0].uri))
            assertArrayEquals(waveBytes, read(received.data.music.tracks[1].uri))
            assertArrayEquals(mp4Bytes, read(received.data.music.tracks[2].uri))
            assertArrayEquals(gifBytes, read(requireNotNull(received.data.background.uri)))
            assertTrue("Chooser files remain readable after import", target.contentResolver.openInputStream(exported.collectionUri)!!.use { it.read() } >= 0)
        } finally {
            sources.deleteRecursively()
            prepared?.let { File(target.cacheDir, "sharing/${it.id}").deleteRecursively() }
            mediaDirectory.listFiles().orEmpty().filter { it.name !in originalNames }.forEach { it.deleteRecursively() }
        }
    }

    @Test fun sharesOneInstallableApkAsDocumentWithTemporaryReadPermission() = runBlocking {
        assertTrue(target.packageName.endsWith(".premiumtest"))
        val sharing = CollectionSharing(target)
        val prepared = sharing.prepareApk(english = true)
        try {
            val intent = sharing.apkShareIntent(prepared)
            assertEquals(Intent.ACTION_SEND, intent.action)
            assertEquals("application/vnd.android.package-archive", intent.type)
            @Suppress("DEPRECATION")
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            assertEquals(prepared.apkUri, uri)
            assertEquals("content", uri?.scheme)
            assertEquals("${target.packageName}.mdpfiles", uri?.authority)
            assertEquals(1, intent.clipData?.itemCount)
            assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, intent.flags and (Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
            assertFalse(intent.hasExtra(Intent.EXTRA_TEXT))
            assertTrue("Test package must not be presented as the production release", intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().contains("Test"))
            val installed = File(target.applicationInfo.sourceDir)
            assertEquals(installed.inputStream().use(::sha256), target.contentResolver.openInputStream(prepared.apkUri)!!.use(::sha256))
            target.contentResolver.openAssetFileDescriptor(prepared.apkUri, "r")!!.use { assertEquals(installed.length(), it.length) }
            val chooser = Intent.createChooser(intent, "Share APK")
            assertTrue("Android chooser must propagate temporary access", chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(1, chooser.clipData?.itemCount)
            val received = CountDownLatch(1)
            var resultCode = Activity.RESULT_CANCELED
            var receipt: Bundle? = null
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(code: Int, data: Bundle?) {
                    resultCode = code
                    receipt = data
                    received.countDown()
                }
            }
            val delivery = Intent(intent).apply {
                component = ComponentName(instrumentation.context.packageName, ApkSharingProbeActivity::class.java.name)
                putExtra("mdp.test.result", receiver)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            instrumentation.runOnMainSync { target.startActivity(delivery) }
            assertTrue("The receiving application must read the APK using its temporary grant", received.await(20, TimeUnit.SECONDS))
            assertEquals(receipt?.getString("error"), Activity.RESULT_OK, resultCode)
            val delivered = checkNotNull(receipt)
            assertNotEquals("This must exercise a different app UID", target.applicationInfo.uid, delivered.getInt("uid"))
            assertEquals(installed.length(), delivered.getLong("bytes"))
            assertEquals(installed.inputStream().use(::sha256), delivered.getString("sha256"))
        } finally {
            File(target.cacheDir, "sharing/${prepared.id}").deleteRecursively()
        }
    }

    @Test fun refusesMissingAndSplitApksInsteadOfOfferingAnUninstallableFile() = runBlocking {
        assertTrue(target.packageName.endsWith(".premiumtest"))
        fun context(info: ApplicationInfo) = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getApplicationInfo(): ApplicationInfo = info
        }
        val missing = ApplicationInfo(target.applicationInfo).apply {
            splitSourceDirs = null
            sourceDir = File(target.cacheDir, "missing-${UUID.randomUUID()}.apk").absolutePath
        }
        val split = ApplicationInfo(target.applicationInfo).apply { splitSourceDirs = arrayOf("/fixture/split.apk") }
        for (info in listOf(missing, split)) {
            try {
                CollectionSharing(context(info)).prepareApk(english = true)
                throw AssertionError("Missing or incomplete APK must never be offered as installable")
            } catch (failure: IOException) {
                assertTrue(failure.message.orEmpty().contains("release", ignoreCase = true))
            }
        }
    }

    @Test fun rejectsFileUrisBeforeReadingPrivateFiles() = runBlocking {
        val marker = File(target.filesDir, "sharing-test-${UUID.randomUUID()}").apply { writeText("preserved") }
        try {
            try {
                CollectionSharing(target).receive(Uri.fromFile(marker))
                throw AssertionError("A shared collection must arrive through a granted content URI")
            } catch (_: IOException) {
                assertEquals("preserved", marker.readText())
            }
        } finally {
            marker.delete()
        }
    }

    private fun createWave(): ByteArray {
        val samples = 256
        val dataBytes = samples * 2 * 2
        return ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + dataBytes)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII)); putInt(16); putShort(1); putShort(2)
            putInt(16_000); putInt(16_000 * 4); putShort(4); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(dataBytes)
            repeat(samples) { index ->
                putShort((if (index % 32 < 16) 8000 else -8000).toShort())
                putShort((if (index % 32 < 16) 2000 else -2000).toShort())
            }
        }.array()
    }

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
