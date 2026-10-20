package ro.mdp.remix.updates

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** No silent installs: downloading and the user's installer confirmation are separate operations. */
enum class UpdatePhase { IDLE, CHECKING, CURRENT, AVAILABLE, DOWNLOADING, READY, VERIFYING, ERROR }
data class UpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val offer: UpdateOffer? = null,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val error: UpdateError? = null,
)

class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = UpdateRepository(application)
    private val mutableState = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()
    private var operation: Job? = null

    fun check() {
        if (operation?.isCompleted == false || state.value.phase == UpdatePhase.VERIFYING) return
        operation = viewModelScope.launch {
            mutableState.value = UpdateState(phase = UpdatePhase.CHECKING)
            try {
                val offer = repository.check()
                mutableState.value = UpdateState(if (offer == null) UpdatePhase.CURRENT else UpdatePhase.AVAILABLE, offer)
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { mutableState.value = UpdateState(phase = UpdatePhase.ERROR, error = reason(failure, UpdateError.NETWORK)) }
        }
    }

    fun download() {
        if (operation?.isCompleted == false || state.value.phase == UpdatePhase.VERIFYING) return
        val offer = state.value.offer ?: return
        operation = viewModelScope.launch {
            mutableState.value = UpdateState(UpdatePhase.DOWNLOADING, offer, totalBytes = offer.sizeBytes)
            try {
                repository.download(offer) { received, total ->
                    mutableState.value = UpdateState(UpdatePhase.DOWNLOADING, offer, received, total)
                }
                mutableState.value = UpdateState(UpdatePhase.READY, offer, offer.sizeBytes, offer.sizeBytes)
            } catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) {
                mutableState.value = UpdateState(UpdatePhase.ERROR, offer, error = reason(failure, UpdateError.DOWNLOAD))
            }
        }
    }

    suspend fun installationIntent(): Intent {
        val offer = state.value.offer ?: throw UpdateException(UpdateError.INVALID_APK)
        if (state.value.phase != UpdatePhase.READY) throw UpdateException(UpdateError.INVALID_APK)
        mutableState.value = state.value.copy(phase = UpdatePhase.VERIFYING, error = null)
        return try {
            repository.installationIntent(offer).also { mutableState.value = state.value.copy(phase = UpdatePhase.READY) }
        } catch (failure: CancellationException) {
            mutableState.value = state.value.copy(phase = UpdatePhase.READY)
            throw failure
        } catch (failure: Exception) {
            mutableState.value = state.value.copy(phase = UpdatePhase.READY, error = reason(failure, UpdateError.INSTALLER))
            throw failure
        }
    }

    suspend fun installedIdentity(): InstalledIdentity = repository.currentInstalledIdentity()

    fun installationFailed(failure: Throwable) {
        mutableState.value = state.value.copy(error = reason(failure, UpdateError.INSTALLER))
    }

    fun cancel() {
        operation?.cancel()
        mutableState.value = state.value.copy(phase = if (state.value.offer == null) UpdatePhase.IDLE else UpdatePhase.AVAILABLE,
            downloadedBytes = 0, error = null)
    }

    private fun reason(failure: Throwable, fallback: UpdateError) = (failure as? UpdateException)?.reason ?: fallback

    override fun onCleared() {
        operation?.cancel()
        repository.discard()
        super.onCleared()
    }
}
