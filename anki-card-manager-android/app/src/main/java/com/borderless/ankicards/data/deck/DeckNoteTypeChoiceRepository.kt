package com.borderless.ankicards.data.deck

/**
 * Persists "which card type did I last use in deck X, and which of its designs
 * did I have ticked" so the generator screen can restore both next time. Pure
 * convenience — no enforcement, no compatibility logic (that lives upstream).
 */
class DeckNoteTypeChoiceRepository(
    private val dao: DeckNoteTypeChoiceDao
) {

    /** The remembered selection for a deck: the note type plus its ticked designs. */
    data class Choice(
        val noteTypeId: String,
        val selectedTemplateIds: Set<String>
    )

    suspend fun getChoice(deckName: String): Choice? =
        dao.getByDeck(deckName)?.let { row ->
            Choice(
                noteTypeId = row.noteTypeId,
                selectedTemplateIds = row.selectedTemplateIds
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            )
        }

    suspend fun setChoice(
        deckName: String,
        noteTypeId: String,
        selectedTemplateIds: Set<String>
    ) {
        dao.upsert(
            DeckNoteTypeChoiceEntity(
                deckName = deckName,
                noteTypeId = noteTypeId,
                selectedTemplateIds = selectedTemplateIds.joinToString(","),
                updatedAt = System.currentTimeMillis()
            )
        )
    }
}
