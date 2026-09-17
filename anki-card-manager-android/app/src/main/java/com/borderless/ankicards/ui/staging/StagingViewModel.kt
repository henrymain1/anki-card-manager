package com.borderless.ankicards.ui.staging

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.borderless.ankicards.data.anki.AnkiDroidRepository
import com.borderless.ankicards.data.queue.QueueRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class StagingViewModel(
    private val queue: QueueRepository,
    private val anki: AnkiDroidRepository
) : ViewModel() {

    private val transient = MutableStateFlow(TransientState())

    val uiState: StateFlow<StagingUiState> =
        combine(queue.items, transient) { items, t ->
            StagingUiState(
                items = items,
                errorMessage = t.errorMessage,
                needsAnkiPermission = t.needsAnkiPermission
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = StagingUiState()
        )

    fun onApprove(id: String) {
        if (!anki.isAnkiDroidInstalled()) {
            transient.update { it.copy(errorMessage = "AnkiDroid is not installed on this device.") }
            return
        }
        if (!anki.hasPermission()) {
            transient.update { it.copy(needsAnkiPermission = true, pendingApproveId = id) }
            return
        }
        viewModelScope.launch {
            queue.approve(id).onFailure { err ->
                transient.update { it.copy(errorMessage = err.message ?: "Failed to add card") }
            }
        }
    }

    fun onPermissionRequestConsumed() {
        transient.update { it.copy(needsAnkiPermission = false) }
    }

    fun onPermissionGranted() {
        val pending = transient.value.pendingApproveId
        transient.update { it.copy(pendingApproveId = null) }
        if (pending != null) onApprove(pending)
    }

    fun onPermissionDenied() {
        transient.update {
            it.copy(
                pendingApproveId = null,
                errorMessage = "AnkiDroid permission was denied. Open Android settings to grant it."
            )
        }
    }

    fun onRetry(id: String) = queue.retry(id)
    fun onDismiss(id: String) = queue.dismiss(id)
    fun onClearAll() = queue.clearAll()
    fun onCardEdited(id: String, updated: com.borderless.ankicards.domain.model.Card) =
        queue.editCard(id, updated)

    fun onMessageShown() {
        transient.update { it.copy(errorMessage = null) }
    }

    private data class TransientState(
        val errorMessage: String? = null,
        val needsAnkiPermission: Boolean = false,
        val pendingApproveId: String? = null
    )

    companion object {
        fun factory(queue: QueueRepository, anki: AnkiDroidRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    StagingViewModel(queue, anki) as T
            }
    }
}
