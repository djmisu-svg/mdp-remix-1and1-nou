package ro.mdp.remix.links

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Versioned JSON. Non-link rows are ignored; unsupported schemas never get overwritten. */
internal object LinkJson {
    private const val VERSION = 1

    fun encode(library: LinkLibrary): String {
        val clean = LinkRules.normalize(library)
        return JSONObject().apply {
            put("version", VERSION)
            put("links", JSONArray().apply {
                clean.links.forEach { link ->
                    put(JSONObject().apply {
                        put("id", link.id)
                        put("title", link.title)
                        put("url", link.url)
                        put("kind", link.kind.name)
                        put("favorite", link.favorite)
                        put("addedAt", link.addedAt)
                        put("lastOpenedAt", link.lastOpenedAt)
                    })
                }
            })
            put("playlists", JSONArray().apply {
                clean.playlists.forEach { playlist ->
                    put(JSONObject().apply {
                        put("id", playlist.id)
                        put("name", playlist.name)
                        put("linkIds", JSONArray(playlist.linkIds))
                        put("favorite", playlist.favorite)
                        put("createdAt", playlist.createdAt)
                        put("lastOpenedAt", playlist.lastOpenedAt)
                    })
                }
            })
        }.toString()
    }

    fun decode(text: String): LinkLibrary {
        val root = JSONObject(text)
        if (!root.has("version")) throw JSONException("Missing link-library schema version")
        if (root.opt("version") != VERSION) throw UnsupportedLinkVersionException()
        // A truncated or wrong top-level shape is corruption, not an empty library.
        val links = root.getJSONArray("links").objects().mapNotNull { row ->
            val target = YouTubeUrl.parse(row.string("url")) ?: return@mapNotNull null
            SavedMusicLink(
                id = row.string("id"),
                title = row.string("title"),
                url = target.canonicalUrl,
                kind = target.kind,
                favorite = row.opt("favorite") as? Boolean ?: false,
                addedAt = row.long("addedAt"),
                lastOpenedAt = row.long("lastOpenedAt"),
            )
        }
        val playlists = root.getJSONArray("playlists").objects().map { row ->
            LinkPlaylist(
                id = row.string("id"),
                name = row.string("name"),
                linkIds = row.optJSONArray("linkIds").strings(),
                favorite = row.opt("favorite") as? Boolean ?: false,
                createdAt = row.long("createdAt"),
                lastOpenedAt = row.long("lastOpenedAt"),
            )
        }
        return LinkRules.normalize(LinkLibrary(links, playlists))
    }

    private fun JSONObject.string(key: String): String = opt(key) as? String ?: ""
    private fun JSONObject.long(key: String): Long = (opt(key) as? Number)?.toLong()?.coerceAtLeast(0) ?: 0L
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { opt(it) as? String }
}

internal class UnsupportedLinkVersionException : IllegalArgumentException(
    "Biblioteca de linkuri are o versiune incompatibilă. Fișierul original a fost păstrat și salvarea este oprită.",
)
