package com.borderless.ankicards.ui.common

import android.util.Base64
import com.borderless.ankicards.data.gemini.GeneratedMedia
import com.borderless.ankicards.data.images.ImageFormat
import com.borderless.ankicards.domain.recipe.CardFace
import com.borderless.ankicards.domain.recipe.CardField
import com.borderless.ankicards.domain.recipe.NoteType
import com.borderless.ankicards.domain.recipe.FieldGenerator
import com.borderless.ankicards.domain.recipe.toAnkiTemplate

/**
 * Build the exact HTML+CSS document AnkiDroid would render for a generated
 * card. Used by [GeneratedCardWebSurface] to drive an in-app WebView preview
 * that visually matches what the user will see in AnkiDroid post-save.
 *
 * What this does:
 *   1. Pulls the qfmt/afmt/css from the card type's [toAnkiTemplate].
 *   2. For the back face, inlines the rendered front in place of
 *      `{{FrontSide}}` — same sigil AnkiDroid resolves at render time.
 *   3. Resolves conditional blocks `{{#Label}}…{{/Label}}` based on whether
 *      the corresponding field has a value.
 *   4. Substitutes `{{Label}}` with the field's rendered HTML — text with
 *      `<br>` for newlines, images as base64 data URLs (Binder can't carry
 *      file URIs to a WebView), and audio as a visual-only `.replay-button`
 *      anchor (the same element AnkiDroid auto-generates from `[sound:…]`).
 *
 * What this does NOT do:
 *   - Play audio. The preview's replay button is visual only; the user can
 *     tap the field editor sheet to actually hear the TTS clip. Wiring real
 *     playback would mean a JS bridge, which is more risk than reward for
 *     a preview surface.
 *   - HTML-escape text field values. The AnkiDroid pipeline doesn't escape
 *     either, so if an LLM returns `<example>` in a definition, both this
 *     preview and AnkiDroid will treat it as a tag. Faithful to production.
 */
fun buildAnkiPreviewHtml(
    noteType: NoteType,
    textByFieldKey: Map<String, String>,
    mediaByFieldKey: Map<String, GeneratedMedia>,
    face: CardFace
): String {
    val template = noteType.toAnkiTemplate()
    val keyByLabel = noteType.fields.associate { it.label to it.key }

    fun renderFace(targetFace: CardFace): String {
        var rendered = when (targetFace) {
            CardFace.FRONT -> template.frontHtml
            CardFace.BACK -> template.backHtml
        }
        // Inline {{FrontSide}} BEFORE field substitution so the front's own
        // {{Field}} placeholders also get resolved when shown on the back.
        if (targetFace == CardFace.BACK) {
            rendered = rendered.replace("{{FrontSide}}", renderFace(CardFace.FRONT))
        }
        // Conditional blocks: keep the inner content only if the named
        // field has a value. Matches AnkiDroid behavior, so optional fields
        // like Measure Word don't leave empty pills on cards that don't use
        // them.
        //
        // Loop until stable: blocks NEST — a multi-design note type wraps each
        // front in a per-design `{{#_cardN}}…{{/_cardN}}` gate, so the field
        // conditionals live *inside* it. Regex.replace is a single pass and
        // doesn't re-scan the content it just emitted, so one pass would strip
        // the outer gate but leave the inner `{{#English}}` markers as literal
        // text. Repeating until no block remains resolves the nesting.
        var previous: String
        do {
            previous = rendered
            rendered = ConditionalBlockRegex.replace(rendered) { match ->
                val label = match.groupValues[1]
                val content = match.groupValues[2]
                // Per-design control fields (`_cardN`) gate the front in a
                // multi-design note type. In the preview we're always showing
                // this design, so treat its control field as set, otherwise the
                // gated front would resolve to empty and the preview goes blank.
                val isControl = label.matches(ControlFieldRegex)
                val key = keyByLabel[label]
                val hasValue = isControl || (key != null &&
                    hasFieldValue(key, noteType, textByFieldKey, mediaByFieldKey))
                if (hasValue) content else ""
            }
        } while (rendered != previous)
        // Field substitution.
        for (field in noteType.fields) {
            val value = renderFieldValue(field, textByFieldKey, mediaByFieldKey)
            rendered = rendered.replace("{{${field.label}}}", value)
        }
        return rendered
    }

    return wrapInHtmlDocument(body = renderFace(face), css = template.css)
}

// Matches Anki's `{{#FieldName}}…{{/FieldName}}` blocks across newlines.
// Non-greedy match on the content so adjacent blocks don't get merged.
private val ConditionalBlockRegex = Regex(
    pattern = """\{\{#([^}]+)\}\}([\s\S]*?)\{\{/\1\}\}"""
)

// Per-design control fields emitted by AnkiTemplateExporter.controlFieldLabel.
private val ControlFieldRegex = Regex("""_card\d+""")

private fun hasFieldValue(
    key: String,
    noteType: NoteType,
    textByFieldKey: Map<String, String>,
    mediaByFieldKey: Map<String, GeneratedMedia>
): Boolean {
    val field = noteType.fields.firstOrNull { it.key == key } ?: return false
    return when (field.generator) {
        is FieldGenerator.ImageGen -> mediaByFieldKey[key] is GeneratedMedia.Image
        is FieldGenerator.Tts -> mediaByFieldKey[key] is GeneratedMedia.Audio
        else -> textByFieldKey[key].orEmpty().isNotBlank()
    }
}

private fun renderFieldValue(
    field: CardField,
    textByFieldKey: Map<String, String>,
    mediaByFieldKey: Map<String, GeneratedMedia>
): String {
    val media = mediaByFieldKey[field.key]
    return when {
        media is GeneratedMedia.Image -> {
            // Embedded base64 — WebView can't reach into our cache via
            // file:// or content:// without extra setup, and base64 keeps
            // the preview self-contained. Images are typically a few
            // hundred KB so this is fine.
            //
            // MIME is sniffed, not assumed: generated images are PNG but
            // searched stock photos are JPEG, and mislabelling a data: URI
            // leaves rendering up to the WebView's content sniffing.
            val mime = ImageFormat.detect(media.bytes).mimeType
            val b64 = Base64.encodeToString(media.bytes, Base64.NO_WRAP)
            "<img src=\"data:$mime;base64,$b64\" alt=\"\">"
        }
        media is GeneratedMedia.Audio -> {
            // Same element AnkiDroid emits when it auto-converts a
            // `[sound:filename.mp3]` token, so the existing
            // `.replay-button` CSS in AnkiTemplateExporter styles it
            // identically to the post-save AnkiDroid render.
            //
            // For the preview specifically, we wire onclick to an inline
            // HTML5 Audio() instance whose source is the MP3 bytes as a
            // base64 data: URL. JS is already enabled in the WebView for
            // height measurement; this reuses that channel for playback.
            // Tap = user gesture, so WebView's autoplay block doesn't
            // apply. ~50-100KB of TTS audio → ~70-130KB after base64.
            val b64 = Base64.encodeToString(media.bytes, Base64.NO_WRAP)
            "<a class=\"replay-button\" href=\"#\" " +
                "onclick=\"window.__playAnkiPreviewAudio('data:audio/mp3;base64,$b64'); return false;\"" +
                "></a>"
        }
        else -> {
            // Plain text — mirror the AnkiDroid upsert path's `toAnkiHtml`
            // normalization so the preview lines up exactly with what gets
            // written. See AnkiDroidRepository.toAnkiHtml for the rationale.
            textByFieldKey[field.key].orEmpty()
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\n")
                .replace("\r\n", "\n")
                .trim()
                .replace(Regex("\n\\s*\n+"), "\n")
                .replace("\n", "<br>")
        }
    }
}

private fun wrapInHtmlDocument(body: String, css: String): String =
    """
    <!DOCTYPE html>
    <html>
    <head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <style>
    /* Zero out the default UA margins so our .card padding is the only one. */
    html, body { margin: 0; padding: 0; }
    $css
    </style>
    </head>
    <body class="card">
    $body
    </body>
    </html>
    """.trimIndent()
