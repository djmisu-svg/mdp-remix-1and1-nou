package ro.mdp.remix.background

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class BackgroundViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = BackgroundRepository(application)
    private data class DimmingRequest(val value: Float, val generation: Long, val revision: Long)
    private val dimmingRequests = Channel<DimmingRequest>(Channel.CONFLATED)
    private val dimmingPreview = MutableStateFlow<Float?>(null)
    @Volatile private var dimmingGeneration = 0L
    @Volatile private var selectionGeneration = 0L
    @Volatile private var pendingDimming: DimmingRequest? = null
    private var dimmingRevision = 0L

    val settings: StateFlow<BackgroundSettings> = combine(repository.settings, dimmingPreview) { saved, preview ->
        if (preview == null) saved else saved.copy(dimming = preview)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BackgroundSettings())
    val error: StateFlow<String?> = repository.error
    val loading: StateFlow<Boolean> = repository.loading
    val originalAvailable: StateFlow<Boolean> = repository.originalAvailable

    init {
        viewModelScope.launch {
            // The quiet period prevents a fast preference writer from committing every
            // slider frame. The visible preview remains independent of disk latency.
            dimmingRequests.receiveAsFlow().debounce(150L).collect { persistDimming(it) }
        }
    }

    fun select(uri: Uri) {
        val generation = ++selectionGeneration
        viewModelScope.launch {
            try {
                repository.select(uri) { generation == selectionGeneration }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                if (generation == selectionGeneration) reportFailure(failure)
            }
        }
    }
    fun setScale(scale: BackgroundScale) = perform { repository.setScale(scale) }
    fun setAnimationEnabled(enabled: Boolean) = perform { repository.setAnimationEnabled(enabled) }

    fun setDimming(value: Float) {
        if (!value.isFinite()) {
            repository.reportError("Choose a valid background dimming value.")
            return
        }
        val normalized = value.coerceIn(0f, 1f)
        dimmingPreview.value = normalized
        val request = DimmingRequest(normalized, dimmingGeneration, ++dimmingRevision)
        pendingDimming = request
        dimmingRequests.trySend(request)
    }

    /** Called when dragging ends or the Activity stops; disk I/O stays off the UI thread. */
    fun flushDimming() {
        val request = pendingDimming ?: return
        viewModelScope.launch { persistDimming(request) }
    }

    fun restoreOriginal() {
        val generation = ++selectionGeneration
        dimmingGeneration++
        pendingDimming = null
        dimmingPreview.value = null
        perform { repository.restoreOriginal { generation == selectionGeneration } }
    }

    fun onRenderError(message: String? = null) {
        repository.reportError(message?.takeIf(String::isNotBlank)
            ?: "The background could not be displayed. Choose the file again or restore Original.")
    }

    fun clearError() { repository.clearError() }

    private suspend fun persistDimming(request: DimmingRequest) {
        fun isCurrent() = request.generation == dimmingGeneration && pendingDimming == request
        if (!isCurrent()) return
        try {
            repository.setDimming(request.value, ::isCurrent)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (isCurrent()) reportFailure(failure)
        } finally {
            if (isCurrent()) {
                pendingDimming = null
                dimmingPreview.value = null
            }
        }
    }

    private fun perform(operation: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                operation()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                reportFailure(failure)
            }
        }
    }

    private fun reportFailure(failure: Exception) {
        repository.reportError(failure.message?.takeIf(String::isNotBlank)
            ?: "Background settings could not be saved. Your previous selection has been kept.")
    }

    override fun onCleared() {
        dimmingRequests.close()
        repository.close()
        super.onCleared()
    }
}
