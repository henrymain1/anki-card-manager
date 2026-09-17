package com.borderless.ankicards.data.gemini

/**
 * One selectable Gemini model option for the Settings dropdown.
 *
 * @param id          The exact model identifier the API uses
 *                    (`models/<id>:generateContent`). Stored in settings
 *                    and threaded into [GeminiRepository] per request.
 * @param displayName Human-readable name shown in the dropdown.
 */
data class GeminiModelOption(
    val id: String,
    val displayName: String
)

/**
 * Curated catalog of Gemini models the user can pick from in Settings.
 * Centralized so [SettingsRepository], [GeminiRepository], and the
 * Settings UI all reference the same set — adding a new model is one edit
 * to this file.
 *
 * Model identifiers verified against https://ai.google.dev/gemini-api/docs/models
 * — if Google retires one, our requests start 404'ing and the user can
 * pick a different option from the dropdown without an app update.
 *
 * "Nano Banana" is the marketing name Google uses for its image-generation
 * models; the actual API identifier is the `*-image*` model id.
 */
object GeminiModels {

    /** Flagship text models. Only Gemini 3.x — 2.x retired from the picker. */
    val textOptions: List<GeminiModelOption> = listOf(
        GeminiModelOption(
            id = "gemini-3.1-pro-preview",
            displayName = "Gemini 3.1 Pro"
        ),
        GeminiModelOption(
            id = "gemini-3-flash-preview",
            displayName = "Gemini 3 Flash"
        ),
        GeminiModelOption(
            id = "gemini-3.5-flash",
            displayName = "Gemini 3.5 Flash"
        )
    )

    /**
     * Image-generation models. "Nano Banana" is Google's marketing name.
     *
     * Low-res variant: same underlying model id as Nano Banana 2, but with
     * a `@<imageSize>` suffix on the option id (`@512`) so
     * [GeminiRepository] knows to add `imageConfig.imageSize = "512"` to
     * `generationConfig` — drops generation time to ~3-4s versus the
     * default ~8-12s. The repository also sets
     * `thinkingConfig.thinkingLevel = "MINIMAL"` on that variant since
     * deep reasoning doesn't help image quality. See [parseImageModelSpec]
     * for the suffix → (modelId, imageSize) parsing.
     *
     * Field shape verified against a real AI Studio request body for
     * Nano Banana 2.
     */
    val imageOptions: List<GeminiModelOption> = listOf(
        GeminiModelOption(
            id = "gemini-3-pro-image-preview",
            displayName = "Nano Banana Pro"
        ),
        GeminiModelOption(
            id = "gemini-3.1-flash-image-preview",
            displayName = "Nano Banana 2"
        ),
        GeminiModelOption(
            id = "gemini-3.1-flash-image-preview@512",
            displayName = "Nano Banana 2 (Low Res)"
        )
    )

    /** Default text model id. Used when no user preference is stored. */
    const val DEFAULT_TEXT_MODEL = "gemini-3-flash-preview"

    /** Default image model id. Used when no user preference is stored. */
    const val DEFAULT_IMAGE_MODEL = "gemini-3-pro-image-preview"

    /**
     * Look up an option by id, falling back to the first option in the
     * matching list if the stored id is no longer in the catalog (e.g.
     * Google retired the model and we removed it). Used by Settings UI
     * to render the currently-selected row.
     */
    fun findText(id: String): GeminiModelOption =
        textOptions.firstOrNull { it.id == id } ?: textOptions.first()

    fun findImage(id: String): GeminiModelOption =
        imageOptions.firstOrNull { it.id == id } ?: imageOptions.first()

    /**
     * True if the given text-model id is a Gemini 3.x Pro variant. Pro
     * models are the only ones for which [ProThinkingLevel] is exposed in
     * the UI — Flash variants honor `thinkingLevel` too, but per Google's
     * docs the default ("high"-equivalent) is already fast enough on
     * Flash, so the user-facing knob is Pro-only to keep Settings simple.
     */
    fun isProTextModel(id: String): Boolean =
        id.startsWith("gemini-3") && id.contains("pro", ignoreCase = true)
}

/**
 * User-selectable reasoning budget for Gemini 3.x Pro models, maps to
 * `generationConfig.thinkingConfig.thinkingLevel` on the request.
 *
 * Gemini 3.x Pro defaults to "HIGH" if the field is omitted, which on
 * `gemini-3.1-pro-preview` measured ~10s of pre-streaming thinking time
 * before the first token. Dropping to MEDIUM or LOW trades some
 * reasoning depth for faster time-to-first-token, which is the whole
 * point of progressive streaming UI.
 *
 * "MINIMAL" is intentionally NOT exposed — Google's docs flag it as
 * unsupported on Gemini 3 Pro ("You cannot disable thinking for
 * Gemini 3.1 Pro"). The image-generation pipeline still uses
 * `thinkingLevel = "MINIMAL"` because it goes through the
 * `gemini-3.1-flash-image-preview` model (Flash, not Pro) where it
 * IS supported.
 *
 * Field-name + enum values verified against
 * https://ai.google.dev/gemini-api/docs/thinking on 2026-05-29.
 */
enum class ProThinkingLevel(val apiValue: String, val displayName: String) {
    High("HIGH", "High"),
    Medium("MEDIUM", "Medium"),
    Low("LOW", "Low");

    companion object {
        val Default: ProThinkingLevel = High

        /** Round-trip a persisted code back to the enum, fall back to default. */
        fun fromCode(code: String?): ProThinkingLevel =
            entries.firstOrNull { it.name == code } ?: Default
    }
}

/**
 * Result of decoding an image-model option id. Some image options carry an
 * imageSize hint after an `@`, e.g. `gemini-3.1-flash-image-preview@512`,
 * because the dropdown lets the user pick "Nano Banana 2 (Low Res)" as a
 * faster variant of the same underlying API model.
 *
 * @param apiModelId The actual model id sent to the proxy's URL.
 * @param resolution Image-size hint to send in
 *                   `generationConfig.imageConfig.imageSize` (e.g. `"512"`,
 *                   `"1K"`, `"2K"`, `"4K"` per the Gemini API), or null
 *                   for the model's default. The 512 value is a bare
 *                   number; the K-sized variants take a `K` suffix. Null
 *                   today for everything except the explicit low-res variant.
 */
internal data class ImageModelSpec(val apiModelId: String, val resolution: String?)

internal fun parseImageModelSpec(optionId: String): ImageModelSpec {
    val at = optionId.indexOf('@')
    return if (at < 0) ImageModelSpec(optionId, resolution = null)
    else ImageModelSpec(
        apiModelId = optionId.substring(0, at),
        resolution = optionId.substring(at + 1).takeIf { it.isNotBlank() }
    )
}
