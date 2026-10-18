package ro.mdp.remix.links

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class LinkViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = LinkRepository(application)
    val state: StateFlow<LinkLibrary> = repository.state
    val loading: StateFlow<Boolean> = repository.loading
    private val mutableMessage = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = mutableMessage.asStateFlow()

    init {
        viewModelScope.launch {
            repository.error.collect { error -> if (error != null) mutableMessage.value = error }
        }
    }

    suspend fun sharedSnapshot(): LinkLibrary = repository.sharedSnapshot()
    suspend fun importShared(library: LinkLibrary, transferId: String) = repository.importShared(library, transferId)
    suspend fun syncBrand(library: LinkLibrary) = repository.syncBrand(library)

    fun addLink(title: String, url: String, playlistId: String? = null) = perform { repository.addLink(title, url, playlistId) }
    fun renameLink(id: String, title: String) = perform { repository.renameLink(id, title) }
    fun deleteLink(id: String) = perform { repository.deleteLink(id) }
    fun toggleLinkFavorite(id: String) = perform { repository.toggleLinkFavorite(id) }
    fun createPlaylist(name: String) = perform { repository.createPlaylist(name) }
    fun renamePlaylist(id: String, name: String) = perform { repository.renamePlaylist(id, name) }
    fun deletePlaylist(id: String) = perform { repository.deletePlaylist(id) }
    fun togglePlaylistFavorite(id: String) = perform { repository.togglePlaylistFavorite(id) }
    fun addToPlaylist(playlistId: String, linkId: String) = perform { repository.addToPlaylist(playlistId, linkId) }
    fun removeFromPlaylist(playlistId: String, linkId: String) = perform { repository.removeFromPlaylist(playlistId, linkId) }
    fun movePlaylist(id: String, direction: Int) = perform { repository.movePlaylist(id, direction) }
    fun moveLink(playlistId: String, linkId: String, direction: Int) = perform { repository.moveLink(playlistId, linkId, direction) }
    fun markLinkOpened(id: String) = perform { repository.markLinkOpened(id) }
    fun markPlaylistOpened(id: String) = perform { repository.markPlaylistOpened(id) }

    fun clearMessage() {
        repository.clearError()
        mutableMessage.value = null
    }

    private fun perform(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                mutableMessage.value = failure.message ?: "Modificarea nu a putut fi salvată."
            }
        }
    }

    override fun onCleared() {
        repository.close()
        super.onCleared()
    }
}
