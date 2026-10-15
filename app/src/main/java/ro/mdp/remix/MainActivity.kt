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
                var externalError by rememberSaveable { mutableStateOf<String?>(null) }
                Scaffold(
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = !localMusic,
                                onClick = { localMusic = false },
                                icon = { Icon(Icons.Default.SmartDisplay, contentDescription = null) },
                                label = { Text("YouTube & linkuri") },
                            )
                            NavigationBarItem(
                                selected = localMusic,
                                onClick = { localMusic = true },
                                icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                                label = { Text("Muzică locală") },
                            )
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        if (localMusic) {
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
