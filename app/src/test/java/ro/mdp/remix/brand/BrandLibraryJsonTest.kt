package ro.mdp.remix.brand

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.mdp.remix.links.LinkKind
import ro.mdp.remix.links.LinkLibrary
import ro.mdp.remix.links.LinkPlaylist
import ro.mdp.remix.links.SavedMusicLink

class BrandLibraryJsonTest {
    private val revision = "14f61291-051f-4b41-95f1-e75eff654f70"
    private val library = LinkLibrary(
        listOf(SavedMusicLink("a", "MdP", "https://www.youtube.com/watch?v=dQw4w9WgXcQ", LinkKind.YOUTUBE_VIDEO, true, 100, 200)),
        listOf(LinkPlaylist("p", "Mix", listOf("a"), true, 300, 400)),
    )

    @Test fun publicRoundTripRetainsFavoritesAndOrderButRemovesListeningHistory() {
        val decoded = BrandLibraryJson.decode(BrandLibraryJson.encode(library, revision))
        assertEquals(revision, decoded.revision)
        assertEquals(library.links.single().copy(addedAt = 0, lastOpenedAt = 0), decoded.library.links.single())
        assertEquals(library.playlists.single().copy(createdAt = 0, lastOpenedAt = 0), decoded.library.playlists.single())
    }

    @Test fun publicationExcludesSyncedMirrorsButRetainsBrandLinksUsedByPersonalPlaylists() {
        val retained = library.links.single().copy(id = "brand:link:used", url = "https://www.youtube.com/watch?v=abcdefghijk")
        val unused = retained.copy(id = "brand:link:unused", url = "https://www.youtube.com/watch?v=12345678901")
        val source = library.copy(links = library.links + retained + unused, playlists = listOf(
            library.playlists.single().copy(linkIds = listOf("a", retained.id)),
            LinkPlaylist("brand:playlist:mirror", "Brand mirror", listOf(unused.id)),
        ))
        val result = BrandLibraryJson.forPublication(source)
        assertEquals(listOf("p"), result.playlists.map { it.id })
        assertEquals(listOf("a", retained.id), result.links.map { it.id })
        assertEquals(source.playlists.first(), result.playlists.single())
        assertEquals(result, BrandLibraryJson.forPublication(result))
    }

    @Test(expected = IllegalArgumentException::class) fun futureSchemaCannotReplaceSavedLibrary() {
        BrandLibraryJson.decode(JSONObject(BrandLibraryJson.encode(library, revision)).put("schemaVersion", 2).toString())
    }

    @Test(expected = IllegalArgumentException::class) fun invalidRevisionIsRejected() {
        BrandLibraryJson.decode(JSONObject(BrandLibraryJson.encode(library, revision)).put("revision", "../../private").toString())
    }

    @Test(expected = IllegalArgumentException::class) fun oversizedUtf8InputIsRejectedBeforeJsonParsing() {
        BrandLibraryJson.decode("ș".repeat(BrandLibraryJson.MAX_BYTES / 2 + 1))
    }

    @Test fun releaseMustBeOfficialDedicatedPrereleaseWithExactAsset() {
        val result = BrandLibrarySource.assetFromRelease(release().toString())
        assertEquals(BrandLibrarySource.ASSET, result.url)
        assertEquals("a".repeat(64), result.sha256)
    }

    @Test(expected = IllegalArgumentException::class) fun stableReleaseCannotActAsBrandLibrary() {
        BrandLibrarySource.assetFromRelease(release().put("prerelease", false).toString())
    }

    @Test(expected = IllegalArgumentException::class) fun foreignAssetCannotBeFetched() {
        val release = release()
        release.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://example.com/library.json")
        BrandLibrarySource.assetFromRelease(release.toString())
    }

    @Test fun redirectedDownloadRequiresHttpsGithubAssetHostsWithoutCredentials() {
        assertTrue(BrandLibrarySource.allowedDownload(BrandLibrarySource.ASSET))
        assertTrue(BrandLibrarySource.allowedDownload("https://release-assets.githubusercontent.com/a/b?token=test"))
        assertFalse(BrandLibrarySource.allowedDownload("http://release-assets.githubusercontent.com/a/b"))
        assertFalse(BrandLibrarySource.allowedDownload("https://release-assets.githubusercontent.com.evil.example/a"))
        assertFalse(BrandLibrarySource.allowedDownload("https://private@release-assets.githubusercontent.com/a"))
        assertFalse(BrandLibrarySource.allowedDownload("https://github.com/other/project/releases/download/tag/a.json"))
    }

    private fun release() = JSONObject().put("tag_name", "brand-library").put("prerelease", true).put("draft", false)
        .put("html_url", "https://github.com/${BrandLibrarySource.REPOSITORY}/releases/tag/brand-library")
        .put("assets", org.json.JSONArray().put(JSONObject().put("name", "brand-library.json")
            .put("browser_download_url", BrandLibrarySource.ASSET).put("size", 500).put("digest", "sha256:${"a".repeat(64)}")))
}
