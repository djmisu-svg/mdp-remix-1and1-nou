package ro.mdp.remix.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ro.mdp.remix.data.LibrarySnapshot
import ro.mdp.remix.data.Playlist
import ro.mdp.remix.data.Track
import ro.mdp.remix.playback.PlaybackState

private enum class LibrarySection(val ro: String, val en: String, val icon: ImageVector) {
    SONGS("Melodii", "Tracks", Icons.Default.LibraryMusic),
    ALBUMS("Albume", "Albums", Icons.Default.Album),
    PLAYLISTS("Playlisturi", "Playlists", Icons.Default.QueueMusic),
    FAVORITES("Favorite", "Favorites", Icons.Default.FavoriteBorder),
}

@Composable
fun RemixApp(
    library: LibrarySnapshot,
    playback: PlaybackState,
    importing: Boolean,
    message: String?,
    onClearMessage: () -> Unit,
    onImport: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onFavorite: (String) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onAddToPlaylist: (String, String) -> Unit,
    onRemoveFromPlaylist: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onRemoveTrack: (String) -> Unit,
    onRenamePlaylist: (String, String) -> Unit,
    onMovePlaylist: (String, Int) -> Unit,
    onMoveTrackInPlaylist: (String, String, Int) -> Unit,
) {
    var section by rememberSaveable { mutableStateOf(LibrarySection.SONGS) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedAlbum by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var deckPowered by rememberSaveable { mutableStateOf(true) }
    val appContext = LocalContext.current.applicationContext
    var recorderDesign by remember(appContext) { mutableStateOf(RecorderDesignPreferences.load(appContext)) }
    var creatingPlaylist by rememberSaveable { mutableStateOf(false) }
    var trackForPlaylist by remember { mutableStateOf<Track?>(null) }
    var trackToRemove by remember { mutableStateOf<Track?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }
    val snackbar = remember { SnackbarHostState() }
    BackHandler(selectedAlbum != null || selectedPlaylistId != null || query.isNotBlank()) {
        if (selectedAlbum != null || selectedPlaylistId != null) {
            selectedAlbum = null
            selectedPlaylistId = null
        } else query = ""
    }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); onClearMessage() }
    }
    LaunchedEffect(library.playlists) {
        if (selectedPlaylistId != null && library.playlists.none { it.id == selectedPlaylistId }) selectedPlaylistId = null
    }

    Scaffold(containerColor = Background, snackbarHost = { SnackbarHost(snackbar) }) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets).testTag("local_library")) {
            val wide = maxWidth >= 900.dp && maxHeight >= 500.dp
            val deckWidth = (maxWidth * 0.44f).coerceAtMost(500.dp)
            val controls: @Composable () -> Unit = {
                LibraryControls(section, query, { item ->
                    section = item; selectedAlbum = null; selectedPlaylistId = null; query = ""
                }, { query = it })
            }
            val deck: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(18.dp).height(2.dp).background(Red))
                        Text(uiText("SESIUNE ANALOGICĂ", "ANALOG SESSION"), Modifier.padding(start = 10.dp),
                            color = Muted, fontSize = 9.sp, letterSpacing = 2.2.sp, fontWeight = FontWeight.Medium)
                    }
                    RecorderDesignSelector(recorderDesign, { design ->
                        RecorderDesignPreferences.save(appContext, design)
                        recorderDesign = design
                    }, Modifier.padding(bottom = 12.dp))
                    PremiumTapeDeck(
                        modifier = Modifier.fillMaxWidth(), playback = playback,
                        onTogglePlayback = onTogglePlayback, onNext = onNext, onPrevious = onPrevious,
                        onSeek = onSeek, onVolume = onVolume, onFavorite = onFavorite,
                        isFavorite = playback.currentTrackId in library.favorites,
                        poweredOverride = deckPowered, onPoweredChange = { deckPowered = it },
                        design = recorderDesign,
                    )
                    Text(uiText("FIȘIERE LOCALE / FĂRĂ CONT / OFFLINE", "LOCAL FILES / NO ACCOUNT / OFFLINE"),
                        color = Muted, fontSize = 8.sp, letterSpacing = 1.5.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 28.dp))
                }
            }
            Row(Modifier.fillMaxSize().padding(horizontal = if (wide) 32.dp else 20.dp), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                if (wide) {
                    Column(Modifier.width(deckWidth).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        BrandHeader(true, importing, onImport)
                        deck()
                    }
                }
                LibraryContent(
                    modifier = Modifier.weight(1f).fillMaxHeight(), library = library, playback = playback,
                    section = section, query = query, selectedAlbum = selectedAlbum, selectedPlaylistId = selectedPlaylistId,
                    importing = importing, onSelectAlbum = { selectedAlbum = it }, onSelectPlaylist = { selectedPlaylistId = it },
                    onImport = onImport, onPlayTracks = onPlayTracks, onTogglePlayback = onTogglePlayback,
                    onFavorite = onFavorite, onCreatePlaylist = { creatingPlaylist = true },
                    onAddToPlaylist = { trackForPlaylist = it }, onRemoveTrack = { trackToRemove = it },
                    onRemoveFromPlaylist = onRemoveFromPlaylist, onDeletePlaylist = { playlistToDelete = it },
                    onRenamePlaylist = { playlistToRename = it }, onMovePlaylist = onMovePlaylist,
                    onMoveTrackInPlaylist = onMoveTrackInPlaylist,
                    header = {
                        if (!wide) { BrandHeader(false, importing, onImport); deck() }
                        else Spacer(Modifier.height(24.dp))
                        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp), color = Red)
                        controls()
                    },
                )
            }
        }
    }
    if (creatingPlaylist) NewPlaylistDialog({ creatingPlaylist = false }) {
        onCreatePlaylist(it); creatingPlaylist = false
    }
    if (!creatingPlaylist) trackForPlaylist?.let { track ->
        PlaylistPickerDialog(track, library, { trackForPlaylist = null }, { id ->
            onAddToPlaylist(id, track.id); trackForPlaylist = null
        }, { creatingPlaylist = true })
    }
    playlistToRename?.let { playlist -> RenamePlaylistDialog(playlist, { playlistToRename = null }) {
        onRenamePlaylist(playlist.id, it); playlistToRename = null
    } }
    trackToRemove?.let { track ->
        AlertDialog(onDismissRequest = { trackToRemove = null },
            title = { Text(uiText("Elimini melodia?", "Remove track?")) },
            text = { Text(uiText("„${track.title}” va fi eliminată din bibliotecă, favorite și playlisturi. Fișierul de pe dispozitiv rămâne păstrat.",
                "“${track.title}” will be removed from your library, favorites and playlists. The file on your device is kept.")) },
            confirmButton = { TextButton(onClick = { onRemoveTrack(track.id); trackToRemove = null }) { Text(uiText("Elimină", "Remove")) } },
            dismissButton = { TextButton(onClick = { trackToRemove = null }) { Text(uiText("Anulează", "Cancel")) } })
    }
    playlistToDelete?.let { playlist ->
        AlertDialog(onDismissRequest = { playlistToDelete = null },
            title = { Text(uiText("Ștergi playlistul?", "Delete playlist?")) },
            text = { Text(uiText("Playlistul „${playlist.name}” va fi șters. Melodiile rămân în bibliotecă.",
                "“${playlist.name}” will be deleted. Your tracks stay in the library.")) },
            confirmButton = { TextButton(onClick = { onDeletePlaylist(playlist.id); playlistToDelete = null }) { Text(uiText("Șterge", "Delete")) } },
            dismissButton = { TextButton(onClick = { playlistToDelete = null }) { Text(uiText("Anulează", "Cancel")) } })
    }
}

@Composable
private fun BrandHeader(wide: Boolean, importing: Boolean, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 14.dp)) {
        Text(uiText("COLECȚIE LOCALĂ", "LOCAL COLLECTION"), color = Red, fontSize = 9.sp,
            letterSpacing = 2.8.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("MdP RemiX", color = Color.White, fontWeight = FontWeight.Light,
                    fontSize = if (wide) 35.sp else 30.sp, letterSpacing = (-1.3).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("1&1", color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp, fontWeight = FontWeight.Light, letterSpacing = 4.sp)
            }
            OutlinedButton(onClick = onImport, enabled = !importing, modifier = Modifier.testTag("import_audio"),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)), shape = RoundedCornerShape(4.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(17.dp), tint = Color.White)
                Spacer(Modifier.width(6.dp))
                Text(uiText("Importă", "Import"), color = Color.White, fontSize = 11.sp)
            }
        }
        HorizontalDivider(Modifier.padding(top = 18.dp), color = Color.White.copy(alpha = 0.12f))
    }
}

@Composable
private fun LibraryControls(section: LibrarySection, query: String, onSection: (LibrarySection) -> Unit, onQuery: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        LibrarySection.entries.forEach { item ->
            Column(Modifier.heightIn(min = 48.dp).selectable(selected = section == item, role = Role.Tab,
                onClick = { onSection(item) }).padding(top = 8.dp, bottom = 12.dp)) {
                Text(uiText(item.ro, item.en), color = if (section == item) Color.White else Muted,
                    fontSize = 12.sp, fontWeight = if (section == item) FontWeight.SemiBold else FontWeight.Normal)
                Box(Modifier.padding(top = 9.dp).height(2.dp).width(22.dp).background(if (section == item) Red else Color.Transparent))
            }
        }
    }
    OutlinedTextField(value = query, onValueChange = onQuery, modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
        singleLine = true, shape = RoundedCornerShape(4.dp),
        placeholder = { Text(uiText("Titlu, artist sau album", "Track, artist or album"), color = Muted, fontSize = 12.sp) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted, modifier = Modifier.size(18.dp)) },
        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, uiText("Șterge căutarea", "Clear search")) } },
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White.copy(alpha = 0.5f),
            unfocusedBorderColor = Color.White.copy(alpha = 0.13f), cursorColor = Red),
    )
}

@Composable
private fun LibraryContent(
    modifier: Modifier,
    library: LibrarySnapshot,
    playback: PlaybackState,
    section: LibrarySection,
    query: String,
    selectedAlbum: String?,
    selectedPlaylistId: String?,
    importing: Boolean,
    onSelectAlbum: (String?) -> Unit,
    onSelectPlaylist: (String?) -> Unit,
    onImport: () -> Unit,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onTogglePlayback: () -> Unit,
    onFavorite: (String) -> Unit,
    onCreatePlaylist: () -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onRemoveTrack: (Track) -> Unit,
    onRemoveFromPlaylist: (String, String) -> Unit,
    onDeletePlaylist: (Playlist) -> Unit,
    onRenamePlaylist: (Playlist) -> Unit,
    onMovePlaylist: (String, Int) -> Unit,
    onMoveTrackInPlaylist: (String, String, Int) -> Unit,
    header: @Composable () -> Unit = {},
) {
    val selectedPlaylist = library.playlists.firstOrNull { it.id == selectedPlaylistId }
    val albums = remember(library.tracks) { library.tracks.groupBy { it.album.ifBlank { "Album necunoscut" } } }
    val baseTracks = remember(library, section, selectedAlbum, selectedPlaylistId) {
        when (section) {
            LibrarySection.FAVORITES -> library.tracks.filter { it.id in library.favorites }
            LibrarySection.ALBUMS -> albums[selectedAlbum].orEmpty()
            LibrarySection.PLAYLISTS -> {
                val indexed = library.tracks.associateBy { it.id }
                selectedPlaylist?.trackIds?.mapNotNull { indexed[it] }.orEmpty()
            }
            LibrarySection.SONGS -> library.tracks
        }
    }
    val visibleTracks = remember(baseTracks, query) {
        baseTracks.filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) }
    }
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "library_header") { Column { header() } }
        when {
            section == LibrarySection.ALBUMS && selectedAlbum == null -> {
                val visibleAlbums = albums.filter { (name, tracks) ->
                    query.isBlank() || name.contains(query, true) || tracks.any { it.artist.contains(query, true) || it.title.contains(query, true) }
                }
                item { SectionTitle(uiText("Albumele tale", "Your albums"), uiText("${albums.size} albume", "${albums.size} albums")) }
                if (visibleAlbums.isEmpty()) item {
                    EmptyState(uiText(if (query.isNotBlank()) "Niciun album găsit" else "Colecția ta începe aici", if (query.isNotBlank()) "No albums found" else "Your collection starts here"),
                        uiText(if (query.isNotBlank()) "Încearcă un alt cuvânt de căutare." else "Importă melodii; albumele se organizează automat.", if (query.isNotBlank()) "Try another search." else "Import your tracks. Albums are organized automatically."),
                        Icons.Default.Album, if (query.isBlank()) onImport else null, importing = importing)
                } else items(visibleAlbums.entries.toList(), key = { "album-${it.key}" }) { album ->
                    CollectionCard(if (album.key == "Album necunoscut") uiText("Album necunoscut", "Unknown album") else album.key,
                        uiText("${album.value.size} melodii", "${album.value.size} tracks") + " / " + formatDuration(album.value.sumOf { it.durationMs }),
                        Icons.Default.Album, { onSelectAlbum(album.key) }) {
                        IconButton(onClick = { onPlayTracks(album.value, 0) }, enabled = playback.connected) {
                            Icon(Icons.Default.PlayArrow, uiText("Redă albumul ${album.key}", "Play album ${album.key}"), tint = Color.White)
                        }
                    }
                }
            }
            section == LibrarySection.PLAYLISTS && selectedPlaylist == null -> {
                val playlists = library.playlists.filter { query.isBlank() || it.name.contains(query, true) }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle(uiText("Playlisturile tale", "Your playlists"), uiText("${library.playlists.size} playlisturi", "${library.playlists.size} playlists"), Modifier.weight(1f))
                        IconButton(onClick = onCreatePlaylist, modifier = Modifier.testTag("local_playlist_new")) {
                            Icon(Icons.Default.PlaylistAdd, uiText("Creează playlist", "Create playlist"), tint = Red)
                        }
                    }
                }
                if (playlists.isEmpty()) item {
                    EmptyState(uiText(if (query.isNotBlank()) "Niciun playlist găsit" else "Un mix pentru fiecare moment", if (query.isNotBlank()) "No playlists found" else "A mix for every moment"),
                        uiText(if (query.isNotBlank()) "Încearcă un alt nume." else "Creează un playlist și adaugă melodii din meniul bibliotecii.", if (query.isNotBlank()) "Try another name." else "Create a playlist and add tracks from the library menu."),
                        Icons.Default.QueueMusic, if (query.isBlank()) onCreatePlaylist else null, uiText("Creează playlist", "Create playlist"))
                } else items(playlists, key = { "playlist-${it.id}" }) { playlist ->
                    CollectionCard(playlist.name, uiText("${playlist.trackIds.size} melodii", "${playlist.trackIds.size} tracks"), Icons.Default.QueueMusic, { onSelectPlaylist(playlist.id) }) {
                        PlaylistActions(playlist, library.playlists.firstOrNull()?.id != playlist.id, library.playlists.lastOrNull()?.id != playlist.id,
                            { onRenamePlaylist(playlist) }, { onDeletePlaylist(playlist) }, { onMovePlaylist(playlist.id, it) })
                    }
                }
            }
            else -> {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (selectedAlbum != null || selectedPlaylist != null) IconButton(onClick = { onSelectAlbum(null); onSelectPlaylist(null) }) {
                            Icon(Icons.Default.ArrowBack, uiText("Înapoi la colecții", "Back to collections"))
                        }
                        SectionTitle(selectedPlaylist?.name ?: selectedAlbum ?: uiText(if (section == LibrarySection.FAVORITES) "Melodiile preferate" else "Biblioteca ta", if (section == LibrarySection.FAVORITES) "Your favorites" else "Your library"),
                            uiText("${visibleTracks.size} melodii", "${visibleTracks.size} tracks"), Modifier.weight(1f))
                        if (selectedPlaylist != null) IconButton(onClick = { onRenamePlaylist(selectedPlaylist) }) {
                            Icon(Icons.Default.Edit, uiText("Redenumește playlistul ${selectedPlaylist.name}", "Rename playlist ${selectedPlaylist.name}"), tint = Muted)
                        }
                        if (visibleTracks.isNotEmpty()) IconButton(onClick = { onPlayTracks(visibleTracks, 0) }, enabled = playback.connected) {
                            Icon(Icons.Default.PlayArrow, uiText("Redă toate melodiile afișate", "Play all displayed tracks"), tint = Red)
                        }
                    }
                }
                if (visibleTracks.isEmpty()) item {
                    val title = when {
                        query.isNotBlank() -> uiText("Nicio melodie găsită", "No tracks found")
                        section == LibrarySection.FAVORITES -> uiText("Păstrează ce îți place", "Keep what you love")
                        selectedPlaylist != null -> uiText("Playlistul este pregătit", "Your playlist is ready")
                        else -> uiText("Dă-i play colecției tale", "Press play on your collection")
                    }
                    val subtitle = when {
                        query.isNotBlank() -> uiText("Caută după titlu, artist sau album.", "Search by track, artist or album.")
                        section == LibrarySection.FAVORITES -> uiText("Atinge inima de lângă o melodie pentru a o găsi aici.", "Tap a track’s heart to find it here.")
                        selectedPlaylist != null -> uiText("Adaugă melodii din meniul ⋮ al bibliotecii.", "Add tracks from the library’s ⋮ menu.")
                        else -> uiText("Importă fișiere audio de pe dispozitiv. Ascultă fără cont, chiar și offline.", "Import audio files from your device. Listen without an account, even offline.")
                    }
                    EmptyState(title, subtitle, if (section == LibrarySection.FAVORITES) Icons.Default.FavoriteBorder else Icons.Default.MusicNote,
                        if (library.tracks.isEmpty() && query.isBlank()) onImport else null, importing = importing)
                } else items(visibleTracks, key = { "track-${it.id}" }) { track ->
                    TrackRow(track, playback.currentTrackId == track.id, playback.isPlaying && playback.currentTrackId == track.id,
                        track.id in library.favorites,
                        { if (playback.currentTrackId == track.id) onTogglePlayback() else onPlayTracks(visibleTracks, visibleTracks.indexOf(track)) },
                        { onFavorite(track.id) }, { onAddToPlaylist(track) }, { onRemoveTrack(track) },
                        selectedPlaylist?.let { playlist -> { onRemoveFromPlaylist(playlist.id, track.id) } },
                        selectedPlaylist?.takeIf { it.trackIds.firstOrNull() != track.id }?.let { playlist -> { onMoveTrackInPlaylist(playlist.id, track.id, -1) } },
                        selectedPlaylist?.takeIf { it.trackIds.lastOrNull() != track.id }?.let { playlist -> { onMoveTrackInPlaylist(playlist.id, track.id, 1) } })
                }
            }
        }
    }
}

@Composable
private fun PlaylistActions(playlist: Playlist, canMoveUp: Boolean, canMoveDown: Boolean, onRename: () -> Unit, onDelete: () -> Unit, onMove: (Int) -> Unit) {
    var expanded by remember(playlist.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Default.MoreVert, uiText("Opțiuni pentru playlistul ${playlist.name}", "Options for playlist ${playlist.name}"), tint = Muted) }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text(uiText("Redenumește", "Rename")) }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { expanded = false; onRename() })
            DropdownMenuItem(text = { Text(uiText("Mută mai sus", "Move up")) }, leadingIcon = { Icon(Icons.Default.ArrowUpward, null) }, enabled = canMoveUp, onClick = { expanded = false; onMove(-1) })
            DropdownMenuItem(text = { Text(uiText("Mută mai jos", "Move down")) }, leadingIcon = { Icon(Icons.Default.ArrowDownward, null) }, enabled = canMoveDown, onClick = { expanded = false; onMove(1) })
            DropdownMenuItem(text = { Text(uiText("Șterge playlistul", "Delete playlist")) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { expanded = false; onDelete() })
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp, bottom = 14.dp)) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = Muted, fontSize = 10.sp, letterSpacing = 0.4.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

@Composable
private fun EmptyState(title: String, subtitle: String, icon: ImageVector, onAction: (() -> Unit)? = null,
    actionLabel: String = "", importing: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(28.dp), tint = Color.White.copy(alpha = 0.5f))
        Text(title, color = Color.White, fontWeight = FontWeight.Light, fontSize = 20.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp).fillMaxWidth())
        Text(subtitle, color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 19.sp,
            modifier = Modifier.padding(top = 9.dp).fillMaxWidth())
        if (onAction != null) OutlinedButton(onClick = onAction, enabled = !importing,
            modifier = Modifier.padding(top = 20.dp), shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))) {
            if (importing) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 1.dp)
            else Icon(Icons.Default.Add, null, Modifier.size(17.dp), tint = Color.White)
            Spacer(Modifier.width(7.dp))
            Text(if (importing) uiText("Se importă…", "Importing…") else if (actionLabel.isBlank()) uiText("Importă muzică", "Import music") else actionLabel, color = Color.White)
        }
    }
}

@Composable
private fun CollectionCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, actions: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(3.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(21.dp), tint = Color.White.copy(alpha = 0.65f))
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
            }
            actions()
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.09f))
    }
}

@Composable
private fun TrackRow(track: Track, active: Boolean, isPlaying: Boolean, favorite: Boolean, onPlay: () -> Unit,
    onFavorite: () -> Unit, onAddToPlaylist: () -> Unit, onRemove: () -> Unit, onRemoveFromPlaylist: (() -> Unit)?,
    onMoveUp: (() -> Unit)?, onMoveDown: (() -> Unit)?) {
    var menuOpen by remember(track.id) { mutableStateOf(false) }
    val playLabel = if (isPlaying) uiText("Pauză", "Pause") else uiText("Redă ${track.title}", "Play ${track.title}")
    Column(Modifier.fillMaxWidth().background(if (active) Red.copy(alpha = 0.1f) else Color.Transparent).clickable(onClickLabel = playLabel, onClick = onPlay)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                Icon(if (isPlaying) Icons.Default.Pause else if (active) Icons.Default.PlayArrow else Icons.Default.MusicNote,
                    null, Modifier.size(19.dp), tint = if (active) Red else Color.White.copy(alpha = 0.45f))
            }
            Column(Modifier.weight(1f).padding(start = 8.dp, end = 4.dp)) {
                Text(track.title, color = Color.White, fontSize = 13.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                val artist = if (track.artist.isBlank() || track.artist == "Artist necunoscut") uiText("Artist necunoscut", "Unknown artist") else track.artist
                Text("$artist / ${formatDuration(track.durationMs)}", color = Muted, fontSize = 10.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
            }
            IconButton(onClick = onFavorite) {
                Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (favorite) uiText("Elimină ${track.title} din favorite", "Remove ${track.title} from favorites") else uiText("Adaugă ${track.title} la favorite", "Favorite ${track.title}"),
                    Modifier.size(19.dp), tint = if (favorite) Red else Muted)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, uiText("Opțiuni pentru ${track.title}", "Options for ${track.title}"), tint = Muted) }
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(uiText("Adaugă în playlist", "Add to playlist")) }, leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) }, onClick = { menuOpen = false; onAddToPlaylist() })
                    if (onRemoveFromPlaylist != null) {
                        DropdownMenuItem(text = { Text(uiText("Mută mai sus în playlist", "Move up in playlist")) }, leadingIcon = { Icon(Icons.Default.ArrowUpward, null) }, enabled = onMoveUp != null, onClick = { menuOpen = false; onMoveUp?.invoke() })
                        DropdownMenuItem(text = { Text(uiText("Mută mai jos în playlist", "Move down in playlist")) }, leadingIcon = { Icon(Icons.Default.ArrowDownward, null) }, enabled = onMoveDown != null, onClick = { menuOpen = false; onMoveDown?.invoke() })
                        DropdownMenuItem(text = { Text(uiText("Elimină din playlist", "Remove from playlist")) }, leadingIcon = { Icon(Icons.Default.Close, null) }, onClick = { menuOpen = false; onRemoveFromPlaylist() })
                    }
                    DropdownMenuItem(text = { Text(uiText("Elimină din bibliotecă", "Remove from library")) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { menuOpen = false; onRemove() })
                }
            }
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
    }
}

@Composable
private fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.QueueMusic, null, tint = Red) },
        title = { Text(uiText("Playlist nou", "New playlist")) },
        text = { OutlinedTextField(name, { name = it.take(80) }, label = { Text(uiText("Numele playlistului", "Playlist name")) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text(uiText("Creează", "Create")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("Anulează", "Cancel")) } })
}

@Composable
private fun RenamePlaylistDialog(playlist: Playlist, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by rememberSaveable(playlist.id) { mutableStateOf(playlist.name) }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.Edit, null, tint = Red) },
        title = { Text(uiText("Redenumește playlistul", "Rename playlist")) },
        text = { OutlinedTextField(name, { name = it.take(128) }, label = { Text(uiText("Numele playlistului", "Playlist name")) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onRename(name.trim()) }, enabled = name.isNotBlank()) { Text(uiText("Salvează", "Save")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("Anulează", "Cancel")) } })
}

@Composable
private fun PlaylistPickerDialog(track: Track, library: LibrarySnapshot, onDismiss: () -> Unit, onSelect: (String) -> Unit, onCreate: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(uiText("Adaugă în playlist", "Add to playlist")) },
        text = {
            Column {
                Text(track.title, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.dp))
                if (library.playlists.isEmpty()) Text(uiText("Creează un playlist pentru această melodie.", "Create a playlist for this track."), color = Muted)
                LazyColumn(Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(library.playlists, key = { it.id }) { playlist ->
                        val alreadyAdded = track.id in playlist.trackIds
                        Surface(modifier = Modifier.fillMaxWidth().clickable(enabled = !alreadyAdded) { onSelect(playlist.id) },
                            shape = RoundedCornerShape(4.dp), color = Color.White.copy(alpha = 0.04f), border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f))) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (alreadyAdded) Icons.Default.Check else Icons.Default.QueueMusic, null, Modifier.size(20.dp), tint = if (alreadyAdded) Red else Color.White)
                                Text(playlist.name, Modifier.weight(1f).padding(start = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCreate) { Text(uiText("Playlist nou", "New playlist")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("Închide", "Close")) } })
}

internal fun formatDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1_000L
    val minutes = seconds / 60L
    return if (minutes >= 60) "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    else "$minutes:${(seconds % 60).toString().padStart(2, '0')}"
}
