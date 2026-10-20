package ro.mdp.remix.transfer

import ro.mdp.remix.updates.OfficialUpdates
import ro.mdp.remix.updates.ReleaseAsset
import ro.mdp.remix.updates.UpdateOffer

/** Only a verified, direct APK endpoint may be offered as the public sharing link. */
object ReleaseApkLink {
    const val APK_NAME = "MdP-RemiX-Premium.apk"
    const val STABLE_URL = "https://github.com/${OfficialUpdates.REPOSITORY}/releases/latest/download/$APK_NAME"

    fun verified(offer: UpdateOffer): VerifiedApkLink {
        val alias = offer.release.assets.singleOrNull { it.name == APK_NAME }
            ?: throw ReleaseShareException(ReleaseShareError.NO_DIRECT_APK)
        // GitHub calculates this digest for the uploaded alias. It must be the
        // same file as the versioned APK declared by the official update manifest.
        if (offer.packageName != "ro.mdp.remix" || alias.size != offer.sizeBytes || alias.sha256 != offer.sha256 ||
            alias.url != "https://github.com/${OfficialUpdates.REPOSITORY}/releases/download/${offer.release.tag}/$APK_NAME") {
            throw ReleaseShareException(ReleaseShareError.UNTRUSTED_RELEASE)
        }
        return VerifiedApkLink(STABLE_URL, offer.versionName, offer.versionCode, alias)
    }
}

data class VerifiedApkLink internal constructor(
    val url: String,
    val versionName: String,
    val versionCode: Long,
    internal val asset: ReleaseAsset,
)

enum class ReleaseShareError { NETWORK, NO_RELEASE, NO_DIRECT_APK, UNTRUSTED_RELEASE }
class ReleaseShareException(val reason: ReleaseShareError, cause: Throwable? = null) : Exception(reason.name, cause)
