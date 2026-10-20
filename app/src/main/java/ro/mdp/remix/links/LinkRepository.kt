package ro.mdp.remix.links

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.util.UUID
import ro.mdp.remix.transfer.CollectionMerge

/** Offline link metadata only. YouTube media is never requested or downloaded here. */
class LinkRepository(context: Context) {
    private val directory = context.applicationContext.filesDir
    private val file = File(directory, "youtube-link-library.json")
    private val atomicFile = AtomicFile(file)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(LinkLibrary())
    val state: StateFlow<LinkLibrary> = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    private val mutableLoading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = mutableLoading.asStateFlow()

    private val initialLoad = scope.async {
        try {
            mutex.withLock { mutableState.value = load() }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            mutableError.value = failure.message ?: "Biblioteca de linkuri nu a putut fi încărcată."
            throw failure
        } finally {
            mutableLoading.value = false
        }
    }

    suspend fun sharedSnapshot(): LinkLibrary = withContext(Dispatchers.IO) {
        initialLoad.await()
        mutex.withLock { mutableState.value }
    }

    suspend fun importShared(library: LinkLibrary, transferId: String) =
        mutate { CollectionMerge.youtube(it, library, transferId) }

    suspend fun syncBrand(library: LinkLibrary) = mutate { CollectionMerge.syncBrand(it, library) }

    suspend fun addLink(title: String, url: String, playlistId: String? = null) = mutate {
        LinkRules.addLink(it, UUID.randomUUID().toString(), title, url, playlistId, System.currentTimeMillis())
    }

    suspend fun renameLink(id: String, title: String) = mutate { LinkRules.renameLink(it, id, title) }
    suspend fun deleteLink(id: String) = mutate { LinkRules.deleteLink(it, id) }
    suspend fun toggleLinkFavorite(id: String) = mutate { LinkRules.toggleLinkFavorite(it, id) }
    suspend fun createPlaylist(name: String) = mutate {
        LinkRules.createPlaylist(it, UUID.randomUUID().toString(), name, System.currentTimeMillis())
    }

    suspend fun renamePlaylist(id: String, name: String) = mutate { LinkRules.renamePlaylist(it, id, name) }
    suspend fun deletePlaylist(id: String) = mutate { LinkRules.deletePlaylist(it, id) }
    suspend fun togglePlaylistFavorite(id: String) = mutate { LinkRules.togglePlaylistFavorite(it, id) }
    suspend fun addToPlaylist(playlistId: String, linkId: String) = mutate { LinkRules.addToPlaylist(it, playlistId, linkId) }
    suspend fun removeFromPlaylist(playlistId: String, linkId: String) = mutate { LinkRules.removeFromPlaylist(it, playlistId, linkId) }
    suspend fun movePlaylist(id: String, direction: Int) = mutate { LinkRules.movePlaylist(it, id, direction) }
    suspend fun moveLink(playlistId: String, linkId: String, direction: Int) = mutate { LinkRules.moveLink(it, playlistId, linkId, direction) }
    suspend fun markLinkOpened(id: String) = mutate { LinkRules.markLinkOpened(it, id, System.currentTimeMillis()) }
    suspend fun markPlaylistOpened(id: String) = mutate { LinkRules.markPlaylistOpened(it, id, System.currentTimeMillis()) }

    fun clearError() { mutableError.value = null }
    fun close() { scope.cancel() }

    private suspend fun mutate(transform: (LinkLibrary) -> LinkLibrary) {
        try {
            withContext(Dispatchers.IO) {
                initialLoad.await()
                mutex.withLock {
                    val previous = mutableState.value
                    val next = LinkRules.normalize(transform(previous))
                    currentCoroutineContext().ensureActive()
                    if (next != previous) persistAndPublish(next)
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            mutableError.value = failure.message ?: "Modificarea nu a putut fi salvată."
            throw failure
        }
    }

    private fun persistAndPublish(library: LinkLibrary) {
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(LinkJson.encode(library).toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
            // The UI changes only after the atomic write succeeds.
            mutableState.value = library
        } catch (failure: Throwable) {
            atomicFile.failWrite(output)
            throw failure
        }
    }

    private fun load(): LinkLibrary {
        val bytes = try {
            atomicFile.readFully()
        } catch (_: FileNotFoundException) {
            // Existing but unreadable storage must not silently become a new library.
            if (file.exists()) throw IllegalStateException("Fișierul bibliotecii nu poate fi citit. Datele au fost păstrate.")
            return LinkLibrary()
        }
        return try {
            LinkJson.decode(bytes.toString(Charsets.UTF_8))
        } catch (failure: UnsupportedLinkVersionException) {
            throw failure
        } catch (_: Exception) {
            // A durable, uniquely named backup is required before recovering.
            // If this fails (for example, storage is full), all mutations stay blocked.
            FileOutputStream(File(directory, "youtube-link-library-corrupt-${UUID.randomUUID()}.json")).use { backup ->
                backup.write(bytes)
                backup.fd.sync()
            }
            mutableError.value = "Fișierul bibliotecii era deteriorat. O copie de siguranță a fost păstrată în memoria aplicației."
            LinkLibrary()
        }
    }
}
