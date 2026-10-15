package ro.mdp.remix.links

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Test

class LinkJsonTest {
    @Test fun metadataFavoritesHistoryAndManualOrderSurviveRoundTrip() {
        val snapshot = LinkLibrary(
            listOf(
                SavedMusicLink("link1", "MdP • Și tu", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", LinkKind.YOUTUBE_VIDEO, true, 100, 300),
                SavedMusicLink("link2", "Remix 1&1", "https://www.youtube.com/playlist?list=PLabcdefghij", LinkKind.YOUTUBE_PLAYLIST, false, 200, 400),
            ),
            listOf(
                LinkPlaylist("p2", "Seară", listOf("link2", "link1"), true, 500, 600),
                LinkPlaylist("p1", "Dimineață", listOf("link1"), false, 700, 800),
            ),
        )
        assertEquals(snapshot, LinkJson.decode(LinkJson.encode(snapshot)))
    }

    @Test fun emptyLibrarySurvivesRoundTrip() {
        assertEquals(LinkLibrary(), LinkJson.decode(LinkJson.encode(LinkLibrary())))
    }

    @Test fun invalidRowsAreIgnoredWhileValidMetadataIsRecovered() {
        val text = """{
          "version": 1,
          "links": [null, 8, {"id":"bad","title":"Bad","url":"https://evil.example"},
            {"id":"good","title":"  Bun  ","url":"https://youtu.be/dQw4w9WgXcQ?si=tracking","kind":"UNKNOWN","favorite":true}],
          "playlists": [{"id":"p","name":"Set","linkIds":["bad","good","good",null,42]}]
        }""".trimIndent()
        val result = LinkJson.decode(text)
        assertEquals(listOf("good"), result.links.map { it.id })
        assertEquals("Bun", result.links.single().title)
        assertEquals(LinkKind.YOUTUBE_VIDEO, result.links.single().kind)
        assertEquals(true, result.links.single().favorite)
        assertEquals(listOf("good"), result.playlists.single().linkIds)
    }

    @Test fun wrongScalarTypesDoNotBecomeMisleadingStringsOrBooleans() {
        val result = LinkJson.decode("""{"version":1,"links":[
          {"id":"one","title":123,"url":"https://youtu.be/dQw4w9WgXcQ"},
          {"id":"two","title":"Bun","url":"https://youtu.be/abcdefghijk","favorite":"true","addedAt":-9,"lastOpenedAt":"123"}
        ],"playlists":[]}""")
        assertEquals(1, result.links.size)
        assertEquals(false, result.links.single().favorite)
        assertEquals(0L, result.links.single().addedAt)
        assertEquals(0L, result.links.single().lastOpenedAt)
    }

    @Test fun duplicateUrlsRemapPlaylistReferencesOnDecode() {
        val result = LinkJson.decode("""{"version":1,"links":[
          {"id":"one","title":"Unu","url":"https://youtube.com/watch?v=dQw4w9WgXcQ"},
          {"id":"two","title":"Doi","url":"https://youtu.be/dQw4w9WgXcQ","favorite":true}
        ],"playlists":[{"id":"p","name":"Set","linkIds":["two","one"]}]}""")
        assertEquals(1, result.links.size)
        assertEquals(true, result.links.single().favorite)
        assertEquals(listOf("one"), result.playlists.single().linkIds)
    }

    @Test(expected = UnsupportedLinkVersionException::class) fun futureSchemasAreRejectedWithoutDowngrading() {
        LinkJson.decode("""{"version":2,"links":[],"playlists":[]}""")
    }

    @Test(expected = JSONException::class) fun missingArraysAreCorruptionRatherThanAnEmptyLibrary() {
        LinkJson.decode("""{"version":1,"links":[]}""")
    }

    @Test(expected = JSONException::class) fun missingVersionIsCorruption() {
        LinkJson.decode("""{"links":[],"playlists":[]}""")
    }

    @Test(expected = JSONException::class) fun malformedJsonIsRejected() {
        LinkJson.decode("{not-json")
    }
}
