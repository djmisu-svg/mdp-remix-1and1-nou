package ro.mdp.remix.links

/** Pure library rules keep mutations and disk recovery consistent and testable. */
internal object LinkRules {
    private const val TITLE_LIMIT = 200
    private const val PLAYLIST_LIMIT = 80

    fun label(value: String): String = value.map { if (it.isISOControl()) ' ' else it }
        .joinToString("").trim().replace(Regex("\\s+"), " ")

    fun validTitle(value: String): String = validateLabel(value, TITLE_LIMIT, "Titlul")
    fun validPlaylistName(value: String): String = validateLabel(value, PLAYLIST_LIMIT, "Numele playlistului")

    private fun validateLabel(value: String, limit: Int, description: String): String {
        val clean = label(value)
        require(clean.isNotEmpty()) { "$description nu poate fi gol." }
        require(clean.length <= limit) { "$description poate avea cel mult $limit de caractere." }
        return clean
    }

    fun normalize(library: LinkLibrary): LinkLibrary {
        val links = mutableListOf<SavedMusicLink>()
        val aliases = mutableMapOf<String, String>()
        val urls = mutableMapOf<String, Int>()
        library.links.forEach { candidate ->
            if (candidate.id.isBlank() || candidate.id in aliases) return@forEach
            val target = YouTubeUrl.parse(candidate.url) ?: return@forEach
            val title = label(candidate.title).take(TITLE_LIMIT).takeIf(String::isNotEmpty) ?: return@forEach
            val existingIndex = urls[target.canonicalUrl]
            if (existingIndex != null) {
                val existing = links[existingIndex]
                aliases[candidate.id] = existing.id
                links[existingIndex] = existing.copy(
                    favorite = existing.favorite || candidate.favorite,
                    lastOpenedAt = maxOf(existing.lastOpenedAt, candidate.lastOpenedAt),
                )
            } else {
                aliases[candidate.id] = candidate.id
                urls[target.canonicalUrl] = links.size
                links += candidate.copy(
                    title = title,
                    url = target.canonicalUrl,
                    kind = target.kind,
                    addedAt = candidate.addedAt.coerceAtLeast(0),
                    lastOpenedAt = candidate.lastOpenedAt.coerceAtLeast(0),
                )
            }
        }
        val playlistIds = mutableSetOf<String>()
        val playlists = library.playlists.mapNotNull { playlist ->
            val name = label(playlist.name).take(PLAYLIST_LIMIT)
            if (playlist.id.isBlank() || name.isEmpty() || !playlistIds.add(playlist.id)) null else playlist.copy(
                name = name,
                linkIds = playlist.linkIds.mapNotNull(aliases::get).distinct(),
                createdAt = playlist.createdAt.coerceAtLeast(0),
                lastOpenedAt = playlist.lastOpenedAt.coerceAtLeast(0),
            )
        }
        return LinkLibrary(links, playlists)
    }

    fun addLink(library: LinkLibrary, id: String, title: String, url: String, playlistId: String?, now: Long): LinkLibrary {
        val cleanTitle = validTitle(title)
        val target = requireNotNull(YouTubeUrl.parse(url)) { "Introdu un link HTTPS YouTube valid către o melodie sau un playlist." }
        require(playlistId == null || library.playlists.any { it.id == playlistId }) { "Playlistul nu mai există." }
        val existing = library.links.firstOrNull { it.url == target.canonicalUrl }
        val added = existing ?: SavedMusicLink(id, cleanTitle, target.canonicalUrl, target.kind, addedAt = now)
        val next = if (existing == null) library.copy(links = library.links + added) else library
        return if (playlistId == null) next else addToPlaylist(next, playlistId, added.id)
    }

    fun renameLink(library: LinkLibrary, id: String, title: String): LinkLibrary {
        val clean = validTitle(title)
        return updateLink(library, id) { it.copy(title = clean) }
    }

    fun deleteLink(library: LinkLibrary, id: String): LinkLibrary = library.copy(
        links = library.links.filterNot { it.id == id },
        playlists = library.playlists.map { it.copy(linkIds = it.linkIds - id) },
    )

    fun toggleLinkFavorite(library: LinkLibrary, id: String): LinkLibrary =
        updateLink(library, id) { it.copy(favorite = !it.favorite) }

    fun createPlaylist(library: LinkLibrary, id: String, name: String, now: Long): LinkLibrary {
        require(id.isNotBlank() && library.playlists.none { it.id == id }) { "Identificator de playlist duplicat." }
        return library.copy(playlists = library.playlists + LinkPlaylist(id, validPlaylistName(name), emptyList(), createdAt = now))
    }

    fun renamePlaylist(library: LinkLibrary, id: String, name: String): LinkLibrary {
        val clean = validPlaylistName(name)
        return updatePlaylist(library, id) { it.copy(name = clean) }
    }

    fun deletePlaylist(library: LinkLibrary, id: String): LinkLibrary =
        library.copy(playlists = library.playlists.filterNot { it.id == id })

    fun togglePlaylistFavorite(library: LinkLibrary, id: String): LinkLibrary =
        updatePlaylist(library, id) { it.copy(favorite = !it.favorite) }

    fun addToPlaylist(library: LinkLibrary, playlistId: String, linkId: String): LinkLibrary {
        require(library.links.any { it.id == linkId }) { "Linkul nu mai există." }
        return updatePlaylist(library, playlistId) { it.copy(linkIds = (it.linkIds + linkId).distinct()) }
    }

    fun removeFromPlaylist(library: LinkLibrary, playlistId: String, linkId: String): LinkLibrary =
        updatePlaylist(library, playlistId) { it.copy(linkIds = it.linkIds - linkId) }

    fun movePlaylist(library: LinkLibrary, id: String, direction: Int): LinkLibrary =
        library.copy(playlists = move(library.playlists, library.playlists.indexOfFirst { it.id == id }, direction))

    fun moveLink(library: LinkLibrary, playlistId: String, linkId: String, direction: Int): LinkLibrary =
        updatePlaylist(library, playlistId) { it.copy(linkIds = move(it.linkIds, it.linkIds.indexOf(linkId), direction)) }

    fun markLinkOpened(library: LinkLibrary, id: String, now: Long): LinkLibrary =
        updateLink(library, id) { it.copy(lastOpenedAt = now.coerceAtLeast(0)) }

    fun markPlaylistOpened(library: LinkLibrary, id: String, now: Long): LinkLibrary =
        updatePlaylist(library, id) { it.copy(lastOpenedAt = now.coerceAtLeast(0)) }

    private fun updateLink(library: LinkLibrary, id: String, transform: (SavedMusicLink) -> SavedMusicLink): LinkLibrary {
        require(library.links.any { it.id == id }) { "Linkul nu mai există." }
        return library.copy(links = library.links.map { if (it.id == id) transform(it) else it })
    }

    private fun updatePlaylist(library: LinkLibrary, id: String, transform: (LinkPlaylist) -> LinkPlaylist): LinkLibrary {
        require(library.playlists.any { it.id == id }) { "Playlistul nu mai există." }
        return library.copy(playlists = library.playlists.map { if (it.id == id) transform(it) else it })
    }

    private fun <T> move(items: List<T>, index: Int, direction: Int): List<T> {
        require(direction == -1 || direction == 1) { "Direcția trebuie să fie -1 sau 1." }
        if (index !in items.indices || index + direction !in items.indices) return items
        return items.toMutableList().apply {
            val adjacent = get(index + direction)
            set(index + direction, get(index))
            set(index, adjacent)
        }
    }
}
