package ro.mdp.remix.updates

import android.content.Context
import android.content.Intent
import android.content.ClipData
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/** APKs stay in private cache; the installer receives only a temporary read grant. */
class UpdateRepository(private val context: Context) {
    private val directory get() = File(context.cacheDir, "updates")
    private val apk get() = File(directory, "verified-update.apk")

    suspend fun check(): UpdateOffer? = withContext(Dispatchers.IO) {
        val release = OfficialUpdates.parseRelease(readMetadata(OfficialUpdates.API, api = true))
        val offer = OfficialUpdates.parseManifest(readMetadata(release.manifest.url, expectedSize = release.manifest.size, expectedHash = release.manifest.sha256), release)
        val installed = installedIdentity()
        // A Premium Test build must not suggest installing a different, production package.
        if (offer.packageName != installed.packageName) throw UpdateException(UpdateError.PACKAGE_MISMATCH)
        if (offer.versionCode <= installed.versionCode) return@withContext null
        OfficialUpdates.requireCompatibleInstalled(offer, installed)
        offer
    }

    suspend fun download(offer: UpdateOffer, onProgress: (Long, Long) -> Unit): Unit = withContext(Dispatchers.IO) {
        OfficialUpdates.requireCompatibleInstalled(offer, installedIdentity())
        if (!directory.isDirectory && !directory.mkdirs()) throw UpdateException(UpdateError.DOWNLOAD)
        val partial = File(directory, "update.part")
        partial.delete()
        apk.delete()
        try {
            val connection = open(offer.apk.url, api = false)
            try {
                val declaredSize = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                if (declaredSize > 0L && declaredSize != offer.sizeBytes) throw UpdateException(UpdateError.INVALID_APK)
                val digest = MessageDigest.getInstance("SHA-256")
                var count = 0L
                var reported = -1L
                connection.inputStream.use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            if (read == 0) continue
                            count += read
                            if (count > offer.sizeBytes || count > OfficialUpdates.MAX_APK_BYTES) throw UpdateException(UpdateError.INVALID_APK)
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                            val percent = count * 100 / offer.sizeBytes
                            if (percent != reported) { reported = percent; onProgress(count, offer.sizeBytes) }
                        }
                    }
                }
                if (count != offer.sizeBytes) throw UpdateException(UpdateError.INVALID_APK)
                if (hex(digest.digest()) != offer.sha256) throw UpdateException(UpdateError.HASH_MISMATCH)
            } finally { connection.disconnect() }
            verifyArchive(partial, offer)
            currentCoroutineContext().ensureActive()
            if (!partial.renameTo(apk)) throw UpdateException(UpdateError.DOWNLOAD)
            onProgress(offer.sizeBytes, offer.sizeBytes)
        } finally { partial.delete() }
    }

    /** Recheck after returning from the unknown-sources screen, before granting an installer access. */
    suspend fun installationIntent(offer: UpdateOffer): Intent = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 26 && !context.packageManager.canRequestPackageInstalls()) {
            throw UpdateException(UpdateError.UNKNOWN_SOURCES)
        }
        verifyArchive(apk, offer)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.mdpfiles", apk)
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = ClipData.newRawUri("Verified MdP update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Public certificate fingerprints identify the installed key; they contain no signing secrets. */
    suspend fun currentInstalledIdentity(): InstalledIdentity = withContext(Dispatchers.IO) { installedIdentity() }

    fun discard() { File(directory, "update.part").delete() }

    private suspend fun verifyArchive(file: File, offer: UpdateOffer) {
        if (!file.isFile || file.length() != offer.sizeBytes) throw UpdateException(UpdateError.INVALID_APK)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        if (hex(digest.digest()) != offer.sha256) throw UpdateException(UpdateError.HASH_MISMATCH)
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, certificateFlags())
            ?: throw UpdateException(UpdateError.INVALID_APK)
        OfficialUpdates.requireCompatibleArchive(offer, installedIdentity(), identity(info))
    }

    @Suppress("DEPRECATION")
    private fun installedIdentity(): InstalledIdentity = identity(context.packageManager.getPackageInfo(context.packageName, certificateFlags()))

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): InstalledIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val fingerprints = signatures?.map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }?.toSet().orEmpty()
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return InstalledIdentity(info.packageName, code, fingerprints)
    }

    @Suppress("DEPRECATION")
    private fun certificateFlags(): Int = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    private suspend fun readMetadata(url: String, api: Boolean = false, expectedSize: Long? = null, expectedHash: String? = null): String {
        val connection = open(url, api)
        try {
            val output = ByteArrayOutputStream()
            var count = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    if (read == 0) continue
                    count += read
                    if (count > OfficialUpdates.MAX_METADATA_BYTES || (expectedSize != null && count > expectedSize)) {
                        throw UpdateException(UpdateError.UNTRUSTED_RELEASE)
                    }
                    output.write(buffer, 0, read)
                }
            }
            if (expectedSize != null && count != expectedSize) throw UpdateException(UpdateError.UNTRUSTED_RELEASE)
            val bytes = output.toByteArray()
            if (expectedHash != null && hex(MessageDigest.getInstance("SHA-256").digest(bytes)) != expectedHash) {
                throw UpdateException(UpdateError.UNTRUSTED_RELEASE)
            }
            return bytes.toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
    }

    private suspend fun open(raw: String, api: Boolean): HttpsURLConnection {
        var next = raw
        repeat(6) {
            currentCoroutineContext().ensureActive()
            val uri = OfficialUpdates.requireHttps(next)
            val allowed = if (api) next == OfficialUpdates.API else OfficialUpdates.isAllowedDownload(next)
            if (!allowed) throw UpdateException(UpdateError.UNTRUSTED_RELEASE)
            val connection = URL(uri.toASCIIString()).openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            if (api) connection.setRequestProperty("Cache-Control", "no-cache")
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("User-Agent", "MdP-RemiX-Android")
            connection.setRequestProperty("Accept", if (api) "application/vnd.github+json" else "application/octet-stream")
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                when (val status = connection.responseCode) {
                    in 200..299 -> return connection
                    301, 302, 303, 307, 308 -> {
                        val location = connection.getHeaderField("Location") ?: throw UpdateException(UpdateError.UNTRUSTED_RELEASE)
                        next = uri.resolve(location).toASCIIString()
                        connection.disconnect()
                    }
                    HttpURLConnection.HTTP_NOT_FOUND -> throw UpdateException(if (api) UpdateError.NO_RELEASE else UpdateError.DOWNLOAD)
                    else -> throw IOException("Update server HTTP $status")
                }
            } catch (failure: Exception) { connection.disconnect(); throw failure }
        }
        throw UpdateException(UpdateError.UNTRUSTED_RELEASE)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
