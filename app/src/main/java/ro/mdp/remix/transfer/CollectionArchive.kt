package ro.mdp.remix.transfer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import ro.mdp.remix.background.BackgroundKind
import ro.mdp.remix.background.BackgroundSettings
import ro.mdp.remix.background.BackgroundSettingsJson
import ro.mdp.remix.background.BackgroundSettingsRules
import ro.mdp.remix.data.LibraryJson
import ro.mdp.remix.data.LibraryRules
import ro.mdp.remix.data.LibrarySnapshot
import ro.mdp.remix.data.Playlist
import ro.mdp.remix.data.Track
import ro.mdp.remix.links.LinkJson
import ro.mdp.remix.links.LinkKind
import ro.mdp.remix.links.LinkLibrary
import ro.mdp.remix.links.LinkPlaylist
import ro.mdp.remix.links.LinkRules
import ro.mdp.remix.links.SavedMusicLink

data class CollectionData(
    val music: LibrarySnapshot,
    val youtube: LinkLibrary,
    val english: Boolean,
    val recorderDesignId: String,
    val background: BackgroundSettings,
    val panelTransparency: Int = 0,
    val brandSyncEnabled: Boolean = true,
)

data class StagedCollection(val id: String, val data: CollectionData, val media: Map<String, File>)
data class ArchiveSummary(val musicTracks: Int, val youtubeLinks: Int, val playlists: Int, val bytes: Long)

/** Portable, versioned collections. Media is streamed; an archive never refers to the sender's files. */
object CollectionArchive {
    data class Limits(
        val maxBytes: Long = 2L * 1024 * 1024 * 1024,
        val maxManifestBytes: Int = 4 * 1024 * 1024,
        val maxEntries: Int = 10_000,
    ) {
        init { require(maxBytes > 0 && maxManifestBytes > 0 && maxEntries > 0) }
    }

    private const val TYPE = "mdp-remix-collection"
    private const val PREFIX = "content://mdp.transfer/media/"
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val portablePattern = Regex("^content://mdp\\.transfer/media/([0-9a-f]{64})\\?source=(0|[1-9][0-9]{0,8})$")
    private val designs = setOf("classic", "black", "red", "studio_pro")

    fun write(output: File, data: CollectionData, openMedia: (String) -> InputStream): ArchiveSummary =
        write(output, data, openMedia, Limits())

    fun write(output: File, data: CollectionData, openMedia: (String) -> InputStream, limits: Limits): ArchiveSummary {
        validate(data)
        val parent = requireNotNull(output.absoluteFile.parentFile)
        require(parent.isDirectory || parent.mkdirs()) { "Cannot create export directory." }
        val work = workDirectory(parent)
        try {
            val sourceUris = mediaUris(data)
            require(sourceUris.size < limits.maxEntries) { "Too many media files." }
            val budget = Budget(limits.maxBytes)
            val aliases = linkedMapOf<String, String>()
            val files = linkedMapOf<String, File>()
            sourceUris.forEachIndexed { index, uri ->
                val temporary = File(work, "source-$index")
                val digest = MessageDigest.getInstance("SHA-256")
                openMedia(uri).use { input -> temporary.outputStream().use { copy(input, it, budget, digest) } }
                val hash = digest.hex()
                val existing = files[hash]
                if (existing == null) {
                    val stored = File(work, hash)
                    check(temporary.renameTo(stored)) { "Cannot stage media." }
                    files[hash] = stored
                } else {
                    // Equal content is stored once, while URI aliases keep distinct tracks intact.
                    check(temporary.delete()) { "Cannot clean up duplicate media." }
                }
                aliases[uri] = "$PREFIX$hash?source=$index"
            }
            val portable = mapUris(data) { requireNotNull(aliases[it]) }
            validate(portable)
            val manifest = JSONObject().apply {
                put("type", TYPE)
                put("version", 1)
                put("id", UUID.randomUUID().toString())
                put("data", encodeData(portable))
                put("media", JSONArray().apply {
                    files.forEach { (hash, file) ->
                        put(JSONObject().put("sha256", hash).put("size", file.length()))
                    }
                })
            }.toString().toByteArray(Charsets.UTF_8)
            require(manifest.size <= limits.maxManifestBytes) { "Collection metadata is too large." }
            // The read limit applies to actual ZIP contents, including the manifest.
            require(files.values.sumOf(File::length) <= limits.maxBytes - manifest.size) { "Collection is too large." }
            val zip = File(work, "complete.zip")
            ZipOutputStream(zip.outputStream().buffered()).use { archive ->
                archive.putNextEntry(ZipEntry("manifest.json"))
                archive.write(manifest)
                archive.closeEntry()
                files.forEach { (hash, file) ->
                    archive.putNextEntry(ZipEntry("media/$hash"))
                    file.inputStream().use { it.copyTo(archive) }
                    archive.closeEntry()
                }
            }
            // Never truncate an existing export if a source cannot be read or validated.
            check(zip.renameTo(output.absoluteFile)) { "Cannot finish collection export." }
            return ArchiveSummary(data.music.tracks.size, data.youtube.links.size,
                data.music.playlists.size + data.youtube.playlists.size, output.length())
        } finally {
            work.deleteRecursively()
        }
    }

    fun stage(input: InputStream, directory: File): StagedCollection = stage(input, directory, Limits())

    fun stage(input: InputStream, directory: File, limits: Limits): StagedCollection {
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create import directory." }
        val work = workDirectory(directory)
        var accepted = false
        try {
            val entries = mutableSetOf<String>()
            val files = linkedMapOf<String, File>()
            var manifest: ByteArray? = null
            val budget = Budget(limits.maxBytes)
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(entries.size < limits.maxEntries && entries.add(entry.name)) { "Too many or duplicate archive entries." }
                    require(!entry.isDirectory) { "Unexpected archive directory." }
                    if (entry.name == "manifest.json") {
                        val bytes = ByteArrayOutputStream()
                        copy(zip, bytes, budget, maximum = limits.maxManifestBytes.toLong())
                        manifest = bytes.toByteArray()
                    } else {
                        require(entry.name.startsWith("media/")) { "Unexpected archive entry." }
                        val hash = entry.name.removePrefix("media/")
                        require(hashPattern.matches(hash)) { "Invalid media path." }
                        val file = File(work, hash)
                        val digest = MessageDigest.getInstance("SHA-256")
                        file.outputStream().use { copy(zip, it, budget, digest) }
                        require(digest.hex() == hash) { "Media checksum does not match." }
                        files[hash] = file
                    }
                    zip.closeEntry()
                }
            }
            val root = JSONObject(requireNotNull(manifest) { "The collection manifest is missing." }.toString(Charsets.UTF_8))
            require(root.string("type") == TYPE && root.integer("version") == 1L) { "Unsupported collection version." }
            val id = root.string("id")
            require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) { "Invalid collection identity." }
            val data = decodeData(root.getJSONObject("data"))
            validate(data)
            val declared = linkedMapOf<String, Long>()
            root.getJSONArray("media").forEachObject { row ->
                val hash = row.string("sha256")
                val size = row.integer("size")
                require(hashPattern.matches(hash) && size >= 0 && declared.put(hash, size) == null) { "Invalid media declaration." }
            }
            require(declared.keys == files.keys) { "Collection media is missing or unexpected." }
            declared.forEach { (hash, size) -> require(files.getValue(hash).length() == size) { "Media size does not match." } }
            val aliases = linkedMapOf<String, File>()
            val usedHashes = mutableSetOf<String>()
            mediaUris(data).forEach { uri ->
                val match = requireNotNull(portablePattern.matchEntire(uri)) { "A collection refers to files outside its archive." }
                val hash = match.groupValues[1]
                require(match.groupValues[2].toLong() < limits.maxEntries) { "Invalid media alias." }
                aliases[uri] = requireNotNull(files[hash]) { "Referenced media is missing." }
                usedHashes += hash
            }
            require(usedHashes == files.keys) { "The collection contains unreferenced media." }
            accepted = true
            return StagedCollection(id, data, aliases)
        } finally {
            if (!accepted) work.deleteRecursively()
        }
    }

    /** Called only after staging succeeds. Android supplies durable, receiver-owned content URIs. */
    fun remap(data: CollectionData, uriForPortable: (String) -> String): CollectionData {
        validate(data)
        val replacements = mediaUris(data).associateWith { uri ->
            require(portablePattern.matches(uri)) { "Expected a portable media URI." }
            uriForPortable(uri).also { require(BackgroundSettingsRules.isDocumentUri(it)) { "Invalid imported media URI." } }
        }
        return mapUris(data) { replacements.getValue(it) }.also(::validate)
    }

    private fun encodeData(data: CollectionData): JSONObject = JSONObject().apply {
        put("music", JSONObject(LibraryJson.encode(data.music)))
        put("youtube", JSONObject(LinkJson.encode(data.youtube)))
        put("english", data.english)
        put("recorderDesignId", data.recorderDesignId)
        put("background", JSONObject(BackgroundSettingsJson.encode(data.background)))
        put("panelTransparency", data.panelTransparency)
        put("brandSyncEnabled", data.brandSyncEnabled)
    }

    /** Import deliberately uses strict rows instead of the forgiving local corruption-recovery decoders. */
    private fun decodeData(root: JSONObject): CollectionData {
        val music = root.getJSONObject("music")
        require(music.integer("version") == 1L) { "Unsupported music-library version." }
        val tracks = mutableListOf<Track>()
        music.getJSONArray("tracks").forEachObject { row ->
            tracks += Track(row.string("id"), row.string("title"), row.string("artist"), row.string("album"),
                row.integer("durationMs"), row.string("uri"))
        }
        val favorites = music.getJSONArray("favorites").strings()
        require(favorites.distinct().size == favorites.size) { "Duplicate favorites." }
        val playlists = mutableListOf<Playlist>()
        music.getJSONArray("playlists").forEachObject { row ->
            playlists += Playlist(row.string("id"), row.string("name"), row.getJSONArray("trackIds").strings())
        }
        val youtube = root.getJSONObject("youtube")
        require(youtube.integer("version") == 1L) { "Unsupported link-library version." }
        val links = mutableListOf<SavedMusicLink>()
        youtube.getJSONArray("links").forEachObject { row ->
            links += SavedMusicLink(row.string("id"), row.string("title"), row.string("url"),
                LinkKind.valueOf(row.string("kind")), row.boolean("favorite"), row.integer("addedAt"), row.integer("lastOpenedAt"))
        }
        val linkPlaylists = mutableListOf<LinkPlaylist>()
        youtube.getJSONArray("playlists").forEachObject { row ->
            linkPlaylists += LinkPlaylist(row.string("id"), row.string("name"), row.getJSONArray("linkIds").strings(),
                row.boolean("favorite"), row.integer("createdAt"), row.integer("lastOpenedAt"))
        }
        val background = root.getJSONObject("background")
        require(background.integer("version") == 1L) { "Unsupported background version." }
        listOf("kind", "scale").forEach { background.string(it) }
        require(background.has("uri") && (background.isNull("uri") || background.get("uri") is String))
        require(background.get("dimming") is Number)
        background.boolean("animationEnabled")
        return CollectionData(
            LibrarySnapshot(tracks, favorites.toSet(), playlists), LinkLibrary(links, linkPlaylists),
            root.boolean("english"), root.string("recorderDesignId"), BackgroundSettingsJson.decode(background.toString()),
            if (root.has("panelTransparency")) root.integer("panelTransparency").also { require(it in 0L..100L) }.toInt() else 0,
            if (root.has("brandSyncEnabled")) root.boolean("brandSyncEnabled") else true,
        )
    }

    private fun validate(data: CollectionData) {
        require(LibraryRules.normalize(data.music) == data.music) { "Music library contains invalid or duplicate rows." }
        require(LinkRules.normalize(data.youtube) == data.youtube) { "YouTube library contains invalid or duplicate rows." }
        BackgroundSettingsRules.validate(data.background)
        require(data.recorderDesignId in designs) { "Unsupported recorder design." }
        require(data.panelTransparency in 0..100) { "Invalid panel transparency." }
    }

    private fun mediaUris(data: CollectionData): List<String> = buildList {
        data.music.tracks.forEach { add(it.uri) }
        if (data.background.kind != BackgroundKind.ORIGINAL) add(requireNotNull(data.background.uri))
    }.distinct()

    private fun mapUris(data: CollectionData, map: (String) -> String): CollectionData = data.copy(
        music = data.music.copy(tracks = data.music.tracks.map { it.copy(uri = map(it.uri)) }),
        background = if (data.background.kind == BackgroundKind.ORIGINAL) data.background
            else data.background.copy(uri = map(requireNotNull(data.background.uri))),
    )

    private fun workDirectory(parent: File): File {
        val file = File.createTempFile("mdp-collection-", ".tmp", parent)
        check(file.delete() && file.mkdir()) { "Cannot create collection staging directory." }
        return file
    }

    private class Budget(var remaining: Long)
    private fun copy(input: InputStream, output: OutputStream, budget: Budget, digest: MessageDigest? = null, maximum: Long = Long.MAX_VALUE) {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        while (true) {
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Collection transfer cancelled.")
            var count = input.read(buffer)
            if (count == -1) return
            if (count == 0) {
                val byte = input.read()
                if (byte == -1) return
                buffer[0] = byte.toByte()
                count = 1
            }
            require(count.toLong() <= budget.remaining && written <= maximum - count) { "Collection exceeds its size limit." }
            budget.remaining -= count
            written += count
            digest?.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun JSONObject.string(key: String): String = (get(key) as? String)
        ?: throw IllegalArgumentException("Invalid string: $key")
    private fun JSONObject.boolean(key: String): Boolean = (get(key) as? Boolean)
        ?: throw IllegalArgumentException("Invalid boolean: $key")
    private fun JSONObject.integer(key: String): Long {
        val value = get(key)
        require(value is Int || value is Long) { "Invalid integer: $key" }
        return (value as Number).toLong()
    }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { index ->
        (get(index) as? String) ?: throw IllegalArgumentException("Invalid string array.")
    }
    private fun JSONArray.forEachObject(action: (JSONObject) -> Unit) {
        (0 until length()).forEach { index -> action(get(index) as? JSONObject ?: throw IllegalArgumentException("Invalid object array.")) }
    }
}
