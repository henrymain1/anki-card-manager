package com.borderless.ankicards.domain.recipe

import com.borderless.ankicards.domain.deck.DeckLanguage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Output of [NoteType.buildLlmRequest]: everything the structured-output Gemini
 * call needs in one place.
 *
 * @param prompt           Full prompt to send (system instructions + the user's
 *                         query + per-field rules built from each field's
 *                         description).
 * @param schema           JSON schema describing the shape Gemini should
 *                         respond in. One property per LLM-routed field, keyed
 *                         by [CardField.key]. Use as `responseSchema` on the
 *                         Gemini request with `responseMimeType = "application/json"`.
 * @param requestedKeys    Field keys the LLM is being asked to fill in,
 *                         preserving the card type's declared order. The
 *                         orchestrator iterates this to merge the response
 *                         back into the assembled card.
 */
data class LlmGenerationRequest(
    val prompt: String,
    val schema: JsonObject,
    val requestedKeys: List<String>
)

/**
 * Synthetic schema property carrying stock-photo search keywords for the
 * card's image. **Not a [CardField]** — it has no placement, never reaches
 * AnkiDroid, and is stripped from the streamed values before the UI sees
 * them. It rides along in the existing structured-output call so the
 * keywords cost ~10 tokens and zero extra latency.
 *
 * The double-underscore prefix marks it as app-internal and keeps it from
 * ever colliding with a user-authored field key.
 */
const val IMAGE_QUERY_KEY = "__image_query"

/**
 * What we ask the model to put in [IMAGE_QUERY_KEY].
 *
 * Keywords, emphatically not an image-generation prompt. Pixabay matches
 * loosely instead of ANDing terms, so a descriptive sentence dilutes into
 * generic stock — a full-sentence query for "hesitate" returned 37,349
 * mostly-irrelevant results, while `crossroads decision` returned 1,856
 * usable ones. See `PixabayImageSearchRepository` for the measurements.
 */
private const val IMAGE_QUERY_DESCRIPTION =
    "Two or three concrete keywords naming a scene a photographer could have " +
        "photographed that shows what this word means. Name visible things and " +
        "actions. For the word \"hesitate\", return: crossroads decision"

/**
 * Build the structured-output LLM call for [this] card type, given the user's
 * query and the deck's language.
 *
 * Includes every field whose generator is [FieldGenerator.Llm] *or*
 * [FieldGenerator.Dictionary] — Dictionary fields fall through to the LLM in
 * Phase 2 (Phase 3 will pre-empt them with real dictionary lookups). [FieldGenerator.Tts],
 * [FieldGenerator.ImageGen], and [FieldGenerator.UserInput] are handled
 * elsewhere and skipped here.
 *
 * Returns `null` if the card type has no fields that need the LLM at all
 * (rare but possible — e.g. a pure-manual card type). Callers should treat
 * that as "no LLM call needed."
 */
fun NoteType.buildLlmRequest(
    query: String,
    language: DeckLanguage,
    /**
     * Add the [IMAGE_QUERY_KEY] control property. Set by the orchestrator only
     * when the card actually has an image field *and* the user's image-source
     * setting can use search — otherwise it's wasted tokens.
     */
    includeImageQuery: Boolean = false
): LlmGenerationRequest? {
    val llmFields = fields.filter {
        it.generator is FieldGenerator.Llm || it.generator is FieldGenerator.Dictionary
    }
    if (llmFields.isEmpty()) return null

    // (key, description) in the order Gemini should emit them. Structured
    // output follows the schema's `properties` declaration order, so this
    // list is also the streaming arrival order.
    val schemaEntries = buildSchemaEntries(llmFields, includeImageQuery)

    val schema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            schemaEntries.forEach { (key, description) ->
                put(key, buildJsonObject {
                    put("type", "string")
                    put("description", description)
                })
            }
        })
        put("required", buildJsonArray {
            // EVERY requested field is required. The user placed each of these
            // on a card, so they all want it filled. Leaving fields optional let
            // Gemini's structured output silently omit them at its discretion —
            // which produced the "Pinyin lands but Measure Word / Example
            // Sentence are blank" bug. A field with genuinely no value (e.g. a
            // measure word for an abstract noun) comes back as "" and collapses
            // cleanly via the template's {{#Field}}…{{/Field}} guard.
            schemaEntries.forEach { (key, _) -> add(key) }
        })
    }

    val prompt = buildPrompt(query, language, schemaEntries)
    return LlmGenerationRequest(
        prompt = prompt,
        schema = schema,
        // Card fields only. IMAGE_QUERY_KEY is deliberately absent — it isn't a
        // field on the card, so nothing downstream should try to merge it in.
        requestedKeys = llmFields.map { it.key }
    )
}

/**
 * Splice [IMAGE_QUERY_KEY] in immediately after whichever of `english` / `word`
 * comes last, so the search keywords land at the same moment the image leg was
 * already waiting for anyway — no added latency.
 *
 * Position matters for a second reason: the model resolves the word's sense
 * *before* naming the scene, which is the same disambiguation guarantee the
 * image leg relies on today. Emitting the keywords first would let it commit to
 * a scene before settling which sense of an ambiguous word it meant.
 */
private fun buildSchemaEntries(
    llmFields: List<CardField>,
    includeImageQuery: Boolean
): List<Pair<String, String>> {
    val entries = llmFields.map { field ->
        field.key to field.description.ifBlank { field.label }
    }
    if (!includeImageQuery) return entries

    val anchor = entries.indexOfLast { (key, _) -> key == "english" || key == "word" }
    val insertAt = if (anchor >= 0) anchor + 1 else 0
    return entries.toMutableList().apply {
        add(insertAt, IMAGE_QUERY_KEY to IMAGE_QUERY_DESCRIPTION)
    }
}

private fun buildPrompt(
    query: String,
    language: DeckLanguage,
    /** Same (key, description) list the schema was built from, same order. */
    fields: List<Pair<String, String>>
): String {
    val languageLine = when (language) {
        DeckLanguage.Generic -> "The user is creating a flashcard."
        else -> "The user is learning ${language.displayName} (BCP-47 code: ${language.code})."
    }
    // No per-field "(optional)" tag — every field is required in the response
    // now (see the schema's `required` list and the "Return EVERY field" rule
    // below). A field with no real value comes back as an empty string.
    val fieldRules = fields.joinToString("\n") { (key, description) ->
        "- `$key`: $description"
    }
    return buildString {
        appendLine(languageLine)
        appendLine()
        appendLine("The user's query is: \"$query\".")
        appendLine(
            "If the query includes disambiguation hints (such as a specific romanization or " +
                "an English gloss), resolve it into a single canonical entry in the target language " +
                "before filling in the other fields. The resolved entry should go in the `word` " +
                "field if one is requested."
        )
        appendLine()
        appendLine("Fill in the following fields. Return ONLY a JSON object matching the schema:")
        appendLine(fieldRules)
        appendLine()
        appendLine(
            "Rules:\n" +
                "- All field values are plain strings (no nested objects, no markdown, no code fences).\n" +
                "- Single-line fields (Word, English, Pinyin, Measure Word, etc.) must be one line — " +
                "no leading/trailing whitespace, no blank lines, no literal `\\n` escape sequences.\n" +
                "- Multi-line fields (Example, Definition, Mnemonic) must include EVERY line their " +
                "description asks for, separated by real newline characters in the JSON string. Do NOT " +
                "collapse a 3-line example into 2 lines or 1 line. Do NOT skip the romanization line of " +
                "an example sentence — it is required when the target language uses a non-Latin script " +
                "(Mandarin, Cantonese, Japanese).\n" +
                "- Return EVERY field in the schema — never omit one. If you have no good answer for a " +
                "field, return an empty string for it — not a placeholder, not whitespace, not the " +
                "two-character literal `\\n`.\n" +
                "- Fill in every applicable field, especially Example Sentence — downstream features like " +
                "audio generation read from it.\n" +
                "- Be concise. Definitions are one or two senses, not exhaustive dictionary entries.\n" +
                "- Example sentences must be natural in the target language, not translated word-for-word."
        )
    }
}
