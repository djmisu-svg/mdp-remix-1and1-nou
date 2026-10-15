package ro.mdp.remix.links

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeUrlTest {
    private val video = "dQw4w9WgXcQ"
    private val list = "PLabcdefghij_1234567890"

    @Test fun officialVideoVariantsCanonicalizeToOneLink() {
        val variants = listOf(
            "https://youtube.com/watch?v=$video",
            "https://www.youtube.com/watch?v=$video&feature=share&t=42",
            "https://m.youtube.com/watch?v=$video",
            "https://music.youtube.com/watch?v=$video",
            "https://youtu.be/$video?si=tracking",
            "https://www.youtu.be/$video",
            "https://www.youtube.com/shorts/$video",
            "https://www.youtube.com/live/$video",
            "https://www.youtube.com/embed/$video",
            "  https://WWW.YOUTUBE.COM:443/watch?v=$video  ",
            "HTTPS://WWW.YOUTUBE.COM/watch?v=$video",
        )
        variants.forEach { input ->
            assertEquals(input, YouTubeTarget("https://www.youtube.com/watch?v=$video", LinkKind.YOUTUBE_VIDEO, video), YouTubeUrl.parse(input))
        }
    }

    @Test fun playlistTakesPrecedenceOverVideoAndDropsTracking() {
        listOf(
            "https://www.youtube.com/playlist?list=$list&si=tracking",
            "https://www.youtube.com/watch?v=$video&list=$list&index=5",
            "https://www.youtube.com/watch?v=ignored&v=another&list=$list",
            "https://youtu.be/$video?list=$list",
            "https://music.youtube.com/playlist?list=$list",
        ).forEach { input ->
            assertEquals(input, YouTubeTarget("https://www.youtube.com/playlist?list=$list", LinkKind.YOUTUBE_PLAYLIST, list), YouTubeUrl.parse(input))
        }
    }

    @Test fun officialShortPlaylistIdentifiersAreAccepted() {
        listOf("WL", "LL").forEach { id ->
            assertEquals(id, YouTubeUrl.parse("https://www.youtube.com/playlist?list=$id")?.externalId)
        }
    }

    @Test fun encodedQueryIdentifierIsDecodedAndCanonicalized() {
        assertEquals(video, YouTubeUrl.parse("https://youtube.com/watch?v=%64Qw4w9WgXcQ")?.externalId)
    }

    @Test fun unsupportedSchemesAndCredentialsAreRejected() {
        listOf(
            "http://youtube.com/watch?v=$video",
            "javascript:alert(1)",
            "file:///watch?v=$video",
            "youtube.com/watch?v=$video",
            "//youtube.com/watch?v=$video",
            "https://user@youtube.com/watch?v=$video",
            "https://user:password@youtube.com/watch?v=$video",
            "https://youtube.com:8443/watch?v=$video",
        ).forEach { assertNull(it, YouTubeUrl.parse(it)) }
    }

    @Test fun lookalikeAndRedirectHostsAreRejected() {
        listOf(
            "https://youtube.com.evil.example/watch?v=$video",
            "https://evil.example/youtube.com/watch?v=$video",
            "https://youtube.com@evil.example/watch?v=$video",
            "https://www.youtube-nocookie.com/embed/$video",
            "https://www.youtubе.com/watch?v=$video", // Cyrillic e.
            "https://studio.youtube.com/watch?v=$video",
        ).forEach { assertNull(it, YouTubeUrl.parse(it)) }
    }

    @Test fun invalidVideoIdentifiersAndPathsAreRejected() {
        listOf(
            "https://youtube.com/watch",
            "https://youtube.com/watch?v=short",
            "https://youtube.com/watch?v=${video}extra",
            "https://youtube.com/watch?v=hello.world",
            "https://youtu.be/$video/extra",
            "https://youtube.com/channel/$video",
            "https://youtube.com/redirect?q=https://youtu.be/$video",
            "https://youtube.com/anything?list=$list",
            "https://youtube.com/embed/%64Qw4w9WgXcQ",
        ).forEach { assertNull(it, YouTubeUrl.parse(it)) }
    }

    @Test fun invalidOrAmbiguousPlaylistIdentifiersAreRejected() {
        listOf(
            "https://youtube.com/playlist?list=",
            "https://youtube.com/playlist?list=A",
            "https://youtube.com/playlist?list=some%20playlist",
            "https://youtube.com/playlist?list=${"a".repeat(129)}",
            "https://youtube.com/watch?v=$video&list=bad+value",
            "https://youtube.com/playlist?list=$list&list=PLdifferent",
        ).forEach { assertNull(it, YouTubeUrl.parse(it)) }
    }

    @Test fun duplicateConflictingVideoIdentifiersAreRejected() {
        assertNull(YouTubeUrl.parse("https://youtube.com/watch?v=$video&v=abcdefghijk"))
        assertEquals(video, YouTubeUrl.parse("https://youtube.com/watch?v=$video&v=$video")?.externalId)
    }

    @Test fun malformedInputNeverThrows() {
        listOf("", " ", "%", "https://youtube.com/watch?v=%QQ", "https://[", "\u0000", "x".repeat(8193))
            .forEach { assertNull(it, YouTubeUrl.parse(it)) }
    }
}
