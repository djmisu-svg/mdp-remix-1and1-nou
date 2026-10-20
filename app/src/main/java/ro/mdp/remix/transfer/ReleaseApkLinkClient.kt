package ro.mdp.remix.transfer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import ro.mdp.remix.updates.OfficialUpdates
import ro.mdp.remix.updates.UpdateError
import ro.mdp.remix.updates.UpdateException

/** Checks release identity and online availability before emitting a public link. */
class ReleaseApkLinkClient(
    private val connectionFactory: (String, Boolean) -> HttpsURLConnection = ::openReleaseConnection,
) {
    suspend fun availableLink(): VerifiedApkLink = withContext(Dispatchers.IO) {
        try {
            val release = OfficialUpdates.parseRelease(readMetadata(OfficialUpdates.API, api = true))
            val manifest = readMetadata(release.manifest.url, size = release.manifest.size, hash = release.manifest.sha256)
            val result = ReleaseApkLink.verified(OfficialUpdates.parseManifest(manifest, release))
            verifyAvailable(result)
            result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ReleaseShareException) {
            throw failure
        } catch (failure: UpdateException) {
            throw ReleaseShareException(if (failure.reason == UpdateError.NO_RELEASE) ReleaseShareError.NO_RELEASE else ReleaseShareError.UNTRUSTED_RELEASE, failure)
        } catch (failure: IOException) {
            throw ReleaseShareException(ReleaseShareError.NETWORK, failure)
        }
    }

    private suspend fun readMetadata(raw: String, api: Boolean = false, size: Long? = null, hash: String? = null): String {
        val connection = openMetadata(raw, api)
        try {
            val bytes = ByteArrayOutputStream()
            var count = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    count += read
                    if (count > OfficialUpdates.MAX_METADATA_BYTES || (size != null && count > size)) untrusted()
                    bytes.write(buffer, 0, read)
                }
            }
            if (size != null && count != size) untrusted()
            val encoded = bytes.toByteArray()
            if (hash != null && MessageDigest.getInstance("SHA-256").digest(encoded).joinToString("") { "%02x".format(it.toInt() and 0xff) } != hash) untrusted()
            return encoded.toString(Charsets.UTF_8)
        } finally { connection.disconnect() }
    }

    private suspend fun openMetadata(raw: String, api: Boolean): HttpsURLConnection {
        var next = raw
        repeat(6) {
            currentCoroutineContext().ensureActive()
            val uri = OfficialUpdates.requireHttps(next)
            if (if (api) next != OfficialUpdates.API else !OfficialUpdates.isAllowedDownload(next)) untrusted()
            val connection = connectionFactory(next, false)
            try {
                when (val status = connection.responseCode) {
                    in 200..299 -> return connection
                    301, 302, 303, 307, 308 -> {
                        next = uri.resolve(connection.getHeaderField("Location") ?: untrusted()).toASCIIString()
                        connection.disconnect()
                    }
                    HttpURLConnection.HTTP_NOT_FOUND -> throw ReleaseShareException(if (api) ReleaseShareError.NO_RELEASE else ReleaseShareError.NO_DIRECT_APK)
                    else -> throw IOException("Release metadata HTTP $status")
                }
            } catch (failure: Exception) { connection.disconnect(); throw failure }
        }
        untrusted()
    }

    private suspend fun verifyAvailable(link: VerifiedApkLink) {
        var next = link.url
        var latestResolved = false
        repeat(6) {
            currentCoroutineContext().ensureActive()
            val uri = OfficialUpdates.requireHttps(next)
            if (next != ReleaseApkLink.STABLE_URL && !OfficialUpdates.isAllowedDownload(next)) untrusted()
            val connection = connectionFactory(next, true)
            try {
                when (val status = connection.responseCode) {
                    in 200..299 -> {
                        if (!latestResolved) untrusted()
                        val size = connection.getHeaderField("Content-Length")?.toLongOrNull()
                        if (size != null && size > 0L && size != link.asset.size) untrusted()
                        return
                    }
                    301, 302, 303, 307, 308 -> {
                        val redirected = uri.resolve(connection.getHeaderField("Location") ?: untrusted()).toASCIIString()
                        if (!latestResolved) {
                            // A newer release appearing during the check requires
                            // a fresh metadata lookup rather than sharing stale identity.
                            if (next != link.url || redirected != link.asset.url) untrusted()
                            latestResolved = true
                        }
                        next = redirected
                    }
                    HttpURLConnection.HTTP_NOT_FOUND -> throw ReleaseShareException(ReleaseShareError.NO_DIRECT_APK)
                    else -> throw IOException("Release APK HTTP $status")
                }
            } finally { connection.disconnect() }
        }
        untrusted()
    }

    private fun untrusted(): Nothing = throw ReleaseShareException(ReleaseShareError.UNTRUSTED_RELEASE)
}

private fun openReleaseConnection(url: String, head: Boolean): HttpsURLConnection = (URL(url).openConnection() as HttpsURLConnection).apply {
    instanceFollowRedirects = false
    useCaches = false
    requestMethod = if (head) "HEAD" else "GET"
    connectTimeout = 15_000
    readTimeout = 20_000
    setRequestProperty("User-Agent", "MdP-RemiX-Android")
    setRequestProperty("Accept", if (head) "application/vnd.android.package-archive" else "application/vnd.github+json, application/octet-stream")
    setRequestProperty("Accept-Encoding", "identity")
    setRequestProperty("Cache-Control", "no-cache")
}

