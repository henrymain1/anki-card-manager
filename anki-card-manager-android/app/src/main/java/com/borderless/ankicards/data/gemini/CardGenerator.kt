package com.borderless.ankicards.data.gemini

import com.borderless.ankicards.data.images.ImageSearchClient
import com.borderless.ankicards.data.images.ImageSearchOutcome
import com.borderless.ankicards.data.settings.ImageSourcePreference
import com.borderless.ankicards.data.tts.TtsClient
import com.borderless.ankicards.domain.recipe.CardField
import com.borderless.ankicards.domain.recipe.CardGenerationRequest
import com.borderless.ankicards.domain.recipe.FieldGenerator
import com.borderless.ankicards.domain.recipe.IMAGE_QUERY_KEY
import com.borderless.ankicards.domain.recipe.buildLlmRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/**
 * Result of generating one card. Maps from field key to whatever was produced:
 * a text value for LLM/dictionary fields, a `[binary]` placeholder for TTS or
 * image fields (the actual bytes are in [media]).
 */
data class GeneratedCard(
    val request: CardGenerationRequest,
    val textByFieldKey: Map<String, String>,
    val media: Map<String, GeneratedMedia>,
    /**
     * Per-field error messages. Missing entry = succeeded. The orchestrator
     * never throws on a single-field failure — partial cards are useful and
     * the user can regenerate the broken field.
     */
    val errors: Map<String, String> = emptyMap()
)

/**
 * Streaming progress events emitted by [CardGenerator.generate]. The VM
 * collects this Flow and updates state per event; the final [Done] carries
 * the complete card for the save flow.
 */
sealed class GenerationProgress {
    data object PipelineStarted : GenerationProgress()

    /** One text field's value just finished streaming from the LLM. */
    data class TextField(val key: String, val value: String) : GenerationProgress()

    /** Image bytes ready. */
    data class ImageReady(val fieldKey: String, val bytes: ByteArray, val sourceWord: String) : GenerationProgress()

    /** TTS bytes ready for one audio field. */
    data class AudioReady(val fieldKey: String, val bytes: ByteArray) : GenerationProgress()

    /** Per-field failure (LLM-level failure surfaces as fieldKey `__llm__`). */
    data class FieldError(val fieldKey: String, val message: String) : GenerationProgress()

    /** Pipeline finished — composite available for save. */
    data class Done(val card: GeneratedCard) : GenerationProgress()
}

sealed class GeneratedMedia {
    data class Image(val bytes: ByteArray, val sourceWord: String) : GeneratedMedia()
    data class Audio(val bytes: ByteArray) : GeneratedMedia()
}

/**
 * Phase 2's generator pipeline. **LLM fires at t=0; image and TTS wait
 * for LLM fields to land before firing.**
 *
 * Current behavior:
 *  - **LLM** fires at t=0 covering every text-generated field at once.
 *  - **Image** waits for the LLM to resolve the `word` and `english`
 *    fields, then uses the English gloss (with the resolved word for
 *    context) as the image prompt. This ensures the image depicts the
 *    same sense the text fields describe. Falls back to `request.query`
 *    when no LLM fields are on the card.
 *  - **TTS** waits for the LLM to resolve its source field (`word`,
 *    `example`, etc.) and then runs.
 *
 * Field filtering: only fields that actually appear in [NoteType.allPlacements]
 * (on either face) are generated, plus any source field a placed audio
 * field depends on. Fields the user has on their card type but hasn't
 * placed on either face are skipped entirely — no LLM token spend, no
 * downstream calls.
 *
 * Partial failure stays normal — individual field errors don't abort the
 * run; the user can regenerate the broken field.
 */
class CardGenerator(
    private val gemini: GeminiClient,
    private val tts: TtsClient,
    /**
     * Stock-photo lookup tried before falling back to (slow, paid) image
     * generation. Defaults to [ImageSearchClient.Disabled] so callers and
     * tests that predate image search keep the always-generate behavior.
     */
    private val imageSearch: ImageSearchClient = ImageSearchClient.Disabled
) {

    /**
     * Streaming card generation. Emits [GenerationProgress] events in real
     * time as fields finish coming off the LLM stream, the image lands,
     * and TTS finishes. The terminal [GenerationProgress.Done] carries the
     * complete card the UI uses for the save flow.
     *
     * Concurrency:
     *  - LLM stream collector runs in one child coroutine, sends
     *    [GenerationProgress.TextField] per pair the parser surfaces.
     *  - Image runs in another child coroutine (uses `request.query` —
     *    parallel with LLM, doesn't wait).
     *  - TTS for each placed audio field runs in its own child, suspending
     *    on a `CompletableDeferred` for the source field's value. As soon
     *    as the LLM stream surfaces e.g. `word`, the TTS job for a
     *    word-source audio field unblocks and fires.
     *
     * The function returns a cold Flow; collection starts the work. Use
     * [channelFlow] so multiple coroutines can `send(...)` without
     * coordination. Cancelling the collector cancels everything.
     */
    fun generate(request: CardGenerationRequest): Flow<GenerationProgress> = channelFlow {
        val pipelineStart = System.currentTimeMillis()
        android.util.Log.d(
            AI_LOG_TAG,
            "▶▶ Pipeline start · query=\"${request.query}\" · deck=${request.deckName} · lang=${request.language.code.ifBlank { "generic" }}"
        )
        send(GenerationProgress.PipelineStarted)

        val originalNoteType = request.noteType

        // Same field-filtering logic as before: only generate what's placed
        // on the card, plus any source field a placed audio depends on. A field
        // placed on *any* template needs generating, so we use allPlacements().
        val placedKeys = originalNoteType.allPlacements().map { it.fieldKey }.toSet()
        val audioSourceDeps = originalNoteType.fields
            .asSequence()
            .filter { it.key in placedKeys && it.generator is FieldGenerator.Tts }
            .map { (it.generator as FieldGenerator.Tts).sourceFieldKey }
            .toSet()
        val keysToGenerate = placedKeys + audioSourceDeps
        val noteType = originalNoteType.copy(
            fields = originalNoteType.fields.filter { it.key in keysToGenerate }
        )

        // Shared mutable bins for the final composite. Channel sends are
        // already synchronized; these maps are only mutated from inside
        // the coroutineScope below where we serialize via .await().
        val errors = mutableMapOf<String, String>()
        val media = mutableMapOf<String, GeneratedMedia>()
        val textByFieldKey = mutableMapOf<String, String>()

        // Ask the LLM for stock-photo search keywords only if the card has an
        // image field AND the user's image-source setting can actually use
        // them. Same principle as the placed-fields-only filter: don't spend
        // tokens on something nothing will read.
        val wantsImageQuery =
            noteType.fields.any { it.generator is FieldGenerator.ImageGen } &&
                imageSearch.mode().usesSearch

        // Per-key deferred so TTS coroutines can await the value of their
        // source field (`word`, `example`, …) the instant it lands in the
        // LLM stream. IMAGE_QUERY_KEY gets one too — it isn't a card field,
        // but the image leg awaits it exactly the same way.
        val fieldValueSignals = buildMap {
            noteType.fields.forEach { put(it.key, CompletableDeferred<String>()) }
            if (wantsImageQuery) put(IMAGE_QUERY_KEY, CompletableDeferred())
        }

        coroutineScope {
            val llmRequest = noteType.buildLlmRequest(
                query = request.query,
                language = request.language,
                includeImageQuery = wantsImageQuery
            )

            // ── 1. LLM streaming ───────────────────────────────────────
            val llmJob: Deferred<Map<String, String>> = async {
                if (llmRequest == null) {
                    // Complete every signal with empty so dependent TTS jobs
                    // unblock and fail cleanly.
                    fieldValueSignals.values.forEach { it.complete("") }
                    return@async emptyMap()
                }
                runCatchingCancellable {
                    var lastSeen = emptySet<String>()
                    val accumulated = mutableMapOf<String, String>()
                    gemini.streamStructured(llmRequest.prompt, llmRequest.schema)
                        .collect { partial ->
                            for ((key, value) in partial) {
                                if (key !in lastSeen) {
                                    // IMAGE_QUERY_KEY is app-internal plumbing, not a
                                    // card field: unblock the image leg with it, but
                                    // keep it out of the composite and off the UI or
                                    // it would render as a text field on the card and
                                    // get saved into the Anki note.
                                    if (key != IMAGE_QUERY_KEY) {
                                        accumulated[key] = value
                                        textByFieldKey[key] = value
                                        send(GenerationProgress.TextField(key, value))
                                    }
                                    fieldValueSignals[key]?.complete(value)
                                }
                            }
                            lastSeen = partial.keys
                        }
                    // Stream ended; any field signals that never landed
                    // get an empty completion so TTS doesn't hang forever.
                    fieldValueSignals.forEach { (k, signal) ->
                        if (!signal.isCompleted) signal.complete(accumulated[k].orEmpty())
                    }
                    accumulated.toMap()
                }.getOrElse {
                    errors["__llm__"] = it.message ?: "LLM call failed"
                    android.util.Log.w(AI_LOG_TAG, "  · LLM FAILED — ${errors["__llm__"]}", it)
                    send(GenerationProgress.FieldError("__llm__", errors["__llm__"]!!))
                    // Unblock any TTS jobs waiting on signals that never landed.
                    fieldValueSignals.values.forEach { if (!it.isCompleted) it.complete("") }
                    emptyMap()
                }
            }

            // ── 2. Image (waits for LLM to resolve meaning) ──────────
            val imageField = noteType.fields.firstOrNull { it.generator is FieldGenerator.ImageGen }
            val imageJob: Deferred<Unit>? = imageField?.let { field ->
                async {
                    runCatchingCancellable {
                        val resolvedWord = fieldValueSignals["word"]
                            ?.await()?.takeIf { it.isNotBlank() }
                        val englishGloss = fieldValueSignals["english"]
                            ?.await()?.takeIf { it.isNotBlank() }

                        val imageQuery = buildImageQuery(
                            resolvedWord, englishGloss, request.query
                        )
                        val sourceWord = resolvedWord ?: request.query

                        // Cheap path first: the LLM's search keywords against a
                        // stock library (~1s, free). Falls through to generation
                        // (~12-15s, paid) whenever the library has nothing that
                        // clears the quality gate — which is most abstract
                        // vocabulary. See ImageSourcePreference.
                        val searchTerms = fieldValueSignals[IMAGE_QUERY_KEY]
                            ?.await()?.takeIf { it.isNotBlank() }
                            ?: englishGloss
                            ?: request.query

                        val bytes = when (val hit = imageSearch.search(searchTerms)) {
                            is ImageSearchOutcome.Found -> hit.bytes
                            // User set "Search only" and nothing matched. Throwing
                            // routes through the getOrElse below into a FieldError
                            // and the snackbar — silence here would look identical
                            // to "the card just has no image."
                            ImageSearchOutcome.Exhausted -> error(
                                "No stock photo matched \"$searchTerms\" — " +
                                    "image source is set to \"Search only\""
                            )
                            ImageSearchOutcome.Miss ->
                                gemini.generateImage(imageQuery).getOrThrow()
                        }
                        media[field.key] = GeneratedMedia.Image(
                            bytes = bytes,
                            sourceWord = sourceWord
                        )
                        send(
                            GenerationProgress.ImageReady(
                                fieldKey = field.key,
                                bytes = bytes,
                                sourceWord = sourceWord
                            )
                        )
                    }.getOrElse {
                        errors[field.key] = it.message ?: "Image generation failed"
                        android.util.Log.w(
                            AI_LOG_TAG,
                            "  · Image FAILED (${field.key}) — ${errors[field.key]}",
                            it
                        )
                        send(GenerationProgress.FieldError(field.key, errors[field.key]!!))
                    }
                }
            }

            // ── 3. TTS (each fires when its source field lands) ────────
            val ttsFields = noteType.fields.filter { it.generator is FieldGenerator.Tts }
            val ttsJobs: List<Deferred<Unit>> = ttsFields.map { field ->
                async {
                    runCatchingCancellable {
                        val gen = field.generator as FieldGenerator.Tts
                        val signal = fieldValueSignals[gen.sourceFieldKey]
                            ?: error("Audio source '${gen.sourceFieldKey}' isn't on the card type")
                        val sourceText = signal.await()
                        require(sourceText.isNotBlank()) {
                            "TTS source field '${gen.sourceFieldKey}' was empty"
                        }
                        val bytes = tts.speak(sourceText, request.language.code.ifBlank { null })
                            .getOrThrow()
                        media[field.key] = GeneratedMedia.Audio(bytes)
                        send(GenerationProgress.AudioReady(field.key, bytes))
                    }.getOrElse {
                        errors[field.key] = it.message ?: "TTS failed"
                        android.util.Log.w(
                            AI_LOG_TAG,
                            "  · TTS FAILED (${field.key}) — ${errors[field.key]}",
                            it
                        )
                        send(GenerationProgress.FieldError(field.key, errors[field.key]!!))
                    }
                }
            }

            // ── 4. Wait for all legs, emit Done ────────────────────────
            val llmText = llmJob.await()
            imageJob?.await()
            ttsJobs.forEach { it.await() }

            val totalMs = System.currentTimeMillis() - pipelineStart
            val errorSummary = if (errors.isEmpty()) "0 errors"
                               else "${errors.size} errors: ${errors.keys.joinToString()}"
            android.util.Log.d(
                AI_LOG_TAG,
                "▶▶ Pipeline done · ${totalMs}ms · $errorSummary"
            )

            send(
                GenerationProgress.Done(
                    GeneratedCard(
                        request = request,
                        textByFieldKey = llmText,
                        media = media.toMap(),
                        errors = errors.toMap()
                    )
                )
            )
        }
    }

    private companion object {
        const val AI_LOG_TAG = "AnkiCardsAI"

        fun buildImageQuery(
            resolvedWord: String?,
            englishGloss: String?,
            fallbackQuery: String
        ): String = buildString {
            append(englishGloss ?: resolvedWord ?: fallbackQuery)
            if (englishGloss != null && resolvedWord != null && resolvedWord != englishGloss) {
                append(" (").append(resolvedWord).append(")")
            }
        }
    }

    /**
     * Regenerate a single field. Used by the "🔄" button on the new generator
     * screen — cheaper than re-running the whole pipeline because we send a
     * single-property schema with the rest of the card as context.
     *
     * For LLM/Dictionary fields: structured output with one property.
     * For Image fields: regenerate the image keyed off the current Word value.
     * For TTS fields: regenerate audio from the current source value.
     * UserInput fields: no-op.
     */
    suspend fun regenerateField(
        field: CardField,
        currentValues: Map<String, String>,
        request: CardGenerationRequest
    ): Result<RegenerationResult> = runCatchingCancellable {
        when (val g = field.generator) {
            is FieldGenerator.Llm, is FieldGenerator.Dictionary -> {
                // Build a one-field synthetic card type for the prompt/schema
                // helper. Drop placements *and* swap any cross-field-referencing
                // generators (e.g. a Dictionary field that looks up `word`) for
                // a plain Llm generator — those refs would fail validation when
                // the referenced field is no longer present.
                val standaloneField = if (field.generator is FieldGenerator.Dictionary) {
                    field.copy(generator = FieldGenerator.Llm())
                } else field
                val singleFieldType = request.noteType.copy(
                    fields = listOf(standaloneField),
                    templates = emptyList()
                )
                val llm = singleFieldType.buildLlmRequest(request.query, request.language)
                    ?: error("Field ${field.key} doesn't need the LLM")
                val contextPrompt = buildContextPrompt(currentValues, field, llm.prompt)
                val out = gemini.generateStructured(contextPrompt, llm.schema).getOrThrow()
                RegenerationResult.Text(out[field.key].orEmpty())
            }
            is FieldGenerator.Tts -> {
                val sourceText = currentValues[g.sourceFieldKey].orEmpty()
                require(sourceText.isNotBlank()) {
                    "Source field '${g.sourceFieldKey}' is empty — fill it before regenerating audio."
                }
                val bytes = tts.speak(sourceText, request.language.code.ifBlank { null })
                    .getOrThrow()
                RegenerationResult.Audio(bytes)
            }
            is FieldGenerator.ImageGen -> {
                val resolvedWord = currentValues["word"]?.takeIf { it.isNotBlank() }
                val englishGloss = currentValues["english"]?.takeIf { it.isNotBlank() }
                val imageQuery = buildImageQuery(resolvedWord, englishGloss, request.query)
                val sourceWord = resolvedWord ?: request.query
                // Manual regenerate deliberately *skips* search and generates.
                // Search is deterministic: re-running it returns the same photo,
                // so the 🔄 button would look broken. The user tapping it is
                // asking for something different, and only generation can give
                // that. The exception is "Search only" — honoring that setting
                // matters more, because generating behind the user's back is
                // exactly the spend they switched it on to avoid.
                val bytes = if (imageSearch.mode() == ImageSourcePreference.SearchOnly) {
                    val terms = englishGloss ?: request.query
                    when (val hit = imageSearch.search(terms)) {
                        is ImageSearchOutcome.Found -> hit.bytes
                        else -> error(
                            "No stock photo matched \"$terms\" — " +
                                "image source is set to \"Search only\""
                        )
                    }
                } else {
                    gemini.generateImage(imageQuery).getOrThrow()
                }
                RegenerationResult.Image(bytes, sourceWord)
            }
            is FieldGenerator.UserInput -> RegenerationResult.NoOp
        }
    }

    private fun buildContextPrompt(
        currentValues: Map<String, String>,
        target: CardField,
        baseFieldPrompt: String
    ): String = buildString {
        appendLine(baseFieldPrompt)
        appendLine()
        appendLine("Existing values on this card (for context — do not echo them back):")
        currentValues
            .filter { (k, v) -> k != target.key && v.isNotBlank() }
            .forEach { (k, v) -> appendLine("- $k: $v") }
    }
}

sealed class RegenerationResult {
    data class Text(val value: String) : RegenerationResult()
    data class Audio(val bytes: ByteArray) : RegenerationResult()
    data class Image(val bytes: ByteArray, val sourceWord: String) : RegenerationResult()
    data object NoOp : RegenerationResult()
}

/**
 * Like [kotlin.runCatching] but propagates [CancellationException] instead of
 * swallowing it.
 *
 * The standard `runCatching` catches every `Throwable`, including the
 * `CancellationException` that the coroutines runtime uses to signal job
 * cancellation. That breaks structured concurrency: a parent that cancels its
 * children will see them silently produce `Result.failure(CancellationException)`
 * and continue, rather than unwinding. Anywhere a coroutine path can be
 * cancelled (which is most places), use this helper instead.
 */
private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
}
