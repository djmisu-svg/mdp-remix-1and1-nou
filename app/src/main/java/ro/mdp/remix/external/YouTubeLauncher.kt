package ro.mdp.remix.external

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import ro.mdp.remix.links.YouTubeUrl

/** Opens a validated HTTPS link through Android's supported external app flow. */
object YouTubeLauncher {
    enum class Result { OPENED, INVALID_LINK, NO_HANDLER }

    fun open(context: Context, rawUrl: String): Result {
        val target = YouTubeUrl.parse(rawUrl) ?: return Result.INVALID_LINK
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target.canonicalUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Try the official Android application first. A normal HTTPS browser
        // intent handles devices where YouTube is absent or disabled.
        for (packageName in listOf("com.google.android.youtube", null)) {
            try {
                context.startActivity(Intent(intent).setPackage(packageName))
                return Result.OPENED
            } catch (_: ActivityNotFoundException) {
                // Continue to the supported browser fallback.
            } catch (_: SecurityException) {
                // A disabled/restricted handler may still allow a browser.
            }
        }
        return Result.NO_HANDLER
    }
}
