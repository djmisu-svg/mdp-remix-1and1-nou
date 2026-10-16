package ro.mdp.remix.links

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Validates public YouTube links without calling a network or playback API. */
object YouTubeUrl {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    // WL and LL are official two-character playlist identifiers.
    private val playlistId = Regex("[A-Za-z0-9_-]{2,128}")
    private val youtubeHosts = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com")
    private val shortHosts = setOf("youtu.be", "www.youtu.be")
    private val videoPath = Regex("/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})")

    fun parse(input: String): YouTubeTarget? {
        val text = input.trim()
        if (text.isEmpty() || text.length > 8192) return null
        return try {
            val uri = URI(text)
            val host = uri.host?.lowercase(Locale.ROOT) ?: return null
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.rawUserInfo != null ||
                (uri.port != -1 && uri.port != 443) || host !in youtubeHosts + shortHosts
            ) return null
            val path = uri.rawPath.orEmpty().removeSuffix("/")
            val pathVideo = if (host in shortHosts) path.removePrefix("/").takeIf(videoId::matches)
                else videoPath.matchEntire(path)?.groupValues?.get(1)
            val supportedPath = pathVideo != null || (host in youtubeHosts && path in setOf("/watch", "/playlist"))
            if (!supportedPath) return null
            val params = uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map { part ->
                decode(part.substringBefore('=')) to decode(part.substringAfter('=', ""))
            }.groupBy({ it.first }, { it.second })
            if (params["list"]?.distinct()?.size?.let { it > 1 } == true) return null
            val list = params["list"]?.firstOrNull()
            if (list != null) {
                if (!playlistId.matches(list)) return null
                return YouTubeTarget("https://www.youtube.com/playlist?list=$list", LinkKind.YOUTUBE_PLAYLIST, list)
            }
            if (params["v"]?.distinct()?.size?.let { it > 1 } == true) return null
            val video = pathVideo ?: if (host in youtubeHosts && path == "/watch") params["v"]?.firstOrNull() else null
            if (video == null || !videoId.matches(video)) return null
            YouTubeTarget("https://www.youtube.com/watch?v=$video", LinkKind.YOUTUBE_VIDEO, video)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.net.URISyntaxException) {
            null
        }
    }

    private fun decode(value: String): String = URLDecoder.decode(value, "UTF-8")
}
