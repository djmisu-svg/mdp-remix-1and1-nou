package ro.mdp.remix.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Brush
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

private enum class LinkSection(val label: String, val icon: ImageVector) {
    ALL("Toate linkurile", Icons.Default.VideoLibrary),
    PLAYLISTS("Playlisturi", Icons.Default.QueueMusic),
    FAVORITES("Favorite", Icons.Default.FavoriteBorder),
    RECENT("Recente", Icons.Default.History),
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

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = Background) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(310.dp),
            state = gridState,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "brand", span = { GridItemSpan(maxLineSpan) }) {
                LinkBrandHeader(library.links.size, library.playlists.size)
            }
            item(key = "search", span = { GridItemSpan(maxLineSpan) }) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    label = { Text("Caută în biblioteca YouTube") },
                    placeholder = { Text("Titlu, link sau nume de playlist") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = if (query.isNotEmpty()) ({
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Șterge căutarea")
                        }
                    }) else null,
                )
            }
            item(key = "navigation", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinkSection.entries.forEach { destination ->
                        FilterChip(
                            selected = section == destination && selectedPlaylist == null,
                            onClick = { section = destination; selectedPlaylistId = null },
                            label = { Text(destination.label) },
                            leadingIcon = { Icon(destination.icon, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            item(key = "actions", span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { addingLink = true }, enabled = !loading, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Adaugă link")
                    }
                    OutlinedButton(onClick = { creatingPlaylist = true }, enabled = !loading, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PlaylistAdd, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Playlist nou")
                    }
                }
            }
            if (selectedPlaylist != null) {
                item(key = "playlist-detail", span = { GridItemSpan(maxLineSpan) }) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { selectedPlaylistId = null }) {
                                Icon(Icons.Default.ArrowBack, "Înapoi la bibliotecă")
                            }
                            Column(Modifier.weight(1f)) {
                                Text(selectedPlaylist.name, style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold)
                                Text("${selectedPlaylist.linkIds.size} linkuri · ordinea ta", color = Muted,
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
                            Text("Alege linkurile din playlist")
                        }
                    }
                }
            } else if (visiblePlaylists.isNotEmpty()) {
                item(key = "playlist-title", span = { GridItemSpan(maxLineSpan) }) {
                    LinkSectionTitle(if (section == LinkSection.RECENT) "Playlisturi accesate recent" else "Playlisturile tale",
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
                        selectedPlaylist != null -> "Linkuri în playlist"
                        section == LinkSection.FAVORITES -> "Linkuri favorite"
                        section == LinkSection.RECENT -> "Linkuri deschise recent"
                        else -> "Biblioteca ta YouTube"
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
                        CircularProgressIndicator(color = Gold)
                        Text("Se încarcă biblioteca salvată…", color = Muted)
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
                    Text("Linkurile se deschid în aplicația YouTube sau în browser. Redarea YouTube necesită internet. " +
                        "Titlurile și playlisturile tale sunt salvate local.",
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
    if (creatingPlaylist) LinkNameDialog("Playlist nou", "Nume playlist", "", 80,
        onDismiss = { creatingPlaylist = false }, onSave = {
            viewModel.createPlaylist(it)
            section = LinkSection.PLAYLISTS
            selectedPlaylistId = null
            query = ""
            creatingPlaylist = false
        })
    editingLink?.let { link ->
        LinkNameDialog("Redenumește linkul", "Titlu", link.title, 200,
            onDismiss = { editingLink = null }, onSave = {
                viewModel.renameLink(link.id, it)
                editingLink = null
            })
    }
    editingPlaylist?.let { playlist ->
        LinkNameDialog("Redenumește playlistul", "Nume playlist", playlist.name, 80,
            onDismiss = { editingPlaylist = null }, onSave = {
                viewModel.renamePlaylist(playlist.id, it)
                editingPlaylist = null
            })
    }
    deletingLink?.let { link ->
        LinkDeleteDialog("Ștergi linkul?", "„${link.title}” va fi eliminat din bibliotecă, favorite și toate playlisturile tale.",
            onDismiss = { deletingLink = null }, onDelete = {
                viewModel.deleteLink(link.id)
                deletingLink = null
            })
    }
    deletingPlaylist?.let { playlist ->
        LinkDeleteDialog("Ștergi playlistul?", "Playlistul „${playlist.name}” va fi șters. Linkurile rămân în bibliotecă.",
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
    Surface(shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, GoldDark), color = Panel) {
        Row(
            Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Panel, GoldDark.copy(alpha = .25f))))
                .padding(22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(shape = CircleShape, color = Gold, modifier = Modifier.size(62.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Text("MdP", color = Background, fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.titleLarge)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("MdP RemiX 1&1", style = MaterialTheme.typography.headlineSmall,
                    color = Gold, fontWeight = FontWeight.Bold)
                Text("Muzica ta. Selecția ta.", color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium)
                Text("$linkCount linkuri salvate · $playlistCount playlisturi", color = Muted,
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun LinkSectionTitle(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        Text(count.toString(), color = Gold, style = MaterialTheme.typography.labelLarge)
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
    Surface(shape = RoundedCornerShape(20.dp), color = Panel,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(12.dp), color = GoldDark.copy(alpha = .35f)) {
                    Icon(if (link.kind == LinkKind.YOUTUBE_PLAYLIST) Icons.Default.QueueMusic else Icons.Default.PlayArrow,
                        null, tint = Gold, modifier = Modifier.padding(10.dp).size(24.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(if (link.kind == LinkKind.YOUTUBE_PLAYLIST) "PLAYLIST YOUTUBE" else "VIDEO YOUTUBE",
                    color = Gold, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                LinkFavoriteButton(link.favorite, "link", onFavorite)
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, "Opțiuni pentru ${link.title}", tint = Muted)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("Redenumește") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                            onClick = { menuExpanded = false; onRename() })
                        DropdownMenuItem(text = { Text("Adaugă în playlisturi") }, leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) },
                            onClick = { menuExpanded = false; onAssign() })
                        if (onRemove != null) DropdownMenuItem(text = { Text("Scoate din acest playlist") },
                            leadingIcon = { Icon(Icons.Default.RemoveCircleOutline, null) },
                            onClick = { menuExpanded = false; onRemove() })
                        HorizontalDivider()
                        DropdownMenuItem(text = { Text("Șterge linkul", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onDelete() })
                    }
                }
            }
            Text(link.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(link.url, style = MaterialTheme.typography.bodySmall, color = Muted,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (playlistNames.isNotEmpty()) Text(playlistNames.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = Gold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = onOpen, modifier = Modifier.weight(1f)) {
                    Text("Deschide YouTube")
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.OpenInNew, null, Modifier.size(16.dp))
                }
                if (onMoveUp != null && onMoveDown != null) {
                    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Icon(Icons.Default.ArrowUpward, "Mută linkul mai sus", Modifier.size(20.dp))
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Icon(Icons.Default.ArrowDownward, "Mută linkul mai jos", Modifier.size(20.dp))
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
    Surface(shape = RoundedCornerShape(20.dp), color = Panel, border = BorderStroke(1.dp, GoldDark)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.QueueMusic, null, tint = Gold, modifier = Modifier.size(32.dp))
                Spacer(Modifier.weight(1f))
                LinkFavoriteButton(playlist.favorite, "playlist", onFavorite)
                LinkPlaylistMenu(onRename, onDelete, onMoveUp, onMoveDown, canMoveUp, canMoveDown)
            }
            Text(playlist.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text("${playlist.linkIds.size} linkuri salvate", style = MaterialTheme.typography.bodyMedium, color = Muted)
            OutlinedButton(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
                Text("Deschide playlistul")
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
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, "Opțiuni playlist", tint = Muted)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Redenumește") }, leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { expanded = false; onRename() })
            DropdownMenuItem(text = { Text("Mută mai sus") }, leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                enabled = canMoveUp, onClick = { expanded = false; onMoveUp() })
            DropdownMenuItem(text = { Text("Mută mai jos") }, leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                enabled = canMoveDown, onClick = { expanded = false; onMoveDown() })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Șterge playlistul", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Default.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
private fun LinkFavoriteButton(favorite: Boolean, kind: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            if (favorite) "Elimină $kind din favorite" else "Adaugă $kind la favorite",
            tint = if (favorite) Gold else Muted)
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
        searching -> "Niciun rezultat"
        inPlaylist -> "Un playlist pentru selecția ta"
        section == LinkSection.PLAYLISTS -> "Creează primul tău playlist"
        section == LinkSection.FAVORITES -> "Păstrează aproape ce îți place"
        section == LinkSection.RECENT -> "Selecțiile recente apar aici"
        else -> "Începe biblioteca MdP RemiX"
    }
    val description = when {
        searching -> "Încearcă alt titlu, link YouTube sau nume de playlist."
        inPlaylist -> "Alege linkuri salvate sau adaugă un link YouTube nou."
        section == LinkSection.PLAYLISTS -> "Grupează videoclipurile și playlisturile YouTube după stil, stare sau eveniment."
        section == LinkSection.FAVORITES -> "Apasă pe inimă lângă un link sau un playlist pentru a-l găsi aici."
        section == LinkSection.RECENT -> "Deschide un playlist sau un link din bibliotecă pentru a-l regăsi rapid."
        else -> "Salvează linkuri de videoclipuri și playlisturi YouTube, apoi organizează-le în colecțiile tale."
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(if (searching) Icons.Default.Search else section.icon, null, tint = Gold, modifier = Modifier.size(46.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = Muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        when {
            searching -> TextButton(onClick = onClearSearch) { Text("Șterge căutarea") }
            section == LinkSection.PLAYLISTS && !inPlaylist -> Button(onClick = onCreate) { Text("Creează playlist") }
            inPlaylist -> Button(onClick = onAdd) { Text("Alege linkuri") }
            section == LinkSection.ALL -> Button(onClick = onAdd) { Text("Adaugă primul link") }
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
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Link, null, tint = Gold) },
        title = { Text("Salvează un link YouTube") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Adaugă un videoclip sau un playlist YouTube. Alegi tu titlul afișat.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Link YouTube") },
                    placeholder = { Text("https://www.youtube.com/…") }, modifier = Modifier.fillMaxWidth(),
                    maxLines = 3, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    isError = attempted && urlError,
                    supportingText = {
                        if (attempted && urlError) Text("Introdu un link YouTube valid de videoclip sau playlist.")
                        else if (parsed != null) Text(if (parsed.kind == LinkKind.YOUTUBE_PLAYLIST) "Playlist YouTube recunoscut" else "Videoclip YouTube recunoscut")
                    })
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Titlu") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    isError = attempted && titleError,
                    supportingText = { if (attempted && titleError) Text("Alege un titlu între 1 și 200 de caractere.") })
                if (playlistName != null) Text("Va fi adăugat și în „$playlistName”.", color = Gold,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = {
            attempted = true
            if (!titleError && !urlError) onSave(title.trim(), url.trim())
        }) { Text("Salvează") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } },
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(label) },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                isError = attempted && invalid,
                supportingText = { if (attempted && invalid) Text("Folosește între 1 și $maxLength de caractere.") })
        },
        confirmButton = { Button(onClick = { attempted = true; if (!invalid) onSave(name.trim()) }) { Text("Salvează") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } })
}

@Composable
private fun LinkDeleteDialog(title: String, explanation: String, onDismiss: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.DeleteOutline, null) },
        title = { Text(title) }, text = { Text(explanation) },
        confirmButton = { TextButton(onClick = onDelete) { Text("Șterge", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Păstrează") } })
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Organizează în playlisturi") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(link.title, color = Gold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Bifează playlisturile în care vrei să păstrezi linkul. Modificările se salvează automat.",
                    style = MaterialTheme.typography.bodySmall)
                if (playlists.isEmpty()) Text("Nu ai încă playlisturi. Creează unul mai jos.", color = Muted)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                    items(playlists, key = { it.id }) { playlist ->
                        val checked = link.id in playlist.linkIds
                        Row(Modifier.fillMaxWidth().clickable { onToggle(playlist, !checked) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = { onToggle(playlist, it) })
                            Text(playlist.name, modifier = Modifier.weight(1f))
                        }
                    }
                }
                HorizontalDivider()
                OutlinedTextField(value = newName, onValueChange = { newName = it },
                    label = { Text("Playlist nou") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = attempted && invalid,
                    supportingText = { if (attempted && invalid) Text("Folosește între 1 și 80 de caractere.") })
                TextButton(onClick = {
                    attempted = true
                    if (!invalid) { onCreate(newName.trim()); newName = ""; attempted = false }
                }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Creează playlist") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Gata") } })
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
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Linkuri în „${playlist.name}”") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Bifează linkurile dorite. Modificările se salvează automat.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(), label = { Text("Caută linkuri salvate") },
                    leadingIcon = { Icon(Icons.Default.Search, null, Modifier.size(20.dp)) })
                if (visible.isEmpty()) Text(if (links.isEmpty()) "Adaugă primul link YouTube în bibliotecă." else "Nu există linkuri pentru această căutare.", color = Muted)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    items(visible, key = { it.id }) { link ->
                        val checked = link.id in playlist.linkIds
                        Row(Modifier.fillMaxWidth().clickable { onToggle(link, !checked) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, onCheckedChange = { onToggle(link, it) })
                            Column(Modifier.weight(1f)) {
                                Text(link.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (link.kind == LinkKind.YOUTUBE_PLAYLIST) "Playlist YouTube" else "Videoclip YouTube",
                                    style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                    }
                }
                TextButton(onClick = onAddNew) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Adaugă un link nou")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Icon(Icons.Default.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Gata") } })
}
