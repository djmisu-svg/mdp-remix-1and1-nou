package ro.mdp.remix.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ro.mdp.remix.data.LibrarySnapshot
import ro.mdp.remix.data.Playlist
import ro.mdp.remix.data.Track
import ro.mdp.remix.playback.PlaybackState

private enum class LibrarySection(val label: String, val icon: ImageVector) {
    SONGS("Melodii", Icons.Default.LibraryMusic),
    ALBUMS("Albume", Icons.Default.Album),
    PLAYLISTS("Playlisturi", Icons.Default.QueueMusic),
    FAVORITES("Favorite", Icons.Default.FavoriteBorder),
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
    var creatingPlaylist by rememberSaveable { mutableStateOf(false) }
    var trackForPlaylist by remember { mutableStateOf<Track?>(null) }
    var trackToRemove by remember { mutableStateOf<Track?>(null) }
    var playlistToDelete by remember { mutableStateOf<Playlist?>(null) }
    var playlistToRename by remember { mutableStateOf<Playlist?>(null) }
    val snackbar = remember { SnackbarHostState() }
    BackHandler(enabled = selectedAlbum != null || selectedPlaylistId != null || query.isNotBlank()) {
        if (selectedAlbum != null || selectedPlaylistId != null) {
            selectedAlbum = null
            selectedPlaylistId = null
        } else {
            query = ""
        }
    }
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            onClearMessage()
        }
    }
    LaunchedEffect(library.playlists) {
        if (selectedPlaylistId != null && library.playlists.none { it.id == selectedPlaylistId }) {
            selectedPlaylistId = null
        }
    }

    Scaffold(
        containerColor = Background,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
            val wide = maxWidth >= 840.dp || (maxWidth >= 520.dp && maxWidth > maxHeight)
            val playerWidth = if (maxWidth < 720.dp) 240.dp else 320.dp
            val compactHeight = maxHeight < 740.dp
            Column(Modifier.fillMaxSize().padding(horizontal = if (maxWidth >= 840.dp) 28.dp else 16.dp)) {
                BrandHeader(maxWidth >= 720.dp, importing, onImport)
                if (importing) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 10.dp), color = Gold)
                }
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            LibrarySection.entries.forEach { item ->
                                FilterChip(
                                    selected = section == item,
                                    onClick = {
                                        section = item
                                        selectedAlbum = null
                                        selectedPlaylistId = null
                                        query = ""
                                    },
                                    label = { Text(item.label) },
                                    leadingIcon = { Icon(item.icon, null, Modifier.size(17.dp)) },
                                )
                            }
                        }
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 12.dp),
                            label = { Text("Caută în bibliotecă") },
                            placeholder = { Text("Titlu, artist sau album", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Default.Search, null, tint = Muted) },
                            trailingIcon = {
                                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Default.Close, "Șterge căutarea")
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(16.dp),
                        )
                        LibraryContent(
                            modifier = Modifier.weight(1f),
                            library = library,
                            playback = playback,
                            section = section,
                            query = query,
                            selectedAlbum = selectedAlbum,
                            selectedPlaylistId = selectedPlaylistId,
                            importing = importing,
                            onSelectAlbum = { selectedAlbum = it },
                            onSelectPlaylist = { selectedPlaylistId = it },
                            onImport = onImport,
                            onPlayTracks = onPlayTracks,
                            onTogglePlayback = onTogglePlayback,
                            onFavorite = onFavorite,
                            onCreatePlaylist = { creatingPlaylist = true },
                            onAddToPlaylist = { trackForPlaylist = it },
                            onRemoveTrack = { trackToRemove = it },
                            onRemoveFromPlaylist = onRemoveFromPlaylist,
                            onDeletePlaylist = { playlistToDelete = it },
                            onRenamePlaylist = { playlistToRename = it },
                            onMovePlaylist = onMovePlaylist,
                            onMoveTrackInPlaylist = onMoveTrackInPlaylist,
                        )
                        if (!wide) {
                            PlayerPanel(
                                Modifier.fillMaxWidth().padding(vertical = 10.dp)
                                    .heightIn(max = (maxHeight * 0.38f).coerceAtLeast(100.dp)),
                                playback, library.favorites, false, compactHeight,
                                onTogglePlayback, onNext, onPrevious, onSeek, onVolume, onFavorite,
                            )
                        }
                    }
                    if (wide) {
                        PlayerPanel(
                            Modifier.width(playerWidth).fillMaxHeight().padding(top = 7.dp, bottom = 18.dp),
                            playback, library.favorites, true, compactHeight,
                            onTogglePlayback, onNext, onPrevious, onSeek, onVolume, onFavorite,
                        )
                    }
                }
            }
        }
    }
    if (creatingPlaylist) {
        NewPlaylistDialog(
            onDismiss = { creatingPlaylist = false },
            onCreate = {
                onCreatePlaylist(it)
                creatingPlaylist = false
            },
        )
    }
    if (!creatingPlaylist) trackForPlaylist?.let { track ->
        PlaylistPickerDialog(
            track = track,
            library = library,
            onDismiss = { trackForPlaylist = null },
            onSelect = { id ->
                onAddToPlaylist(id, track.id)
                trackForPlaylist = null
            },
            onCreate = { creatingPlaylist = true },
        )
    }
    playlistToRename?.let { playlist ->
        RenamePlaylistDialog(
            playlist = playlist,
            onDismiss = { playlistToRename = null },
            onRename = { name ->
                onRenamePlaylist(playlist.id, name)
                playlistToRename = null
            },
        )
    }
    trackToRemove?.let { track ->
        AlertDialog(
            onDismissRequest = { trackToRemove = null },
            title = { Text("Elimini melodia?") },
            text = { Text("„${track.title}” va fi eliminată din bibliotecă, favorite și playlisturi. Fișierul din memoria telefonului rămâne păstrat.") },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveTrack(track.id)
                    trackToRemove = null
                }) { Text("Elimină") }
            },
            dismissButton = { TextButton(onClick = { trackToRemove = null }) { Text("Anulează") } },
        )
    }
    playlistToDelete?.let { playlist ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            title = { Text("Ștergi playlistul?") },
            text = { Text("Playlistul „${playlist.name}” va fi șters. Melodiile rămân în bibliotecă.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeletePlaylist(playlist.id)
                    playlistToDelete = null
                }) { Text("Șterge") }
            },
            dismissButton = { TextButton(onClick = { playlistToDelete = null }) { Text("Anulează") } },
        )
    }
}

@Composable
private fun BrandHeader(wide: Boolean, importing: Boolean, onImport: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monogram(Modifier.size(if (wide) 60.dp else 46.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                "MdP RemiX 1&1",
                fontSize = if (wide) 25.sp else 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.2.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("MUZICA TA, ORIUNDE", color = Muted, fontSize = 9.sp, letterSpacing = 1.8.sp)
        }
        if (wide) {
            Button(onClick = onImport, enabled = !importing, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Add, null, Modifier.size(19.dp))
                Spacer(Modifier.width(7.dp))
                Text("Adaugă muzică")
            }
        } else {
            FilledTonalIconButton(onClick = onImport, enabled = !importing, modifier = Modifier.size(46.dp)) {
                Icon(Icons.Default.Add, "Adaugă melodii audio din memoria dispozitivului")
            }
        }
    }
}

@Composable
private fun Monogram(modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(15.dp)).background(
            Brush.linearGradient(listOf(Color(0xFF4B3920), Color(0xFF1A1917))),
        ).clearAndSetSemantics { contentDescription = "Sigla MdP RemiX 1&1" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(Gold.copy(alpha = 0.6f), style = Stroke(1.2.dp.toPx()))
            drawLine(Gold.copy(alpha = 0.55f), Offset(size.width * 0.22f, size.height * 0.76f), Offset(size.width * 0.78f, size.height * 0.76f), 1.dp.toPx())
        }
        Text("MdP", color = Gold, fontWeight = FontWeight.Black, fontSize = 16.sp, letterSpacing = (-1).sp)
    }
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
        baseTracks.filter {
            query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
        }
    }
    Column(modifier.fillMaxWidth()) {
        when {
            section == LibrarySection.ALBUMS && selectedAlbum == null -> {
                SectionTitle("Albumele tale", "${albums.size} albume")
                val visibleAlbums = albums.filter { (name, tracks) ->
                    query.isBlank() || name.contains(query, true) || tracks.any { it.artist.contains(query, true) || it.title.contains(query, true) }
                }
                if (visibleAlbums.isEmpty()) {
                    EmptyState(
                        if (query.isNotBlank()) "Niciun album găsit" else "Colecția ta începe aici",
                        if (query.isNotBlank()) "Încearcă un alt cuvânt de căutare." else "Adaugă melodii și albumele se vor organiza automat.",
                        Icons.Default.Album,
                        if (query.isBlank()) onImport else null,
                        importing = importing,
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visibleAlbums.entries.toList(), key = { it.key }) { album ->
                            CollectionCard(album.key, "${album.value.size} melodii • ${formatDuration(album.value.sumOf { it.durationMs })}", Icons.Default.Album, { onSelectAlbum(album.key) }) {
                                IconButton(onClick = { onPlayTracks(album.value, 0) }, enabled = playback.connected) {
                                    Icon(Icons.Default.PlayArrow, "Redă albumul ${album.key}", tint = Gold)
                                }
                            }
                        }
                    }
                }
            }
            section == LibrarySection.PLAYLISTS && selectedPlaylist == null -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Playlisturile tale", "${library.playlists.size} playlisturi", Modifier.weight(1f))
                    IconButton(onClick = onCreatePlaylist) { Icon(Icons.Default.PlaylistAdd, "Creează playlist", tint = Gold) }
                }
                val playlists = library.playlists.filter { query.isBlank() || it.name.contains(query, true) }
                if (playlists.isEmpty()) {
                    EmptyState(
                        if (query.isNotBlank()) "Niciun playlist găsit" else "Un mix pentru fiecare moment",
                        if (query.isNotBlank()) "Încearcă un alt nume." else "Creează un playlist, apoi adaugă melodii din meniul bibliotecii.",
                        Icons.Default.QueueMusic,
                        if (query.isBlank()) onCreatePlaylist else null,
                        actionLabel = "Creează playlist",
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            CollectionCard(playlist.name, "${playlist.trackIds.size} melodii", Icons.Default.QueueMusic, { onSelectPlaylist(playlist.id) }) {
                                PlaylistActions(
                                    playlist = playlist,
                                    canMoveUp = library.playlists.firstOrNull()?.id != playlist.id,
                                    canMoveDown = library.playlists.lastOrNull()?.id != playlist.id,
                                    onRename = { onRenamePlaylist(playlist) },
                                    onDelete = { onDeletePlaylist(playlist) },
                                    onMove = { direction -> onMovePlaylist(playlist.id, direction) },
                                )
                            }
                        }
                    }
                }
            }
            else -> {
                val isCollection = selectedAlbum != null || selectedPlaylist != null
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (isCollection) {
                        IconButton(onClick = {
                            onSelectAlbum(null)
                            onSelectPlaylist(null)
                        }) { Icon(Icons.Default.ArrowBack, "Înapoi la colecții") }
                    }
                    SectionTitle(
                        selectedPlaylist?.name ?: selectedAlbum ?: if (section == LibrarySection.FAVORITES) "Melodiile preferate" else "Biblioteca ta",
                        "${visibleTracks.size} melodii",
                        Modifier.weight(1f),
                    )
                    if (selectedPlaylist != null) {
                        IconButton(onClick = { onRenamePlaylist(selectedPlaylist) }) {
                            Icon(Icons.Default.Edit, "Redenumește playlistul ${selectedPlaylist.name}", tint = Muted)
                        }
                    }
                    if (visibleTracks.isNotEmpty()) {
                        FilledTonalIconButton(onClick = { onPlayTracks(visibleTracks, 0) }, enabled = playback.connected) {
                            Icon(Icons.Default.PlayArrow, "Redă toate melodiile afișate")
                        }
                    }
                }
                if (visibleTracks.isEmpty()) {
                    val title = when {
                        query.isNotBlank() -> "Nicio melodie găsită"
                        section == LibrarySection.FAVORITES -> "Păstrează ce îți place"
                        selectedPlaylist != null -> "Playlistul este pregătit"
                        else -> "Dă-i play colecției tale"
                    }
                    val subtitle = when {
                        query.isNotBlank() -> "Caută după titlu, artist sau album."
                        section == LibrarySection.FAVORITES -> "Atinge inima de lângă o melodie pentru a o găsi aici."
                        selectedPlaylist != null -> "Adaugă melodii din meniul ⋮ al bibliotecii."
                        else -> "Alege fișiere audio de pe telefon sau tabletă. Ascultă-le fără cont, chiar și offline."
                    }
                    EmptyState(title, subtitle, if (section == LibrarySection.FAVORITES) Icons.Default.FavoriteBorder else Icons.Default.MusicNote, if (library.tracks.isEmpty() && query.isBlank()) onImport else null, importing = importing)
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(visibleTracks, key = { it.id }) { track ->
                            TrackRow(
                                track = track,
                                active = playback.currentTrackId == track.id,
                                isPlaying = playback.isPlaying && playback.currentTrackId == track.id,
                                favorite = track.id in library.favorites,
                                onPlay = {
                                    if (playback.currentTrackId == track.id) onTogglePlayback()
                                    else onPlayTracks(visibleTracks, visibleTracks.indexOf(track))
                                },
                                onFavorite = { onFavorite(track.id) },
                                onAddToPlaylist = { onAddToPlaylist(track) },
                                onRemove = { onRemoveTrack(track) },
                                onRemoveFromPlaylist = selectedPlaylist?.let { playlist -> { onRemoveFromPlaylist(playlist.id, track.id) } },
                                onMoveUp = selectedPlaylist?.takeIf { it.trackIds.firstOrNull() != track.id }
                                    ?.let { playlist -> { onMoveTrackInPlaylist(playlist.id, track.id, -1) } },
                                onMoveDown = selectedPlaylist?.takeIf { it.trackIds.lastOrNull() != track.id }
                                    ?.let { playlist -> { onMoveTrackInPlaylist(playlist.id, track.id, 1) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaylistActions(
    playlist: Playlist,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int) -> Unit,
) {
    var expanded by remember(playlist.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, "Opțiuni pentru playlistul ${playlist.name}", tint = Muted)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Redenumește") },
                leadingIcon = { Icon(Icons.Default.Edit, null) },
                onClick = { expanded = false; onRename() },
            )
            DropdownMenuItem(
                text = { Text("Mută mai sus") },
                leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                enabled = canMoveUp,
                onClick = { expanded = false; onMove(-1) },
            )
            DropdownMenuItem(
                text = { Text("Mută mai jos") },
                leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                enabled = canMoveDown,
                onClick = { expanded = false; onMove(1) },
            )
            DropdownMenuItem(
                text = { Text("Șterge playlistul") },
                leadingIcon = { Icon(Icons.Default.DeleteOutline, null) },
                onClick = { expanded = false; onDelete() },
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 5.dp, bottom = 12.dp)) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, fontSize = 12.sp, color = Muted, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun EmptyState(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onAction: (() -> Unit)? = null,
    actionLabel: String = "Adaugă muzică",
    importing: Boolean = false,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        item {
            Surface(shape = CircleShape, color = Gold.copy(alpha = 0.08f), border = BorderStroke(1.dp, Gold.copy(alpha = 0.2f))) {
                Icon(icon, null, Modifier.padding(17.dp).size(32.dp), tint = Gold)
            }
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp).fillMaxWidth())
            Text(subtitle, color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp).fillMaxWidth())
            if (onAction != null) {
                Button(onClick = onAction, enabled = !importing, modifier = Modifier.padding(top = 20.dp), shape = RoundedCornerShape(14.dp)) {
                    if (importing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (importing) "Se importă…" else actionLabel)
                }
            }
        }
    }
}

@Composable
private fun CollectionCard(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit, actions: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(17.dp), color = Panel, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(GoldDark.copy(alpha = 0.6f), Color(0xFF202123)))), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(27.dp), tint = Gold)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            actions()
        }
    }
}

@Composable
private fun TrackRow(
    track: Track,
    active: Boolean,
    isPlaying: Boolean,
    favorite: Boolean,
    onPlay: () -> Unit,
    onFavorite: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemove: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        color = if (active) Gold.copy(alpha = 0.09f) else Color.Transparent,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(
            onClickLabel = if (isPlaying) "Pauză" else "Redă ${track.title}",
            onClick = onPlay,
        ),
    ) {
        Row(Modifier.padding(start = 8.dp, top = 9.dp, bottom = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)).background(if (active) GoldDark.copy(alpha = 0.45f) else Panel), contentAlignment = Alignment.Center) {
                Icon(
                    when {
                        isPlaying -> Icons.Default.Pause
                        active -> Icons.Default.PlayArrow
                        else -> Icons.Default.MusicNote
                    },
                    null,
                    Modifier.size(23.dp),
                    tint = if (active) Gold else Muted,
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                Text(track.title, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (active) Gold else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${track.artist.ifBlank { "Artist necunoscut" }} • ${formatDuration(track.durationMs)}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            IconButton(onClick = onFavorite) {
                Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (favorite) "Elimină ${track.title} din favorite" else "Adaugă ${track.title} la favorite", Modifier.size(21.dp), tint = if (favorite) Gold else Muted)
            }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, "Opțiuni pentru ${track.title}", tint = Muted) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Adaugă în playlist") }, leadingIcon = { Icon(Icons.Default.PlaylistAdd, null) }, onClick = {
                        menuOpen = false
                        onAddToPlaylist()
                    })
                    if (onRemoveFromPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("Mută mai sus în playlist") },
                            leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                            enabled = onMoveUp != null,
                            onClick = { menuOpen = false; onMoveUp?.invoke() },
                        )
                        DropdownMenuItem(
                            text = { Text("Mută mai jos în playlist") },
                            leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                            enabled = onMoveDown != null,
                            onClick = { menuOpen = false; onMoveDown?.invoke() },
                        )
                        DropdownMenuItem(text = { Text("Elimină din playlist") }, leadingIcon = { Icon(Icons.Default.Close, null) }, onClick = {
                            menuOpen = false
                            onRemoveFromPlaylist()
                        })
                    }
                    DropdownMenuItem(text = { Text("Elimină din bibliotecă") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = {
                        menuOpen = false
                        onRemove()
                    })
                }
            }
        }
    }
}

@Composable
private fun PlayerPanel(
    modifier: Modifier,
    playback: PlaybackState,
    favorites: Set<String>,
    expanded: Boolean,
    compactHeight: Boolean,
    onTogglePlayback: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onFavorite: (String) -> Unit,
) {
    val hasTrack = playback.currentTrackId != null
    val controlsEnabled = playback.connected && hasTrack
    val duration = playback.durationMs.coerceAtLeast(0L)
    var seeking by remember(playback.currentTrackId) { mutableStateOf(false) }
    var seekFraction by remember(playback.currentTrackId) { mutableFloatStateOf(0f) }
    val fraction = if (duration > 0) (playback.positionMs.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Panel,
        border = BorderStroke(1.dp, Gold.copy(alpha = 0.13f)),
    ) {
        val contentModifier = Modifier.verticalScroll(rememberScrollState())
            .padding(if (expanded) 22.dp else 14.dp)
        Column(contentModifier, horizontalAlignment = Alignment.CenterHorizontally) {
            if (expanded) {
                Text("ACUM ÎN REDARE", color = Gold, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.padding(bottom = 16.dp))
                if (!compactHeight) {
                    RecordArtwork(Modifier.fillMaxWidth().aspectRatio(1f))
                    Spacer(Modifier.height(22.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!expanded) {
                    RecordArtwork(Modifier.size(43.dp))
                    Spacer(Modifier.width(11.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(if (hasTrack) playback.title else "Muzica ta. Ritmul tău.", fontWeight = FontWeight.SemiBold, fontSize = if (expanded) 20.sp else 14.sp, maxLines = if (expanded) 2 else 1, overflow = TextOverflow.Ellipsis)
                    Text(if (hasTrack) playback.artist.ifBlank { "Artist necunoscut" } else "Alege o melodie din bibliotecă", color = Muted, fontSize = if (expanded) 13.sp else 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                if (hasTrack) {
                    IconButton(onClick = { playback.currentTrackId?.let(onFavorite) }) {
                        val favorite = playback.currentTrackId in favorites
                        Icon(if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (favorite) "Elimină melodia curentă din favorite" else "Adaugă melodia curentă la favorite", tint = if (favorite) Gold else Muted)
                    }
                }
            }
            Slider(
                value = if (seeking) seekFraction else fraction,
                onValueChange = { seeking = true; seekFraction = it },
                onValueChangeFinished = {
                    onSeek((seekFraction * duration).toLong())
                    seeking = false
                },
                enabled = controlsEnabled && duration > 0,
                modifier = Modifier.fillMaxWidth().height(if (expanded) 40.dp else 28.dp).padding(top = if (expanded) 10.dp else 3.dp).semantics {
                    contentDescription = "Poziția redării"
                    stateDescription = "${formatDuration(if (seeking) (seekFraction * duration).toLong() else playback.positionMs)} din ${formatDuration(duration)}"
                },
                colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Color(0xFF3A3A3D)),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(if (seeking) (seekFraction * duration).toLong() else playback.positionMs), color = Muted, fontSize = 10.sp)
                Text(formatDuration(duration), color = Muted, fontSize = 10.sp)
            }
            Row(
                Modifier.fillMaxWidth().padding(top = if (expanded) 15.dp else 1.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious, enabled = controlsEnabled, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipPrevious, "Melodia anterioară", Modifier.size(29.dp))
                }
                Spacer(Modifier.width(if (expanded) 20.dp else 13.dp))
                FilledTonalIconButton(
                    onClick = onTogglePlayback,
                    enabled = controlsEnabled,
                    modifier = Modifier.size(if (expanded) 66.dp else 50.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Gold, contentColor = Color(0xFF251B0B)),
                ) {
                    Icon(if (playback.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (playback.isPlaying) "Pauză" else "Redă", Modifier.size(if (expanded) 34.dp else 29.dp))
                }
                Spacer(Modifier.width(if (expanded) 20.dp else 13.dp))
                IconButton(onClick = onNext, enabled = controlsEnabled, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.SkipNext, "Melodia următoare", Modifier.size(29.dp))
                }
            }
            if (expanded) Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.VolumeDown, null, Modifier.size(18.dp), tint = Muted)
                Slider(
                    value = playback.volume.coerceIn(0f, 1f),
                    onValueChange = onVolume,
                    modifier = Modifier.weight(1f).height(if (expanded) 35.dp else 25.dp).padding(horizontal = 8.dp).semantics {
                        contentDescription = "Volumul playerului"
                        stateDescription = "${(playback.volume.coerceIn(0f, 1f) * 100).toInt()} la sută"
                    },
                    enabled = playback.connected,
                    colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Gold, inactiveTrackColor = Color(0xFF3A3A3D)),
                )
                Icon(Icons.Default.VolumeUp, null, Modifier.size(18.dp), tint = Muted)
            }
            if (expanded) {
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = Gold.copy(alpha = 0.15f), modifier = Modifier.padding(top = 16.dp, bottom = 12.dp))
                Text(if (playback.connected) "LOCAL • FĂRĂ CONT • OFFLINE" else "Se conectează playerul…", color = Muted, fontSize = 9.sp, letterSpacing = 1.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun RecordArtwork(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(18.dp)).background(Brush.linearGradient(listOf(Color(0xFF433321), Color(0xFF24211D), Color(0xFF101113)))), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = size.minDimension * 0.37f
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Color(0xFF111214), radius, center)
            for (ring in 1..7) {
                drawCircle(Gold.copy(alpha = 0.08f + ring * 0.008f), radius * (0.25f + ring * 0.1f), center, style = Stroke(size.minDimension * 0.006f))
            }
            drawCircle(Gold, radius * 0.28f, center)
            drawCircle(Color(0xFF191817), radius * 0.055f, center)
            drawLine(Gold.copy(alpha = 0.09f), Offset(0f, size.height * 0.05f), Offset(size.width, size.height * 0.85f), size.minDimension * 0.1f)
        }
    }
}

@Composable
private fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.QueueMusic, null, tint = Gold) },
        title = { Text("Playlist nou") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("Numele playlistului") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onCreate(name.trim()) }, enabled = name.isNotBlank()) { Text("Creează") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } },
    )
}

@Composable
private fun RenamePlaylistDialog(playlist: Playlist, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by rememberSaveable(playlist.id) { mutableStateOf(playlist.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Edit, null, tint = Gold) },
        title = { Text("Redenumește playlistul") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(128) },
                label = { Text("Numele playlistului") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(name.trim()) }, enabled = name.isNotBlank()) { Text("Salvează") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anulează") } },
    )
}

@Composable
private fun PlaylistPickerDialog(track: Track, library: LibrarySnapshot, onDismiss: () -> Unit, onSelect: (String) -> Unit, onCreate: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Adaugă în playlist") },
        text = {
            Column {
                Text(track.title, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(bottom = 12.dp))
                if (library.playlists.isEmpty()) Text("Creează un playlist pentru această melodie.", color = Muted)
                LazyColumn(Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(library.playlists, key = { it.id }) { playlist ->
                        val alreadyAdded = track.id in playlist.trackIds
                        Surface(modifier = Modifier.fillMaxWidth().clickable(enabled = !alreadyAdded) { onSelect(playlist.id) }, shape = RoundedCornerShape(12.dp), color = Color(0xFF24262A)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (alreadyAdded) Icons.Default.Check else Icons.Default.QueueMusic, null, Modifier.size(20.dp), tint = Gold)
                                Text(playlist.name, Modifier.weight(1f).padding(start = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onCreate) { Text("Playlist nou") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Închide") } },
    )
}

internal fun formatDuration(durationMs: Long): String {
    val seconds = durationMs.coerceAtLeast(0L) / 1_000L
    val minutes = seconds / 60L
    return if (minutes >= 60) {
        "${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
    } else {
        "$minutes:${(seconds % 60).toString().padStart(2, '0')}"
    }
}
