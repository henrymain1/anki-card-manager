package com.borderless.ankicards.domain.recipe

/**
 * Ready-to-use [NoteType]s for each supported language. Used to seed new
 * decks during onboarding and as the starting point when a user creates a
 * new card type and picks a language.
 *
 * **Every preset ships exactly ONE card design (template).** The default is the
 * "production" direction — *front*: English meaning + image; *back*: the
 * target-language word + romanization / accents, the example sentence, the
 * image again, and the audio replay button.
 *
 * This is deliberate: the whole project is customization-first, so the app must
 * not invent card designs the user didn't make. If the user wants a second
 * direction (e.g. a word-front "recognition" card, or an audio-front card),
 * they create it themselves in the designer as another template on this note
 * type — at which point selecting it on the generator produces that extra card.
 * (An earlier version baked a second "Recognition" template into every preset;
 * that was removed because a code-authored design the user can't see or edit is
 * indistinguishable from hard-coding — see STATUS.md Active Priority #6.)
 *
 * Each face is fully self-contained — the back does not echo the front. If a
 * user wants a field from the front to also appear on the back, they place it
 * explicitly on the back face in the designer.
 *
 * Every preset includes an `english` field (the bare English translation).
 * `definition` was dropped from the defaults; users who want the longer
 * sentence-form explanation can drag `FieldPresets.definition` onto the
 * card manually via the designer.
 */
object LanguagePresets {

    fun cantonese(): NoteType {
        val fields = listOf(
            FieldPresets.word(),
            FieldPresets.english(),
            FieldPresets.jyutping(),
            FieldPresets.cantoneseMeasureWord(),
            FieldPresets.exampleSentence(),
            FieldPresets.audio(),
            FieldPresets.image(style = "watercolor illustration")
        )
        return NoteType(
            name = "Cantonese Vocabulary",
            language = "yue",
            defaultImageStyle = "watercolor illustration",
            fields = fields,
            templates = singleDesign(
                "Cantonese Vocabulary",
                englishFrontPlacements() +
                    eastAsianBackPlacements(romanizationKey = "jyutping", includeMeasureWord = true)
            )
        )
    }

    fun mandarin(): NoteType {
        val fields = listOf(
            FieldPresets.word(),
            FieldPresets.english(),
            FieldPresets.pinyin(),
            FieldPresets.mandarinMeasureWord(),
            FieldPresets.exampleSentence(),
            FieldPresets.audio(),
            FieldPresets.image(style = "watercolor illustration")
        )
        return NoteType(
            name = "Mandarin Vocabulary",
            language = "cmn",
            defaultImageStyle = "watercolor illustration",
            fields = fields,
            templates = singleDesign(
                "Mandarin Vocabulary",
                englishFrontPlacements() +
                    eastAsianBackPlacements(romanizationKey = "pinyin", includeMeasureWord = true)
            )
        )
    }

    fun japanese(): NoteType {
        val fields = listOf(
            FieldPresets.word(),
            FieldPresets.english(),
            FieldPresets.furigana(),
            FieldPresets.pitchAccent(),
            FieldPresets.exampleSentence(),
            FieldPresets.audio(),
            FieldPresets.image(style = "anime illustration")
        )
        return NoteType(
            name = "Japanese Vocabulary",
            language = "ja",
            defaultImageStyle = "anime illustration",
            fields = fields,
            templates = singleDesign("Japanese Vocabulary", englishFrontPlacements() + japaneseBackPlacements())
        )
    }

    fun spanish(): NoteType {
        val fields = listOf(
            FieldPresets.word(),
            FieldPresets.english(),
            FieldPresets.gender(),
            FieldPresets.pluralForm(),
            FieldPresets.exampleSentence(),
            FieldPresets.audio(),
            FieldPresets.image()
        )
        return NoteType(
            name = "Spanish Vocabulary",
            language = "es",
            defaultImageStyle = "realistic photo",
            fields = fields,
            templates = singleDesign("Spanish Vocabulary", englishFrontPlacements() + europeanBackPlacements())
        )
    }

    fun french(): NoteType {
        val fields = listOf(
            FieldPresets.word(),
            FieldPresets.english(),
            FieldPresets.gender(),
            FieldPresets.pluralForm(),
            FieldPresets.exampleSentence(),
            FieldPresets.audio(),
            FieldPresets.image()
        )
        return NoteType(
            name = "French Vocabulary",
            language = "fr",
            defaultImageStyle = "realistic photo",
            fields = fields,
            templates = singleDesign("French Vocabulary", englishFrontPlacements() + europeanBackPlacements())
        )
    }

    fun german(): NoteType {
        val fields = listOf(
            FieldPresets.word(),
            FieldPresets.english(),
            FieldPresets.gender(),
            FieldPresets.pluralForm(),
            FieldPresets.exampleSentence(),
            FieldPresets.audio(),
            FieldPresets.image()
        )
        return NoteType(
            name = "German Vocabulary",
            language = "de",
            defaultImageStyle = "realistic photo",
            fields = fields,
            templates = singleDesign("German Vocabulary", englishFrontPlacements() + europeanBackPlacements())
        )
    }

    fun italian(): NoteType {
        val fields = listOf(
            FieldPresets.word(),
            FieldPresets.english(),
            FieldPresets.gender(),
            FieldPresets.pluralForm(),
            FieldPresets.exampleSentence(),
            FieldPresets.audio(),
            FieldPresets.image()
        )
        return NoteType(
            name = "Italian Vocabulary",
            language = "it",
            defaultImageStyle = "realistic photo",
            fields = fields,
            templates = singleDesign("Italian Vocabulary", englishFrontPlacements() + europeanBackPlacements())
        )
    }

    fun thai(): NoteType {
        val fields = listOf(
            FieldPresets.word(),
            FieldPresets.english(),
            FieldPresets.thaiRomanization(),
            FieldPresets.thaiClassifier(),
            FieldPresets.exampleSentence(),
            FieldPresets.audio(),
            FieldPresets.image()
        )
        return NoteType(
            name = "Thai Vocabulary",
            language = "th",
            defaultImageStyle = "realistic photo",
            fields = fields,
            templates = singleDesign(
                "Thai Vocabulary",
                englishFrontPlacements() +
                    eastAsianBackPlacements(romanizationKey = "romanization", includeMeasureWord = true)
            )
        )
    }

    fun generic(): NoteType {
        val fields = listOf(
            FieldPresets.word(),
            FieldPresets.english(),
            FieldPresets.exampleSentence(),
            FieldPresets.audio(),
            FieldPresets.image()
        )
        return NoteType(
            name = "Vocabulary",
            language = null,
            defaultImageStyle = "realistic photo",
            fields = fields,
            templates = singleDesign("Vocabulary", englishFrontPlacements() + genericBackPlacements())
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * Wrap one face-set of placements into the single starter design, named
     * after the note type so the user-facing "card type" name is meaningful
     * (e.g. "Spanish Vocabulary") instead of a bare "Card 1".
     */
    private fun singleDesign(name: String, placements: List<FieldPlacement>): List<Template> =
        listOf(Template(name = name, placements = placements))

    /**
     * Front face: English at the top (the meaning the user is being asked),
     * image filling the lower portion. Identical across languages — the front
     * is the question, and the question shape doesn't vary by target language.
     */
    private fun englishFrontPlacements(): List<FieldPlacement> = listOf(
        FieldPlacement("english", CardFace.FRONT, FieldLayout(col = 1, row = 1, w = 10, h = 3)),
        FieldPlacement("image", CardFace.FRONT, FieldLayout(col = 2, row = 5, w = 8, h = 9))
    )

    /**
     * Back for east-Asian language presets: target-language word at top,
     * romanization underneath, optional measure word, image again (confirms the
     * visual), example sentence, audio.
     *
     * Reminder: rendered Anki card uses flow layout (placements sorted by
     * row then col → vertical stack). Width/height affect the in-app
     * designer preview only. So the row numbers here determine which field
     * appears above which on the rendered card; w/h just shape the designer.
     */
    private fun eastAsianBackPlacements(
        romanizationKey: String,
        includeMeasureWord: Boolean
    ): List<FieldPlacement> {
        val list = mutableListOf(
            FieldPlacement("word", CardFace.BACK, FieldLayout(col = 1, row = 1, w = 10, h = 2)),
            FieldPlacement(romanizationKey, CardFace.BACK, FieldLayout(col = 1, row = 3, w = 10, h = 1))
        )
        if (includeMeasureWord) {
            list += FieldPlacement(
                "measure_word", CardFace.BACK, FieldLayout(col = 1, row = 4, w = 10, h = 1)
            )
        }
        list += FieldPlacement("image", CardFace.BACK, FieldLayout(col = 2, row = 5, w = 8, h = 6))
        list += FieldPlacement("example", CardFace.BACK, FieldLayout(col = 1, row = 11, w = 10, h = 3))
        list += FieldPlacement("audio", CardFace.BACK, FieldLayout(col = 4, row = 14, w = 4, h = 2))
        return list
    }

    /** Back for Japanese: word, furigana, pitch accent, image, example, audio. */
    private fun japaneseBackPlacements(): List<FieldPlacement> = listOf(
        FieldPlacement("word", CardFace.BACK, FieldLayout(col = 1, row = 1, w = 10, h = 2)),
        FieldPlacement("furigana", CardFace.BACK, FieldLayout(col = 1, row = 3, w = 10, h = 1)),
        FieldPlacement("pitch_accent", CardFace.BACK, FieldLayout(col = 1, row = 4, w = 10, h = 1)),
        FieldPlacement("image", CardFace.BACK, FieldLayout(col = 2, row = 5, w = 8, h = 6)),
        FieldPlacement("example", CardFace.BACK, FieldLayout(col = 1, row = 11, w = 10, h = 3)),
        FieldPlacement("audio", CardFace.BACK, FieldLayout(col = 4, row = 14, w = 4, h = 2))
    )

    /** Back for European-language presets: word, gender+plural row, image, example, audio. */
    private fun europeanBackPlacements(): List<FieldPlacement> = listOf(
        FieldPlacement("word", CardFace.BACK, FieldLayout(col = 1, row = 1, w = 10, h = 2)),
        FieldPlacement("gender", CardFace.BACK, FieldLayout(col = 1, row = 3, w = 4, h = 1)),
        FieldPlacement("plural", CardFace.BACK, FieldLayout(col = 5, row = 3, w = 6, h = 1)),
        FieldPlacement("image", CardFace.BACK, FieldLayout(col = 2, row = 5, w = 8, h = 6)),
        FieldPlacement("example", CardFace.BACK, FieldLayout(col = 1, row = 11, w = 10, h = 3)),
        FieldPlacement("audio", CardFace.BACK, FieldLayout(col = 4, row = 14, w = 4, h = 2))
    )

    /** Back for the generic preset: word, image, example, audio. */
    private fun genericBackPlacements(): List<FieldPlacement> = listOf(
        FieldPlacement("word", CardFace.BACK, FieldLayout(col = 1, row = 1, w = 10, h = 2)),
        FieldPlacement("image", CardFace.BACK, FieldLayout(col = 2, row = 4, w = 8, h = 6)),
        FieldPlacement("example", CardFace.BACK, FieldLayout(col = 1, row = 11, w = 10, h = 3)),
        FieldPlacement("audio", CardFace.BACK, FieldLayout(col = 4, row = 14, w = 4, h = 2))
    )

    /** Catalog of all language presets, for the new-card-type picker. */
    val all: List<() -> NoteType> = listOf(
        ::cantonese, ::mandarin, ::japanese, ::thai,
        ::spanish, ::french, ::german, ::italian,
        ::generic
    )
}
