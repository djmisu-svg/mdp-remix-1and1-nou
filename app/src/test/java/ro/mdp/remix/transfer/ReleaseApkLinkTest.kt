package ro.mdp.remix.transfer

import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ro.mdp.remix.updates.OfficialUpdates
import ro.mdp.remix.updates.UpdateOffer

class ReleaseApkLinkTest {
    private val hash = "a".repeat(64)
    private val tag = "v1.0.1"
    private val versionedName = "MdP-RemiX-Premium-1.0.1.apk"
    private val root = "https://github.com/${OfficialUpdates.REPOSITORY}/releases"
    private val manifestUrl = "$root/download/$tag/update-manifest.json"
    private val aliasUrl = "$root/download/$tag/${ReleaseApkLink.APK_NAME}"
    private val cdn = "https://release-assets.githubusercontent.com/test/apk?sig=fixture"
    private val manifest = JSONObject().put("schemaVersion", 1).put("packageName", "ro.mdp.remix")
        .put("versionCode", 4).put("versionName", "1.0.1").put("apkName", versionedName)
        .put("sha256", hash).put("certificateSha256", "b".repeat(64)).put("sizeBytes", 1024).toString()

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    private fun asset(name: String, size: Long, hash: String? = null): JSONObject = JSONObject()
        .put("name", name).put("size", size).put("state", "uploaded")
        .put("browser_download_url", "$root/download/$tag/$name").apply { hash?.let { put("digest", "sha256:$it") } }
    private fun releaseJson() = JSONObject().put("draft", false).put("prerelease", false).put("tag_name", tag)
        .put("html_url", "$root/tag/$tag").put("url", "https://api.github.com/repos/${OfficialUpdates.REPOSITORY}/releases/1")
        .put("assets", JSONArray().put(asset("update-manifest.json", manifest.toByteArray().size.toLong(), digest(manifest)))
            .put(asset(versionedName, 1024, hash)).put(asset(ReleaseApkLink.APK_NAME, 1024, hash)))
    private fun offer(): UpdateOffer = OfficialUpdates.parseManifest(manifest, OfficialUpdates.parseRelease(releaseJson().toString()))
    private fun changedAlias(change: (ro.mdp.remix.updates.ReleaseAsset) -> ro.mdp.remix.updates.ReleaseAsset): UpdateOffer {
        val original = offer()
        return original.copy(release = original.release.copy(assets = original.release.assets.map {
            if (it.name == ReleaseApkLink.APK_NAME) change(it) else it
        }))
    }
    private fun reject(reason: ReleaseShareError, operation: () -> Unit) {
        try { operation(); fail("An unavailable or untrusted APK link was accepted") }
        catch (failure: ReleaseShareException) { assertEquals(reason, failure.reason) }
    }

    @Test fun sharesOnlyStableDirectApkEndpoint() {
        val link = ReleaseApkLink.verified(offer())
        assertEquals("https://github.com/${OfficialUpdates.REPOSITORY}/releases/latest/download/MdP-RemiX-Premium.apk", link.url)
        assertEquals(4L, link.versionCode)
        assertEquals("1.0.1", link.versionName)
        assertFalse(link.url.contains("/actions/"))
        assertFalse(link.url.contains("/tag/"))
    }
    @Test fun rejectsMissingAliasInsteadOfFallingBackToRepository() {
        val old = offer()
        reject(ReleaseShareError.NO_DIRECT_APK) { ReleaseApkLink.verified(old.copy(release = old.release.copy(assets = old.release.assets.filter { it.name != ReleaseApkLink.APK_NAME }))) }
    }
    @Test fun rejectsAliasWithDifferentApkHash() = reject(ReleaseShareError.UNTRUSTED_RELEASE) {
        ReleaseApkLink.verified(changedAlias { it.copy(sha256 = "c".repeat(64)) })
    }
    @Test fun requiresGithubAliasDigest() = reject(ReleaseShareError.UNTRUSTED_RELEASE) {
        ReleaseApkLink.verified(changedAlias { it.copy(sha256 = null) })
    }
    @Test fun rejectsAliasSizeMismatch() = reject(ReleaseShareError.UNTRUSTED_RELEASE) {
        ReleaseApkLink.verified(changedAlias { it.copy(size = 1000) })
    }
    @Test fun rejectsForeignOrRepositoryAliasUrl() = reject(ReleaseShareError.UNTRUSTED_RELEASE) {
        ReleaseApkLink.verified(changedAlias { it.copy(url = "https://github.com/other/repo") })
    }
    @Test fun rejectsPackageOtherThanProduction() = reject(ReleaseShareError.UNTRUSTED_RELEASE) {
        ReleaseApkLink.verified(offer().copy(packageName = "ro.mdp.remix.premiumtest"))
    }

    private data class Reply(val status: Int = 200, val body: String = "", val headers: Map<String, String> = emptyMap())
    private fun replies(): MutableMap<String, Reply> = mutableMapOf(
        OfficialUpdates.API to Reply(body = releaseJson().toString()),
        manifestUrl to Reply(body = manifest),
        ReleaseApkLink.STABLE_URL to Reply(302, headers = mapOf("Location" to aliasUrl)),
        aliasUrl to Reply(302, headers = mapOf("Location" to cdn)),
        cdn to Reply(headers = mapOf("Content-Length" to "1024")),
    )
    private fun client(responses: Map<String, Reply>, requests: MutableList<Pair<String, Boolean>> = mutableListOf()) = ReleaseApkLinkClient { url, head ->
        requests += url to head
        val reply = responses[url] ?: throw IOException("No connection")
        object : HttpsURLConnection(URL(url)) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = reply.status
            override fun getInputStream() = ByteArrayInputStream(reply.body.toByteArray())
            override fun getHeaderField(name: String?) = reply.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
            override fun getCipherSuite() = "TLS_TEST"
            override fun getLocalCertificates(): Array<Certificate>? = null
            override fun getServerCertificates(): Array<Certificate> = emptyArray()
        }
    }
    private fun rejectNetwork(reason: ReleaseShareError, responses: Map<String, Reply>) = reject(reason) {
        runBlocking { client(responses).availableLink() }
    }

    @Test fun verifiesMetadataAndRealAvailabilityBeforeReturningLink() = runBlocking {
        val calls = mutableListOf<Pair<String, Boolean>>()
        val link = client(replies(), calls).availableLink()
        assertEquals(ReleaseApkLink.STABLE_URL, link.url)
        assertEquals(listOf(OfficialUpdates.API to false, manifestUrl to false,
            ReleaseApkLink.STABLE_URL to true, aliasUrl to true, cdn to true), calls)
    }
    @Test fun reportsOfflineWithoutReturningUnverifiedLink() = rejectNetwork(ReleaseShareError.NETWORK, emptyMap())
    @Test fun reportsMissingRelease() = rejectNetwork(ReleaseShareError.NO_RELEASE,
        replies().apply { put(OfficialUpdates.API, Reply(404)) })
    @Test fun reportsMissingApkDownload() = rejectNetwork(ReleaseShareError.NO_DIRECT_APK,
        replies().apply { put(ReleaseApkLink.STABLE_URL, Reply(404)) })
    @Test fun rejectsRedirectToOtherHost() = rejectNetwork(ReleaseShareError.UNTRUSTED_RELEASE,
        replies().apply { put(aliasUrl, Reply(302, headers = mapOf("Location" to "https://evil.example/app.apk"))) })
    @Test fun rejectsReleaseChangingDuringAvailabilityCheck() = rejectNetwork(ReleaseShareError.UNTRUSTED_RELEASE,
        replies().apply { put(ReleaseApkLink.STABLE_URL, Reply(302, headers = mapOf("Location" to "$root/download/v2.0.0/${ReleaseApkLink.APK_NAME}"))) })
    @Test fun rejectsManifestChangedSinceGithubDigest() = rejectNetwork(ReleaseShareError.UNTRUSTED_RELEASE,
        replies().apply { put(manifestUrl, Reply(body = manifest.replace("1.0.1", "9.0.9"))) })
    @Test fun rejectsDownloadLengthMismatch() = rejectNetwork(ReleaseShareError.UNTRUSTED_RELEASE,
        replies().apply { put(cdn, Reply(headers = mapOf("Content-Length" to "999"))) })
}
