package ro.mdp.remix.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.json.JSONException
import org.junit.Test

class LibraryJsonTest {
    @Test fun roundTripRetainsUnicodeFavoritesAndPlaylistOrder() {
        val first = Track("one", "Ploaie, \"șoapte\" și dor", "Ștefan", "Acasă", 135_500, "content://audio/document/1")
        val second = Track("two", "Înapoi", "Mélodie", "Noapte", 65_000, "content://audio/document/2")
        val snapshot = LibrarySnapshot(listOf(first, second), setOf("two"), listOf(Playlist("p", "Seară", listOf("two", "one"))))
        assertEquals(snapshot, LibraryJson.decode(LibraryJson.encode(snapshot)))
    }

    @Test fun decodingPartialRowsKeepsValidDataAndRemovesDanglingIds() {
        val result = LibraryJson.decode("""
            {
              "version": 1,
              "tracks": [
                {"id":"one","title":"Melodie","uri":"content://audio/1","durationMs":-99},
                {"id":"invalid","title":"Fără URI"},
                null
              ],
              "favorites": ["one", "missing", 3],
              "playlists": [{"id":"p","name":"Mix","trackIds":["one","missing","one"]}]
            }
        """.trimIndent())
        assertEquals(1, result.tracks.size)
        assertEquals(0L, result.tracks.single().durationMs)
        assertEquals("Artist necunoscut", result.tracks.single().artist)
        assertEquals(setOf("one"), result.favorites)
        assertEquals(listOf("one"), result.playlists.single().trackIds)
    }

    @Test fun emptyObjectLoadsAsEmptyLibrary() {
        assertEquals(LibrarySnapshot(), LibraryJson.decode("{}"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedVersionIsRejectedInsteadOfSilentlyOverwritten() {
        LibraryJson.decode("""{"version":99,"tracks":[]}""")
    }

    @Test fun serializedDataHasExplicitSchemaVersion() {
        assertTrue(LibraryJson.encode(LibrarySnapshot()).contains("\"version\":1"))
    }

    @Test fun wrongFieldTypesAreDiscardedWithoutStringifyingObjectsOrNull() {
        val result = LibraryJson.decode("""
            {"version":1,"tracks":[
              {"id":null,"title":"Bad","uri":"content://audio/1"},
              {"id":"bad","title":{"value":"Bad"},"uri":"content://audio/2"},
              {"id":"good","title":"Bun","uri":"content://audio/3","artist":null,"album":3,"durationMs":"90"}
            ],"playlists":[
              {"id":"p","name":null,"trackIds":["good"]},
              {"id":"q","name":"Păstrat","trackIds":[{},null,"good"]}
            ]}
        """.trimIndent())
        assertEquals(listOf("good"), result.tracks.map { it.id })
        assertEquals("Artist necunoscut", result.tracks.single().artist)
        assertEquals("Album necunoscut", result.tracks.single().album)
        assertEquals(0L, result.tracks.single().durationMs)
        assertEquals(listOf(Playlist("q", "Păstrat", listOf("good"))), result.playlists)
    }

    @Test(expected = UnsupportedLibraryVersionException::class)
    fun malformedVersionIsRejectedInsteadOfAssumedCurrent() {
        LibraryJson.decode("""{"version":"unknown"}""")
    }

    @Test(expected = JSONException::class)
    fun truncatedJsonIsRejectedForRepositoryRecovery() {
        LibraryJson.decode("""{"version":1,"tracks":[""")
    }

    @Test fun writingAndReadingRepairDuplicatesAndReferencesConsistently() {
        val track = Track("one", "O melodie", "", "", -10, "content://audio/1")
        val damaged = LibrarySnapshot(
            listOf(track, track.copy(id = "duplicate")),
            setOf("one", "duplicate", "missing"),
            listOf(Playlist("p", "  Mix  ", listOf("one", "missing", "one"))),
        )
        val restored = LibraryJson.decode(LibraryJson.encode(damaged))
        assertEquals(LibraryRules.normalize(damaged), restored)
        assertEquals(setOf("one"), restored.favorites)
        assertEquals(listOf("one"), restored.playlists.single().trackIds)
    }

    @Test fun editedPlaylistNamesAndCustomOrdersSurvivePersistence() {
        val one = Track("one", "Unu", "Artist", "Album", 1_000, "content://audio/1")
        val two = one.copy(id = "two", title = "Doi", uri = "content://audio/2")
        val initial = LibrarySnapshot(
            tracks = listOf(one, two),
            playlists = listOf(Playlist("p", "Mix", listOf("one", "two")), Playlist("q", "Alt mix", emptyList())),
        )
        val renamed = LibraryRules.renamePlaylist(initial, "p", "Noapte și ritm")
        val reorderedSongs = LibraryRules.moveTrackInPlaylist(renamed, "p", "two", -1)
        val reorderedPlaylists = LibraryRules.movePlaylist(reorderedSongs, "q", -1)
        val restored = LibraryJson.decode(LibraryJson.encode(reorderedPlaylists))
        assertEquals(reorderedPlaylists, restored)
        assertEquals(listOf("q", "p"), restored.playlists.map { it.id })
        assertEquals("Noapte și ritm", restored.playlists.last().name)
        assertEquals(listOf("two", "one"), restored.playlists.last().trackIds)
    }
}
