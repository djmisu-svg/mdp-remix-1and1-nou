package ro.mdp.remix

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import ro.mdp.remix.external.YouTubeLauncher
import ro.mdp.remix.links.LinkViewModel
import ro.mdp.remix.playback.PlayerViewModel
import ro.mdp.remix.ui.LinksScreen
import ro.mdp.remix.ui.RemixApp
import ro.mdp.remix.ui.RemixTheme

class MainActivity : ComponentActivity() {
    private val playerViewModel: PlayerViewModel by viewModels()
    private val linkViewModel: LinkViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            RemixTheme {
                var localMusic by rememberSaveable { mutableStateOf(false) }
                var showSettings by rememberSaveable { mutableStateOf(false) }
                val preferences = remember { getSharedPreferences("mdp_settings", Context.MODE_PRIVATE) }
                var english by rememberSaveable { mutableStateOf(preferences.getBoolean("english", false)) }
                var externalError by rememberSaveable { mutableStateOf<String?>(null) }
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = !localMusic,
                                onClick = { localMusic = false; showSettings = false },
                                icon = { Icon(Icons.Default.SmartDisplay, contentDescription = null) },
                                label = { Text(if (english) "YouTube & links" else "YouTube & linkuri") },
                            )
                            NavigationBarItem(
                                selected = localMusic,
                                onClick = { localMusic = true; showSettings = false },
                                icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                                label = { Text(if (english) "Local music" else "Muzică locală") },
                            )
                            NavigationBarItem(
                                selected = showSettings,
                                onClick = { showSettings = true },
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text(if (english) "Settings" else "Setări") },
                            )
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        if (showSettings) {
                            Column(modifier = Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Text(if (english) "Language" else "Limbă")
                                OutlinedButton(onClick = { english = false; preferences.edit().putBoolean("english", false).apply() }, modifier = Modifier.fillMaxWidth()) { Text(if (english) "Romanian" else "Română" + if (!english) " ✓" else "") }
                                OutlinedButton(onClick = { english = true; preferences.edit().putBoolean("english", true).apply() }, modifier = Modifier.fillMaxWidth()) { Text("English" + if (english) " ✓" else "") }
                                Button(onClick = {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, "MdP RemiX 1&1 – https://github.com/djmisu-svg/mdp-remix-1and1-nou/releases")
                                    }
                                    startActivity(Intent.createChooser(intent, if (english) "Share app" else "Distribuie aplicația"))
                                }, modifier = Modifier.fillMaxWidth()) { Text(if (english) "Share app" else "Distribuie aplicația") }
                                OutlinedButton(onClick = {
                                    startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/djmisu-svg/mdp-remix-1and1-nou/releases")))
                                }, modifier = Modifier.fillMaxWidth()) { Text(if (english) "Check for updates" else "Verifică actualizări") }
                                Text(if (english) "Updates open the releases page. Installation requires Android confirmation. Saved local files are not shared automatically." else "Actualizările se deschid în pagina de versiuni. Instalarea cere confirmarea Android. Fișierele locale nu sunt distribuite automat.")
                            }
                        } else if (localMusic) {
                            LocalMusicScreen()
                        } else {
                            LinksScreen(
                                viewModel = linkViewModel,
                                onOpenLink = { link ->
                                    when (YouTubeLauncher.open(this@MainActivity, link.url)) {
                                        YouTubeLauncher.Result.OPENED -> linkViewModel.markLinkOpened(link.id)
                                        YouTubeLauncher.Result.INVALID_LINK -> {
                                            externalError = "Linkul YouTube nu este valid. Editează sau adaugă din nou linkul."
                                        }
                                        YouTubeLauncher.Result.NO_HANDLER -> {
                                            externalError = "Instalează aplicația YouTube sau un browser pentru a deschide linkul."
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                externalError?.let { error ->
                    AlertDialog(
                        onDismissRequest = { externalError = null },
                        title = { Text("Linkul nu a putut fi deschis") },
                        text = { Text(error) },
                        confirmButton = {
                            TextButton(onClick = { externalError = null }) { Text("Închide") }
                        },
                    )
                }
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
