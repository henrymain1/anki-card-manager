package com.borderless.ankicards.domain.recipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pre-allocated control-field mechanism in [toAnkiTemplates].
 *
 * Every note type now always gets [MAX_DESIGNS] control fields (`_card1`
 * through `_card5`) and every front is gated, even single-design types.
 * This lets us pre-allocate template slots on the AnkiDroid model so
 * adding designs later doesn't require model recreation.
 */
class AnkiTemplateExporterTest {

    private fun fields() = listOf(
        CardField(key = "word", label = "Word", description = "", generator = FieldGenerator.Llm()),
        CardField(key = "english", label = "English", description = "", generator = FieldGenerator.Llm())
    )

    private fun design(id: String, name: String) = Template(
        id = id,
        name = name,
        placements = listOf(
            FieldPlacement("english", CardFace.FRONT, FieldLayout(1, 1, 10, 2)),
            FieldPlacement("word", CardFace.BACK, FieldLayout(1, 1, 10, 2))
        )
    )

    private fun noteType(vararg templates: Template) =
        NoteType(name = "T", fields = fields(), templates = templates.toList())

    @Test
    fun `single-design note type has all MAX_DESIGNS control fields and a gated front`() {
        val ct = noteType(design("t1", "Card 1"))
        val templates = ct.toAnkiTemplates()
        assertEquals(1, templates.size)
        // Field order includes content fields + all MAX_DESIGNS control fields.
        val expectedOrder = listOf("English", "Word") +
            (1..MAX_DESIGNS).map { "_card$it" }
        assertEquals(expectedOrder, templates[0].fieldOrder)
        // Front is gated on _card1.
        assertTrue("front must be gated", templates[0].frontHtml.contains("{{#_card1}}"))
        assertTrue(ct.usesDesignControlFields())
    }

    @Test
    fun `two-design note type gates each front on its own control field`() {
        val ct = noteType(design("t1", "Card 1"), design("t2", "Card 2"))
        val templates = ct.toAnkiTemplates()
        assertEquals(2, templates.size)
        // Both templates share the same field order: content + all MAX_DESIGNS
        // control fields.
        val expectedOrder = listOf("English", "Word") +
            (1..MAX_DESIGNS).map { "_card$it" }
        assertEquals(expectedOrder, templates[0].fieldOrder)
        assertEquals(expectedOrder, templates[1].fieldOrder)
        assertTrue(templates[0].frontHtml.contains("{{#_card1}}"))
        assertTrue(templates[0].frontHtml.contains("{{/_card1}}"))
        assertTrue(templates[1].frontHtml.contains("{{#_card2}}"))
        assertTrue(ct.usesDesignControlFields())
    }

    @Test
    fun `control values include all MAX_DESIGNS slots`() {
        val ct = noteType(design("t1", "Card 1"), design("t2", "Card 2"))
        val values = ct.designControlValues(setOf("t1"))
        // All MAX_DESIGNS control fields present.
        assertEquals(MAX_DESIGNS, values.size)
        // First design selected, second not, rest are unused.
        assertEquals("1", values["_card1"])
        assertEquals("", values["_card2"])
        for (i in 3..MAX_DESIGNS) {
            assertEquals("Unused slot _card$i should be empty", "", values["_card$i"])
        }
    }

    @Test
    fun `empty selection means all active designs selected`() {
        val ct = noteType(design("t1", "Card 1"), design("t2", "Card 2"))
        val values = ct.designControlValues(emptySet())
        assertEquals("1", values["_card1"])
        assertEquals("1", values["_card2"])
        // Unused slots still empty.
        for (i in 3..MAX_DESIGNS) {
            assertEquals("", values["_card$i"])
        }
    }

    @Test
    fun `single-design control values activate only the first slot`() {
        val ct = noteType(design("t1", "Card 1"))
        val values = ct.designControlValues(emptySet())
        assertEquals(MAX_DESIGNS, values.size)
        assertEquals("1", values["_card1"])
        for (i in 2..MAX_DESIGNS) {
            assertEquals("", values["_card$i"])
        }
    }
}
