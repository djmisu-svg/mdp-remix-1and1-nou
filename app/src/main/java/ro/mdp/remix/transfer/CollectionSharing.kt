package ro.mdp.remix.transfer

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class PreparedCollectionShare(val id: String, val apkUri: Uri, val collectionUri: Uri)
data class ReceivedCollection(val id: String, val data: CollectionData)

/** Shares the installed signed APK and a separate, validated collection; never downloads YouTube media. */
class CollectionSharing(context: Context) {
    private val app = context.applicationContext
    private val authority = "${app.packageName}.mdpfiles"
    private val sharingDirectory get() = File(app.cacheDir, "sharing")

    suspend fun prepareShare(data: CollectionData): PreparedCollectionShare = withContext(Dispatchers.IO) {
        val english = data.english
        if (!app.applicationInfo.splitSourceDirs.isNullOrEmpty()) {
            throw IOException(if (english) {
                "This installation uses split APKs. A single base APK cannot be shared as an installable app. Use the official complete APK instead."
            } else {
                "Această instalare folosește APK-uri separate. APK-ul de bază nu se poate distribui singur ca aplicație instalabilă. Folosește APK-ul complet oficial."
            })
        }
        prepareDirectory(sharingDirectory)
        removeExpiredCache()
        val id = UUID.randomUUID().toString()
        val directory = File(sharingDirectory, id)
        prepareDirectory(directory)
        try {
            val installedApk = File(app.applicationInfo.sourceDir)
            if (!installedApk.isFile || !installedApk.canRead()) {
                throw IOException(if (english) "The installed APK cannot be read." else "APK-ul instalat nu poate fi citit.")
            }
            val coroutine = currentCoroutineContext()
            val apk = File(directory, "MdP-RemiX.apk")
            installedApk.inputStream().use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        coroutine.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            val collection = File(directory, "MdP-RemiX.mdpremix")
            CollectionArchive.write(collection, data) { value ->
                try {
                    val input = if (value.startsWith("asset:///")) {
                        app.assets.open(value.removePrefix("asset:///"))
                    } else {
                        app.contentResolver.openInputStream(Uri.parse(value))
                            ?: throw IOException("The document provider returned no stream.")
                    }
                    object : FilterInputStream(input) {
                        override fun read(): Int {
                            coroutine.ensureActive()
                            return try {
                                super.read()
                            } catch (error: IOException) {
                                throw mediaReadError(data, value, error)
                            }
                        }

                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            coroutine.ensureActive()
                            return try {
                                super.read(buffer, offset, length)
                            } catch (error: IOException) {
                                throw mediaReadError(data, value, error)
                            }
                        }
                    }
                } catch (error: IOException) {
                    throw mediaReadError(data, value, error)
                } catch (error: SecurityException) {
                    throw mediaReadError(data, value, error)
                }
            }
            coroutine.ensureActive()
            PreparedCollectionShare(
                id,
                FileProvider.getUriForFile(app, authority, apk),
                FileProvider.getUriForFile(app, authority, collection),
            )
        } catch (error: Exception) {
            directory.deleteRecursively()
            throw error
        }
    }

    /** The caller opens the Android chooser; no recipient or messaging service is selected automatically. */
    fun shareIntent(prepared: PreparedCollectionShare, english: Boolean): Intent =
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(prepared.apkUri, prepared.collectionUri))
            putExtra(Intent.EXTRA_SUBJECT, "MdP RemiX 1&1")
            putExtra(Intent.EXTRA_TEXT, if (english) {
                "Install MdP-RemiX.apk, then open Settings → Import shared collection and select MdP-RemiX.mdpremix. " +
                    "The collection includes playlists, local audio, background and settings. Keep both files. " +
                    "If Android reports a signing conflict, keep your existing app and its data; do not uninstall it."
            } else {
                "Instalează MdP-RemiX.apk, apoi deschide Setări → Importă colecția primită și alege MdP-RemiX.mdpremix. " +
                    "Colecția include playlisturile, fișierele audio locale, fundalul și setările. Păstrează ambele fișiere. " +
                    "Dacă Android raportează un conflict de semnătură, păstrează aplicația existentă și datele ei; nu o dezinstala."
            })
            clipData = ClipData.newUri(app.contentResolver, "MdP-RemiX.apk", prepared.apkUri).apply {
                addItem(ClipData.Item(prepared.collectionUri))
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /** Copies incoming media into durable private storage before returning data for explicit import. */
    suspend fun receive(uri: Uri): ReceivedCollection = withContext(Dispatchers.IO) {
        if (uri.scheme != "content" || uri.authority.isNullOrBlank()) {
            throw IOException("Choose the received .mdpremix collection with the Android file picker.")
        }
        prepareDirectory(sharingDirectory)
        removeExpiredCache()
        val stageDirectory = File(sharingDirectory, "receive-${UUID.randomUUID()}")
        prepareDirectory(stageDirectory)
        try {
            val staged = app.contentResolver.openInputStream(uri)?.use { input ->
                CollectionArchive.stage(input, stageDirectory)
            } ?: throw IOException("The shared collection cannot be read. Download the file and choose it again.")
            val mediaDirectory = File(app.filesDir, "shared-media")
            prepareDirectory(mediaDirectory)
            val mapped = HashMap<String, String>()
            val promoted = HashMap<String, File>()
            val coroutine = currentCoroutineContext()
            for ((portable, source) in staged.media) {
                coroutine.ensureActive()
                val alias = Uri.parse(portable)
                val hash = alias.pathSegments.lastOrNull().orEmpty()
                if (alias.scheme != "content" || alias.authority != "mdp.transfer" ||
                    alias.pathSegments.size != 2 || alias.pathSegments.first() != "media" ||
                    !hash.matches(Regex("[a-f0-9]{64}"))
                ) {
                    throw IOException("The shared collection contains an invalid media reference.")
                }
                val file = promoted.getOrPut(hash) {
                    if (!source.isFile || !isInside(source, stageDirectory)) {
                        throw IOException("The shared collection contains an invalid media file.")
                    }
                    promote(source, mediaDirectory, hash) { coroutine.ensureActive() }
                }
                mapped[portable] = FileProvider.getUriForFile(app, authority, file).buildUpon()
                    .encodedQuery(alias.encodedQuery).build().toString()
            }
            val data = CollectionArchive.remap(staged.data) { portable ->
                mapped[portable] ?: throw IOException("The shared collection is missing a referenced media file.")
            }
            ReceivedCollection(staged.id, data)
        } finally {
            // Promoted files are retained even if applying the library later fails or is cancelled.
            stageDirectory.deleteRecursively()
        }
    }

    private fun promote(source: File, directory: File, hash: String, checkActive: () -> Unit): File {
        val destination = File(directory, hash)
        val temporary = File(directory, ".$hash-${UUID.randomUUID()}.part")
        try {
            source.inputStream().use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (sha256(temporary) != hash) throw IOException("The shared media checksum is invalid.")
            synchronized(promotionLock) {
                if (destination.exists()) {
                    if (!destination.isFile || !isInside(destination, directory) || sha256(destination) != hash) {
                        throw IOException("An existing imported media file is damaged. It has not been overwritten.")
                    }
                } else if (!temporary.renameTo(destination)) {
                    throw IOException("The shared media could not be saved. Check available device storage.")
                }
            }
            return destination
        } finally {
            temporary.delete()
        }
    }

    private fun mediaReadError(data: CollectionData, uri: String, cause: Exception): IOException {
        val track = data.music.tracks.firstOrNull { it.uri == uri }
        val label = track?.title?.replace(Regex("[\\p{Cntrl}]"), " ")?.take(160)
        val message = if (data.english) {
            if (track != null) "Cannot read local audio ‘$label’. Select the file again before sharing."
            else "Cannot read the selected background. Select it again before sharing."
        } else {
            if (track != null) "Fișierul audio local «$label» nu poate fi citit. Selectează-l din nou înainte de distribuire."
            else "Fundalul selectat nu poate fi citit. Selectează-l din nou înainte de distribuire."
        }
        return IOException(message, cause)
    }

    private fun prepareDirectory(directory: File) {
        if ((!directory.isDirectory && !directory.mkdirs()) || !directory.isDirectory) {
            throw IOException("Cannot create private sharing storage. Check available device space.")
        }
    }

    private fun removeExpiredCache() {
        val cutoff = System.currentTimeMillis() - CACHE_RETENTION_MS
        sharingDirectory.listFiles()?.forEach { file ->
            if (file.isDirectory && file.lastModified() < cutoff && isInside(file, sharingDirectory)) {
                file.deleteRecursively()
            }
        }
    }

    private fun isInside(file: File, directory: File): Boolean =
        file.canonicalPath.startsWith(directory.canonicalPath + File.separator)

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_BYTES)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
        const val CACHE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        val promotionLock = Any()
    }
}
