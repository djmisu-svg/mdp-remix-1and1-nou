package ro.mdp.remix.brand

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

internal object BrandLibrarySource {
    const val REPOSITORY = "djmisu-svg/mdp-remix-1and1-nou"
    const val API = "https://api.github.com/repos/$REPOSITORY/releases/tags/brand-library"
    const val ASSET = "https://github.com/$REPOSITORY/releases/download/brand-library/brand-library.json"

    data class Asset(val url: String, val sha256: String?)

    fun assetFromRelease(text: String): Asset {
        val release = JSONObject(text)
        require(release.getString("tag_name") == "brand-library" &&
            release.optBoolean("prerelease") && !release.optBoolean("draft") &&
            release.getString("html_url") == "https://github.com/$REPOSITORY/releases/tag/brand-library") {
            "The brand library must come from the official prerelease."
        }
        val assets = release.getJSONArray("assets")
        val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name") == "brand-library.json" }
        require(matches.size == 1) { "The official brand library is unavailable." }
        val row = matches.single()
        require(row.getString("browser_download_url") == ASSET &&
            row.getLong("size") in 1..BrandLibraryJson.MAX_BYTES.toLong()) { "Invalid brand library asset." }
        val digest = row.optString("digest").takeIf(String::isNotBlank)?.also {
            require(Regex("sha256:[0-9a-fA-F]{64}").matches(it)) { "Invalid library digest." }
        }?.substringAfter(':')?.lowercase()
        return Asset(ASSET, digest)
    }

    fun allowedDownload(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.rawUserInfo == null && uri.port in listOf(-1, 443) && uri.rawFragment == null &&
            (url == ASSET || uri.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com",
                "objects-origin.githubusercontent.com"))
    }.getOrDefault(false)
}

/** Anonymous reads only. No GitHub token or owner credential is ever shipped in the app. */
class BrandLibraryClient {
    suspend fun fetch(): BrandLibrarySnapshot? = withContext(Dispatchers.IO) {
        val metadata = read(BrandLibrarySource.API, allowMissing = true, api = true) ?: return@withContext null
        val asset = BrandLibrarySource.assetFromRelease(metadata.toString(Charsets.UTF_8))
        val bytes = requireNotNull(read(asset.url, allowMissing = false, api = false))
        if (asset.sha256 != null) {
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            require(actual == asset.sha256) { "Brand library integrity check failed." }
        }
        BrandLibraryJson.decode(bytes.toString(Charsets.UTF_8))
    }

    private suspend fun read(initialUrl: String, allowMissing: Boolean, api: Boolean): ByteArray? {
        var address = initialUrl
        repeat(6) { redirect ->
            currentCoroutineContext().ensureActive()
            require(if (api) address == BrandLibrarySource.API else BrandLibrarySource.allowedDownload(address)) {
                "Unexpected brand library download origin."
            }
            val connection = URL(address).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "MdP-RemiX-Android")
                connection.setRequestProperty("Accept", if (api) "application/vnd.github+json" else "application/octet-stream")
                val status = connection.responseCode
                if (status == 404 && allowMissing) return null
                if (status in setOf(301, 302, 303, 307, 308)) {
                    require(!api && redirect < 5) { "Unexpected library redirect." }
                    address = URL(URL(address), connection.getHeaderField("Location")
                        ?: throw IOException("Missing redirect destination.")).toString()
                    return@repeat
                }
                if (status != 200) throw IOException("GitHub library request failed ($status).")
                require((connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1) <=
                    BrandLibraryJson.MAX_BYTES) { "Library response is too large." }
                return connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= BrandLibraryJson.MAX_BYTES) { "Library response is too large." }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many library redirects.")
    }
}
