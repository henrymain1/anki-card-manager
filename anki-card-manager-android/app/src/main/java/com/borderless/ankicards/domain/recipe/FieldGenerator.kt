package com.borderless.ankicards.domain.recipe

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How a single field on a card gets its content.
 *
 * Each generator has different cost and latency:
 *   UserInput    – free, instant. The user types it.
 *   Dictionary   – free, instant. Local bundled dictionary lookup (CC-CEDICT,
 *                  CC-Canto, JMDict, etc.). Falls back to [fallback] on miss.
 *   Llm          – costs tokens. Generated as part of the single unified
 *                  structured-output call for the card.
 *   Tts          – separate TTS API. Reads [sourceFieldKey] aloud.
 *   ImageGen     – separate image-gen API. Most expensive per call, so cards
 *                  default to [deferred] = true so the user opts in per card.
 *
 * Sealed + polymorphic so the JSON has an explicit "type" tag and the compiler
 * forces every routing site to handle every generator.
 */
@Serializable
sealed class FieldGenerator {

    /**
     * The field's value is whatever the user types into it manually on the
     * card editor — never AI-generated, never looked up.
     *
     * Use this for fields the user explicitly owns: a personal mnemonic they
     * came up with, a free-form note, tags. Do NOT use this for the canonical
     * Word field, because the user's *query* is not the same as the *word*:
     * a query like "evening je6maan5" is a disambiguation hint that needs to
     * be resolved by the LLM into the canonical word before any dictionary
     * lookups can run.
     */
    @Serializable
    @SerialName("user_input")
    data object UserInput : FieldGenerator()

    /**
     * Look up the field's value in a bundled dictionary, keyed by the value
     * of another field on the card (typically the canonical Word field).
     *
     * IMPORTANT: dictionaries are looked up by canonical form, NOT by the raw
     * user query. The user might type "evening je6maan5" to disambiguate
     * between 夜晚 and 晚黑; that query is resolved into the Word field by the
     * LLM first, and only the resolved word is sent to the dictionary. This
     * prevents disambiguation hints from breaking lookups.
     *
     * If the lookup misses (uncommon word, proper noun, slang), fall back to
     * [fallback] — by default an LLM call that produces the field's value
     * from the existing card context.
     */
    @Serializable
    @SerialName("dictionary")
    data class Dictionary(
        val source: DictionarySource,
        val lookupFieldKey: String = "word",
        val fallback: FieldGenerator? = Llm()
    ) : FieldGenerator()

    /**
     * LLM-generated. The card builder bundles every field with this generator
     * into a single structured-output call, so adding LLM fields costs more
     * tokens but never more requests.
     *
     * [promptOverride] is optional and only used by power users who want to
     * supersede the auto-derived prompt (which is built from the field's
     * [CardField.description]).
     */
    @Serializable
    @SerialName("llm")
    data class Llm(
        val promptOverride: String? = null
    ) : FieldGenerator()

    /** Synthesize speech from another field's value (typically the example sentence). */
    @Serializable
    @SerialName("tts")
    data class Tts(
        val sourceFieldKey: String
    ) : FieldGenerator()

    /**
     * Generate an image. Defaults to deferred so the (expensive) call only
     * happens when the user explicitly taps the image button on the card.
     */
    @Serializable
    @SerialName("image_gen")
    data class ImageGen(
        val style: String = "realistic photo",
        val promptTemplate: String = "{{word}}: {{definition}}",
        val deferred: Boolean = true
    ) : FieldGenerator()
}

/**
 * Which bundled dictionary a [FieldGenerator.Dictionary] generator should consult.
 * Each value corresponds to an open-source dictionary we ship inside the app.
 */
@Serializable
enum class DictionarySource {
    @SerialName("cc_canto") CC_CANTO,     // Cantonese (Jyutping)
    @SerialName("cc_cedict") CC_CEDICT,   // Mandarin (Pinyin)
    @SerialName("jmdict") JMDICT,         // Japanese
    @SerialName("wiktionary_en") WIKTIONARY_EN  // English fallback
}
