package ro.mdp.remix

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ro.mdp.remix.background.BackgroundMediaLayer
import ro.mdp.remix.background.BackgroundSettingsPanel
import ro.mdp.remix.background.BackgroundViewModel
import ro.mdp.remix.external.YouTubeLauncher
import ro.mdp.remix.links.LinkViewModel
import ro.mdp.remix.playback.PlayerViewModel
import ro.mdp.remix.ui.*

private enum class Destination(val tag: String) {
    STUDIO("nav_studio"), YOUTUBE("nav_youtube"), SETTINGS("nav_settings")
}
private const val RELEASES_URL = "https://github.com/djmisu-svg/mdp-remix-1and1-nou/releases"

class MainActivity : ComponentActivity() {
    private val playerViewModel: PlayerViewModel by viewModels()
    private val linkViewModel: LinkViewModel by viewModels()
    private val backgroundViewModel: BackgroundViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            RemixTheme {
                val preferences = remember { getSharedPreferences("mdp_settings", Context.MODE_PRIVATE) }
                var english by rememberSaveable { mutableStateOf(preferences.getBoolean("english", false)) }
                var destination by rememberSaveable { mutableStateOf(Destination.STUDIO) }
                var externalError by rememberSaveable { mutableStateOf<String?>(null) }
                val screenStates = rememberSaveableStateHolder()
                val backgroundSettings by backgroundViewModel.settings.collectAsStateWithLifecycle()
                val backgroundRenderError = remember(backgroundViewModel) {
                    { message: String? ->
                        if (message == null) backgroundViewModel.clearError()
                        else backgroundViewModel.onRenderError(message)
                    }
                }
                val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    uri?.let(backgroundViewModel::select)
                }
                CompositionLocalProvider(LocalEnglish provides english) {
                    BackHandler(destination == Destination.SETTINGS) { destination = Destination.STUDIO }
                    BoxWithConstraints(Modifier.fillMaxSize().background(Background)) {
                        val wide = maxWidth >= 840.dp
                        BackgroundMediaLayer(backgroundSettings, Modifier.matchParentSize(), backgroundRenderError)
                        Row(Modifier.fillMaxSize()) {
                            if (wide) NavigationRail(
                                modifier = Modifier.fillMaxHeight().width(104.dp),
                                containerColor = Background,
                                header = {
                                    Surface(color = Red, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(top = 20.dp, bottom = 30.dp).size(56.dp)) {
                                        Box(contentAlignment = Alignment.Center) { Text("M", fontWeight = FontWeight.Black, fontSize = 28.sp) }
                                    }
                                },
                            ) {
                                Destination.entries.forEach { target ->
                                    NavigationRailItem(
                                        selected = destination == target,
                                        onClick = { destination = target },
                                        icon = { DestinationIcon(target) },
                                        label = { Text(destinationLabel(target), maxLines = 1) },
                                        modifier = Modifier.testTag(target.tag).padding(vertical = 8.dp),
                                        colors = NavigationRailItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color.White, indicatorColor = Red, unselectedIconColor = Muted, unselectedTextColor = Muted),
                                    )
                                }
                            }
                            Scaffold(
                                modifier = Modifier.weight(1f), containerColor = Color.Transparent,
                                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                                bottomBar = {
                                    if (!wide) Column {
                                        HorizontalDivider(color = Hairline)
                                        NavigationBar(containerColor = Background, tonalElevation = 0.dp) {
                                            Destination.entries.forEach { target ->
                                                NavigationBarItem(
                                                    selected = destination == target,
                                                    onClick = { destination = target },
                                                    icon = { DestinationIcon(target) },
                                                    label = { Text(destinationLabel(target), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                                    modifier = Modifier.testTag(target.tag),
                                                    colors = NavigationBarItemDefaults.colors(selectedIconColor = Color.White, selectedTextColor = Color.White, indicatorColor = Red, unselectedIconColor = Muted, unselectedTextColor = Muted),
                                                )
                                            }
                                        }
                                    }
                                },
                            ) { padding ->
                                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                                    screenStates.SaveableStateProvider(destination.name) {
                                    when (destination) {
                                        Destination.STUDIO -> LocalMusicScreen()
                                        Destination.YOUTUBE -> LinksScreen(linkViewModel) { link ->
                                            when (YouTubeLauncher.open(this@MainActivity, link.url)) {
                                                YouTubeLauncher.Result.OPENED -> linkViewModel.markLinkOpened(link.id)
                                                YouTubeLauncher.Result.INVALID_LINK -> externalError = if (english) "This YouTube link is invalid. Edit it or add it again." else "Linkul YouTube nu este valid. Editează sau adaugă din nou linkul."
                                                YouTubeLauncher.Result.NO_HANDLER -> externalError = if (english) "Install YouTube or a browser to open this link." else "Instalează YouTube sau un browser pentru a deschide linkul."
                                            }
                                        }
                                        Destination.SETTINGS -> PreferencesScreen(english, { value ->
                                            english = value
                                            preferences.edit().putBoolean("english", value).apply()
                                        }, onShare = {
                                            val share = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, "MdP RemiX 1&1 – $RELEASES_URL")
                                            }
                                            openExternal(Intent.createChooser(share, if (english) "Share app" else "Distribuie aplicația")) {
                                                externalError = if (english) "No sharing app is available." else "Nu există o aplicație pentru distribuire."
                                            }
                                        }, onUpdates = {
                                            openExternal(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL))) {
                                                externalError = if (english) "Install a browser to check for updates." else "Instalează un browser pentru a verifica actualizările."
                                            }
                                        }, onChangeBackground = {
                                            backgroundPicker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "image/gif", "video/*"))
                                        })
                                    }
                                    }
                                }
                            }
                        }
                    }
                    externalError?.let { error ->
                        AlertDialog(onDismissRequest = { externalError = null }, title = { Text(uiText("Acțiunea nu este disponibilă", "Action unavailable")) }, text = { Text(error) }, confirmButton = { TextButton(onClick = { externalError = null }) { Text(uiText("Închide", "Close")) } })
                    }
                }
            }
        }
    }

    private fun openExternal(intent: Intent, onFailure: () -> Unit) {
        try { startActivity(intent) } catch (_: ActivityNotFoundException) { onFailure() } catch (_: SecurityException) { onFailure() }
    }

    override fun onStop() {
        backgroundViewModel.flushDimming()
        super.onStop()
    }

    @Composable
    private fun DestinationIcon(destination: Destination) {
        Icon(when (destination) { Destination.STUDIO -> Icons.Default.GraphicEq; Destination.YOUTUBE -> Icons.Default.SmartDisplay; Destination.SETTINGS -> Icons.Default.Tune }, null)
    }

    @Composable
    private fun destinationLabel(destination: Destination): String = when (destination) {
        Destination.STUDIO -> "Studio"
        Destination.YOUTUBE -> "YouTube"
        Destination.SETTINGS -> uiText("Setări", "Settings")
    }

    @Composable
    private fun PreferencesScreen(english: Boolean, onLanguage: (Boolean) -> Unit, onShare: () -> Unit, onUpdates: () -> Unit, onChangeBackground: () -> Unit) {
        val backgroundSettings by backgroundViewModel.settings.collectAsStateWithLifecycle()
        val backgroundLoading by backgroundViewModel.loading.collectAsStateWithLifecycle()
        val originalAvailable by backgroundViewModel.originalAvailable.collectAsStateWithLifecycle()
        val backgroundError by backgroundViewModel.error.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 900.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(7.dp).background(Red, RoundedCornerShape(2.dp)))
                    Text("MdP RemiX 1&1 / PREMIUM", style = MaterialTheme.typography.labelMedium, color = Muted)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(uiText("Preferințe", "Preferences"), style = MaterialTheme.typography.displaySmall)
                    Text(uiText("Fă loc sunetului tău.", "Make room for your sound."), color = Muted)
                }
                BackgroundSettingsPanel(
                    settings = backgroundSettings, loading = backgroundLoading, originalAvailable = originalAvailable,
                    error = backgroundError,
                    onChange = onChangeBackground,
                    onRestore = backgroundViewModel::restoreOriginal, onScale = backgroundViewModel::setScale,
                    onDimming = backgroundViewModel::setDimming, onAnimation = backgroundViewModel::setAnimationEnabled,
                    onClearError = backgroundViewModel::clearError,
                    onDimmingFinished = backgroundViewModel::flushDimming,
                )
                Surface(shape = RoundedCornerShape(24.dp), color = Background, border = BorderStroke(1.dp, Hairline)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Language, null, tint = Color.White)
                            Spacer(Modifier.width(12.dp))
                            Text(uiText("Limbă", "Language"), style = MaterialTheme.typography.titleLarge)
                        }
                        Text(uiText("Alege limba interfeței. Preferința se păstrează pe acest dispozitiv.", "Choose your interface language. Your preference is saved on this device."), color = Muted, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            LanguageChoice("Română", !english, Modifier.weight(1f).testTag("language_romanian")) { onLanguage(false) }
                            LanguageChoice("English", english, Modifier.weight(1f).testTag("language_english")) { onLanguage(true) }
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(24.dp), color = Background, border = BorderStroke(1.dp, Hairline)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Icon(Icons.Default.Share, null, tint = Red, modifier = Modifier.size(28.dp))
                        Text(uiText("Sunetul merge mai departe.", "Good sound travels."), style = MaterialTheme.typography.titleLarge)
                        Text(uiText("Distribuie MdP RemiX 1&1 prin aplicațiile tale preferate. Biblioteca personală nu este distribuită.", "Share MdP RemiX 1&1 through your favorite apps. Your personal library stays private."), color = Muted)
                        Button(onClick = onShare, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("share_app"), shape = RoundedCornerShape(14.dp)) {
                            Text(uiText("Distribuie aplicația", "Share app")); Spacer(Modifier.width(10.dp)); Icon(Icons.Default.ArrowOutward, null, Modifier.size(18.dp))
                        }
                    }
                }
                Surface(shape = RoundedCornerShape(24.dp), color = Background, border = BorderStroke(1.dp, Hairline)) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SystemUpdate, null, tint = Red)
                            Spacer(Modifier.width(12.dp)); Text(uiText("Rămâi în ritm.", "Stay in the loop."), style = MaterialTheme.typography.titleLarge)
                        }
                        Text(uiText("Vezi versiunile publicate pe GitHub. Orice instalare cere confirmarea Android.", "View published releases on GitHub. Android asks you to confirm any installation."), color = Muted)
                        OutlinedButton(onClick = onUpdates, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("check_updates"), shape = RoundedCornerShape(14.dp)) { Text(uiText("Verifică actualizări", "Check for updates"), color = Color.White) }
                    }
                }
                Text((if (packageName.endsWith(".premiumtest")) "PREMIUM TEST" else "PREMIUM") + " / 1.1.0", style = MaterialTheme.typography.labelSmall, color = Muted, modifier = Modifier.padding(bottom = 16.dp))
            }
        }
    }

    @Composable
    private fun LanguageChoice(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Surface(onClick = onClick, modifier = modifier.semantics { selected = active }, color = if (active) Red.copy(alpha = 0.18f) else Background, border = BorderStroke(if (active) 2.dp else 1.dp, if (active) Red else Hairline), shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontWeight = FontWeight.SemiBold)
                if (active) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }

    @Composable
    private fun LocalMusicScreen() {
        val library by playerViewModel.library.collectAsStateWithLifecycle()
        val playback by playerViewModel.playback.collectAsStateWithLifecycle()
        val importing by playerViewModel.importing.collectAsStateWithLifecycle()
        val message by playerViewModel.message.collectAsStateWithLifecycle()
        var notificationRequested by rememberSaveable { mutableStateOf(false) }
        val importer = rememberLauncherForActivityResult(
            LocalAudioDocuments(),
        ) { uris ->
            if (uris.isNotEmpty()) playerViewModel.importUris(uris)
        }
        val notificationPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* Redarea funcționează și când notificările sunt dezactivate. */ }
        fun requestNotifications() {
            if (!notificationRequested &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationRequested = true
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        RemixApp(
            library = library,
            playback = playback,
            importing = importing,
            message = message,
            onClearMessage = playerViewModel::clearMessage,
            onImport = { importer.launch(arrayOf("audio/*")) },
            onPlayTracks = { tracks, index ->
                playerViewModel.playTracks(tracks, index)
                requestNotifications()
            },
            onTogglePlayback = {
                playerViewModel.togglePlayPause()
                if (!playback.isPlaying && playback.currentTrackId != null) requestNotifications()
            },
            onNext = playerViewModel::next,
            onPrevious = playerViewModel::previous,
            onSeek = playerViewModel::seekTo,
            onVolume = playerViewModel::setVolume,
            onFavorite = playerViewModel::toggleFavorite,
            onCreatePlaylist = playerViewModel::createPlaylist,
            onAddToPlaylist = playerViewModel::addToPlaylist,
            onRemoveFromPlaylist = playerViewModel::removeFromPlaylist,
            onDeletePlaylist = playerViewModel::deletePlaylist,
            onRemoveTrack = playerViewModel::removeTrack,
            onRenamePlaylist = playerViewModel::renamePlaylist,
            onMovePlaylist = playerViewModel::movePlaylist,
            onMoveTrackInPlaylist = playerViewModel::moveTrackInPlaylist,
        )
    }
}

private class LocalAudioDocuments : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}
