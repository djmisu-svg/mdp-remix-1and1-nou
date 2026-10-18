package ro.mdp.remix.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.mdp.remix.links.LinkKind
import ro.mdp.remix.links.LinkPlaylist
import ro.mdp.remix.links.LinkViewModel
import ro.mdp.remix.links.SavedMusicLink
import ro.mdp.remix.links.YouTubeUrl

private enum class LinkSection(val roLabel: String, val enLabel: String, val icon: ImageVector) {
    ALL("Toate", "All links", Icons.Default.VideoLibrary),
    PLAYLISTS("Playlisturi", "Playlists", Icons.Default.QueueMusic),
    FAVORITES("Favorite", "Favorites", Icons.Default.FavoriteBorder),
    RECENT("Recente", "Recent", Icons.Default.History),
}

/** Saved YouTube references are opened by the Activity through official Android intents. */
@Composable
fun LinksScreen(viewModel: LinkViewModel, onOpenLink: (SavedMusicLink) -> Unit) {
    val library by viewModel.state.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(LinkSection.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var addingLink by rememberSaveable { mutableStateOf(false) }
    var creatingPlaylist by rememberSaveable { mutableStateOf(false) }
    var editingLink by remember { mutableStateOf<SavedMusicLink?>(null) }
    var deletingLink by remember { mutableStateOf<SavedMusicLink?>(null) }
    var editingPlaylist by remember { mutableStateOf<LinkPlaylist?>(null) }
    var deletingPlaylist by remember { mutableStateOf<LinkPlaylist?>(null) }
    var assigningLinkId by rememberSaveable { mutableStateOf<String?>(null) }
    var managingPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val gridState = rememberLazyGridState()
    val selectedPlaylist = library.playlists.firstOrNull { it.id == selectedPlaylistId }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(library.playlists, loading) {
        if (!loading && selectedPlaylistId != null && selectedPlaylist == null) {
            selectedPlaylistId = null
        }
    }
    BackHandler(selectedPlaylistId != null || query.isNotBlank()) {
        if (selectedPlaylistId != null) selectedPlaylistId = null else query = ""
    }
    LaunchedEffect(section, selectedPlaylistId, query) { gridState.scrollToItem(0) }

    val normalizedQuery = query.trim()
    val matchingPlaylistLinks = remember(library.playlists, normalizedQuery) {
        if (normalizedQuery.isBlank()) emptySet() else library.playlists
            .filter { it.name.contains(normalizedQuery, ignoreCase = true) }
            .flatMap { it.linkIds }.toSet()
    }
    val matches: (SavedMusicLink) -> Boolean = { link ->
        normalizedQuery.isBlank() || link.title.contains(normalizedQuery, true) ||
            link.url.contains(normalizedQuery, true) || link.id in matchingPlaylistLinks
    }
    val visibleLinks = remember(library, section, selectedPlaylist, normalizedQuery) {
        val candidates = if (selectedPlaylist != null) {
            val byId = library.links.associateBy { it.id }
            selectedPlaylist.linkIds.mapNotNull(byId::get)
        } else {
            when (section) {
                LinkSection.ALL -> library.links.sortedByDescending { it.addedAt }
                LinkSection.PLAYLISTS -> emptyList()
                LinkSection.FAVORITES -> library.links.filter { it.favorite }.sortedByDescending { it.addedAt }
                LinkSection.RECENT -> library.links.filter { it.lastOpenedAt > 0 }.sortedByDescending { it.lastOpenedAt }
            }
        }
        candidates.filter(matches)
    }
    val visiblePlaylists = remember(library, section, selectedPlaylist, normalizedQuery) {
        if (selectedPlaylist != null || section == LinkSection.ALL) emptyList() else {
            val candidates = when (section) {
                LinkSection.FAVORITES -> library.playlists.filter { it.favorite }
                LinkSection.RECENT -> library.playlists.filter { it.lastOpenedAt > 0 }.sortedByDescending { it.lastOpenedAt }
                else -> library.playlists
            }
            candidates.filter { playlist ->
                normalizedQuery.isBlank() || playlist.name.contains(normalizedQuery, true) ||
                    library.links.any { it.id in playlist.linkIds &&
                        (it.title.contains(normalizedQuery, true) || it.url.contains(normalizedQuery, true)) }
            }
        }
    }

    Scaffold(modifier = Modifier.testTag("youtube_library"), snackbarHost = { SnackbarHost(snackbar) }, containerColor = Color.Transparent) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(320.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().padding(innerPadding).testTag("youtube_grid"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(key = "brand", span = { GridItemSpan(maxLineSpan) }) {
                LinkBrandHeader(library.links.size, library.playlists.size)
            }
            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("youtube_search"),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text(uiText("Caută în biblioteca YouTube", "Search your YouTube library")) },
                    placeholder = { Text(uiText("Titlu, link sau nume de playlist", "Title, link or playlist name")) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) ({
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = uiText("Șterge căutarea", "Clear search"))
                        }
                    }) else null,
                )
            }
            item(key = "navigation", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinkSection.entries.forEach { destination ->
                        FilterChip(
                            modifier = Modifier.testTag("link_section_${destination.name.lowercase()}"),
                            selected = section == destination && selectedPlaylist == null,
                            onClick = { section = destination; selectedPlaylistId = null },
                            label = {
                                val count = when (destination) {
                                    LinkSection.ALL -> library.links.size
                                    LinkSection.PLAYLISTS -> library.playlists.size
                                    LinkSection.FAVORITES -> library.links.count { it.favorite } + library.playlists.count { it.favorite }
                                    LinkSection.RECENT -> library.links.count { it.lastOpenedAt > 0 } + library.playlists.count { it.lastOpenedAt > 0 }
                                }
                                Text(uiText(destination.roLabel, destination.enLabel) + "  " + count)
                            },
                            leadingIcon = { Icon(destination.icon, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            item(key = "actions", span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { addingLink = true }, enabled = !loading, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("add_youtube_link"), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(uiText("Adaugă link", "Add link"))
                    }
                    OutlinedButton(onClick = { creatingPlaylist = true }, enabled = !loading, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("new_link_playlist"), shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, Hairline)) {
                        Icon(Icons.Default.PlaylistAdd, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(uiText("Playlist nou", "New playlist"))
                    }
                }
            }
            if (selectedPlaylist != null) {
                item(key = "playlist-detail", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selectedPlaylistId = null }) {
                                Icon(Icons.Default.ArrowBack, uiText("Înapoi la bibliotecă", "Back to library"))
                            }
                            Column(Modifier.weight(1f)) {
                                Text(selectedPlaylist.name, style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold)
                                Text(uiText("${selectedPlaylist.linkIds.size} linkuri · ordinea ta", "${selectedPlaylist.linkIds.size} links · your order"), color = Muted,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            LinkFavoriteButton(selectedPlaylist.favorite, "playlist", {
                                viewModel.togglePlaylistFavorite(selectedPlaylist.id)
                            })
                            LinkPlaylistMenu(
                                onRename = { editingPlaylist = selectedPlaylist },
                                onDelete = { deletingPlaylist = selectedPlaylist },
                                onMoveUp = { viewModel.movePlaylist(selectedPlaylist.id, -1) },
                                onMoveDown = { viewModel.movePlaylist(selectedPlaylist.id, 1) },
                                canMoveUp = library.playlists.indexOf(selectedPlaylist) > 0,
                                canMoveDown = library.playlists.indexOf(selectedPlaylist) < library.playlists.lastIndex,
                            )
                        }
                        OutlinedButton(onClick = { managingPlaylistId = selectedPlaylist.id }) {
                            Icon(Icons.Default.PlaylistAdd, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(uiText("Alege linkurile din playlist", "Choose playlist links"))
                        }
                    }
                }
            } else if (visiblePlaylists.isNotEmpty()) {
                item(key = "playlist-title", span = { GridItemSpan(maxLineSpan) }) {
                    LinkSectionTitle(if (section == LinkSection.RECENT) uiText("Playlisturi accesate recent", "Recently opened playlists") else uiText("Playlisturile tale", "Your playlists"),
                        visiblePlaylists.size)
                }
            }
            items(visiblePlaylists, key = { "playlist-${it.id}" }) { playlist ->
                LinkPlaylistCard(
                    playlist = playlist,
                    onOpen = {
                        selectedPlaylistId = playlist.id
                        query = ""
                        viewModel.markPlaylistOpened(playlist.id)
                    },
                    onFavorite = { viewModel.togglePlaylistFavorite(playlist.id) },
                    onRename = { editingPlaylist = playlist },
                    onDelete = { deletingPlaylist = playlist },
                    onMoveUp = { viewModel.movePlaylist(playlist.id, -1) },
                    onMoveDown = { viewModel.movePlaylist(playlist.id, 1) },
                    canMoveUp = library.playlists.indexOf(playlist) > 0,
                    canMoveDown = library.playlists.indexOf(playlist) < library.playlists.lastIndex,
                )
            }
            if (visibleLinks.isNotEmpty()) {
                item(key = "links-title", span = { GridItemSpan(maxLineSpan) }) {
                    LinkSectionTitle(when {
                        selectedPlaylist != null -> uiText("Linkuri în playlist", "Playlist links")
                        section == LinkSection.FAVORITES -> uiText("Linkuri favorite", "Favorite links")
                        section == LinkSection.RECENT -> uiText("Linkuri deschise recent", "Recently opened links")
                        else -> uiText("Biblioteca ta YouTube", "Your YouTube library")
                    }, visibleLinks.size)
                }
            }
            items(visibleLinks, key = { "link-${it.id}" }) { link ->
                val index = selectedPlaylist?.linkIds?.indexOf(link.id) ?: -1
                LinkMusicCard(
                    link = link,
                    playlistNames = library.playlists.filter { link.id in it.linkIds }.map { it.name },
                    onOpen = { onOpenLink(link) },
                    onFavorite = { viewModel.toggleLinkFavorite(link.id) },
                    onRename = { editingLink = link },
                    onDelete = { deletingLink = link },
                    onAssign = { assigningLinkId = link.id },
                    onRemove = selectedPlaylist?.let { playlist ->
                        { viewModel.removeFromPlaylist(playlist.id, link.id) }
                    },
                    onMoveUp = selectedPlaylist?.let { playlist ->
                        { viewModel.moveLink(playlist.id, link.id, -1) }
                    },
                    onMoveDown = selectedPlaylist?.let { playlist ->
                        { viewModel.moveLink(playlist.id, link.id, 1) }
                    },
                    canMoveUp = index > 0,
                    canMoveDown = selectedPlaylist != null && index in 0 until selectedPlaylist.linkIds.lastIndex,
                )
            }
            if (loading) {
                item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CircularProgressIndicator(color = Red)
                        Text(uiText("Se încarcă biblioteca salvată…", "Loading your saved library…"), color = Muted)
                    }
                }
            } else if (visibleLinks.isEmpty() && visiblePlaylists.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    LinkEmptyState(
                        searching = normalizedQuery.isNotBlank(),
                        section = section,
                        inPlaylist = selectedPlaylist != null,
                        onAdd = { if (selectedPlaylist != null) managingPlaylistId = selectedPlaylist.id else addingLink = true },
                        onCreate = { creatingPlaylist = true },
                        onClearSearch = { query = "" },
                    )
                }
            }
            item(key = "official-note", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.OpenInNew, null, Modifier.size(18.dp), tint = Muted)
                    Text(uiText("Linkurile se deschid în YouTube sau browser. Biblioteca ta este salvată local.",
                        "Links open in YouTube or your browser. Your collection is saved on this device."),
                        color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    if (addingLink) LinkAddDialog(
        playlistName = selectedPlaylist?.name,
        onDismiss = { addingLink = false },
        onSave = { title, url ->
            viewModel.addLink(title, url, selectedPlaylist?.id)
            addingLink = false
        },
    )
    if (creatingPlaylist) LinkNameDialog(uiText("Playlist nou", "New playlist"), uiText("Nume playlist", "Playlist name"), "", 80,
        onDismiss = { creatingPlaylist = false }, onSave = {
            viewModel.createPlaylist(it)
            section = LinkSection.PLAYLISTS
            selectedPlaylistId = null
            query = ""
            creatingPlaylist = false
        })
    editingLink?.let { link ->
        LinkNameDialog(uiText("Redenumește linkul", "Rename link"), uiText("Titlu", "Title"), link.title, 200,
            onDismiss = { editingLink = null }, onSave = {
                viewModel.renameLink(link.id, it)
                editingLink = null
            })
    }
    editingPlaylist?.let { playlist ->
        LinkNameDialog(uiText("Redenumește playlistul", "Rename playlist"), uiText("Nume playlist", "Playlist name"), playlist.name, 80,
            onDismiss = { editingPlaylist = null }, onSave = {
                viewModel.renamePlaylist(playlist.id, it)
                editingPlaylist = null
            })
    }
    deletingLink?.let { link ->
        LinkDeleteDialog(uiText("Ștergi linkul?", "Delete this link?"), uiText("„${link.title}” va fi eliminat din bibliotecă, favorite și toate playlisturile tale.", "“${link.title}” will be removed from your library, favorites and every playlist."),
            onDismiss = { deletingLink = null }, onDelete = {
                viewModel.deleteLink(link.id)
                deletingLink = null
            })
    }
    deletingPlaylist?.let { playlist ->
        LinkDeleteDialog(uiText("Ștergi playlistul?", "Delete this playlist?"), uiText("Playlistul „${playlist.name}” va fi șters. Linkurile rămân în bibliotecă.", "“${playlist.name}” will be deleted. Its links stay in your library."),
            onDismiss = { deletingPlaylist = null }, onDelete = {
                viewModel.deletePlaylist(playlist.id)
                deletingPlaylist = null
            })
    }
    library.links.firstOrNull { it.id == assigningLinkId }?.let { link ->
        LinkAssignDialog(link, library.playlists,
            onDismiss = { assigningLinkId = null },
            onCreate = viewModel::createPlaylist,
            onToggle = { playlist, checked ->
                if (checked) viewModel.addToPlaylist(playlist.id, link.id)
                else viewModel.removeFromPlaylist(playlist.id, link.id)
            })
    }
    library.playlists.firstOrNull { it.id == managingPlaylistId }?.let { playlist ->
        LinkManageDialog(playlist, library.links,
            onDismiss = { managingPlaylistId = null },
            onToggle = { link, checked ->
                if (checked) viewModel.addToPlaylist(playlist.id, link.id)
                else viewModel.removeFromPlaylist(playlist.id, link.id)
            },
            onAddNew = { managingPlaylistId = null; addingLink = true })
    }
}

@Composable
private fun LinkBrandHeader(linkCount: Int, playlistCount: Int) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wide = maxWidth >= 600.dp
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(38.dp).background(Red, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                    Text("M", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(12.dp))
                Text("MdP RemiX 1&1", fontWeight = FontWeight.Bold, fontSize = 17.sp, letterSpacing = (-.4).sp)
                Spacer(Modifier.weight(1f))
                Text("1&1", color = Color.White.copy(alpha = .3f), style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider(color = Hairline)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (wide) 36.dp else 12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(5.dp).background(Red, CircleShape))
                        Text("YOUTUBE COLLECTION", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    }
                    Text(uiText("Selecția ta.\nPe repeat.", "Your collection.\nOn repeat."),
                        fontSize = if (wide) 44.sp else 32.sp, lineHeight = if (wide) 49.sp else 37.sp,
                        letterSpacing = (-1.3).sp, fontWeight = FontWeight.Bold, color = Color.White)
                    Text(uiText("Sunetul tău, într-un singur loc.", "Your sound, all in one place."),
                        style = MaterialTheme.typography.bodyMedium, color = Muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 6.dp)) {
                        LinkStat(linkCount, uiText("LINKURI", "LINKS"))
                        Box(Modifier.height(34.dp).width(1.dp).background(Hairline))
                        LinkStat(playlistCount, uiText("PLAYLISTURI", "PLAYLISTS"))
                    }
                }
                LinkVinyl(Modifier.size(if (wide) 210.dp else 100.dp))
            }
        }
    }
}

@Composable
private fun LinkStat(count: Int, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(count.toString().padStart(2, '0'), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LinkVinyl(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension / 2
            drawCircle(Color.White.copy(alpha = .025f), radius)
            for (ring in 0..8) drawCircle(Color.White.copy(alpha = if (ring % 3 == 0) .12f else .045f),
                radius * (.96f - ring * .065f), style = Stroke(1.dp.toPx()))
            drawCircle(Red, radius * .34f)
            drawCircle(Color.White.copy(alpha = .25f), radius * .34f, style = Stroke(1.dp.toPx()))
            drawCircle(Background, radius * .055f)
            drawLine(Color.White.copy(alpha = .08f), Offset(radius * .3f, radius * .35f),
                Offset(radius * 1.4f, radius * 1.65f), strokeWidth = radius * .12f)
        }
        Text("1&1", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun LinkSectionTitle(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        Text(count.toString(), color = Red, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun LinkMusicCard(
    link: SavedMusicLink,
    playlistNames: List<String>,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onAssign: () -> Unit,
    onRemove: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    Surface(modifier = Modifier.shadow(10.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black, spotColor = Color.Black), shape = RoundedCornerShape(22.dp), color = panelColor(),
        border = BorderStroke(1.dp, Hairline)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = Red.copy(alpha = .12f)) {
                    Icon(if (link.kind == LinkKind.YOUTUBE_PLAYLIST) Icons.Default.QueueMusic else Icons.Default.PlayArrow,
                        null, tint = Red, modifier = Modifier.padding(10.dp).size(24.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(if (link.kind == LinkKind.YOUTUBE_PLAYLIST) uiText("PLAYLIST YOUTUBE", "YOUTUBE PLAYLIST") else uiText("VIDEO YOUTUBE", "YOUTUBE VIDEO"),
                    color = Muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                LinkFavoriteButton(link.favorite, "link", onFavorite)
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, uiText("Opțiuni pentru ${link.title}", "Options for ${link.title}"), tint = Muted)
                    }
                    DropdownMenu(containerColor = Background, expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text(uiText("Redenumește", "Rename")) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuExpanded = false; onRename() })
                        DropdownMenuItem(text = { Text(uiText("Adaugă în playlisturi", "Add to playlists")) }, leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) },
                            onClick = { menuExpanded = false; onAssign() })
                        if (onRemove != null) DropdownMenuItem(text = { Text(uiText("Scoate din acest playlist", "Remove from this playlist")) },
                            leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null) },
                            onClick = { menuExpanded = false; onRemove() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text(uiText("Șterge linkul", "Delete link"), color = Red) },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = Red) },
                            onClick = { menuExpanded = false; onDelete() })
                    }
                }
            }
            Text(link.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(link.url, style = MaterialTheme.typography.bodySmall, color = Muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (playlistNames.isNotEmpty()) Text(playlistNames.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = Red,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp)) {
                    Text(uiText("Deschide YouTube", "Open YouTube"))
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                }
                if (onMoveUp != null && onMoveDown != null) {
                    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Icon(Icons.Default.ArrowUpward, uiText("Mută linkul mai sus", "Move link up"), Modifier.size(20.dp))
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Icon(Icons.Default.ArrowDownward, uiText("Mută linkul mai jos", "Move link down"), Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkPlaylistCard(
    playlist: LinkPlaylist,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
) {
    Surface(modifier = Modifier.shadow(10.dp, RoundedCornerShape(22.dp), ambientColor = Color.Black, spotColor = Color.Black), shape = RoundedCornerShape(22.dp), color = panelColor(), border = BorderStroke(1.dp, Hairline)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QueueMusic, null, tint = Red, modifier = Modifier.size(32.dp))
                Spacer(Modifier.weight(1f))
                LinkFavoriteButton(playlist.favorite, "playlist", onFavorite)
                LinkPlaylistMenu(onRename, onDelete, onMoveUp, onMoveDown, canMoveUp, canMoveDown)
            }
            Text(playlist.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(uiText("${playlist.linkIds.size} linkuri salvate", "${playlist.linkIds.size} saved links"), style = MaterialTheme.typography.bodyMedium, color = Muted)
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, Hairline)) {
                Text(uiText("Deschide playlistul", "Open playlist"))
            }
        }
    }
}

@Composable
private fun LinkPlaylistMenu(
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag("link_playlist_options")) {
            Icon(Icons.Default.MoreVert, uiText("Opțiuni playlist", "Playlist options"), tint = Muted)
        }
        DropdownMenu(containerColor = Background, expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(modifier = Modifier.testTag("rename_link_playlist"), text = { Text(uiText("Redenumește", "Rename")) }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { expanded = false; onRename() })
            DropdownMenuItem(text = { Text(uiText("Mută mai sus", "Move up")) }, leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                enabled = canMoveUp, onClick = { expanded = false; onMoveUp() })
            DropdownMenuItem(text = { Text(uiText("Mută mai jos", "Move down")) }, leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                enabled = canMoveDown, onClick = { expanded = false; onMoveDown() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text(uiText("Șterge playlistul", "Delete playlist"), color = Red) },
                leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = Red) },
                onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
private fun LinkFavoriteButton(favorite: Boolean, kind: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            if (favorite) uiText("Elimină $kind din favorite", "Remove $kind from favorites") else uiText("Adaugă $kind la favorite", "Add $kind to favorites"),
            tint = if (favorite) Red else Muted)
    }
}

@Composable
private fun LinkEmptyState(
    searching: Boolean,
    section: LinkSection,
    inPlaylist: Boolean,
    onAdd: () -> Unit,
    onCreate: () -> Unit,
    onClearSearch: () -> Unit,
) {
    val title = when {
        searching -> uiText("Niciun rezultat", "No results")
        inPlaylist -> uiText("Un playlist pentru selecția ta", "A home for your selection")
        section == LinkSection.PLAYLISTS -> uiText("Creează primul tău playlist", "Create your first playlist")
        section == LinkSection.FAVORITES -> uiText("Păstrează aproape ce îți place", "Keep your favorites close")
        section == LinkSection.RECENT -> uiText("Selecțiile recente apar aici", "Your recent selections appear here")
        else -> uiText("Începe biblioteca MdP RemiX", "Build your MdP RemiX collection")
    }
    val description = when {
        searching -> uiText("Încearcă alt titlu, link YouTube sau nume de playlist.", "Try a different title, YouTube link or playlist name.")
        inPlaylist -> uiText("Alege linkuri salvate sau adaugă un link YouTube nou.", "Choose saved links or add a new YouTube link.")
        section == LinkSection.PLAYLISTS -> uiText("Grupează videoclipurile și playlisturile YouTube după stil, stare sau eveniment.", "Collect YouTube videos and playlists by style, mood or occasion.")
        section == LinkSection.FAVORITES -> uiText("Apasă pe inimă lângă un link sau un playlist pentru a-l găsi aici.", "Tap the heart on a link or playlist to find it here.")
        section == LinkSection.RECENT -> uiText("Deschide un playlist sau un link din bibliotecă pentru a-l regăsi rapid.", "Open a saved link or playlist to find it here again.")
        else -> uiText("Salvează linkuri de videoclipuri și playlisturi YouTube, apoi organizează-le în colecțiile tale.", "Save YouTube videos and playlists, then make the collection your own.")
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(if (searching) Icons.Default.Search else section.icon, null, tint = Red, modifier = Modifier.size(46.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = Muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        when {
            searching -> TextButton(onClick = onClearSearch) { Text(uiText("Șterge căutarea", "Clear search")) }
            section == LinkSection.PLAYLISTS && !inPlaylist -> Button(onClick = onCreate) { Text(uiText("Creează playlist", "Create playlist")) }
            inPlaylist -> Button(onClick = onAdd) { Text(uiText("Alege linkuri", "Choose links")) }
            section == LinkSection.ALL -> Button(onClick = onAdd) { Text(uiText("Adaugă primul link", "Add your first link")) }
        }
    }
}

@Composable
private fun LinkAddDialog(playlistName: String?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val parsed = remember(url) { YouTubeUrl.parse(url) }
    val titleError = title.trim().isEmpty() || title.trim().length > 200
    val urlError = parsed == null
    AlertDialog(
        containerColor = Background,
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Link, null, tint = Red) },
        title = { Text(uiText("Salvează un link YouTube", "Save a YouTube link")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(uiText("Adaugă un videoclip sau un playlist YouTube. Alegi tu titlul afișat.", "Add a YouTube video or playlist and give it a title of your own."),
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text(uiText("Link YouTube", "YouTube link")) },
                    placeholder = { Text("https://www.youtube.com/…") }, modifier = Modifier.fillMaxWidth().testTag("youtube_link_url"),
                    maxLines = 3, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = attempted && urlError,
                    supportingText = {
                        if (attempted && urlError) Text(uiText("Introdu un link YouTube valid de videoclip sau playlist.", "Enter a valid YouTube video or playlist link."))
                        else if (parsed != null) Text(if (parsed.kind == LinkKind.YOUTUBE_PLAYLIST) uiText("Playlist YouTube recunoscut", "YouTube playlist recognized") else uiText("Videoclip YouTube recunoscut", "YouTube video recognized"))
                    })
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(uiText("Titlu", "Title")) },
                    modifier = Modifier.fillMaxWidth().testTag("youtube_link_title"), singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    isError = attempted && titleError,
                    supportingText = { if (attempted && titleError) Text(uiText("Alege un titlu între 1 și 200 de caractere.", "Choose a title between 1 and 200 characters.")) })
                if (playlistName != null) Text(uiText("Va fi adăugat și în „$playlistName”.", "Also added to “$playlistName”."), color = Red,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(modifier = Modifier.testTag("save_youtube_link"), onClick = {
            attempted = true
            if (!titleError && !urlError) onSave(title.trim(), url.trim())
        }) { Text(uiText("Salvează", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("Anulează", "Cancel")) } },
    )
}

@Composable
private fun LinkNameDialog(
    title: String,
    label: String,
    initialValue: String,
    maxLength: Int,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val invalid = name.trim().isEmpty() || name.trim().length > maxLength
    AlertDialog(containerColor = Background, onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(label) },
                modifier = Modifier.fillMaxWidth().testTag(if (maxLength == 80) "link_playlist_name" else "link_title"), singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                isError = attempted && invalid,
                supportingText = { if (attempted && invalid) Text(uiText("Folosește între 1 și $maxLength de caractere.", "Use between 1 and $maxLength characters.")) })
        },
        confirmButton = { Button(modifier = Modifier.testTag("link_name_save"), onClick = { attempted = true; if (!invalid) onSave(name.trim()) }) { Text(uiText("Salvează", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("Anulează", "Cancel")) } })
}

@Composable
private fun LinkDeleteDialog(title: String, explanation: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(containerColor = Background, onDismissRequest = onDismiss, icon = { Icon(Icons.Default.DeleteOutline, null) },
        title = { Text(title) }, text = { Text(explanation) },
        confirmButton = { TextButton(onClick = onDelete) { Text(uiText("Șterge", "Delete"), color = Red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("Păstrează", "Keep")) } })
}

@Composable
private fun LinkAssignDialog(
    link: SavedMusicLink,
    playlists: List<LinkPlaylist>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
    onToggle: (LinkPlaylist, Boolean) -> Unit,
) {
    var newName by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val invalid = newName.trim().isEmpty() || newName.trim().length > 80
    AlertDialog(containerColor = Background, onDismissRequest = onDismiss, title = { Text(uiText("Organizează în playlisturi", "Organize into playlists")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(link.title, color = Red, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(uiText("Bifează playlisturile în care vrei să păstrezi linkul. Modificările se salvează automat.", "Choose the playlists for this link. Changes save automatically."),
                    style = MaterialTheme.typography.bodySmall)
                if (playlists.isEmpty()) Text(uiText("Nu ai încă playlisturi. Creează unul mai jos.", "No playlists yet. Create one below."), color = Muted)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        val checked = link.id in playlist.linkIds
                        Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(playlist, it) }).padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Text(playlist.name, modifier = Modifier.weight(1f))
                        }
                    }
                }
                HorizontalDivider()
                OutlinedTextField(value = newName, onValueChange = { newName = it },
                    label = { Text(uiText("Playlist nou", "New playlist")) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = attempted && invalid,
                    supportingText = { if (attempted && invalid) Text(uiText("Folosește între 1 și 80 de caractere.", "Use between 1 and 80 characters.")) })
                TextButton(onClick = {
                    attempted = true
                    if (!invalid) { onCreate(newName.trim()); newName = ""; attempted = false }
                }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(uiText("Creează playlist", "Create playlist")) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(uiText("Gata", "Done")) } })
}

@Composable
private fun LinkManageDialog(
    playlist: LinkPlaylist,
    links: List<SavedMusicLink>,
    onDismiss: () -> Unit,
    onToggle: (SavedMusicLink, Boolean) -> Unit,
    onAddNew: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val visible = remember(links, query) { links.filter {
        it.title.contains(query.trim(), true) || it.url.contains(query.trim(), true)
    }.sortedBy { it.title.lowercase() } }
    AlertDialog(containerColor = Background, onDismissRequest = onDismiss, title = { Text(uiText("Linkuri în „${playlist.name}”", "Links in “${playlist.name}”")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(uiText("Bifează linkurile dorite. Modificările se salvează automat.", "Choose your links. Changes save automatically."), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text(uiText("Caută linkuri salvate", "Search saved links")) },
                    leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) })
                if (visible.isEmpty()) Text(if (links.isEmpty()) uiText("Adaugă primul link YouTube în bibliotecă.", "Add your first YouTube link to the library.") else uiText("Nu există linkuri pentru această căutare.", "No links match your search."), color = Muted)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(visible, key = { it.id }) { link ->
                        val checked = link.id in playlist.linkIds
                        Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(link, it) }).padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = null)
                            Column(Modifier.weight(1f)) {
                                Text(link.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (link.kind == LinkKind.YOUTUBE_PLAYLIST) uiText("Playlist YouTube", "YouTube playlist") else uiText("Videoclip YouTube", "YouTube video"),
                                    style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                    }
                }
                TextButton(onClick = onAddNew) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(uiText("Adaugă un link nou", "Add a new link"))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(uiText("Gata", "Done")) } })
}
