package ro.mdp.remix.playback

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ro.mdp.remix.data.LibrarySnapshot
import ro.mdp.remix.data.MusicRepository
import ro.mdp.remix.data.Track

data class PlaybackState(
    val currentTrackId: String? = null,
    val title: String = "Nicio melodie selectată",
    val artist: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Float = 1f,
    val connected: Boolean = false,
)

/** Bridges local library operations and the service-owned player to the UI. */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MusicRepository(application)
    val library: StateFlow<LibrarySnapshot> = repository.state

    private val mutablePlayback = MutableStateFlow(PlaybackState())
    val playback: StateFlow<PlaybackState> = mutablePlayback.asStateFlow()
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()
    private val mutableImporting = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = mutableImporting.asStateFlow()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var reconnectJob: Job? = null
    private var connectionGeneration = 0
    private var connectionFailures = 0
    private var requestedVolume: Float? = null
    private var cleared = false
    private var pendingQueue: Pair<List<Track>, Int>? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updatePlayback()
        }

        override fun onPlayerError(error: PlaybackException) {
            mutableMessage.value =
                "Melodia nu poate fi redată. Verifică fișierul sau importă-l din nou."
            updatePlayback()
        }
    }

    init {
        connectController()

        // Position changes do not trigger Player.Listener, so update the seek bar
        // while the service continues to own audio and the playback lifecycle.
        viewModelScope.launch {
            while (isActive) {
                updatePlayback()
                delay(500)
            }
        }
    }

    private fun connectController() {
        if (cleared || controller != null || controllerFuture != null) return
        val application = getApplication<Application>()
        val generation = ++connectionGeneration
        val future = MediaController.Builder(
            application,
            SessionToken(application, ComponentName(application, PlaybackService::class.java)),
        ).setListener(object : MediaController.Listener {
            override fun onDisconnected(controller: MediaController) {
                if (!cleared && generation == connectionGeneration) connectionLost()
            }
        }).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (!cleared && generation == connectionGeneration) {
                    try {
                        controller = future.get().also { player ->
                            player.addListener(listener)
                            // Reopening the Activity must preserve the service's
                            // volume unless the user explicitly moved the slider.
                            requestedVolume?.let { player.volume = it }
                        }
                        connectionFailures = 0
                        updatePlayback()
                        pendingQueue?.let { (tracks, index) ->
                            pendingQueue = null
                            playTracks(tracks, index)
                        }
                    } catch (error: Exception) {
                        connectionLost()
                    }
                }
            },
            ContextCompat.getMainExecutor(application),
        )
    }

    private fun connectionLost() {
        // Invalidate callbacks from a former connection before releasing it.
        connectionGeneration++
        controller?.removeListener(listener)
        controller = null
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        mutablePlayback.value = mutablePlayback.value.copy(isPlaying = false, connected = false)
        reconnectJob?.cancel()
        if (++connectionFailures <= 3) {
            reconnectJob = viewModelScope.launch {
                delay(1_500)
                connectController()
            }
        } else {
            mutableMessage.value = "Playerul nu a putut porni. Selectează din nou o melodie."
        }
    }

    fun importUris(uris: List<Uri>) {
        if (uris.isEmpty() || mutableImporting.value) return
        mutableImporting.value = true
        viewModelScope.launch {
            try {
                val result = repository.importUris(uris.distinct())
                mutableMessage.value = when {
                    result.added > 0 && result.failed > 0 ->
                        "${result.added} melodii adăugate; ${result.failed} fișiere nu au putut fi importate."
                    result.added > 0 -> "${result.added} melodii adăugate în bibliotecă."
                    result.failed > 0 -> "Fișierele selectate nu au putut fi importate."
                    else -> "Melodiile selectate sunt deja în bibliotecă."
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableMessage.value = "Nu am putut adăuga fișierele selectate. Încearcă din nou."
            } finally {
                mutableImporting.value = false
            }
        }
    }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) {
            mutableMessage.value = "Adaugă melodii pentru a începe redarea."
            return
        }
        val player = controller
        if (player == null) {
            pendingQueue = tracks.toList() to startIndex.coerceIn(tracks.indices)
            if (controllerFuture == null) {
                connectionFailures = 0
                reconnectJob?.cancel()
                connectController()
            }
            return
        }
        runPlayerCommand {
            player.setMediaItems(tracks.map { it.toMediaItem() }, startIndex.coerceIn(tracks.indices), 0L)
            player.prepare()
            player.play()
        }
    }

    fun togglePlayPause() {
        val player = controller ?: run {
            playTracks(library.value.tracks)
            return
        }
        if (player.mediaItemCount == 0) {
            playTracks(library.value.tracks)
            return
        }
        runPlayerCommand {
            if (player.playWhenReady) player.pause() else {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
                player.play()
            }
        }
    }

    fun next() {
        val player = controller ?: return
        runPlayerCommand {
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
            }
        }
    }

    fun previous() {
        val player = controller ?: return
        runPlayerCommand {
            // Media3 restarts the current song after the initial few seconds;
            // near its beginning, it goes to the preceding song in the queue.
            player.seekToPrevious()
            if (player.mediaItemCount > 0 && player.playbackState == Player.STATE_IDLE) player.prepare()
        }
    }

    fun seekTo(positionMs: Long) {
        val player = controller ?: return
        runPlayerCommand {
            val duration = player.duration
            val target = if (duration != C.TIME_UNSET && duration >= 0) {
                positionMs.coerceIn(0L, duration)
            } else {
                positionMs.coerceAtLeast(0L)
            }
            player.seekTo(target)
        }
    }

    fun setVolume(volume: Float) {
        if (!volume.isFinite()) return
        val level = volume.coerceIn(0f, 1f)
        requestedVolume = level
        mutablePlayback.value = mutablePlayback.value.copy(volume = level)
        runPlayerCommand { controller?.volume = level }
    }

    fun toggleFavorite(id: String) = editLibrary {
        repository.toggleFavorite(id)
    }

    fun createPlaylist(name: String) = editLibrary {
        repository.createPlaylist(name)
    }

    fun renamePlaylist(id: String, name: String) = editLibrary {
        repository.renamePlaylist(id, name)
    }

    fun movePlaylist(id: String, direction: Int) = editLibrary {
        repository.movePlaylist(id, direction)
    }

    fun moveTrackInPlaylist(playlistId: String, trackId: String, direction: Int) = editLibrary {
        repository.moveTrackInPlaylist(playlistId, trackId, direction)
    }

    fun addToPlaylist(playlistId: String, trackId: String) = editLibrary {
        repository.addToPlaylist(playlistId, trackId)
    }

    fun removeFromPlaylist(playlistId: String, trackId: String) = editLibrary {
        repository.removeFromPlaylist(playlistId, trackId)
    }

    fun deletePlaylist(id: String) = editLibrary {
        repository.deletePlaylist(id)
    }

    fun removeTrack(id: String) = editLibrary {
        repository.removeTrack(id)
        // A removed library item should not remain in the active playback queue.
        controller?.let { player ->
            for (index in player.mediaItemCount - 1 downTo 0) {
                if (player.getMediaItemAt(index).mediaId == id) player.removeMediaItem(index)
            }
        }
        pendingQueue = pendingQueue?.let { (tracks, index) ->
            val selectedId = tracks.getOrNull(index)?.id
            val remaining = tracks.filterNot { it.id == id }
            if (remaining.isEmpty()) null else {
                val nextIndex = remaining.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 }
                    ?: index.coerceIn(remaining.indices)
                remaining to nextIndex
            }
        }
        updatePlayback()
    }

    fun clearMessage() {
        mutableMessage.value = null
    }

    private fun editLibrary(operation: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: IllegalArgumentException) {
                mutableMessage.value = error.message ?: "Verifică datele introduse."
            } catch (error: Exception) {
                mutableMessage.value = "Modificarea nu a putut fi salvată. Încearcă din nou."
            }
        }
    }

    private fun runPlayerCommand(command: () -> Unit) {
        try {
            command()
            updatePlayback()
        } catch (error: Exception) {
            mutableMessage.value = "Redarea nu a putut fi actualizată. Selectează din nou melodia."
        }
    }

    private fun updatePlayback() {
        val player = controller ?: return
        val item = player.currentMediaItem
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it >= 0L }
            ?: library.value.tracks.firstOrNull { it.id == item?.mediaId }?.durationMs
            ?: 0L
        mutablePlayback.value = PlaybackState(
            currentTrackId = item?.mediaId,
            title = item?.mediaMetadata?.title?.toString() ?: "Nicio melodie selectată",
            artist = item?.mediaMetadata?.artist?.toString().orEmpty(),
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration.coerceAtLeast(0L),
            volume = player.volume,
            connected = true,
        )
    }

    override fun onCleared() {
        cleared = true
        connectionGeneration++
        reconnectJob?.cancel()
        controller?.removeListener(listener)
        controller = null
        // Unbind the UI; the foreground service keeps playing and owns its player.
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        super.onCleared()
    }
}

private fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id)
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .build(),
    )
    .build()
