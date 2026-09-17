package com.borderless.ankicards.data.gemini

import com.borderless.ankicards.data.images.ImageSearchClient
import com.borderless.ankicards.data.images.ImageSearchOutcome
import com.borderless.ankicards.data.settings.ImageSourcePreference
import com.borderless.ankicards.data.tts.TtsClient
import com.borderless.ankicards.domain.deck.DeckLanguage
import com.borderless.ankicards.domain.recipe.CardFace
import com.borderless.ankicards.domain.recipe.CardField
import com.borderless.ankicards.domain.recipe.CardGenerationRequest
import com.borderless.ankicards.domain.recipe.NoteType
import com.borderless.ankicards.domain.recipe.FieldGenerator
import com.borderless.ankicards.domain.recipe.FieldLayout
import com.borderless.ankicards.domain.recipe.FieldPlacement
import com.borderless.ankicards.domain.recipe.IMAGE_QUERY_KEY
import com.borderless.ankicards.domain.recipe.Template
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [CardGenerator] — primarily that generation respects the
 * placed-fields-only contract.
 *
 * The fakes here implement [GeminiClient] / [TtsClient] directly, so we
 * exercise the real [CardGenerator] orchestration code without any network
 * or DataStore dependency.
 */
class CardGeneratorTest {

    @Test
    fun `only fields placed on the card type get sent to the LLM`() = runTest {
        // Two text fields: only `word` is placed on the front. `notes` exists
        // on the card type but isn't placed — should be skipped.
        val noteType = NoteType(
            name = "Test",
            language = "cmn",
            fields = listOf(
                CardField(key = "word", label = "Word", description = "", generator = FieldGenerator.Llm()),
                CardField(key = "notes", label = "Notes", description = "", generator = FieldGenerator.Llm())
            ),
            templates = listOf(
                Template(
                    placements = listOf(
                        FieldPlacement("word", CardFace.FRONT, FieldLayout(1, 1, 10, 3))
                    )
                )
            )
        )
        val gemini = RecordingGeminiClient(
            structuredResponse = mapOf("word" to "苍蝇")
        )

        val generator = CardGenerator(gemini, NoOpTtsClient)
        val events = generator.generate(
            CardGenerationRequest("fly", noteType, "TestDeck", DeckLanguage.Mandarin)
        ).toList()
        // Done is always the last emission for a successful run.
        assertTrue(
            "pipeline should have emitted at least PipelineStarted + Done",
            events.any { it is GenerationProgress.Done }
        )

        // The recorded schema should only have the `word` property, not `notes`.
        val recordedSchema = gemini.lastSchema
            ?: error("Expected the LLM to be called once")
        val properties = recordedSchema.get("properties") as? JsonObject
            ?: error("Schema had no `properties` object")
        assertTrue("word property present", properties.containsKey("word"))
        assertFalse("notes property absent (not placed)", properties.containsKey("notes"))
    }

    @Test
    fun `image generation only fires when an image field is placed`() = runTest {
        // Image field exists but is NOT placed. Should never reach the
        // image generator.
        val noteType = NoteType(
            name = "TextOnly",
            language = "cmn",
            fields = listOf(
                CardField(key = "word", label = "Word", description = "", generator = FieldGenerator.Llm()),
                CardField(
                    key = "image", label = "Image",
                    description = "",
                    generator = FieldGenerator.ImageGen(style = "realistic photo")
                )
            ),
            templates = listOf(
                Template(
                    placements = listOf(
                        FieldPlacement("word", CardFace.FRONT, FieldLayout(1, 1, 10, 3))
                    )
                )
            )
        )
        val gemini = RecordingGeminiClient(
            structuredResponse = mapOf("word" to "苍蝇")
        )

        val generator = CardGenerator(gemini, NoOpTtsClient)
        generator.generate(
            CardGenerationRequest("fly", noteType, "TestDeck", DeckLanguage.Mandarin)
        ).toList()

        assertEquals(
            "image generator should not have been called",
            0, gemini.imageCalls
        )
    }

    @Test
    fun `image generation fires when an image field IS placed`() = runTest {
        val noteType = NoteType(
            name = "WithImage",
            language = "cmn",
            fields = listOf(
                CardField(key = "word", label = "Word", description = "", generator = FieldGenerator.Llm()),
                CardField(
                    key = "image", label = "Image",
                    description = "",
                    generator = FieldGenerator.ImageGen(style = "realistic photo")
                )
            ),
            templates = listOf(
                Template(
                    placements = listOf(
                        FieldPlacement("word", CardFace.FRONT, FieldLayout(1, 1, 10, 3)),
                        FieldPlacement("image", CardFace.FRONT, FieldLayout(1, 5, 10, 8))
                    )
                )
            )
        )
        val gemini = RecordingGeminiClient(
            structuredResponse = mapOf("word" to "苍蝇"),
            imageBytes = byteArrayOf(1, 2, 3)
        )

        val generator = CardGenerator(gemini, NoOpTtsClient)
        val events = generator.generate(
            CardGenerationRequest("fly", noteType, "TestDeck", DeckLanguage.Mandarin)
        ).toList()
        val card = (events.last() as GenerationProgress.Done).card

        assertEquals(1, gemini.imageCalls)
        assertTrue("image media attached", card.media.containsKey("image"))
    }

    @Test
    fun `TTS only fires when an audio field is placed`() = runTest {
        // Audio field defined but not placed. TTS should never be called.
        val noteType = NoteType(
            name = "NoAudio",
            language = "cmn",
            fields = listOf(
                CardField(key = "word", label = "Word", description = "", generator = FieldGenerator.Llm()),
                CardField(
                    key = "audio", label = "Audio",
                    description = "",
                    generator = FieldGenerator.Tts(sourceFieldKey = "word")
                )
            ),
            templates = listOf(
                Template(
                    placements = listOf(
                        FieldPlacement("word", CardFace.FRONT, FieldLayout(1, 1, 10, 3))
                    )
                )
            )
        )
        val tts = RecordingTtsClient()
        val generator = CardGenerator(
            gemini = RecordingGeminiClient(structuredResponse = mapOf("word" to "苍蝇")),
            tts = tts
        )
        generator.generate(
            CardGenerationRequest("fly", noteType, "TestDeck", DeckLanguage.Mandarin)
        ).toList()
        assertEquals("tts client should not have been called", 0, tts.calls)
    }

    @Test
    fun `image falls back to query when no LLM fields are present`() = runTest {
        val noteType = NoteType(
            name = "ImageOnly",
            language = "cmn",
            fields = listOf(
                CardField(
                    key = "image", label = "Image",
                    description = "",
                    generator = FieldGenerator.ImageGen(style = "realistic photo")
                )
            ),
            templates = listOf(
                Template(
                    placements = listOf(
                        FieldPlacement("image", CardFace.FRONT, FieldLayout(1, 1, 10, 10))
                    )
                )
            )
        )
        val gemini = RecordingGeminiClient(imageBytes = byteArrayOf(9))
        val generator = CardGenerator(gemini, NoOpTtsClient)
        generator.generate(
            CardGenerationRequest("fly", noteType, "TestDeck", DeckLanguage.Mandarin)
        ).toList()
        assertEquals("image prompt should be the user's query", "fly", gemini.lastImageWord)
        assertNull("no LLM call expected when no LLM fields are placed", gemini.lastSchema)
    }

    @Test
    fun `image uses english gloss with resolved word for disambiguation`() = runTest {
        val noteType = NoteType(
            name = "Full",
            language = "cmn",
            fields = listOf(
                CardField(key = "word", label = "Word", description = "", generator = FieldGenerator.Llm()),
                CardField(key = "english", label = "English", description = "", generator = FieldGenerator.Llm()),
                CardField(
                    key = "image", label = "Image",
                    description = "",
                    generator = FieldGenerator.ImageGen(style = "realistic photo")
                )
            ),
            templates = listOf(
                Template(
                    placements = listOf(
                        FieldPlacement("word", CardFace.FRONT, FieldLayout(1, 1, 10, 3)),
                        FieldPlacement("english", CardFace.BACK, FieldLayout(1, 1, 10, 3)),
                        FieldPlacement("image", CardFace.FRONT, FieldLayout(1, 5, 10, 8))
                    )
                )
            )
        )
        val gemini = RecordingGeminiClient(
            structuredResponse = mapOf("word" to "米", "english" to "meter (unit of length)"),
            imageBytes = byteArrayOf(1, 2, 3)
        )
        val generator = CardGenerator(gemini, NoOpTtsClient)
        generator.generate(
            CardGenerationRequest("meter", noteType, "TestDeck", DeckLanguage.Mandarin)
        ).toList()

        assertEquals(
            "image prompt should combine english gloss with resolved word",
            "meter (unit of length) (米)",
            gemini.lastImageWord
        )
    }

    @Test
    fun `image uses resolved word when only word field is placed`() = runTest {
        val noteType = NoteType(
            name = "WordAndImage",
            language = "cmn",
            fields = listOf(
                CardField(key = "word", label = "Word", description = "", generator = FieldGenerator.Llm()),
                CardField(
                    key = "image", label = "Image",
                    description = "",
                    generator = FieldGenerator.ImageGen(style = "realistic photo")
                )
            ),
            templates = listOf(
                Template(
                    placements = listOf(
                        FieldPlacement("word", CardFace.FRONT, FieldLayout(1, 1, 10, 3)),
                        FieldPlacement("image", CardFace.FRONT, FieldLayout(1, 5, 10, 8))
                    )
                )
            )
        )
        val gemini = RecordingGeminiClient(
            structuredResponse = mapOf("word" to "苍蝇"),
            imageBytes = byteArrayOf(1, 2, 3)
        )
        val generator = CardGenerator(gemini, NoOpTtsClient)
        generator.generate(
            CardGenerationRequest("fly", noteType, "TestDeck", DeckLanguage.Mandarin)
        ).toList()

        assertEquals(
            "image prompt should use resolved word when no english field",
            "苍蝇",
            gemini.lastImageWord
        )
    }

    // ── Stock-photo search (search-first, generate-on-miss) ────────────

    /** word + english + image, all placed. The shape the search tests need. */
    private fun imageNoteType() = NoteType(
        name = "WithImage",
        language = "cmn",
        fields = listOf(
            CardField(key = "word", label = "Word", description = "", generator = FieldGenerator.Llm()),
            CardField(key = "english", label = "English", description = "", generator = FieldGenerator.Llm()),
            CardField(
                key = "image", label = "Image",
                description = "",
                generator = FieldGenerator.ImageGen(style = "realistic photo")
            )
        ),
        templates = listOf(
            Template(
                placements = listOf(
                    FieldPlacement("word", CardFace.FRONT, FieldLayout(1, 1, 10, 3)),
                    FieldPlacement("english", CardFace.BACK, FieldLayout(1, 1, 10, 3)),
                    FieldPlacement("image", CardFace.FRONT, FieldLayout(1, 5, 10, 8))
                )
            )
        )
    )

    @Test
    fun `a search hit is used and image generation never runs`() = runTest {
        val gemini = RecordingGeminiClient(
            structuredResponse = mapOf(
                "word" to "猶豫",
                "english" to "to hesitate",
                IMAGE_QUERY_KEY to "crossroads decision"
            ),
            imageBytes = byteArrayOf(1, 2, 3)
        )
        val search = FakeImageSearchClient(
            outcome = ImageSearchOutcome.Found(byteArrayOf(7, 7), "https://pixabay.com/photos/x")
        )

        val generator = CardGenerator(gemini, NoOpTtsClient, search)
        val events = generator.generate(
            CardGenerationRequest("hesitate", imageNoteType(), "TestDeck", DeckLanguage.Mandarin)
        ).toList()
        val card = (events.last() as GenerationProgress.Done).card

        assertEquals("search should have been consulted", 1, search.calls)
        assertEquals(
            "search should use the LLM's keywords, not the gloss",
            "crossroads decision", search.lastQuery
        )
        assertEquals("generation must be skipped on a hit", 0, gemini.imageCalls)
        assertTrue("searched photo attached", card.media.containsKey("image"))
    }

    @Test
    fun `a search miss falls back to image generation`() = runTest {
        val gemini = RecordingGeminiClient(
            structuredResponse = mapOf(
                "word" to "猶豫",
                "english" to "to hesitate",
                IMAGE_QUERY_KEY to "crossroads decision"
            ),
            imageBytes = byteArrayOf(1, 2, 3)
        )
        val search = FakeImageSearchClient(outcome = ImageSearchOutcome.Miss)

        val generator = CardGenerator(gemini, NoOpTtsClient, search)
        val events = generator.generate(
            CardGenerationRequest("hesitate", imageNoteType(), "TestDeck", DeckLanguage.Mandarin)
        ).toList()
        val card = (events.last() as GenerationProgress.Done).card

        assertEquals(1, search.calls)
        assertEquals("generation should cover the miss", 1, gemini.imageCalls)
        assertEquals(
            "generation still gets the disambiguated prompt, not the search keywords",
            "to hesitate (猶豫)", gemini.lastImageWord
        )
        assertTrue("generated image attached", card.media.containsKey("image"))
    }

    @Test
    fun `search-only mode surfaces an error instead of quietly generating`() = runTest {
        val gemini = RecordingGeminiClient(
            structuredResponse = mapOf(
                "word" to "猶豫",
                "english" to "to hesitate",
                IMAGE_QUERY_KEY to "crossroads decision"
            ),
            imageBytes = byteArrayOf(1, 2, 3)
        )
        val search = FakeImageSearchClient(
            outcome = ImageSearchOutcome.Exhausted,
            sourceMode = ImageSourcePreference.SearchOnly
        )

        val generator = CardGenerator(gemini, NoOpTtsClient, search)
        val events = generator.generate(
            CardGenerationRequest("hesitate", imageNoteType(), "TestDeck", DeckLanguage.Mandarin)
        ).toList()

        assertEquals("must not spend money the user ruled out", 0, gemini.imageCalls)
        assertTrue(
            "the missing image must be surfaced, not silent",
            events.any { it is GenerationProgress.FieldError && it.fieldKey == "image" }
        )
    }

    @Test
    fun `the search-keyword property is only requested when search is on`() = runTest {
        val on = RecordingGeminiClient(structuredResponse = mapOf("word" to "猶豫"))
        CardGenerator(on, NoOpTtsClient, FakeImageSearchClient()).generate(
            CardGenerationRequest("hesitate", imageNoteType(), "TestDeck", DeckLanguage.Mandarin)
        ).toList()

        val onProps = (on.lastSchema?.get("properties") as? JsonObject)
            ?: error("Schema had no `properties` object")
        assertTrue("keywords requested when search is on", onProps.containsKey(IMAGE_QUERY_KEY))
        assertEquals(
            "keywords must follow the fields that disambiguate the word's sense",
            listOf("word", "english", IMAGE_QUERY_KEY),
            onProps.keys.toList()
        )

        val off = RecordingGeminiClient(structuredResponse = mapOf("word" to "猶豫"))
        CardGenerator(
            off, NoOpTtsClient,
            FakeImageSearchClient(sourceMode = ImageSourcePreference.GenerateOnly)
        ).generate(
            CardGenerationRequest("hesitate", imageNoteType(), "TestDeck", DeckLanguage.Mandarin)
        ).toList()

        val offProps = (off.lastSchema?.get("properties") as? JsonObject)
            ?: error("Schema had no `properties` object")
        assertFalse(
            "no wasted tokens when search can't use them",
            offProps.containsKey(IMAGE_QUERY_KEY)
        )
    }

    @Test
    fun `the search keywords never reach the card or the UI`() = runTest {
        val gemini = RecordingGeminiClient(
            structuredResponse = mapOf(
                "word" to "猶豫",
                "english" to "to hesitate",
                IMAGE_QUERY_KEY to "crossroads decision"
            )
        )
        val generator = CardGenerator(gemini, NoOpTtsClient, FakeImageSearchClient())
        val events = generator.generate(
            CardGenerationRequest("hesitate", imageNoteType(), "TestDeck", DeckLanguage.Mandarin)
        ).toList()
        val card = (events.last() as GenerationProgress.Done).card

        assertFalse(
            "control field must not be saved onto the note",
            card.textByFieldKey.containsKey(IMAGE_QUERY_KEY)
        )
        assertFalse(
            "control field must not render as a text field",
            events.any { it is GenerationProgress.TextField && it.key == IMAGE_QUERY_KEY }
        )
    }
}

// ── Fakes ──────────────────────────────────────────────────────────────

/** Records inputs so tests can assert on what was sent. */
private class RecordingGeminiClient(
    private val structuredResponse: Map<String, String> = emptyMap(),
    private val imageBytes: ByteArray = ByteArray(0)
) : GeminiClient {
    var lastPrompt: String? = null
    var lastSchema: JsonObject? = null
    var lastImageWord: String? = null
    var imageCalls: Int = 0

    override suspend fun generateStructured(
        prompt: String,
        schema: JsonObject
    ): Result<Map<String, String>> {
        lastPrompt = prompt
        lastSchema = schema
        return Result.success(structuredResponse)
    }

    override fun streamStructured(
        prompt: String,
        schema: JsonObject
    ): Flow<Map<String, String>> = flow {
        // CardGenerator now uses streaming; the fake records the schema
        // here too and emits the canned response as one delta. Tests
        // assert against `lastSchema` to verify schema shape.
        lastPrompt = prompt
        lastSchema = schema
        if (structuredResponse.isNotEmpty()) emit(structuredResponse)
    }

    override suspend fun generateImage(word: String): Result<ByteArray> {
        imageCalls++
        lastImageWord = word
        return Result.success(imageBytes)
    }
}

private class RecordingTtsClient : TtsClient {
    var calls: Int = 0
    var lastText: String? = null
    var lastLang: String? = null

    override suspend fun speak(text: String, languageCode: String?): Result<ByteArray> {
        calls++
        lastText = text
        lastLang = languageCode
        return Result.success(byteArrayOf(42))
    }
}

private object NoOpTtsClient : TtsClient {
    override suspend fun speak(text: String, languageCode: String?): Result<ByteArray> =
        Result.success(ByteArray(0))
}

/**
 * Canned stock-photo search. [sourceMode] drives the schema decision
 * (whether the LLM is asked for keywords at all); [outcome] drives what the
 * image leg does with the answer.
 */
private class FakeImageSearchClient(
    private val outcome: ImageSearchOutcome = ImageSearchOutcome.Miss,
    private val sourceMode: ImageSourcePreference = ImageSourcePreference.SearchThenGenerate
) : ImageSearchClient {
    var calls: Int = 0
    var lastQuery: String? = null

    override suspend fun search(query: String): ImageSearchOutcome {
        calls++
        lastQuery = query
        return outcome
    }

    override suspend fun mode(): ImageSourcePreference = sourceMode
}
