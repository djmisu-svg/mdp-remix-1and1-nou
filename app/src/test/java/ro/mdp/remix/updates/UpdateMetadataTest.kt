package ro.mdp.remix.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UpdateMetadataTest {
    private val hash = "a".repeat(64)
    private val cert = "b".repeat(64)
    private val name = "MdP-RemiX-Premium-1.2.0.apk"
    private val tag = "v1.2.0"
    private val prefix = "https://github.com/${OfficialUpdates.REPOSITORY}/releases"

    private fun releaseJson(): JSONObject = JSONObject().put("draft", false).put("prerelease", false)
        .put("tag_name", tag).put("html_url", "$prefix/tag/$tag")
        .put("url", "https://api.github.com/repos/${OfficialUpdates.REPOSITORY}/releases/12")
        .put("body", "Real release notes")
        .put("assets", JSONArray().put(asset(OfficialUpdates.MANIFEST_NAME, 500)).put(asset(name, 1024)))

    private fun asset(name: String, size: Long): JSONObject = JSONObject().put("name", name).put("size", size)
        .put("state", "uploaded").put("browser_download_url", "$prefix/download/$tag/$name")
    private fun manifestJson(): JSONObject = JSONObject().put("schemaVersion", 1).put("packageName", "ro.mdp.remix")
        .put("versionCode", 12).put("versionName", "1.2.0").put("apkName", name)
        .put("sha256", hash).put("certificateSha256", cert).put("sizeBytes", 1024)
    private fun release() = OfficialUpdates.parseRelease(releaseJson().toString())
    private fun offer() = OfficialUpdates.parseManifest(manifestJson().toString(), release())
    private fun installed(packageName: String = "ro.mdp.remix", version: Long = 11, certificates: Set<String> = setOf(cert)) =
        InstalledIdentity(packageName, version, certificates)
    private fun reject(error: UpdateError = UpdateError.UNTRUSTED_RELEASE, block: () -> Unit) {
        try { block(); fail("Untrusted update was accepted") }
        catch (failure: UpdateException) { assertEquals(error, failure.reason) }
    }

    @Test fun acceptsOfficialStableReleaseAndVersionCode() {
        val offer = offer()
        assertEquals(12L, offer.versionCode)
        assertEquals("Real release notes", offer.release.notes)
        OfficialUpdates.requireCompatibleArchive(offer, installed(), installed(version = 12))
    }
    @Test fun comparesActualIntegerVersionCodeRatherThanVersionName() {
        val manifest = manifestJson().put("versionName", "0.1.0").put("versionCode", 99)
        assertEquals(99L, OfficialUpdates.parseManifest(manifest.toString(), release()).versionCode)
    }
    @Test fun rejectsDraftAndPrereleaseIncludingSharedLibrary() {
        reject { OfficialUpdates.parseRelease(releaseJson().put("draft", true).toString()) }
        reject { OfficialUpdates.parseRelease(releaseJson().put("prerelease", true).toString()) }
    }
    @Test fun rejectsForeignRepositoryAndLookalikeHosts() {
        reject { OfficialUpdates.parseRelease(releaseJson().put("html_url", "$prefix.evil/tag/$tag").toString()) }
        reject { OfficialUpdates.parseRelease(releaseJson().put("html_url", "https://github.com.evil/${OfficialUpdates.REPOSITORY}/releases/tag/$tag").toString()) }
        reject { OfficialUpdates.parseRelease(releaseJson().put("url", "https://api.github.com/repos/attacker/app/releases/12").toString()) }
    }
    @Test fun rejectsAssetTraversalEncodedPathAndForeignSources() {
        listOf("../bad.apk", "x%2f.apk", "x/y.apk").forEach { bad ->
            val json = releaseJson().put("assets", JSONArray().put(asset(OfficialUpdates.MANIFEST_NAME, 500)).put(asset(bad, 1024)))
            reject { OfficialUpdates.parseRelease(json.toString()) }
        }
        val assets = JSONArray().put(asset(OfficialUpdates.MANIFEST_NAME, 500)).put(asset(name, 1024).put("browser_download_url", "https://evil.example/$name"))
        reject { OfficialUpdates.parseRelease(releaseJson().put("assets", assets).toString()) }
    }
    @Test fun redirectsAllowOnlyHttpsOfficialReleaseHosts() {
        assertTrue(OfficialUpdates.isAllowedDownload("$prefix/download/$tag/$name"))
        assertTrue(OfficialUpdates.isAllowedDownload("https://release-assets.githubusercontent.com/a?sig=123"))
        listOf("http://github.com/${OfficialUpdates.REPOSITORY}/releases/download/$tag/$name",
            "https://release-assets.githubusercontent.com.evil/a", "https://evil@release-assets.githubusercontent.com/a",
            "https://github.com/attacker/app/releases/download/v1/a.apk", "file:///tmp/app.apk",
            "https://objects.githubusercontent.com:8443/a").forEach { assertFalse(it, OfficialUpdates.isAllowedDownload(it)) }
    }
    @Test fun rejectsDuplicateAndMissingManifestAssets() {
        reject { OfficialUpdates.parseRelease(releaseJson().put("assets", JSONArray().put(asset(name, 1024))).toString()) }
        reject { OfficialUpdates.parseRelease(releaseJson().put("assets", JSONArray().put(asset(OfficialUpdates.MANIFEST_NAME, 500)).put(asset(OfficialUpdates.MANIFEST_NAME, 500))).toString()) }
    }
    @Test fun rejectsMalformedHashesAndWrongSizes() {
        reject { OfficialUpdates.parseManifest(manifestJson().put("sha256", "bad").toString(), release()) }
        reject { OfficialUpdates.parseManifest(manifestJson().put("certificateSha256", "b".repeat(63)).toString(), release()) }
        reject { OfficialUpdates.parseManifest(manifestJson().put("sizeBytes", 999).toString(), release()) }
    }
    @Test fun rejectsCoercedNegativeOverflowAndFractionalCodes() {
        listOf<Any>("12", 0, -1, 2.5, Int.MAX_VALUE.toLong() + 1).forEach { code ->
            reject { OfficialUpdates.parseManifest(manifestJson().put("versionCode", code).toString(), release()) }
        }
    }
    @Test fun rejectsManifestForDifferentPackage() {
        reject { OfficialUpdates.parseManifest(manifestJson().put("packageName", "ro.other").toString(), release()) }
    }
    @Test fun rejectsProductionApkForIsolatedTestApp() {
        reject(UpdateError.PACKAGE_MISMATCH) { OfficialUpdates.requireCompatibleInstalled(offer(), installed("ro.mdp.remix.premiumtest")) }
    }
    @Test fun rejectsDifferentOrMultipleSigningCertificates() {
        reject(UpdateError.SIGNATURE_MISMATCH) { OfficialUpdates.requireCompatibleInstalled(offer(), installed(certificates = setOf(hash))) }
        reject(UpdateError.SIGNATURE_MISMATCH) { OfficialUpdates.requireCompatibleInstalled(offer(), installed(certificates = setOf(cert, hash))) }
        reject(UpdateError.SIGNATURE_MISMATCH) { OfficialUpdates.requireCompatibleArchive(offer(), installed(), installed(version = 12, certificates = setOf(hash))) }
    }
    @Test fun rejectsOlderSameOrMisrepresentedArchiveVersion() {
        reject(UpdateError.INVALID_APK) { OfficialUpdates.requireCompatibleArchive(offer(), installed(version = 12), installed(version = 12)) }
        reject(UpdateError.INVALID_APK) { OfficialUpdates.requireCompatibleArchive(offer(), installed(), installed(version = 10)) }
        reject(UpdateError.PACKAGE_MISMATCH) { OfficialUpdates.requireCompatibleArchive(offer(), installed(), installed(packageName = "evil", version = 12)) }
    }
    @Test fun crossChecksGitHubAssetDigestWhenPresent() {
        val assets = JSONArray().put(asset(OfficialUpdates.MANIFEST_NAME, 500)).put(asset(name, 1024).put("digest", "sha256:$cert"))
        val parsed = OfficialUpdates.parseRelease(releaseJson().put("assets", assets).toString())
        reject { OfficialUpdates.parseManifest(manifestJson().toString(), parsed) }
    }
}
