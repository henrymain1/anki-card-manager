package com.borderless.ankicards.domain.recipe

/**
 * Result of translating a [NoteType] into an AnkiDroid note-type template.
 *
 * AnkiDroid stores templates as three pieces of text per note type:
 *  - [frontHtml] (the `qfmt` column on AnkiDroid's `CardTemplate` table)
 *  - [backHtml]  (the `afmt` column)
 *  - [css]       (shared by every template on the note type, stored on the
 *                 note type itself, not the template)
 *
 * [fieldOrder] is the canonical order of field names that AnkiDroid will
 * remember on this note type. The note-type's FLDS payload at insert/update
 * time must match this order. Field *names* (not keys) are used because
 * AnkiDroid's UI surfaces them — we use [CardField.label] verbatim.
 */
data class AnkiTemplate(
    val name: String,
    val frontHtml: String,
    val backHtml: String,
    val css: String,
    val fieldOrder: List<String>
)

/**
 * Maximum number of designs (card templates) per note type. AnkiDroid's
 * ContentProvider cannot add templates to an existing model, so we
 * pre-allocate this many template slots (and control fields) on every
 * model insert. Unused slots are gated by empty control fields → Anki's
 * empty-card rule suppresses them. When the user adds a new design later,
 * we just overwrite the next unused slot — no model recreation needed.
 *
 * This is a stopgap; see Todoist "Decision: Replace pre-allocated template
 * slots with one-model-per-design architecture" for the long-term approach.
 */
const val MAX_DESIGNS = 5

/**
 * Translate a [NoteType] into its Anki note-type templates — one [AnkiTemplate]
 * per [Template] (Anki's N-templates-per-note-type model: each template yields
 * one card per note).
 *
 * All templates on a note type **share one field array and one CSS block**, so
 * [AnkiTemplate.fieldOrder] and [AnkiTemplate.css] are identical across the
 * returned list; only [AnkiTemplate.frontHtml] / [backHtml] / [name] differ.
 * The shared field order is computed once across *all* templates (union in
 * template order, front-then-back) so AnkiDroid's note-type field array stays
 * stable no matter which template a field appears in.
 *
 * Output uses a **flow layout** — each placed field is rendered as a vertical
 * div inside the card shell, in the order produced by sorting placements by
 * `(row, col)`. The grid `row` ⇒ vertical order in Anki; `col` is the
 * tiebreaker. Width and height of placements are NOT translated to render
 * size today (that's a future "real CSS layout controls" project).
 *
 * Every field is wrapped in `{{#Label}}...{{/Label}}` so empty fields collapse
 * cleanly instead of leaving holes in the card — important for optional
 * things like "Measure Word" that don't apply to every note.
 *
 * Each div carries two classes:
 *   - `field-text`/`field-image`/`field-audio` (by generator role)
 *   - `field-key-<key>` (so per-field styling like big 3D Chinese for `word`,
 *     monospace muted for `jyutping`, amber pill for `measure_word`, etc. can
 *     target individual fields without the user authoring CSS).
 *
 * **Pre-allocation (model-evolution stopgap):** Every note type always gets
 * [MAX_DESIGNS] control fields (`_card1` through `_card5`) in its field order,
 * and every front is gated with `{{#_cardN}}…{{/_cardN}}`. The AnkiDroid model
 * is inserted with `NUM_CARDS = MAX_DESIGNS` to pre-create all template slots.
 * Unused slots get a placeholder front that's always suppressed (control field
 * empty → front renders empty → Anki creates no card).
 */
fun NoteType.toAnkiTemplates(): List<AnkiTemplate> {
    val fieldByKey = fields.associateBy { it.key }
    val contentOrder = canonicalFieldOrder(fields, templates).map { it.label }

    // A note type must have at least one template. An empty/unplaced card type
    // still exports one (blank) template so the AnkiDroid push has an ord 0.
    val effectiveTemplates = templates.ifEmpty { listOf(Template()) }
    require(effectiveTemplates.size <= MAX_DESIGNS) {
        "This card type has ${effectiveTemplates.size} designs, but the maximum is $MAX_DESIGNS."
    }

    // Always include all MAX_DESIGNS control fields so the field array is
    // stable regardless of how many designs currently exist. This is what
    // makes it safe to add a design to an already-pushed model later.
    val controlLabels = (0 until MAX_DESIGNS).map { controlFieldLabel(it) }
    val fieldOrder = contentOrder + controlLabels
    val css = defaultCardCss()

    return effectiveTemplates.mapIndexed { index, template ->
        val front = renderFace(template, CardFace.FRONT, fieldByKey)
        val label = controlFieldLabel(index)
        val gatedFront = "{{#$label}}\n$front\n{{/$label}}"
        AnkiTemplate(
            name = template.name.ifBlank { "Card ${index + 1}" },
            frontHtml = gatedFront,
            backHtml = renderFace(template, CardFace.BACK, fieldByKey),
            css = css,
            fieldOrder = fieldOrder
        )
    }
}

/**
 * The primary template's [AnkiTemplate]. Convenience for the single-template
 * consumers — the in-app WebView preview and the generated-note field-order
 * resolution (which only needs the shared field order + css, identical across
 * every template). For the AnkiDroid push, use [toAnkiTemplates].
 */
fun NoteType.toAnkiTemplate(): AnkiTemplate =
    toAnkiTemplates().first()

/** Hidden control-field label gating design [index]'s card (0-based). */
internal fun controlFieldLabel(index: Int): String = "_card${index + 1}"

/** Always true — every note type now uses pre-allocated control fields. */
fun NoteType.usesDesignControlFields(): Boolean = true

/**
 * The control-field values to write on a note, given which designs are
 * selected. Always returns all [MAX_DESIGNS] control fields:
 *   - Active design + selected → "1"
 *   - Active design + not selected → ""
 *   - Pre-allocated unused slot → "" (always suppressed)
 *
 * An empty [selectedTemplateIds] means "all active designs selected".
 */
fun NoteType.designControlValues(selectedTemplateIds: Set<String>): Map<String, String> {
    val effective = templates.ifEmpty { listOf(Template()) }
    return (0 until MAX_DESIGNS).associate { index ->
        val label = controlFieldLabel(index)
        val value = if (index < effective.size) {
            val on = selectedTemplateIds.isEmpty() || effective[index].id in selectedTemplateIds
            if (on) "1" else ""
        } else {
            "" // Pre-allocated unused slot — always suppressed.
        }
        label to value
    }
}

/**
 * Render one face of one [template] as plain HTML. **Each face is
 * self-contained** — the back does NOT auto-include `{{FrontSide}}`. If the
 * user wants the front (or any specific field from it) echoed on the back, they
 * place it explicitly on the back face in the designer.
 *
 * Previously this added a dimmed `{{FrontSide}}` echo + divider at the top
 * of the back, following Anki's "back = front + divider + answer"
 * convention. The user reported this as unwanted — they expected the back
 * to be exactly what they placed on the back, nothing else.
 */
private fun renderFace(
    template: Template,
    face: CardFace,
    fieldByKey: Map<String, CardField>
): String {
    // Sort by row, then col. That becomes the visible vertical order.
    val placed = template.placementsOn(face).sortedWith(
        compareBy({ it.layout.row }, { it.layout.col })
    )
    return buildString {
        append("<div class=\"card-shell\">\n")
        placed.forEach { p ->
            val field = fieldByKey[p.fieldKey] ?: return@forEach
            val fieldName = escapeFieldName(field.label)
            val genClass = generatorCssClass(field)
            val keyClass = "field-key-${field.key.lowercase().replace(Regex("[^a-z0-9_-]"), "-")}"
            append(
                "  {{#$fieldName}}" +
                    "<div class=\"field $genClass $keyClass\">{{$fieldName}}</div>" +
                    "{{/$fieldName}}\n"
            )
        }
        append("</div>")
    }
}

private fun generatorCssClass(field: CardField): String = when (field.generator) {
    is FieldGenerator.ImageGen -> "field-image"
    is FieldGenerator.Tts -> "field-audio"
    is FieldGenerator.Llm,
    is FieldGenerator.Dictionary,
    is FieldGenerator.UserInput -> "field-text"
}

private fun escapeFieldName(name: String): String =
    // AnkiDroid field placeholders are {{Name}} where Name matches the field
    // name exactly. Field names cannot contain { } or HTML; we trust the label
    // for v1 and strip the bare minimum.
    name.replace("{", "").replace("}", "").trim()

/**
 * Canonical ordering for the note type's **shared** field array: every placed
 * field in template order (front-then-back reading order within each
 * template), followed by any unplaced fields in their declared order. Computed
 * across *all* templates because AnkiDroid stores one field array per note
 * type, shared by every template. Stable across calls so subsequent re-pushes
 * to AnkiDroid don't shuffle the field array and break existing cards' field
 * bindings.
 */
private fun canonicalFieldOrder(
    fields: List<CardField>,
    templates: List<Template>
): List<CardField> {
    val byKey = fields.associateBy { it.key }
    val seen = LinkedHashSet<String>()
    val ordered = mutableListOf<CardField>()
    val faceOrder = listOf(CardFace.FRONT, CardFace.BACK)
    templates.forEach { template ->
        faceOrder.forEach { face ->
            template.placementsOn(face)
                .sortedWith(compareBy({ it.layout.row }, { it.layout.col }))
                .forEach { p ->
                    if (seen.add(p.fieldKey)) {
                        byKey[p.fieldKey]?.let { ordered.add(it) }
                    }
                }
        }
    }
    fields.forEach { f ->
        if (seen.add(f.key)) ordered.add(f)
    }
    return ordered
}

/**
 * The default stylesheet shipped with every card type. Adapted from a real
 * hand-tuned Anki template the user shared as the target visual quality:
 *
 *  - Dark surface with a blue accent border at the bottom of the card shell.
 *  - Big bold "3D" Chinese / Word text (layered text-shadow, blue palette).
 *  - Monospace muted Jyutping / Pinyin / Furigana.
 *  - Amber pill Measure Word / Classifier.
 *  - Boxed Example with the EXAMPLE label.
 *  - Rounded shadowed images.
 *  - Custom `.replay-button` styling so AnkiDroid's auto-generated audio
 *    control renders as a tappable blue play circle that pulses on press.
 *
 * Per-field selectors target `.field-key-<key>` — so a card type that uses
 * `word` / `jyutping` / `definition` / `example` / `measure_word` / `image`
 * field keys picks up the styling automatically. Custom keys can also pick
 * up styling by overriding the CSS or aligning their key to a known one.
 */
private fun defaultCardCss(): String = """
/* ── Page background (the .card wrapper Anki adds around our template) ── */
.card {
    font-family: "Helvetica Neue", Helvetica, Arial,
                 "PingFang SC", "Microsoft YaHei", sans-serif;
    font-size: 20px;
    text-align: center;
    color: #e8eaed;
    background-color: #121212;
    line-height: 1.5;
    padding: 20px 10px;
}

/* ── Card shell — the visible "card" rectangle ─────────────────────── */
.card-shell {
    background-color: #1e1e1e;
    border-radius: 16px;
    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.6);
    border: 1px solid #333;
    border-bottom: 4px solid #82b1ff;
    max-width: 500px;
    margin: 0 auto;
    padding: 30px 20px;
}

/* Spacing between fields. */
.card-shell > .field { margin: 14px 0; }
.card-shell > .field:first-child { margin-top: 0; }
.card-shell > .field:last-child { margin-bottom: 0; }

/* ── Per-generator defaults ─────────────────────────────────────────── */
.field-text { color: #e8eaed; }
.field-image img,
.field-image > img,
.field img {
    display: block;
    margin: 0 auto;
    max-width: 100%;
    height: auto;
    border-radius: 12px;
    border: 1px solid rgba(255, 255, 255, 0.10);
    box-shadow: 0 12px 28px rgba(0, 0, 0, 0.55);
}
.field-audio {
    /* The actual `[sound:...]` Anki control is styled below as .replay-button.
       This wrapper just centers it horizontally. */
    text-align: center;
}

/* ── Front-side / translation / question text ──────────────────────── */
.field-key-front,
.field-key-english,
.field-key-translation {
    font-size: 24px;
    font-weight: 600;
    color: #ffffff;
}

/* ── Big bold Word / Chinese (3D layered shadow) ───────────────────── */
.field-key-word,
.field-key-chinese {
    font-size: 60px;
    color: #82b1ff;
    font-weight: 800;
    line-height: 1.15;
    text-shadow:
        1px 1px 0 #3c6bc4,
        2px 2px 0 #3c6bc4,
        3px 3px 0 #3c6bc4,
        4px 4px 0 #3c6bc4,
        6px 6px 12px rgba(0, 0, 0, 0.6);
}

/* ── Romanization — monospace, muted ───────────────────────────────── */
.field-key-jyutping,
.field-key-pinyin,
.field-key-furigana,
.field-key-romanization {
    font-family: "Courier New", Courier, monospace;
    font-size: 16px;
    color: #9aa0a6;
    letter-spacing: 1px;
    text-transform: lowercase;
}

/* ── Measure word — amber pill ─────────────────────────────────────── */
.field-key-measure_word,
.field-key-classifier {
    display: inline-block;
    font-size: 15px;
    color: #ffcc80;
    background-color: rgba(255, 204, 128, 0.10);
    border: 1px solid rgba(255, 204, 128, 0.30);
    padding: 4px 12px;
    border-radius: 20px;
    font-weight: 600;
}

/* ── Definition — clean light text, slightly larger ────────────────── */
.field-key-definition {
    font-size: 17px;
    color: #e8eaed;
    line-height: 1.4;
}

/* ── Example — boxed sub-card with subtle border ───────────────────── */
.field-key-example,
.field-key-example_sentence {
    font-size: 17px;
    text-align: left;
    color: #e8eaed;
    background-color: #2b2b2b;
    border: 1px solid #444;
    border-radius: 12px;
    padding: 15px;
    box-shadow: 0 4px 8px rgba(0, 0, 0, 0.2);
}

/* ── Anki's auto-generated audio replay button ─────────────────────── */
.replay-button {
    display: inline-block;
    width: 50px;
    height: 50px;
    background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24' fill='%2382b1ff'%3E%3Cpath d='M8 5v14l11-7z'/%3E%3C/svg%3E");
    background-repeat: no-repeat;
    background-position: center;
    background-size: 28px;
    background-color: rgba(130, 177, 255, 0.05);
    border: 2px solid #82b1ff;
    border-radius: 50%;
    margin: 12px auto;
    cursor: pointer;
    text-decoration: none;
    box-shadow: none;
    -webkit-tap-highlight-color: transparent;
    user-select: none;
    outline: none;
}
.replay-button svg { display: none !important; }
.replay-button:active {
    transform: scale(0.92);
    background-color: rgba(130, 177, 255, 0.20);
    box-shadow: 0 0 15px rgba(130, 177, 255, 0.60);
    transition: transform 0.1s;
}

/* ── Mobile adjustments ────────────────────────────────────────────── */
@media (max-width: 480px) {
    .card-shell { padding: 20px 15px; }
    .field-key-word, .field-key-chinese { font-size: 50px; }
}
""".trimIndent()
