package ro.mdp.remix.background

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Returns only the genuine, canonical asset; its absence must never select a substitute. */
fun resolveOriginalBackgroundAsset(context: Context): String? = try {
    context.assets.open(ORIGINAL_COUPLE_ASSET).use { ORIGINAL_COUPLE_ASSET }
} catch (_: IOException) {
    null
}

/** Background preferences are private to this installation and independent of every music library. */
class BackgroundRepository(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences(BACKGROUND_PREFERENCES, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private var loadSucceeded = false
    private val mutableSettings = MutableStateFlow(BackgroundSettings())
    val settings: StateFlow<BackgroundSettings> = mutableSettings.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = mutableError.asStateFlow()
    private val mutableLoading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = mutableLoading.asStateFlow()
    private val mutableOriginalAvailable = MutableStateFlow(false)
    val originalAvailable: StateFlow<Boolean> = mutableOriginalAvailable.asStateFlow()

    private val initialLoad = scope.async {
        mutex.withLock {
            try {
                val saved = preferences.getString(BACKGROUND_SETTINGS_KEY, null)
                mutableSettings.value = saved?.let(BackgroundSettingsJson::decode) ?: BackgroundSettings()
                loadSucceeded = true
                // A revoked or temporarily inaccessible document stays selected on disk.
                mutableSettings.value.uri?.let { uri ->
                    try {
                        requireReadable(Uri.parse(uri))
                    } catch (failure: Exception) {
                        mutableError.value = "The saved background is unavailable. Choose the file again or restore Original. Your selection has been preserved."
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                mutableError.value = "Background settings could not be read. Saved settings have been preserved. Choose a background or restore Original to replace them."
            } finally {
                try {
                    mutableOriginalAvailable.value = resolveOriginalBackgroundAsset(appContext) != null
                } finally {
                    mutableLoading.value = false
                }
            }
        }
    }

    suspend fun select(uri: Uri, isCurrentSelection: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        initialLoad.await()
        mutex.withLock {
            if (!isCurrentSelection()) return@withLock
            require(BackgroundSettingsRules.isDocumentUri(uri.toString())) {
                "Choose an image, GIF or video using the Android document picker."
            }
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
            val kind = requireNotNull(BackgroundSettingsRules.classify(resolver.getType(uri), name)) {
                "Choose a JPEG, PNG, WebP, GIF or an Android-supported video."
            }
            val verifiedKind = validateMedia(uri, kind)
            currentCoroutineContext().ensureActive()
            if (!isCurrentSelection()) return@withLock
            // Document grants can also be used by the music library. This feature never
            // releases a grant, including when replacing or restoring a background.
            if (!hasPersistedReadPermission(uri)) {
                try {
                    resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {
                    throw IllegalArgumentException("This provider cannot retain access to the background. Choose a file from another document provider.")
                }
            }
            require(hasPersistedReadPermission(uri)) {
                "Persistent read access was not granted. Your previous background has been kept."
            }
            currentCoroutineContext().ensureActive()
            if (!isCurrentSelection()) return@withLock
            val previous = if (loadSucceeded) mutableSettings.value else BackgroundSettings()
            persistAndPublish(previous.copy(uri = uri.toString(), kind = verifiedKind))
        }
    }

    /** Imported files are private copies exposed by our own provider, not SAF grants. */
    suspend fun importShared(settings: BackgroundSettings) = withContext(Dispatchers.IO) {
        initialLoad.await()
        mutex.withLock {
            BackgroundSettingsRules.validate(settings)
            if (settings.kind != BackgroundKind.ORIGINAL) {
                val uri = Uri.parse(requireNotNull(settings.uri))
                require(uri.scheme == "content" && uri.authority == "${appContext.packageName}.mdpfiles" &&
                    uri.path.orEmpty().matches(Regex("/media/[0-9a-f]{64}"))) {
                    "Received backgrounds must use this installation's copied media files."
                }
                require(validateMedia(uri, settings.kind) == settings.kind) {
                    "The received background type does not match its media."
                }
            }
            currentCoroutineContext().ensureActive()
            persistAndPublish(settings)
        }
    }

    suspend fun setScale(scale: BackgroundScale) = update { it.copy(scale = scale) }
    suspend fun setDimming(value: Float, isCurrentUpdate: () -> Boolean = { true }) =
        update(isCurrentUpdate) { BackgroundSettingsRules.withDimming(it, value) }
    suspend fun setAnimationEnabled(enabled: Boolean) = update { it.copy(animationEnabled = enabled) }

    suspend fun restoreOriginal(isCurrentSelection: () -> Boolean = { true }) = withContext(Dispatchers.IO) {
        initialLoad.await()
        mutex.withLock {
            if (isCurrentSelection()) persistAndPublish(BackgroundSettings())
        }
    }

    fun clearError() { mutableError.value = null }
    fun reportError(message: String) { mutableError.value = message }
    fun close() { scope.cancel() }

    private suspend fun update(
        isCurrentUpdate: () -> Boolean = { true },
        transform: (BackgroundSettings) -> BackgroundSettings,
    ) = withContext(Dispatchers.IO) {
        initialLoad.await()
        mutex.withLock {
            if (!isCurrentUpdate()) return@withLock
            check(loadSucceeded) { "Saved background settings are unavailable. Choose a background or restore Original first." }
            currentCoroutineContext().ensureActive()
            val next = BackgroundSettingsRules.validate(transform(mutableSettings.value))
            if (isCurrentUpdate() && next != mutableSettings.value) persistAndPublish(next)
        }
    }

    private fun persistAndPublish(settings: BackgroundSettings) {
        val encoded = BackgroundSettingsJson.encode(settings)
        check(preferences.edit().putString(BACKGROUND_SETTINGS_KEY, encoded).commit()) {
            "The background could not be saved. Your previous settings have been kept."
        }
        loadSucceeded = true
        mutableSettings.value = settings
        mutableError.value = null
    }

    private fun hasPersistedReadPermission(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    private fun requireReadable(uri: Uri) {
        resolver.openAssetFileDescriptor(uri, "r")?.use { return }
        throw IOException("The background document cannot be opened.")
    }

    private fun validateMedia(uri: Uri, kind: BackgroundKind): BackgroundKind {
        requireReadable(uri)
        if (kind == BackgroundKind.VIDEO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(appContext, uri)
                require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                    .equals("yes", ignoreCase = true)) { "The selected file does not contain a supported video." }
            } finally {
                retriever.release()
            }
            return BackgroundKind.VIDEO
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth > 0 && options.outHeight > 0) { "The selected image is damaged or unsupported." }
        return requireNotNull(BackgroundSettingsRules.classify(options.outMimeType, null)
            ?.takeIf { it == BackgroundKind.IMAGE || it == BackgroundKind.GIF }) {
            "The selected image must be JPEG, PNG, WebP or GIF."
        }
    }
}
