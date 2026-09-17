package com.borderless.ankicards.data.images

import com.borderless.ankicards.data.settings.ImageSourcePreference

/**
 * Result of trying to satisfy a card's image from a stock library.
 *
 * The distinction between [Miss] and [Exhausted] is the whole policy: the
 * *client* owns whether generation is an allowed fallback (it reads the
 * user's [com.borderless.ankicards.data.settings.ImageSourcePreference]), so
 * [com.borderless.ankicards.data.gemini.CardGenerator] doesn't need a
 * settings dependency of its own — it just switches on the outcome.
 */
sealed interface ImageSearchOutcome {

    /** A photo cleared the quality gate and its bytes were downloaded. */
    data class Found(
        val bytes: ByteArray,
        /** Pixabay page for the photo. Logged only — not shown on the card. */
        val sourceUrl: String
    ) : ImageSearchOutcome {
        // ByteArray in a data class means identity equals/hashCode, which is
        // wrong-ish but harmless here (we never compare outcomes). Overridden
        // so lint and any future `assertEquals` behave sanely.
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Found && sourceUrl == other.sourceUrl && bytes.contentEquals(other.bytes))

        override fun hashCode(): Int = 31 * sourceUrl.hashCode() + bytes.contentHashCode()
    }

    /** Nothing usable found (or search is switched off). Caller should generate. */
    data object Miss : ImageSearchOutcome

    /** Nothing usable found and the user has forbidden generation. Caller must not generate. */
    data object Exhausted : ImageSearchOutcome
}

/**
 * Narrow surface [com.borderless.ankicards.data.gemini.CardGenerator] depends
 * on, mirroring the [com.borderless.ankicards.data.gemini.GeminiClient]
 * pattern so tests can fake it without Retrofit, OkHttp, or DataStore.
 */
interface ImageSearchClient {

    /**
     * Look up [query] — expected to be two or three concrete keywords, not a
     * sentence. Pixabay matches loosely rather than ANDing terms, so a prose
     * description dilutes into generic stock; see the doc on
     * [PixabayImageSearchRepository] for the measurements.
     *
     * Never throws: a network failure is reported as [ImageSearchOutcome.Miss]
     * (or [ImageSearchOutcome.Exhausted]) so a dead search endpoint degrades
     * into "generate the image" rather than a broken card.
     */
    suspend fun search(query: String): ImageSearchOutcome

    /**
     * The user's current image-source setting. The orchestrator reads it to
     * decide whether to ask the LLM for search keywords at all (no point when
     * search is off — same reasoning as the placed-fields-only filter) and
     * whether manual regeneration is allowed to spend money.
     */
    suspend fun mode(): ImageSourcePreference

    /**
     * No-op implementation. Used as the default in
     * [com.borderless.ankicards.data.gemini.CardGenerator]'s constructor so
     * existing callers and tests keep the pre-search behavior (always
     * generate) without being edited.
     */
    object Disabled : ImageSearchClient {
        override suspend fun search(query: String): ImageSearchOutcome = ImageSearchOutcome.Miss
        override suspend fun mode(): ImageSourcePreference = ImageSourcePreference.GenerateOnly
    }
}
