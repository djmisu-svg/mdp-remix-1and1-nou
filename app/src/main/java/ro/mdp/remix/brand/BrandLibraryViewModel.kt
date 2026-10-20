package ro.mdp.remix.brand

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ro.mdp.remix.links.LinkLibrary

enum class BrandLibraryStatus { IDLE, UPDATED, CURRENT, NOT_PUBLISHED, ERROR }

data class BrandLibraryState(
    val enabled: Boolean = true,
    val syncing: Boolean = false,
    val status: BrandLibraryStatus = BrandLibraryStatus.IDLE,
    val lastSyncedAt: Long = 0,
)

/** Uses the existing link ViewModel's suspend mutation, never a second repository instance. */
class BrandLibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("mdp_brand_sync", 0)
    private val client = BrandLibraryClient()
    private val mutableState = MutableStateFlow(BrandLibraryState(
        enabled = preferences.getBoolean("enabled", true),
        lastSyncedAt = preferences.getLong("last_synced", 0),
    ))
    val state: StateFlow<BrandLibraryState> = mutableState.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("enabled", enabled).apply()
        mutableState.value = mutableState.value.copy(enabled = enabled)
    }

    fun syncIfDue(onLibrary: suspend (LinkLibrary, String) -> Unit) {
        val age = System.currentTimeMillis() - preferences.getLong("last_checked", 0)
        if (mutableState.value.enabled && (age >= 60 * 60 * 1000L || age < 0)) sync(onLibrary)
    }

    fun sync(onLibrary: suspend (LinkLibrary, String) -> Unit) {
        if (mutableState.value.syncing) return
        mutableState.value = mutableState.value.copy(syncing = true)
        preferences.edit().putLong("last_checked", System.currentTimeMillis()).apply()
        viewModelScope.launch {
            try {
                val snapshot = client.fetch()
                val status = when {
                    snapshot == null -> BrandLibraryStatus.NOT_PUBLISHED
                    snapshot.revision == preferences.getString("revision", null) -> BrandLibraryStatus.CURRENT
                    else -> {
                        onLibrary(snapshot.library, snapshot.revision)
                        val now = System.currentTimeMillis()
                        preferences.edit().putString("revision", snapshot.revision).putLong("last_synced", now).apply()
                        mutableState.value = mutableState.value.copy(lastSyncedAt = now)
                        BrandLibraryStatus.UPDATED
                    }
                }
                mutableState.value = mutableState.value.copy(status = status)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A network or parse failure never deletes the previously saved offline collection.
                mutableState.value = mutableState.value.copy(status = BrandLibraryStatus.ERROR)
            } finally {
                mutableState.value = mutableState.value.copy(syncing = false)
            }
        }
    }
}
