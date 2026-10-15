package ro.mdp.remix.data

import org.json.JSONArray
import org.json.JSONObject

/** Versioned on-disk representation. Invalid rows are discarded without losing valid songs. */
internal object LibraryJson {
    private const val VERSION = 1

    fun encode(snapshot: LibrarySnapshot): String {
        val clean = LibraryRules.normalize(snapshot)
        return JSONObject().apply {
            put("version", VERSION)
            put("tracks", JSONArray().apply {
                clean.tracks.forEach { track ->
                    put(JSONObject().apply {
                        put("id", track.id)
                        put("title", track.title)
                        put("artist", track.artist)
                        put("album", track.album)
                        put("durationMs", track.durationMs)
                        put("uri", track.uri)
                    })
                }
            })
            put("favorites", JSONArray(clean.favorites.toList()))
            put("playlists", JSONArray().apply {
                clean.playlists.forEach { playlist ->
                    put(JSONObject().apply {
                        put("id", playlist.id)
                        put("name", playlist.name)
                        put("trackIds", JSONArray(playlist.trackIds))
                    })
                }
            })
        }.toString()
    }

    fun decode(text: String): LibrarySnapshot {
        val root = JSONObject(text)
        if (root.has("version") && root.opt("version") != VERSION) {
            throw UnsupportedLibraryVersionException()
        }
        val tracks = root.optJSONArray("tracks").objects().mapNotNull { row ->
            val id = row.string("id")
            val title = row.string("title")
            val uri = row.string("uri")
            if (id.isBlank() || title.isBlank() || uri.isBlank()) null else Track(
                id = id,
                title = title,
                artist = row.string("artist").ifBlank { "Artist necunoscut" },
                album = row.string("album").ifBlank { "Album necunoscut" },
                durationMs = (row.opt("durationMs") as? Number)?.toLong() ?: 0L,
                uri = uri,
            )
        }
        val playlists = root.optJSONArray("playlists").objects().map { row ->
            Playlist(row.string("id"), row.string("name"), row.optJSONArray("trackIds").strings())
        }
        return LibraryRules.normalize(LibrarySnapshot(tracks, root.optJSONArray("favorites").strings().toSet(), playlists))
    }

    private fun JSONObject.string(key: String): String = opt(key) as? String ?: ""

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { index ->
            (opt(index) as? String)?.takeIf(String::isNotBlank)
        }
}

internal class UnsupportedLibraryVersionException : IllegalArgumentException(
    "Biblioteca a fost salvată de o versiune incompatibilă a aplicației. Datele au fost păstrate.",
)
