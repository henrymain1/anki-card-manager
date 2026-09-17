package com.borderless.ankicards.ui.staging

import com.borderless.ankicards.data.queue.QueueItem

data class StagingUiState(
    val items: List<QueueItem> = emptyList(),
    val errorMessage: String? = null,
    val needsAnkiPermission: Boolean = false
)
