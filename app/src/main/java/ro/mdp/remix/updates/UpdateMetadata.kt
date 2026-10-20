package ro.mdp.remix.updates

import java.net.URI
import org.json.JSONObject

/** The repository is fixed in the binary. Neither local files nor external intents choose an update host. */
object OfficialUpdates {
    const val REPOSITORY = "djmisu-svg/mdp-remix-1and1-nou"
    const val API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val MAX_METADATA_BYTES = 1_048_576L
    const val MAX_APK_BYTES = 536_870_912L
    const val MANIFEST_NAME = "update-manifest.json"

    fun requireHttps(raw: String): URI = try {
        URI(raw).also {
            require(it.scheme == "https" && it.host != null && it.userInfo == null && it.port == -1 && it.fragment == null)
        }
    } catch (failure: Exception) { throw UpdateException(UpdateError.UNTRUSTED_RELEASE, failure) }

    fun isAllowedDownload(raw: String): Boolean = runCatching {
        val uri = requireHttps(raw)
        (uri.host == "github.com" && uri.rawPath.startsWith("/$REPOSITORY/releases/download/")) ||
            uri.host == "release-assets.githubusercontent.com" || uri.host == "objects.githubusercontent.com"
    }.getOrDefault(false)

    private fun validAssetName(name: String): Boolean = name.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,160}")) && !name.contains("..")

    fun parseRelease(json: String): OfficialRelease = verified {
        val release = JSONObject(json)
        require(!release.getBoolean("draft") && !release.getBoolean("prerelease"))
        val tag = release.getString("tag_name")
        require(tag.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,100}")) && !tag.contains(".."))
        val html = requireHttps(release.getString("html_url"))
        require(html.host == "github.com" && html.rawQuery == null && html.rawPath == "/$REPOSITORY/releases/tag/$tag")
        val api = requireHttps(release.getString("url"))
        require(api.host == "api.github.com" && api.rawQuery == null &&
            api.rawPath.matches(Regex("/repos/$REPOSITORY/releases/[0-9]+")))
        val assets = release.getJSONArray("assets")
        require(assets.length() in 1..100)
        val parsed = (0 until assets.length()).map { index ->
            val asset = assets.getJSONObject(index)
            val name = asset.getString("name")
            require(validAssetName(name) && asset.getString("state") == "uploaded")
            val size = positiveLong(asset, "size")
            val url = asset.getString("browser_download_url")
            val uri = requireHttps(url)
            require(uri.host == "github.com" && uri.rawQuery == null &&
                uri.rawPath == "/$REPOSITORY/releases/download/$tag/$name")
            val digest = asset.optString("digest").takeIf { it.isNotBlank() && it != "null" }
            require(digest == null || digest.matches(Regex("sha256:[A-Fa-f0-9]{64}")))
            ReleaseAsset(name, url, size, digest?.substringAfter(':')?.lowercase())
        }
        require(parsed.map { it.name }.distinct().size == parsed.size)
        val manifest = parsed.single { it.name == MANIFEST_NAME }
        require(manifest.size <= MAX_METADATA_BYTES)
        OfficialRelease(tag, release.optString("body").take(60_000), html.toASCIIString(), parsed, manifest)
    }

    fun parseManifest(json: String, release: OfficialRelease): UpdateOffer = verified {
        val item = JSONObject(json)
        require(positiveLong(item, "schemaVersion") == 1L)
        val packageName = item.getString("packageName")
        require(packageName == "ro.mdp.remix")
        val code = positiveLong(item, "versionCode")
        require(code <= Int.MAX_VALUE)
        val version = item.getString("versionName")
        require(version.isNotBlank() && version.length <= 80 && !version.any { it.isISOControl() })
        val name = item.getString("apkName")
        require(validAssetName(name) && name.startsWith("MdP-RemiX-Premium-") && name.endsWith(".apk"))
        val hash = item.getString("sha256").lowercase()
        val certificate = item.getString("certificateSha256").lowercase()
        require(hash.matches(Regex("[a-f0-9]{64}")) && certificate.matches(Regex("[a-f0-9]{64}")))
        val size = positiveLong(item, "sizeBytes")
        require(size <= MAX_APK_BYTES)
        val asset = release.assets.single { it.name == name }
        require(asset.size == size && (asset.sha256 == null || asset.sha256 == hash))
        UpdateOffer(packageName, code, version, hash, certificate, size, asset, release)
    }

    fun requireCompatibleInstalled(offer: UpdateOffer, identity: InstalledIdentity) {
        if (offer.packageName != identity.packageName) throw UpdateException(UpdateError.PACKAGE_MISMATCH)
        if (identity.certificates.size != 1 || identity.certificates.single().lowercase() != offer.certificateSha256) {
            throw UpdateException(UpdateError.SIGNATURE_MISMATCH)
        }
    }

    fun requireCompatibleArchive(offer: UpdateOffer, identity: InstalledIdentity, archive: InstalledIdentity) {
        requireCompatibleInstalled(offer, identity)
        if (archive.packageName != identity.packageName || archive.packageName != offer.packageName) {
            throw UpdateException(UpdateError.PACKAGE_MISMATCH)
        }
        if (archive.versionCode != offer.versionCode || archive.versionCode <= identity.versionCode) {
            throw UpdateException(UpdateError.INVALID_APK)
        }
        if (archive.certificates.size != 1 || archive.certificates != identity.certificates ||
            archive.certificates.single().lowercase() != offer.certificateSha256) {
            throw UpdateException(UpdateError.SIGNATURE_MISMATCH)
        }
    }

    private fun positiveLong(item: JSONObject, key: String): Long {
        val value = item.get(key)
        require(value is Int || value is Long)
        return (value as Number).toLong().also { require(it > 0) }
    }

    private inline fun <T> verified(block: () -> T): T = try { block() }
    catch (failure: UpdateException) { throw failure }
    catch (failure: Exception) { throw UpdateException(UpdateError.UNTRUSTED_RELEASE, failure) }
}

data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)
data class OfficialRelease(val tag: String, val notes: String, val htmlUrl: String, val assets: List<ReleaseAsset>, val manifest: ReleaseAsset)
data class UpdateOffer(
    val packageName: String, val versionCode: Long, val versionName: String, val sha256: String,
    val certificateSha256: String, val sizeBytes: Long, val apk: ReleaseAsset, val release: OfficialRelease,
)
data class InstalledIdentity(val packageName: String, val versionCode: Long, val certificates: Set<String>)
enum class UpdateError {
    NETWORK, NO_RELEASE, UNTRUSTED_RELEASE, PACKAGE_MISMATCH, SIGNATURE_MISMATCH,
    DOWNLOAD, INVALID_APK, HASH_MISMATCH, INSTALLER, UNKNOWN_SOURCES,
}
class UpdateException(val reason: UpdateError, cause: Throwable? = null) : Exception(reason.name, cause)
