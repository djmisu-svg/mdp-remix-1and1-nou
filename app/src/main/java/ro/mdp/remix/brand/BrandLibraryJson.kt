package ro.mdp.remix.brand

import org.json.JSONObject
import ro.mdp.remix.links.LinkJson
import ro.mdp.remix.links.LinkLibrary
import java.util.UUID

data class BrandLibrarySnapshot(val revision: String, val library: LinkLibrary)

/** Public YouTube metadata only. Local media, background URIs and credentials have no fields here. */
object BrandLibraryJson {
    const val MAX_BYTES = 1024 * 1024

    /** A synced mirror must not be republished beside the owner's original curated playlists. */
    fun forPublication(library: LinkLibrary): LinkLibrary {
        val personalPlaylists = library.playlists.filterNot { it.id.startsWith("brand:playlist:") }
        val usedByPersonal = personalPlaylists.flatMap { it.linkIds }.toSet()
        return library.copy(
            playlists = personalPlaylists,
            links = library.links.filter { !it.id.startsWith("brand:link:") || it.id in usedByPersonal },
        )
    }

    fun encode(library: LinkLibrary, revision: String = UUID.randomUUID().toString()): String {
        validateRevision(revision)
        // Listening history is private to the device and is never part of the public library.
        val publicLibrary = library.copy(
            links = library.links.map { it.copy(addedAt = 0, lastOpenedAt = 0) },
            playlists = library.playlists.map { it.copy(createdAt = 0, lastOpenedAt = 0) },
        )
        return JSONObject().put("schemaVersion", 1).put("revision", revision)
            .put("library", JSONObject(LinkJson.encode(publicLibrary))).toString().also(::checkSize)
    }

    fun decode(text: String): BrandLibrarySnapshot {
        checkSize(text)
        val root = JSONObject(text)
        require(root.opt("schemaVersion") == 1) { "Unsupported brand library schema." }
        val revision = root.getString("revision")
        validateRevision(revision)
        val library = LinkJson.decode(root.getJSONObject("library").toString())
        return BrandLibrarySnapshot(revision, library)
    }

    private fun checkSize(text: String) {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Brand library exceeds 1 MiB." }
    }

    private fun validateRevision(revision: String) {
        require(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
            .matches(revision)) { "Invalid brand library revision." }
    }
}
