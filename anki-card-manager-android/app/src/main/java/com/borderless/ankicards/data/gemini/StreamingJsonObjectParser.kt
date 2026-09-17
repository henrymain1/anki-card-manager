package com.borderless.ankicards.data.gemini

/**
 * Streaming parser for a JSON object whose top-level properties are all
 * string-valued. Feeds the text chunk-by-chunk from a Gemini streaming
 * response and emits the accumulated `(key, value)` map as soon as each
 * complete property lands.
 *
 * Why we need this: Gemini's `:streamGenerateContent` endpoint streams
 * tokens as the model generates them. For structured output the response
 * grows character-by-character, e.g.:
 *   chunk 1: `{"word"`
 *   chunk 2: `: "苍蝇", "py`
 *   chunk 3: `inyin": "cāngyíng",...`
 *
 * We want to surface `word` to the UI the instant the model finishes its
 * value (between chunks 1 and 2 here, when the closing `"` lands). This
 * parser tracks position in an accumulating buffer and pulls complete
 * `"key": "value"` pairs out greedily after each `feed()` call.
 *
 * Scope: this parser only handles top-level **string** properties. That
 * matches our schema — every card field is `{ "type": "string" }`. It does
 * not attempt to parse nested objects, arrays, numbers, or booleans; if it
 * encounters anything other than a string after the colon it gives up on
 * that pair (advances past it once the value's syntactic boundary is
 * detectable). Good enough for our use case.
 *
 * Not thread-safe — caller is expected to feed sequentially from one
 * coroutine.
 */
class StreamingJsonObjectParser {

    private val buf = StringBuilder()
    private var pos = 0
    private val accumulated = mutableMapOf<String, String>()
    private val emittedKeys = mutableSetOf<String>()

    /**
     * Append [chunk] to the internal buffer, extract as many complete
     * pairs as possible, and return the current accumulated map. The
     * returned map is a snapshot — callers can compare its size or keys
     * against the previous snapshot to detect new arrivals.
     */
    fun feed(chunk: String): Map<String, String> {
        buf.append(chunk)
        while (extractOnePair()) {
            // keep going until we hit an incomplete pair
        }
        return accumulated.toMap()
    }

    /** Returns the keys that have landed so far. */
    fun keysSoFar(): Set<String> = emittedKeys.toSet()

    /**
     * Attempt to consume the next complete `"key": "value"` pair from the
     * buffer starting at [pos]. Returns true if a pair was extracted, false
     * if the buffer doesn't yet contain a complete pair (caller should wait
     * for more data) or if we ran past the closing `}` of the object.
     */
    private fun extractOnePair(): Boolean {
        var p = pos
        // Skip whitespace + opening brace + comma between pairs.
        while (p < buf.length && (buf[p].isWhitespace() || buf[p] == ',' || buf[p] == '{')) {
            p++
        }
        if (p >= buf.length) return false
        if (buf[p] == '}') {
            // End of object — done.
            pos = p
            return false
        }
        if (buf[p] != '"') return false  // anomaly; wait for more or bail

        // Read the key string.
        val keyStart = p + 1
        var keyEnd = keyStart
        while (keyEnd < buf.length) {
            when (buf[keyEnd]) {
                '\\' -> keyEnd += 2  // skip the escaped character
                '"' -> { break }
                else -> keyEnd++
            }
        }
        if (keyEnd >= buf.length) return false  // key still streaming
        val key = decodeJsonString(buf.substring(keyStart, keyEnd))
        p = keyEnd + 1

        // Skip whitespace + colon.
        while (p < buf.length && (buf[p].isWhitespace() || buf[p] == ':')) p++
        if (p >= buf.length) return false
        if (buf[p] != '"') {
            // Value isn't a string. Our schema is string-only, so this
            // should never happen, but if it does we want to recover —
            // skip until we find the next comma at this depth so we don't
            // get permanently stuck.
            val nextComma = findTopLevelComma(p)
            if (nextComma < 0) return false
            pos = nextComma + 1
            return true
        }

        // Read the value string.
        val valueStart = p + 1
        var valueEnd = valueStart
        while (valueEnd < buf.length) {
            when (buf[valueEnd]) {
                '\\' -> valueEnd += 2  // skip the escaped character (handles \", \\, \n, etc.)
                '"' -> { break }
                else -> valueEnd++
            }
        }
        if (valueEnd >= buf.length) return false  // value still streaming
        val value = decodeJsonString(buf.substring(valueStart, valueEnd))

        // Commit.
        accumulated[key] = value
        emittedKeys.add(key)
        pos = valueEnd + 1
        return true
    }

    private fun findTopLevelComma(from: Int): Int {
        var depth = 0
        var i = from
        while (i < buf.length) {
            when (buf[i]) {
                '"' -> {
                    // Skip a string literal.
                    i++
                    while (i < buf.length && buf[i] != '"') {
                        if (buf[i] == '\\') i++
                        i++
                    }
                }
                '{', '[' -> depth++
                '}', ']' -> depth--
                ',' -> if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    /**
     * Minimal JSON string escape decoder for the four escapes Gemini
     * actually emits inside our values: `\"`, `\\`, `\n`, `\t`. Other
     * escapes pass through with the backslash stripped, which is good
     * enough — our values are plain text from the LLM, not arbitrary JSON.
     */
    private fun decodeJsonString(raw: String): String {
        if ('\\' !in raw) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                when (val esc = raw[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'u' -> {
                        if (i + 5 < raw.length) {
                            val hex = raw.substring(i + 2, i + 6)
                            val code = hex.toIntOrNull(16)
                            if (code != null) {
                                sb.append(code.toChar())
                                i += 6
                                continue
                            }
                        }
                        sb.append(esc)
                    }
                    else -> sb.append(esc)
                }
                i += 2
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
