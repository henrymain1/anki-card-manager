package com.borderless.ankicards.domain.deck

/**
 * The language a deck is for. Locked at deck creation and never changed.
 *
 * Stored as a BCP-47-ish language subtag (`yue`, `cmn`, `ja`, `es`, `fr`,
 * or empty string for [Generic]). The string form is what gets written to
 * the database and threaded through into card-type matching, dictionary
 * routing, TTS voice selection, and the LLM prompt.
 *
 * The enum exists so the UI has a fixed list to render and so misspellings
 * can't happen. The companion provides round-tripping between the enum and
 * its stored code, including a forward-compatible "decode unknown code as
 * Generic" so older app versions don't blow up on newer decks.
 */
enum class DeckLanguage(val code: String, val displayName: String) {
    Cantonese("yue", "Cantonese"),
    Mandarin("cmn", "Mandarin"),
    Japanese("ja", "Japanese"),
    Thai("th", "Thai"),
    Spanish("es", "Spanish"),
    French("fr", "French"),
    German("de", "German"),
    Italian("it", "Italian"),
    /**
     * No language smarts. Dictionaries, language-specific TTS, and
     * language-aware LLM prompting are all disabled. Useful for non-language
     * decks (e.g. trivia, a programming-language deck) where the AI shouldn't
     * assume a target language.
     */
    Generic("", "Generic / other");

    companion object {
        /** Round-trip from the stored code. Unknown codes decode as [Generic]. */
        fun fromCode(code: String?): DeckLanguage =
            entries.firstOrNull { it.code == (code ?: "") } ?: Generic

        /** All choices in display order. */
        val all: List<DeckLanguage> = entries.toList()
    }
}
