package ro.mdp.remix.transfer

import java.security.MessageDigest
import java.util.UUID
import ro.mdp.remix.data.LibraryRules
import ro.mdp.remix.data.LibrarySnapshot
import ro.mdp.remix.links.LinkLibrary
import ro.mdp.remix.links.LinkRules

/** Transfers append to a recipient's library; a repeated package never duplicates it. */
object CollectionMerge {
    fun music(recipient: LibrarySnapshot, incoming: LibrarySnapshot, transferId: String): LibrarySnapshot {
        val namespace = transferNamespace(transferId)
        val saved = LibraryRules.normalize(recipient)
        val source = LibraryRules.normalize(incoming)
        val tracks = saved.tracks.toMutableList()
        val byUri = tracks.associateByTo(mutableMapOf()) { it.uri }
        val byId = tracks.associateByTo(mutableMapOf()) { it.id }
        val aliases = mutableMapOf<String, String>()
        source.tracks.forEach { track ->
            val id = namespacedId(namespace, "track", track.id)
            val existing = byUri[track.uri] ?: byId[id]
            if (existing != null) {
                aliases[track.id] = existing.id
            } else {
                val added = track.copy(id = id)
                tracks += added
                byUri[added.uri] = added
                byId[id] = added
                aliases[track.id] = id
            }
        }
        val playlists = saved.playlists.toMutableList()
        source.playlists.forEach { playlist ->
            val id = namespacedId(namespace, "audio-playlist", playlist.id)
            val members = playlist.trackIds.mapNotNull(aliases::get).distinct()
            val index = playlists.indexOfFirst { it.id == id }
            if (index < 0) playlists += playlist.copy(id = id, trackIds = members)
            else playlists[index] = playlists[index].copy(trackIds = (playlists[index].trackIds + members).distinct())
        }
        return LibraryRules.normalize(LibrarySnapshot(
            tracks = tracks,
            favorites = saved.favorites + source.favorites.mapNotNull(aliases::get),
            playlists = playlists,
        ))
    }

    fun youtube(recipient: LinkLibrary, incoming: LinkLibrary, transferId: String): LinkLibrary {
        val namespace = transferNamespace(transferId)
        val saved = LinkRules.normalize(recipient)
        val source = LinkRules.normalize(incoming)
        val links = saved.links.toMutableList()
        val byUrl = links.withIndex().associateTo(mutableMapOf()) { it.value.url to it.index }
        val aliases = mutableMapOf<String, String>()
        source.links.forEach { link ->
            val index = byUrl[link.url]
            if (index != null) {
                val existing = links[index]
                links[index] = existing.copy(
                    favorite = existing.favorite || link.favorite,
                    lastOpenedAt = maxOf(existing.lastOpenedAt, link.lastOpenedAt),
                )
                aliases[link.id] = existing.id
            } else {
                val added = link.copy(id = namespacedId(namespace, "link", link.id))
                byUrl[added.url] = links.size
                links += added
                aliases[link.id] = added.id
            }
        }
        val playlists = saved.playlists.toMutableList()
        source.playlists.forEach { playlist ->
            val id = namespacedId(namespace, "link-playlist", playlist.id)
            val members = playlist.linkIds.mapNotNull(aliases::get).distinct()
            val index = playlists.indexOfFirst { it.id == id }
            if (index < 0) playlists += playlist.copy(id = id, linkIds = members)
            else {
                val existing = playlists[index]
                playlists[index] = existing.copy(
                    linkIds = (existing.linkIds + members).distinct(),
                    favorite = existing.favorite || playlist.favorite,
                    lastOpenedAt = maxOf(existing.lastOpenedAt, playlist.lastOpenedAt),
                )
            }
        }
        return LinkRules.normalize(LinkLibrary(links, playlists))
    }

    /** Only the explicitly managed brand namespace is replaced by an online refresh. */
    fun syncBrand(recipient: LinkLibrary, incoming: LinkLibrary): LinkLibrary {
        val saved = LinkRules.normalize(recipient)
        val source = LinkRules.normalize(incoming)
        val personalPlaylists = saved.playlists.filterNot { it.id.startsWith(BRAND_PLAYLIST_PREFIX) }
        val personalReferences = personalPlaylists.flatMapTo(mutableSetOf()) { it.linkIds }
        val links = saved.links.filter {
            !it.id.startsWith(BRAND_LINK_PREFIX) || it.id in personalReferences
        }.toMutableList()
        val byUrl = links.withIndex().associateTo(mutableMapOf()) { it.value.url to it.index }
        val aliases = mutableMapOf<String, String>()
        source.links.forEach { link ->
            val id = BRAND_LINK_PREFIX + digest(link.id)
            val index = byUrl[link.url]
            val previous = saved.links.firstOrNull { it.id == id }
            if (index == null) {
                val added = link.copy(
                    id = id,
                    favorite = link.favorite || previous?.favorite == true,
                    lastOpenedAt = maxOf(link.lastOpenedAt, previous?.lastOpenedAt ?: 0L),
                )
                val retainedIndex = links.indexOfFirst { it.id == id }
                if (retainedIndex < 0) {
                    byUrl[link.url] = links.size
                    links += added
                } else {
                    byUrl.remove(links[retainedIndex].url)
                    links[retainedIndex] = added
                    byUrl[link.url] = retainedIndex
                }
                aliases[link.id] = added.id
            } else {
                val existing = links[index]
                if (existing.id.startsWith(BRAND_LINK_PREFIX)) links[index] = link.copy(
                    id = existing.id,
                    favorite = link.favorite || existing.favorite,
                    lastOpenedAt = maxOf(link.lastOpenedAt, existing.lastOpenedAt),
                )
                // A personal duplicate keeps its title, favorites and history intact.
                aliases[link.id] = existing.id
            }
        }
        val brandPlaylists = source.playlists.map { playlist ->
            val id = BRAND_PLAYLIST_PREFIX + digest(playlist.id)
            val previous = saved.playlists.firstOrNull { it.id == id }
            playlist.copy(
                id = id,
                linkIds = playlist.linkIds.mapNotNull(aliases::get).distinct(),
                favorite = playlist.favorite || previous?.favorite == true,
                lastOpenedAt = maxOf(playlist.lastOpenedAt, previous?.lastOpenedAt ?: 0L),
            )
        }
        return LinkRules.normalize(LinkLibrary(links, personalPlaylists + brandPlaylists))
    }

    private fun transferNamespace(value: String): String {
        val uuid = UUID.fromString(value)
        require(uuid.toString().equals(value, ignoreCase = true)) { "Invalid collection transfer identifier." }
        return uuid.toString()
    }

    private fun namespacedId(transferId: String, kind: String, sourceId: String): String =
        "shared:" + digest("$transferId\u0000$kind\u0000$sourceId")

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private const val BRAND_LINK_PREFIX = "brand:link:"
    private const val BRAND_PLAYLIST_PREFIX = "brand:playlist:"
}
