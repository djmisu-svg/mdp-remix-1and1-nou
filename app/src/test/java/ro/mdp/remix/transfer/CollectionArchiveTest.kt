package ro.mdp.remix.transfer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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

class CollectionArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val audioUri = "content://sender.private/music/one"
    private val secondUri = "content://sender.private/music/two"
    private val backgroundUri = "content://sender.private/background/dancing.gif"
    private val audio = byteArrayOf(0, 4, 16, 64, -1)
    private val background = "GIF89a-test".toByteArray()

    @Test fun roundTripIncludesAudioBackgroundMetadataAndSettingsWithoutSenderUris() {
        val source = sample()
        val archive = exported(source)
        val manifest = zipEntries(archive.readBytes()).getValue("manifest.json").toString(Charsets.UTF_8)
        assertFalse(manifest.contains("sender.private"))
        val staged = CollectionArchive.stage(archive.inputStream(), temporary.newFolder("staged"))
        assertEquals(2, staged.media.size)
        assertArrayEquals(audio, staged.media.getValue(staged.data.music.tracks.single().uri).readBytes())
        assertArrayEquals(background, staged.media.getValue(requireNotNull(staged.data.background.uri)).readBytes())
        val imported = CollectionArchive.remap(staged.data) { "content://receiver.collection/media/" + it.substringAfterLast('/') }
        assertEquals(source.copy(
            music = source.music.copy(tracks = source.music.tracks.map { it.copy(uri = imported.music.tracks.single().uri) }),
            background = source.background.copy(uri = imported.background.uri),
        ), imported)
        assertTrue(imported.music.tracks.single().uri.startsWith("content://receiver.collection/"))
        assertEquals(source.youtube, staged.data.youtube)
        assertEquals(source.music.playlists, staged.data.music.playlists)
    }

    @Test fun equalBytesKeepBothTrackIdsAndPlaylistOrderWhileStoringOneMediaEntry() {
        val first = sample().music.tracks.single()
        val source = sample().copy(
            music = LibrarySnapshot(listOf(first, first.copy(id = "two", uri = secondUri, title = "Doi")),
                setOf("one", "two"), listOf(Playlist("p", "Mix", listOf("two", "one")))),
            background = BackgroundSettings(),
        )
        val archive = exported(source)
        assertEquals(2, zipEntries(archive.readBytes()).size) // Manifest + one deduplicated audio blob.
        val staged = CollectionArchive.stage(archive.inputStream(), temporary.newFolder())
        assertEquals(2, staged.data.music.tracks.size)
        val uris = staged.data.music.tracks.map { it.uri }
        assertNotEquals(uris[0], uris[1])
        assertEquals(staged.media.getValue(uris[0]), staged.media.getValue(uris[1]))
        val imported = CollectionArchive.remap(staged.data) { "content://receiver.collection/media/" + it.substringAfterLast('/') }
        assertEquals(source.music.playlists, imported.music.playlists)
        assertEquals(listOf("one", "two"), imported.music.tracks.map { it.id })
    }

    @Test fun originalBackgroundAndEmptyLibrariesNeedNoMediaFiles() {
        val source = CollectionData(LibrarySnapshot(), LinkLibrary(), false, "black", BackgroundSettings())
        val archive = exported(source)
        val staged = CollectionArchive.stage(archive.inputStream(), temporary.newFolder())
        assertEquals(source, staged.data)
        assertTrue(staged.media.isEmpty())
        assertEquals(source, CollectionArchive.remap(staged.data) { error("No files expected") })
    }

    @Test fun archiveSummaryReportsBothKindsOfPlaylistsAndActualFileSize() {
        val output = File(temporary.root, "summary.mdpcollection")
        val summary = CollectionArchive.write(output, sample(), ::open)
        assertEquals(1, summary.musicTracks)
        assertEquals(1, summary.youtubeLinks)
        assertEquals(2, summary.playlists)
        assertEquals(output.length(), summary.bytes)
    }

    @Test fun unreadableMediaFailsWithoutReplacingExistingExportOrLeavingTemporaryFiles() {
        val output = temporary.newFile("preserve.mdpcollection").apply { writeText("previous export") }
        rejects { CollectionArchive.write(output, sample()) { throw IOException("Permission revoked") } }
        assertEquals("previous export", output.readText())
        assertEquals(listOf(output.name), temporary.root.listFiles()!!.map(File::getName))
    }

    @Test fun interruptedMediaStreamCleansUpPartialExport() {
        val output = File(temporary.root, "broken.mdpcollection")
        rejects { CollectionArchive.write(output, sample()) { object : ByteArrayInputStream(audio) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = throw IOException("Broken document")
        } } }
        assertFalse(output.exists())
        assertTrue(temporary.root.listFiles()!!.isEmpty())
    }

    @Test fun checksumMismatchIsRejectedBeforeImportAndOnlyStagingDirectoryIsRemoved() {
        val entries = exportedEntries()
        val media = entries.keys.first { it.startsWith("media/") }
        entries[media] = "changed".toByteArray()
        rejectedArchive(entries)
    }

    @Test fun missingMediaIsRejected() {
        val entries = exportedEntries()
        entries.remove(entries.keys.first { it.startsWith("media/") })
        rejectedArchive(entries)
    }

    @Test fun unreferencedMediaIsRejected() {
        val entries = exportedEntries()
        val extra = "unused content".toByteArray()
        val hash = sha256(extra)
        entries["media/$hash"] = extra
        updateManifest(entries) { it.getJSONArray("media").put(JSONObject().put("sha256", hash).put("size", extra.size)) }
        rejectedArchive(entries)
    }

    @Test fun undeclaredMediaIsRejected() {
        val entries = exportedEntries()
        val extra = "undeclared".toByteArray()
        entries["media/${sha256(extra)}"] = extra
        rejectedArchive(entries)
    }

    @Test fun traversalDirectoryAndUnexpectedFilesAreRejected() {
        listOf("../escape", "media/../escape", "/absolute", "media/", "unexpected.txt").forEach { path ->
            val entries = exportedEntries()
            entries[path] = byteArrayOf(1)
            rejectedArchive(entries)
        }
    }

    @Test fun duplicateZipEntryIsRejected() {
        // ZipOutputStream refuses duplicates, so change equal-length STORED entry names afterward.
        val entries = exportedEntries()
        entries["duplicate.xxx"] = entries.getValue("manifest.json")
        val raw = zip(entries, stored = true)
        val from = "duplicate.xxx".toByteArray()
        val to = "manifest.json".toByteArray()
        for (index in 0..raw.size - from.size) {
            if (from.indices.all { raw[index + it] == from[it] }) to.copyInto(raw, index)
        }
        rejectedBytes(raw)
    }

    @Test fun unsupportedArchiveAndNestedSchemaVersionsAreRejected() {
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.put("version", 2) },
            { it.put("type", "another-application") },
            { it.getJSONObject("data").getJSONObject("music").put("version", 2) },
            { it.getJSONObject("data").getJSONObject("youtube").put("version", "1") },
            { it.getJSONObject("data").getJSONObject("background").put("version", 2) },
        )
        edits.forEach { edit -> val entries = exportedEntries(); updateManifest(entries, edit); rejectedArchive(entries) }
    }

    @Test fun senderUriMalformedRowsAndDanglingPlaylistIdsAreRejectedRatherThanDropped() {
        val edits: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("data").getJSONObject("music").getJSONArray("tracks").getJSONObject(0).put("uri", audioUri) },
            { it.getJSONObject("data").getJSONObject("music").getJSONArray("tracks").getJSONObject(0).put("title", 123) },
            { it.getJSONObject("data").getJSONObject("music").getJSONArray("playlists").getJSONObject(0).getJSONArray("trackIds").put("missing") },
            { it.getJSONObject("data").getJSONObject("youtube").getJSONArray("links").getJSONObject(0).put("url", "https://invalid.example/song") },
            { it.getJSONObject("data").put("recorderDesignId", "unknown") },
            { it.getJSONObject("data").put("english", "true") },
            { it.getJSONObject("data").put("panelTransparency", 101) },
        )
        edits.forEach { edit -> val entries = exportedEntries(); updateManifest(entries, edit); rejectedArchive(entries) }
    }

    @Test fun mediaSizesAndDuplicateManifestDeclarationsAreValidated() {
        val entries = exportedEntries()
        updateManifest(entries) { it.getJSONArray("media").getJSONObject(0).put("size", 999) }
        rejectedArchive(entries)
        val duplicate = exportedEntries()
        updateManifest(duplicate) { val list = it.getJSONArray("media"); list.put(list.getJSONObject(0)) }
        rejectedArchive(duplicate)
    }

    @Test fun optionalNewSettingsDefaultForOlderCollections() {
        val entries = exportedEntries()
        updateManifest(entries) { it.getJSONObject("data").apply { remove("panelTransparency"); remove("brandSyncEnabled") } }
        val staged = CollectionArchive.stage(ByteArrayInputStream(zip(entries)), temporary.newFolder())
        assertEquals(0, staged.data.panelTransparency)
        assertTrue(staged.data.brandSyncEnabled)
    }

    @Test fun importLimitsBoundUncompressedDataManifestAndEntries() {
        val bytes = exported(sample()).readBytes()
        listOf(
            CollectionArchive.Limits(maxBytes = 4),
            CollectionArchive.Limits(maxManifestBytes = 32),
            CollectionArchive.Limits(maxEntries = 1),
        ).forEach { limits ->
            val directory = temporary.newFolder()
            rejects { CollectionArchive.stage(ByteArrayInputStream(bytes), directory, limits) }
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun exportLimitsDoNotLeaveAnIncompleteArchive() {
        listOf(
            CollectionArchive.Limits(maxBytes = 4),
            CollectionArchive.Limits(maxManifestBytes = 32),
            CollectionArchive.Limits(maxEntries = 1),
        ).forEachIndexed { index, limits ->
            val output = File(temporary.root, "limited-$index.mdpcollection")
            rejects { CollectionArchive.write(output, sample(), ::open, limits) }
            assertFalse(output.exists())
        }
    }

    @Test fun invalidExportAndCollapsingImportMappingsAreRejected() {
        val bad = sample().copy(music = sample().music.copy(favorites = setOf("missing")))
        rejects { exported(bad) }
        val source = sample().copy(background = BackgroundSettings(), music = sample().music.let { library ->
            library.copy(tracks = library.tracks + library.tracks.single().copy(id = "two", uri = secondUri))
        })
        val staged = CollectionArchive.stage(exported(source).inputStream(), temporary.newFolder())
        rejects { CollectionArchive.remap(staged.data) { "file:///outside" } }
        rejects { CollectionArchive.remap(staged.data) { "content://receiver/same-file" } }
        rejects { CollectionArchive.remap(source) { "content://receiver/one" } }
    }

    private fun sample() = CollectionData(
        music = LibrarySnapshot(
            listOf(Track("one", "Șoapte și dor", "MdP", "RemiX", 12_345, audioUri)),
            setOf("one"), listOf(Playlist("local", "Noapte", listOf("one"))),
        ),
        youtube = LinkLibrary(
            listOf(SavedMusicLink("yt", "Mix", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", LinkKind.YOUTUBE_VIDEO, true, 12, 34)),
            listOf(LinkPlaylist("links", "Favorite", listOf("yt"), true, 56, 78)),
        ),
        english = true,
        recorderDesignId = "studio_pro",
        background = BackgroundSettings(backgroundUri, BackgroundKind.GIF, BackgroundScale.FIT, 0.4f, true),
        panelTransparency = 37,
        brandSyncEnabled = false,
    )

    private fun open(uri: String) = ByteArrayInputStream(when (uri) {
        audioUri, secondUri -> audio
        backgroundUri -> background
        else -> throw IOException("Missing media")
    })

    private fun exported(data: CollectionData): File = File(temporary.root, "${java.util.UUID.randomUUID()}.mdpcollection").also {
        CollectionArchive.write(it, data, ::open)
    }
    private fun exportedEntries() = zipEntries(exported(sample()).readBytes())
    private fun updateManifest(entries: MutableMap<String, ByteArray>, edit: (JSONObject) -> Unit) {
        val root = JSONObject(entries.getValue("manifest.json").toString(Charsets.UTF_8))
        edit(root)
        entries["manifest.json"] = root.toString().toByteArray()
    }
    private fun rejectedArchive(entries: Map<String, ByteArray>) = rejectedBytes(zip(entries))
    private fun rejectedBytes(bytes: ByteArray) {
        val directory = temporary.newFolder()
        val untouched = File(directory, "existing-data").apply { writeText("keep") }
        rejects { CollectionArchive.stage(ByteArrayInputStream(bytes), directory) }
        assertEquals("keep", untouched.readText())
        assertEquals(listOf(untouched.name), directory.listFiles()!!.map(File::getName))
    }
    private fun rejects(action: () -> Unit) {
        var rejected = false
        try { action() } catch (_: Exception) { rejected = true }
        assertTrue("Expected rejection", rejected)
    }
    private fun zipEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) { val entry = zip.nextEntry ?: break; result[entry.name] = zip.readBytes(); zip.closeEntry() }
        }
        return result
    }
    private fun zip(entries: Map<String, ByteArray>, stored: Boolean = false): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                val entry = ZipEntry(name)
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
