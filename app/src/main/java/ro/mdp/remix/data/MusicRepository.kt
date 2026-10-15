package ro.mdp.remix.data

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import java.security.MessageDigest
import java.util.UUID

/** Local library only. Audio stays in its selected location and no network is used. */
class MusicRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val file = File(appContext.filesDir, "music-library.json")
    private val atomicFile = AtomicFile(file)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(LibrarySnapshot())
    val state: StateFlow<LibrarySnapshot> = mutableState.asStateFlow()

    private val initialLoad = scope.async {
        mutex.withLock { mutableState.value = load() }
    }

    suspend fun importUris(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        initialLoad.await()
        // Keep URI grants and the library update in one serialized operation. A
        // simultaneous remove/import must never revoke another import's grant.
        mutex.withLock {
            val previous = mutableState.value
            val existingUris = previous.tracks.mapTo(mutableSetOf()) { it.uri }
            val newlyRetained = mutableSetOf<Uri>()
            val candidates = mutableListOf<Track>()
            var failed = 0
            try {
                uris.distinctBy(Uri::toString).forEach { uri ->
                    currentCoroutineContext().ensureActive()
                    val hadPermission = hasPersistedReadPermission(uri)
                    try {
                        retainReadPermission(uri)
                        if (!hadPermission) newlyRetained.add(uri)
                        if (uri.toString() !in existingUris) candidates.add(readTrack(uri))
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Exception) {
                        failed++
                        if (!hadPermission && uri.toString() !in existingUris) {
                            releaseReadPermission(uri)
                            newlyRetained.remove(uri)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val next = LibraryRules.mergeTracks(previous, candidates)
                if (next != previous) persistAndPublish(next)
                val savedUris = next.tracks.mapTo(mutableSetOf()) { it.uri }
                newlyRetained.filterNot { it.toString() in savedUris }.forEach(::releaseReadPermission)
                ImportResult(next.tracks.size - previous.tracks.size, failed)
            } catch (failure: Throwable) {
                // A full disk or cancelled import must not exhaust Android's
                // persisted-document grant quota with uncommitted selections.
                newlyRetained.filterNot { it.toString() in existingUris }.forEach(::releaseReadPermission)
                throw failure
            }
        }
    }

    suspend fun toggleFavorite(id: String) = mutate { LibraryRules.toggleFavorite(it, id) }

    suspend fun createPlaylist(name: String) =
        mutate { LibraryRules.createPlaylist(it, UUID.randomUUID().toString(), name) }

    suspend fun renamePlaylist(id: String, name: String) =
        mutate { LibraryRules.renamePlaylist(it, id, name) }

    suspend fun movePlaylist(id: String, direction: Int) =
        mutate { LibraryRules.movePlaylist(it, id, direction) }

    suspend fun moveTrackInPlaylist(playlistId: String, trackId: String, direction: Int) =
        mutate { LibraryRules.moveTrackInPlaylist(it, playlistId, trackId, direction) }

    suspend fun addToPlaylist(playlistId: String, trackId: String) =
        mutate { LibraryRules.addToPlaylist(it, playlistId, trackId) }

    suspend fun removeFromPlaylist(playlistId: String, trackId: String) =
        mutate { LibraryRules.removeFromPlaylist(it, playlistId, trackId) }

    suspend fun deletePlaylist(id: String) = mutate { LibraryRules.deletePlaylist(it, id) }

    suspend fun removeTrack(id: String) = withContext(Dispatchers.IO) {
        initialLoad.await()
        mutex.withLock {
            val previous = mutableState.value
            val removed = previous.tracks.firstOrNull { it.id == id } ?: return@withLock
            persistAndPublish(LibraryRules.removeTrack(previous, id))
            // Removing a library entry never deletes the original audio file.
            releaseReadPermission(Uri.parse(removed.uri))
        }
    }

    private suspend fun mutate(transform: (LibrarySnapshot) -> LibrarySnapshot) = withContext(Dispatchers.IO) {
        initialLoad.await()
        mutex.withLock {
            val previous = mutableState.value
            val next = LibraryRules.normalize(transform(previous))
            if (next != previous) persistAndPublish(next)
        }
    }

    private fun persistAndPublish(snapshot: LibrarySnapshot) {
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(LibraryJson.encode(snapshot).toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(output)
            mutableState.value = snapshot
        } catch (failure: Throwable) {
            atomicFile.failWrite(output)
            throw failure
        }
    }

    private fun load(): LibrarySnapshot = try {
        atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { LibraryJson.decode(it.readText()) }
    } catch (_: FileNotFoundException) {
        LibrarySnapshot()
    } catch (failure: UnsupportedLibraryVersionException) {
        // Do not downgrade or replace a library written with another schema.
        throw failure
    } catch (_: Exception) {
        // Recovery is permitted only after making a durable copy. If copying
        // fails (for example, a full disk), initialLoad fails and writes stop.
        file.inputStream().use { source ->
            FileOutputStream(File(appContext.filesDir, "music-library-corrupt-${UUID.randomUUID()}.json")).use { backup ->
                source.copyTo(backup)
                backup.fd.sync()
            }
        }
        LibrarySnapshot()
    }

    private fun hasPersistedReadPermission(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun retainReadPermission(uri: Uri) {
        require(uri.scheme == "content") { "Selectează melodii cu selectorul de fișiere Android." }
        try {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (failure: SecurityException) {
            if (!hasPersistedReadPermission(uri)) throw failure
        }
    }

    private fun releaseReadPermission(uri: Uri) {
        runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private fun readTrack(uri: Uri): Track {
        val displayName = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
            }
        }.getOrNull().orEmpty()
        val declaredType = resolver.getType(uri).orEmpty()
        require(!declaredType.startsWith("video/", ignoreCase = true)) { "Selectează un fișier audio." }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
            require(hasAudio.equals("yes", ignoreCase = true) && !hasVideo.equals("yes", ignoreCase = true)) {
                "Fișierul selectat nu conține o melodie audio compatibilă."
            }
            val title = LibraryRules.cleanLabel(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
                .ifBlank { LibraryRules.cleanLabel(displayName.substringBeforeLast('.', displayName)).ifBlank { "Melodie importată" } }
            val artist = LibraryRules.cleanLabel(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST))
                .ifBlank { "Artist necunoscut" }
            val album = LibraryRules.cleanLabel(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
                .ifBlank { "Album necunoscut" }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?.coerceAtLeast(0L) ?: 0L
            return Track(idFor(uri), title, artist, album, duration, uri.toString())
        } finally {
            retriever.release()
        }
    }

    private fun idFor(uri: Uri): String = MessageDigest.getInstance("SHA-256")
        .digest(uri.toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
