package ro.mdp.remix.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRulesTest {
    private val first = Track("one", "Prima melodie", "Artist", "Album", 120_000, "content://music/one")
    private val second = Track("two", "A doua melodie", "Artist", "Album", 90_000, "content://music/two")

    @Test fun deletingSongRemovesEveryReferenceWithoutAffectingOtherSongs() {
        val library = LibrarySnapshot(
            listOf(first, second), setOf("one", "two"),
            listOf(Playlist("p", "Călătorii", listOf("one", "two"))),
        )
        val result = LibraryRules.removeTrack(library, "one")
        assertEquals(listOf(second), result.tracks)
        assertEquals(setOf("two"), result.favorites)
        assertEquals(listOf("two"), result.playlists.single().trackIds)
    }

    @Test fun duplicateImportKeepsFirstSongAndOnlyAddsNewUri() {
        val result = LibraryRules.mergeTracks(
            LibrarySnapshot(listOf(first)),
            listOf(first.copy(title = "Changed"), first.copy(id = "different-id"), second),
        )
        assertEquals(listOf(first, second), result.tracks)
    }

    @Test fun addingToPlaylistIsIdempotentAndRejectsMissingSong() {
        val library = LibrarySnapshot(listOf(first), playlists = listOf(Playlist("p", "Mix", emptyList())))
        val once = LibraryRules.addToPlaylist(library, "p", "one")
        val twice = LibraryRules.addToPlaylist(once, "p", "one")
        assertEquals(once, twice)
        assertEquals(once, LibraryRules.addToPlaylist(once, "p", "missing"))
        assertEquals(once, LibraryRules.addToPlaylist(once, "missing", "one"))
    }

    @Test fun favoritesToggleAndDoNotAcceptUnknownSong() {
        val library = LibrarySnapshot(listOf(first))
        val favorite = LibraryRules.toggleFavorite(library, "one")
        assertTrue("one" in favorite.favorites)
        assertFalse("one" in LibraryRules.toggleFavorite(favorite, "one").favorites)
        assertEquals(library, LibraryRules.toggleFavorite(library, "missing"))
    }

    @Test fun normalizationRepairsDanglingReferencesAndInvalidRows() {
        val damaged = LibrarySnapshot(
            listOf(first.copy(durationMs = -1), first.copy(id = "duplicate-uri"), second.copy(id = "")),
            setOf("one", "missing"),
            listOf(Playlist("p", "  Mix  ", listOf("one", "one", "missing")), Playlist("p", "Duplicate", listOf("one")), Playlist("bad", " ", emptyList())),
        )
        val result = LibraryRules.normalize(damaged)
        assertEquals(listOf(first.copy(durationMs = 0)), result.tracks)
        assertEquals(setOf("one"), result.favorites)
        assertEquals(listOf(Playlist("p", "Mix", listOf("one"))), result.playlists)
        assertEquals(result, LibraryRules.normalize(result))
    }

    @Test fun playlistOperationsPreserveOrderAndRequireNonblankName() {
        val empty = LibrarySnapshot()
        assertEquals(empty, LibraryRules.createPlaylist(empty, "p", "  "))
        val created = LibraryRules.createPlaylist(empty, "p", "  Dans  ")
        assertEquals("Dans", created.playlists.single().name)
        assertEquals(empty, LibraryRules.deletePlaylist(created, "p"))
        val withSongs = created.copy(tracks = listOf(first, second), playlists = listOf(Playlist("p", "Dans", listOf("one", "two"))))
        assertEquals(listOf("two"), LibraryRules.removeFromPlaylist(withSongs, "p", "one").playlists.single().trackIds)
    }

    @Test fun persistedTracksMustUseLocalDocumentUris() {
        val result = LibraryRules.normalize(LibrarySnapshot(
            listOf(first, second.copy(uri = "https://example.com/song.mp3")),
            setOf("one", "two"),
            listOf(Playlist("p", "Offline", listOf("two", "one"))),
        ))
        assertEquals(listOf(first), result.tracks)
        assertEquals(setOf("one"), result.favorites)
        assertEquals(listOf("one"), result.playlists.single().trackIds)
    }

    @Test fun metadataAndPlaylistNamesAreCleanedAndBounded() {
        val result = LibraryRules.normalize(LibrarySnapshot(
            listOf(first.copy(title = "  Ploaie\nși dor  ", artist = "\n\t", album = " ")),
            playlists = listOf(Playlist("p", "  " + "ș".repeat(200), emptyList())),
        ))
        assertEquals("Ploaie și dor", result.tracks.single().title)
        assertEquals("Artist necunoscut", result.tracks.single().artist)
        assertEquals("Album necunoscut", result.tracks.single().album)
        assertEquals(128, result.playlists.single().name.length)
        assertEquals(250, LibraryRules.cleanLabel("a".repeat(300)).length)
        assertEquals(result, LibraryRules.normalize(result))
    }

    @Test fun renamingPlaylistCleansNameAndPreservesSongsAndOtherPlaylists() {
        val library = LibrarySnapshot(
            tracks = listOf(first, second),
            favorites = setOf("two"),
            playlists = listOf(Playlist("p", "Mix", listOf("two", "one")), Playlist("other", "Dans", listOf("one"))),
        )
        val renamed = LibraryRules.renamePlaylist(library, "p", "  Noapte\nși ritm  ")
        assertEquals("Noapte și ritm", renamed.playlists.first().name)
        assertEquals(listOf("two", "one"), renamed.playlists.first().trackIds)
        assertEquals(library.playlists.last(), renamed.playlists.last())
        assertEquals(library.tracks, renamed.tracks)
        assertEquals(library.favorites, renamed.favorites)
        assertEquals(library, LibraryRules.renamePlaylist(library, "p", "\n\t "))
        assertEquals(library, LibraryRules.renamePlaylist(library, "missing", "Valid"))
        assertEquals(128, LibraryRules.renamePlaylist(library, "p", "ș".repeat(200)).playlists.first().name.length)
    }

    @Test fun movingPlaylistSwapsOnlyAdjacentPlaylistsAndPreservesTheirContents() {
        val playlists = listOf(
            Playlist("first", "Primul", listOf("one")),
            Playlist("second", "Al doilea", listOf("two", "one")),
            Playlist("third", "Al treilea", emptyList()),
        )
        val library = LibrarySnapshot(listOf(first, second), setOf("two"), playlists)
        val movedUp = LibraryRules.movePlaylist(library, "second", -1)
        assertEquals(listOf(playlists[1], playlists[0], playlists[2]), movedUp.playlists)
        assertEquals(library, LibraryRules.movePlaylist(movedUp, "second", 1))
        assertEquals(library.tracks, movedUp.tracks)
        assertEquals(library.favorites, movedUp.favorites)
    }

    @Test fun playlistOrderingRejectsUnknownIdsInvalidDirectionsAndBoundaryMoves() {
        val library = LibrarySnapshot(playlists = listOf(
            Playlist("first", "Primul", emptyList()), Playlist("last", "Ultimul", emptyList()),
        ))
        assertEquals(library, LibraryRules.movePlaylist(library, "first", -1))
        assertEquals(library, LibraryRules.movePlaylist(library, "last", 1))
        assertEquals(library, LibraryRules.movePlaylist(library, "missing", 1))
        listOf(0, 2, -2, Int.MIN_VALUE, Int.MAX_VALUE).forEach { direction ->
            assertEquals(library, LibraryRules.movePlaylist(library, "first", direction))
        }
    }

    @Test fun movingSongChangesOnlyItsPlaylistOrderAndPersistsThroughNormalization() {
        val third = first.copy(id = "three", uri = "content://music/three")
        val library = LibrarySnapshot(
            tracks = listOf(first, second, third),
            favorites = setOf("one"),
            playlists = listOf(
                Playlist("p", "Mix", listOf("one", "two", "three")),
                Playlist("other", "Alt mix", listOf("two", "one")),
            ),
        )
        val moved = LibraryRules.moveTrackInPlaylist(library, "p", "two", 1)
        assertEquals(listOf("one", "three", "two"), moved.playlists.first().trackIds)
        assertEquals(library.playlists.last(), moved.playlists.last())
        assertEquals(library.tracks, moved.tracks)
        assertEquals(library.favorites, moved.favorites)
        assertEquals(moved, LibraryRules.normalize(moved))
        assertEquals(library, LibraryRules.moveTrackInPlaylist(moved, "p", "two", -1))
    }

    @Test fun songOrderingRejectsMissingEntriesInvalidDirectionsAndBoundaryMoves() {
        val library = LibrarySnapshot(
            tracks = listOf(first, second),
            playlists = listOf(Playlist("p", "Mix", listOf("one", "two"))),
        )
        assertEquals(library, LibraryRules.moveTrackInPlaylist(library, "p", "one", -1))
        assertEquals(library, LibraryRules.moveTrackInPlaylist(library, "p", "two", 1))
        assertEquals(library, LibraryRules.moveTrackInPlaylist(library, "missing", "one", 1))
        assertEquals(library, LibraryRules.moveTrackInPlaylist(library, "p", "missing", 1))
        listOf(0, 2, -2, Int.MIN_VALUE, Int.MAX_VALUE).forEach { direction ->
            assertEquals(library, LibraryRules.moveTrackInPlaylist(library, "p", "one", direction))
        }
    }
}
