package com.borderless.ankicards.data.queue

import com.borderless.ankicards.domain.model.Card
import java.util.UUID

/**
 * One word that's been shared into the app and is on its way to becoming a card.
 *
 * Lifecycle: Generating → Ready → (user approves) → removed from queue.
 * On error: → Failed → (user retries or dismisses).
 */
data class QueueItem(
    val id: String = UUID.randomUUID().toString(),
    val input: String,
    val createdAt: Long = System.currentTimeMillis(),
    val state: State = State.Generating,
    val card: Card? = null,
    val errorMessage: String? = null
) {
    enum class State { Generating, Ready, Failed, Approving }
}
