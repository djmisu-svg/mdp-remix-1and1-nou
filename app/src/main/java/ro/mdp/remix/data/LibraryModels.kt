package ro.mdp.remix.data

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: String,
)

data class Playlist(val id: String, val name: String, val trackIds: List<String>)

data class LibrarySnapshot(
    val tracks: List<Track> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
)

data class ImportResult(val added: Int, val failed: Int)

/** Pure rules shared by persistence and mutations: no dangling or duplicate references. */
internal object LibraryRules {
    fun normalize(snapshot: LibrarySnapshot): LibrarySnapshot {
        val ids = mutableSetOf<String>()
        val uris = mutableSetOf<String>()
        val tracks = snapshot.tracks.filter { track ->
            if (track.id.isBlank() || !track.uri.startsWith("content://") || cleanLabel(track.title).isBlank() ||
                track.id in ids || track.uri in uris
            ) {
                false
            } else {
                ids.add(track.id)
                uris.add(track.uri)
                true
            }
        }.map {
            it.copy(
                title = cleanLabel(it.title),
                artist = cleanLabel(it.artist).ifBlank { "Artist necunoscut" },
                album = cleanLabel(it.album).ifBlank { "Album necunoscut" },
                durationMs = it.durationMs.coerceAtLeast(0L),
            )
        }
        val playlistIds = mutableSetOf<String>()
        val playlists = snapshot.playlists.mapNotNull { playlist ->
            val name = cleanLabel(playlist.name).take(128)
            if (playlist.id.isBlank() || name.isBlank() || !playlistIds.add(playlist.id)) {
                null
            } else {
                playlist.copy(name = name, trackIds = playlist.trackIds.distinct().filter(ids::contains))
            }
        }
        return LibrarySnapshot(tracks, snapshot.favorites.intersect(ids), playlists)
    }

    fun mergeTracks(snapshot: LibrarySnapshot, candidates: List<Track>): LibrarySnapshot =
        normalize(snapshot.copy(tracks = snapshot.tracks + candidates))

    fun toggleFavorite(snapshot: LibrarySnapshot, id: String): LibrarySnapshot {
        if (snapshot.tracks.none { it.id == id }) return snapshot
        val favorites = if (id in snapshot.favorites) snapshot.favorites - id else snapshot.favorites + id
        return snapshot.copy(favorites = favorites)
    }

    fun createPlaylist(snapshot: LibrarySnapshot, id: String, name: String): LibrarySnapshot {
        val cleanName = cleanLabel(name).take(128)
        if (id.isBlank() || cleanName.isBlank() || snapshot.playlists.any { it.id == id }) return snapshot
        return snapshot.copy(playlists = snapshot.playlists + Playlist(id, cleanName, emptyList()))
    }

    fun renamePlaylist(snapshot: LibrarySnapshot, id: String, name: String): LibrarySnapshot {
        val cleanName = cleanLabel(name).take(128)
        if (cleanName.isBlank()) return snapshot
        return snapshot.copy(playlists = snapshot.playlists.map { playlist ->
            if (playlist.id == id) playlist.copy(name = cleanName) else playlist
        })
    }

    fun movePlaylist(snapshot: LibrarySnapshot, id: String, direction: Int): LibrarySnapshot {
        val index = snapshot.playlists.indexOfFirst { it.id == id }
        val reordered = moveByOne(snapshot.playlists, index, direction)
        return if (reordered === snapshot.playlists) snapshot else snapshot.copy(playlists = reordered)
    }

    fun moveTrackInPlaylist(
        snapshot: LibrarySnapshot,
        playlistId: String,
        trackId: String,
        direction: Int,
    ): LibrarySnapshot = snapshot.copy(playlists = snapshot.playlists.map { playlist ->
        if (playlist.id != playlistId) playlist else {
            val reordered = moveByOne(playlist.trackIds, playlist.trackIds.indexOf(trackId), direction)
            if (reordered === playlist.trackIds) playlist else playlist.copy(trackIds = reordered)
        }
    })

    fun addToPlaylist(snapshot: LibrarySnapshot, playlistId: String, trackId: String): LibrarySnapshot {
        if (snapshot.tracks.none { it.id == trackId }) return snapshot
        return snapshot.copy(playlists = snapshot.playlists.map { playlist ->
            if (playlist.id == playlistId && trackId !in playlist.trackIds) {
                playlist.copy(trackIds = playlist.trackIds + trackId)
            } else playlist
        })
    }

    fun removeFromPlaylist(snapshot: LibrarySnapshot, playlistId: String, trackId: String): LibrarySnapshot =
        snapshot.copy(playlists = snapshot.playlists.map { playlist ->
            if (playlist.id == playlistId) playlist.copy(trackIds = playlist.trackIds - trackId) else playlist
        })

    fun deletePlaylist(snapshot: LibrarySnapshot, id: String): LibrarySnapshot =
        snapshot.copy(playlists = snapshot.playlists.filterNot { it.id == id })

    fun removeTrack(snapshot: LibrarySnapshot, id: String): LibrarySnapshot = normalize(
        snapshot.copy(tracks = snapshot.tracks.filterNot { it.id == id }),
    )

    private fun <T> moveByOne(items: List<T>, index: Int, direction: Int): List<T> {
        if (direction != -1 && direction != 1) return items
        val destination = index + direction
        if (index !in items.indices || destination !in items.indices) return items
        return items.toMutableList().apply { add(destination, removeAt(index)) }
    }

    fun cleanLabel(value: String?): String = value.orEmpty()
        .map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().take(250)
}
