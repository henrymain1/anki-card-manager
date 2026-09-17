package com.borderless.ankicards.data.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingJsonObjectParserTest {

    @Test
    fun `emits each pair as it completes`() {
        val parser = StreamingJsonObjectParser()
        // Simulate Gemini streaming the JSON character-by-character.
        val full = """{"word":"苍蝇","pinyin":"cāngyíng","english":"fly"}"""
        val chunkSize = 5
        val seenKeys = mutableListOf<Set<String>>()
        var i = 0
        while (i < full.length) {
            val end = minOf(i + chunkSize, full.length)
            val map = parser.feed(full.substring(i, end))
            seenKeys.add(map.keys.toSet())
            i = end
        }
        val final = seenKeys.last()
        assertEquals(setOf("word", "pinyin", "english"), final)
        // Should have seen the keys land in declared order, not all at once.
        val firstSawWord = seenKeys.indexOfFirst { "word" in it }
        val firstSawPinyin = seenKeys.indexOfFirst { "pinyin" in it }
        val firstSawEnglish = seenKeys.indexOfFirst { "english" in it }
        assertTrue("word should land before pinyin", firstSawWord < firstSawPinyin)
        assertTrue("pinyin should land before english", firstSawPinyin < firstSawEnglish)
    }

    @Test
    fun `decodes JSON string escapes`() {
        val parser = StreamingJsonObjectParser()
        val map = parser.feed("""{"example":"line one\nline two\nline three"}""")
        assertEquals(
            mapOf("example" to "line one\nline two\nline three"),
            map
        )
    }

    @Test
    fun `handles escaped quote inside value`() {
        val parser = StreamingJsonObjectParser()
        val map = parser.feed("""{"quote":"she said \"hi\""}""")
        assertEquals("she said \"hi\"", map["quote"])
    }

    @Test
    fun `incomplete value waits for more`() {
        val parser = StreamingJsonObjectParser()
        // Mid-value: should not emit the partial. (Triple-quote content
        // here is the literal 9 chars `{"word":"苍`.)
        var map = parser.feed("{\"word\":\"苍")
        assertEquals(emptyMap<String, String>(), map)
        // Now complete the value with `蝇"}`.
        map = parser.feed("蝇\"}")
        assertEquals(mapOf("word" to "苍蝇"), map)
    }
}
