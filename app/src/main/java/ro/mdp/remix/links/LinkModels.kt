package ro.mdp.remix.links

enum class LinkKind { YOUTUBE_VIDEO, YOUTUBE_PLAYLIST }

data class SavedMusicLink(
    val id: String,
    val title: String,
    val url: String,
    val kind: LinkKind,
    val favorite: Boolean = false,
    val addedAt: Long = 0,
    val lastOpenedAt: Long = 0,
)

data class LinkPlaylist(
    val id: String,
    val name: String,
    val linkIds: List<String>,
    val favorite: Boolean = false,
    val createdAt: Long = 0,
    val lastOpenedAt: Long = 0,
)

data class LinkLibrary(
    val links: List<SavedMusicLink> = emptyList(),
    val playlists: List<LinkPlaylist> = emptyList(),
)

data class YouTubeTarget(val canonicalUrl: String, val kind: LinkKind, val externalId: String)
