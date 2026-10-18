package ro.mdp.remix.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ro.mdp.remix.data.LibrarySnapshot
import ro.mdp.remix.data.Playlist
import ro.mdp.remix.data.Track
import ro.mdp.remix.links.LinkKind
import ro.mdp.remix.links.LinkLibrary
import ro.mdp.remix.links.LinkPlaylist
import ro.mdp.remix.links.SavedMusicLink

class CollectionMergeTest {
    private val transferId = "5e86dff0-2723-4e06-b1bb-1d7f09a76f37"
    private fun track(id: String, uri: String = "content://ro.mdp.remix.premiumtest.mdpfiles/media/${"a".repeat(64)}?source=$id") =
        Track(id, "Title $id", "Artist $id", "Album $id", 123_456, uri)
    private fun link(id: String, video: String = "dQw4w9WgXcQ") = SavedMusicLink(
        id, "Video $id", "https://www.youtube.com/watch?v=$video", LinkKind.YOUTUBE_VIDEO,
        addedAt = 100, lastOpenedAt = 200,
    )

    @Test fun freshMusicRecipientPreservesMetadataOrderMembershipAndFavorites() {
        val source = LibrarySnapshot(
            tracks = listOf(track("b"), track("a")), favorites = setOf("a"),
            playlists = listOf(Playlist("p2", "Second", listOf("a", "b")), Playlist("p1", "First", emptyList())),
        )
        val result = CollectionMerge.music(LibrarySnapshot(), source, transferId)
        assertEquals(source.tracks.map { it.title }, result.tracks.map { it.title })
        assertEquals(source.tracks.map { it.artist }, result.tracks.map { it.artist })
        assertEquals(source.tracks.map { it.durationMs }, result.tracks.map { it.durationMs })
        assertEquals(listOf("Second", "First"), result.playlists.map { it.name })
        assertEquals(listOf(result.tracks[1].id, result.tracks[0].id), result.playlists[0].trackIds)
        assertEquals(setOf(result.tracks[1].id), result.favorites)
        assertNotEquals(source.tracks.first().id, result.tracks.first().id)
    }

    @Test fun musicImportKeepsRecipientRowsEvenWhenSenderIdentifiersCollide() {
        val recipient = LibrarySnapshot(listOf(track("same", "content://personal/audio")), setOf("same"),
            listOf(Playlist("same", "Personal", listOf("same"))))
        val source = LibrarySnapshot(listOf(track("same")), setOf("same"), listOf(Playlist("same", "Shared", listOf("same"))))
        val result = CollectionMerge.music(recipient, source, transferId)
        assertEquals(recipient.tracks.single(), result.tracks.first())
        assertEquals(recipient.playlists.single(), result.playlists.first())
        assertEquals(2, result.tracks.size)
        assertEquals(2, result.playlists.size)
        assertEquals(2, result.favorites.size)
        assertEquals(listOf(result.tracks.last().id), result.playlists.last().trackIds)
    }

    @Test fun repeatedPackageIsIdempotentAndKeepsRecipientRenames() {
        val source = LibrarySnapshot(listOf(track("one")), setOf("one"), listOf(Playlist("p", "From sender", listOf("one"))))
        val first = CollectionMerge.music(LibrarySnapshot(), source, transferId)
        val renamed = first.copy(playlists = first.playlists.map { it.copy(name = "Recipient title") })
        assertEquals(renamed, CollectionMerge.music(renamed, source, transferId))
    }

    @Test fun identicalBytesWithDifferentPortableAliasesRetainBothTracks() {
        val source = LibrarySnapshot(listOf(track("one"), track("two")), playlists =
            listOf(Playlist("p", "Aliases", listOf("two", "one"))))
        val result = CollectionMerge.music(LibrarySnapshot(), source, transferId)
        assertEquals(2, result.tracks.size)
        assertEquals(2, result.playlists.single().trackIds.size)
        assertEquals(listOf(result.tracks[1].id, result.tracks[0].id), result.playlists.single().trackIds)
    }

    @Test fun existingAudioUriIsReusedAndFavoritesAreUnited() {
        val existing = track("personal")
        val recipient = LibrarySnapshot(listOf(existing))
        val source = LibrarySnapshot(listOf(track("incoming", existing.uri)), setOf("incoming"),
            listOf(Playlist("p", "Incoming", listOf("incoming"))))
        val result = CollectionMerge.music(recipient, source, transferId)
        assertEquals(listOf(existing), result.tracks)
        assertEquals(setOf(existing.id), result.favorites)
        assertEquals(listOf(existing.id), result.playlists.single().trackIds)
    }

    @Test fun youtubeCanonicalDuplicatesReuseRecipientTitleAndUnionFavoritesAndHistory() {
        val personal = link("same").copy(title = "My title", addedAt = 1, lastOpenedAt = 2)
        val recipient = LinkLibrary(listOf(personal), listOf(LinkPlaylist("same", "Personal", listOf("same"))))
        val incoming = link("same").copy(url = "https://youtu.be/dQw4w9WgXcQ?si=shared", favorite = true)
        val source = LinkLibrary(listOf(incoming, link("two", "abcdefghijk")), listOf(
            LinkPlaylist("same", "Shared", listOf("two", "same"), favorite = true, createdAt = 3, lastOpenedAt = 4)))
        val result = CollectionMerge.youtube(recipient, source, transferId)
        assertEquals("My title", result.links.first().title)
        assertEquals(1L, result.links.first().addedAt)
        assertEquals(200L, result.links.first().lastOpenedAt)
        assertTrue(result.links.first().favorite)
        assertEquals(recipient.playlists.first(), result.playlists.first())
        assertEquals(listOf(result.links[1].id, "same"), result.playlists.last().linkIds)
        assertTrue(result.playlists.last().favorite)
        assertEquals(3L, result.playlists.last().createdAt)
        assertEquals(4L, result.playlists.last().lastOpenedAt)
        assertEquals(result, CollectionMerge.youtube(result, source, transferId))
    }

    @Test fun freshYoutubeRecipientKeepsSenderPlaylistOrderAndEmptyPlaylists() {
        val source = LinkLibrary(listOf(link("b"), link("a", "abcdefghijk")), listOf(
            LinkPlaylist("second", "Second", listOf("a", "b"), favorite = true),
            LinkPlaylist("first", "First", emptyList())))
        val result = CollectionMerge.youtube(LinkLibrary(), source, transferId)
        assertEquals(listOf("Second", "First"), result.playlists.map { it.name })
        assertEquals(listOf(result.links[1].id, result.links[0].id), result.playlists.first().linkIds)
        assertEquals(emptyList<String>(), result.playlists.last().linkIds)
        assertTrue(result.playlists.first().favorite)
    }

    @Test fun separateTransfersCannotCollideUsingSenderIdentifiers() {
        val source = LibrarySnapshot(listOf(track("one")), playlists = listOf(Playlist("p", "First", listOf("one"))))
        val first = CollectionMerge.music(LibrarySnapshot(), source, transferId)
        val secondId = "db048f6a-1b76-4cdb-8c97-b97ad93d8c11"
        val secondSource = source.copy(tracks = listOf(track("one", "content://other/audio")))
        val result = CollectionMerge.music(first, secondSource, secondId)
        assertEquals(2, result.tracks.size)
        assertEquals(2, result.playlists.size)
        assertEquals(2, result.tracks.map { it.id }.toSet().size)
        assertEquals(2, result.playlists.map { it.id }.toSet().size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedTransferIdentifiersAreRejectedBeforeMerge() {
        CollectionMerge.music(LibrarySnapshot(), LibrarySnapshot(), "not-a-transfer-uuid")
    }

    @Test fun brandRefreshReplacesOnlyManagedEntriesAndKeepsPersonalReferences() {
        val brand = LinkLibrary(listOf(link("old"), link("gone", "abcdefghijk")), listOf(
            LinkPlaylist("collection", "Old name", listOf("old", "gone"))))
        val first = CollectionMerge.syncBrand(LinkLibrary(), brand)
        val retainedId = first.links.first().id
        val personalLink = link("my-link", "1234567890_")
        val personalPlaylist = LinkPlaylist("personal-playlist", "My set", listOf(retainedId, personalLink.id))
        val recipient = first.copy(links = first.links + personalLink, playlists = first.playlists + personalPlaylist)
        val updated = LinkLibrary(listOf(link("new", "ZZZZZZZZZZZ")), listOf(LinkPlaylist("new-set", "Latest set", listOf("new"))))
        val result = CollectionMerge.syncBrand(recipient, updated)
        assertEquals(personalPlaylist, result.playlists.first())
        assertTrue(result.links.contains(personalLink))
        assertTrue(result.links.any { it.id == retainedId })
        assertFalse(result.links.any { it.url.contains("abcdefghijk") })
        assertEquals(listOf("My set", "Latest set"), result.playlists.map { it.name })
        assertEquals(result, CollectionMerge.syncBrand(result, updated))
    }

    @Test fun brandRefreshUpdatesManagedTitlesButDoesNotOverwritePersonalCanonicalDuplicate() {
        val initial = LinkLibrary(listOf(link("brand")), listOf(LinkPlaylist("set", "Before", listOf("brand"))))
        val first = CollectionMerge.syncBrand(LinkLibrary(), initial)
        val changed = initial.copy(links = initial.links.map { it.copy(title = "Latest title") },
            playlists = initial.playlists.map { it.copy(name = "After") })
        val refreshed = CollectionMerge.syncBrand(first, changed)
        assertEquals(first.links.single().id, refreshed.links.single().id)
        assertEquals("Latest title", refreshed.links.single().title)
        assertEquals("After", refreshed.playlists.single().name)
        val personal = link("personal").copy(title = "My own title", favorite = true)
        val merged = CollectionMerge.syncBrand(LinkLibrary(listOf(personal)), changed)
        assertEquals(listOf(personal), merged.links)
        assertEquals(listOf(personal.id), merged.playlists.single().linkIds)
    }
}
