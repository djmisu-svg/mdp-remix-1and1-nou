package ro.mdp.remix.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRulesTest {
    private fun link(id: String, video: String = "dQw4w9WgXcQ") =
        SavedMusicLink(id, "Melodie $id", "https://www.youtube.com/watch?v=$video", LinkKind.YOUTUBE_VIDEO)
    private fun library() = LinkLibrary(
        listOf(link("one"), link("two", "abcdefghijk"), link("three", "1234567890_")),
        listOf(LinkPlaylist("p1", "Set unu", listOf("one", "two", "three")), LinkPlaylist("p2", "Set doi", emptyList())),
    )

    @Test fun addLinkCanonicalizesAndStoresCreationTime() {
        val result = LinkRules.addLink(LinkLibrary(), "new", "  Melodia mea  ", "https://youtu.be/dQw4w9WgXcQ?si=abc", null, 1234)
        assertEquals("Melodia mea", result.links.single().title)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", result.links.single().url)
        assertEquals(1234L, result.links.single().addedAt)
    }

    @Test fun duplicateCanonicalLinkAddsExistingEntryToCollectionWithoutDuplicating() {
        val original = library()
        val result = LinkRules.addLink(original, "duplicate", "Another title", "https://youtu.be/dQw4w9WgXcQ", "p2", 500)
        assertEquals(original.links, result.links)
        assertEquals(listOf("one"), result.playlists[1].linkIds)
    }

    @Test fun playlistOperationsPreserveOrderAndReferences() {
        val original = library()
        val added = LinkRules.addToPlaylist(original, "p2", "two")
        val addedTwice = LinkRules.addToPlaylist(added, "p2", "two")
        assertEquals(listOf("two"), addedTwice.playlists[1].linkIds)
        assertEquals(emptyList<String>(), LinkRules.removeFromPlaylist(addedTwice, "p2", "two").playlists[1].linkIds)
    }

    @Test fun renameCreateAndDeletePlaylistLeaveSavedLinksIntact() {
        val original = library()
        val created = LinkRules.createPlaylist(original, "p3", "  Nou  ", 100)
        assertEquals("Nou", created.playlists.last().name)
        assertEquals(100L, created.playlists.last().createdAt)
        val renamed = LinkRules.renamePlaylist(created, "p3", "  Seară\n de\t vară  ")
        assertEquals("Seară de vară", renamed.playlists.last().name)
        val deleted = LinkRules.deletePlaylist(renamed, "p3")
        assertEquals(original, deleted)
    }

    @Test fun deletingLinkRemovesEveryCollectionReference() {
        val original = LinkRules.addToPlaylist(library(), "p2", "one")
        val result = LinkRules.deleteLink(original, "one")
        assertFalse(result.links.any { it.id == "one" })
        assertTrue(result.playlists.all { "one" !in it.linkIds })
        assertEquals(listOf("two", "three"), result.playlists.first().linkIds)
    }

    @Test fun linkAndPlaylistFavoritesAreIndependentAndToggle() {
        val favored = LinkRules.togglePlaylistFavorite(LinkRules.toggleLinkFavorite(library(), "one"), "p1")
        assertTrue(favored.links.first().favorite)
        assertTrue(favored.playlists.first().favorite)
        assertFalse(favored.links[1].favorite)
        val unfavored = LinkRules.togglePlaylistFavorite(LinkRules.toggleLinkFavorite(favored, "one"), "p1")
        assertEquals(library(), unfavored)
    }

    @Test fun recentHistoryPersistsIndependentlyForLinksAndCollections() {
        val result = LinkRules.markPlaylistOpened(LinkRules.markLinkOpened(library(), "two", 2000), "p1", 3000)
        assertEquals(2000L, result.links[1].lastOpenedAt)
        assertEquals(3000L, result.playlists[0].lastOpenedAt)
        assertEquals(0L, result.links[0].lastOpenedAt)
    }

    @Test fun adjacentMovementHasStableBoundaryBehavior() {
        val original = library()
        assertEquals(original, LinkRules.movePlaylist(original, "p1", -1))
        assertEquals(original, LinkRules.moveLink(original, "p1", "three", 1))
        assertEquals(listOf("p2", "p1"), LinkRules.movePlaylist(original, "p1", 1).playlists.map { it.id })
        assertEquals(listOf("two", "one", "three"), LinkRules.moveLink(original, "p1", "one", 1).playlists[0].linkIds)
    }

    @Test fun normalizationMergesDuplicateUrlsAndRemapsReferencesInOriginalOrder() {
        val first = link("first").copy(lastOpenedAt = 10)
        val duplicate = first.copy(id = "alias", url = "https://youtu.be/dQw4w9WgXcQ", favorite = true, lastOpenedAt = 50)
        val result = LinkRules.normalize(LinkLibrary(
            listOf(first, duplicate, link("second", "abcdefghijk")),
            listOf(LinkPlaylist("p", "Set", listOf("second", "alias", "missing", "first", "second"))),
        ))
        assertEquals(listOf("first", "second"), result.links.map { it.id })
        assertTrue(result.links.first().favorite)
        assertEquals(50L, result.links.first().lastOpenedAt)
        assertEquals(listOf("second", "first"), result.playlists.single().linkIds)
    }

    @Test fun normalizationDropsInvalidRowsAndDuplicateIdsAndCorrectsKind() {
        val result = LinkRules.normalize(LinkLibrary(
            listOf(link("one").copy(kind = LinkKind.YOUTUBE_PLAYLIST, addedAt = -4), link("one", "abcdefghijk"), link(""), link("bad").copy(url = "https://evil.example")),
            listOf(LinkPlaylist("p", "  Set  ", listOf("one", "bad")), LinkPlaylist("p", "Duplicate", emptyList()), LinkPlaylist("empty", "  ", emptyList())),
        ))
        assertEquals(1, result.links.size)
        assertEquals(LinkKind.YOUTUBE_VIDEO, result.links.single().kind)
        assertEquals(0L, result.links.single().addedAt)
        assertEquals(listOf("one"), result.playlists.single().linkIds)
        assertEquals("Set", result.playlists.single().name)
    }

    @Test fun renamingLinkTrimsAndKeepsMetadata() {
        val original = library()
        val renamed = LinkRules.renameLink(original, "one", "  Titlu nou  ")
        assertEquals(original.links.first().copy(title = "Titlu nou"), renamed.links.first())
        assertEquals(original.playlists, renamed.playlists)
    }

    @Test(expected = IllegalArgumentException::class) fun blankTitlesAreRejected() {
        LinkRules.addLink(LinkLibrary(), "one", " \n\t", "https://youtu.be/dQw4w9WgXcQ", null, 0)
    }

    @Test(expected = IllegalArgumentException::class) fun oversizedTitlesAreRejected() {
        LinkRules.renameLink(library(), "one", "x".repeat(201))
    }

    @Test(expected = IllegalArgumentException::class) fun oversizedPlaylistNamesAreRejected() {
        LinkRules.createPlaylist(library(), "new", "x".repeat(81), 0)
    }

    @Test(expected = IllegalArgumentException::class) fun invalidYoutubeLinkIsRejected() {
        LinkRules.addLink(LinkLibrary(), "new", "Melodie", "https://evil.example/watch?v=dQw4w9WgXcQ", null, 0)
    }

    @Test(expected = IllegalArgumentException::class) fun addingToMissingPlaylistIsRejectedBeforeAddingLink() {
        LinkRules.addLink(LinkLibrary(), "new", "Melodie", "https://youtu.be/dQw4w9WgXcQ", "missing", 0)
    }

    @Test(expected = IllegalArgumentException::class) fun missingLinksCannotBeAddedToPlaylist() {
        LinkRules.addToPlaylist(library(), "p1", "missing")
    }

    @Test(expected = IllegalArgumentException::class) fun nonAdjacentReorderingIsRejected() {
        LinkRules.movePlaylist(library(), "p1", 2)
    }
}
