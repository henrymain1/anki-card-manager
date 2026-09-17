package com.borderless.ankicards.data.settings

/**
 * Where a card's image comes from.
 *
 * Image generation is the slowest and by far the most expensive leg of the
 * pipeline (~12-15s and ~$0.13/card on Nano Banana Pro). Stock-photo search
 * is ~1s and free, but only for words a photographer has actually shot —
 * see `Project_docs/card_generation_pipeline.md` for the measured quality
 * split between concrete and abstract vocabulary.
 *
 * [SearchThenGenerate] is the default because it gets the cheap path when it
 * works and silently falls back when it doesn't.
 */
enum class ImageSourcePreference(
    val code: String,
    /** Chip label. Kept short so all three fit one row without wrapping. */
    val displayName: String,
    /** Caption shown under the chips for whichever option is selected. */
    val description: String
) {

    /** Search a stock library first; generate only when search comes up empty. Default. */
    SearchThenGenerate(
        "search_then_generate",
        "Search first",
        "Looks for a stock photo first (fast and free). Falls back to generating " +
            "one when nothing good matches — which is most abstract words."
    ),

    /** Always generate. The behavior this app shipped with. */
    GenerateOnly(
        "generate_only",
        "Generate",
        "Always generates the image. Best quality and works for any word, but " +
            "it's the slowest part of building a card and the only part that costs money."
    ),

    /**
     * Only ever search. Free and fast, but a card whose word has no good
     * stock photo gets no image at all rather than a generated one.
     */
    SearchOnly(
        "search_only",
        "Search only",
        "Never generates. Cards whose word has no good stock photo are saved " +
            "without an image."
    );

    val usesSearch: Boolean get() = this != GenerateOnly

    companion object {
        fun fromCode(code: String?): ImageSourcePreference =
            entries.firstOrNull { it.code == code } ?: SearchThenGenerate
    }
}
