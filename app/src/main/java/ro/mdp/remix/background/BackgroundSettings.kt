package ro.mdp.remix.background

import java.net.URI
import java.util.Locale
import org.json.JSONObject

const val ORIGINAL_COUPLE_ASSET = "backgrounds/original_couple.gif"
const val BACKGROUND_PREFERENCES = "mdp_background_v1"
const val BACKGROUND_SETTINGS_KEY = "background_settings_json"

enum class BackgroundKind { ORIGINAL, IMAGE, GIF, VIDEO }
enum class BackgroundScale { FIT, FILL }

data class BackgroundSettings(
    val uri: String? = null,
    val kind: BackgroundKind = BackgroundKind.ORIGINAL,
    val scale: BackgroundScale = BackgroundScale.FILL,
    val dimming: Float = 0.55f,
    val animationEnabled: Boolean = true,
)

/** File-type and settings validation shared by persistence, selection and JVM tests. */
object BackgroundSettingsRules {
    private val imageTypes = setOf("image/jpeg", "image/jpg", "image/png", "image/webp")
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")
    private val videoExtensions = setOf(
        "mp4", "m4v", "webm", "3gp", "3gpp", "3g2", "mkv", "ts", "mov", "mpeg", "mpg", "avi",
    )

    fun classify(mimeType: String?, displayName: String?): BackgroundKind? {
        val mime = mimeType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        return when {
            mime == "image/gif" -> BackgroundKind.GIF
            mime in imageTypes -> BackgroundKind.IMAGE
            mime.startsWith("video/") && mime.length > "video/".length && mime != "video/*" -> BackgroundKind.VIDEO
            mime.isNotBlank() && mime !in setOf("application/octet-stream", "image/*", "video/*") -> null
            else -> {
                val extension = displayName.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
                when {
                    extension == "gif" && mime != "video/*" -> BackgroundKind.GIF
                    extension in imageExtensions && mime != "video/*" -> BackgroundKind.IMAGE
                    extension in videoExtensions && mime != "image/*" -> BackgroundKind.VIDEO
                    else -> null
                }
            }
        }
    }

    fun validate(settings: BackgroundSettings): BackgroundSettings {
        require(settings.dimming.isFinite() && settings.dimming in 0f..1f) { "Background dimming must be between 0 and 1." }
        if (settings.kind == BackgroundKind.ORIGINAL) {
            require(settings.uri == null) { "The original background cannot reference a selected file." }
        } else {
            require(isDocumentUri(settings.uri)) { "Choose a background with the Android document picker." }
        }
        return settings
    }

    fun isDocumentUri(value: String?): Boolean {
        if (value == null || value.length > 8192) return false
        val uri = runCatching { URI(value) }.getOrNull() ?: return false
        return uri.scheme == "content" && !uri.rawAuthority.isNullOrBlank() &&
            !uri.rawPath.isNullOrBlank() && uri.rawFragment == null
    }

    fun withDimming(settings: BackgroundSettings, value: Float): BackgroundSettings {
        require(value.isFinite()) { "Choose a valid background dimming value." }
        return settings.copy(dimming = value.coerceIn(0f, 1f))
    }
}

/** Separate, versioned preference value; music libraries and language settings are untouched. */
object BackgroundSettingsJson {
    fun encode(settings: BackgroundSettings): String {
        BackgroundSettingsRules.validate(settings)
        return JSONObject().apply {
            put("version", 1)
            put("uri", settings.uri ?: JSONObject.NULL)
            put("kind", settings.kind.name)
            put("scale", settings.scale.name)
            put("dimming", settings.dimming.toDouble())
            put("animationEnabled", settings.animationEnabled)
        }.toString()
    }

    fun decode(text: String): BackgroundSettings {
        val root = JSONObject(text)
        require(!root.has("version") || root.get("version") == 1) { "These background settings belong to an unsupported app version." }
        val defaults = BackgroundSettings()
        val kind = if (root.has("kind")) BackgroundKind.valueOf(root.getString("kind")) else defaults.kind
        val scale = if (root.has("scale")) BackgroundScale.valueOf(root.getString("scale")) else defaults.scale
        val uri = if (!root.has("uri") || root.isNull("uri")) null else {
            require(root.get("uri") is String) { "Invalid saved background URI." }
            root.getString("uri")
        }
        val dimming = if (root.has("dimming")) {
            require(root.get("dimming") is Number) { "Invalid saved background dimming." }
            root.getDouble("dimming").toFloat()
        } else defaults.dimming
        val animated = if (root.has("animationEnabled")) {
            require(root.get("animationEnabled") is Boolean) { "Invalid saved background animation setting." }
            root.getBoolean("animationEnabled")
        } else defaults.animationEnabled
        return BackgroundSettingsRules.validate(BackgroundSettings(uri, kind, scale, dimming, animated))
    }
}
