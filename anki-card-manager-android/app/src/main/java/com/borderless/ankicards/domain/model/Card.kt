package com.borderless.ankicards.domain.model

/**
 * A generated Cantonese flashcard, ready for preview or insertion into AnkiDroid.
 * Field set mirrors the desktop app's note model "Basic":
 *   Front, Chinese, Jyutping, Measure Word, Example (3 lines), image, Sound.
 */
data class Card(
    val front: String,
    val chinese: String,
    val jyutping: String,
    val measureWord: String,
    val exampleEnglish: String,
    val exampleJyutping: String,
    val exampleChinese: String,
    val imageBytes: ByteArray? = null,
    val audioBytes: ByteArray? = null
) {
    /** The "Example" Anki field content — three lines joined with <br>, matching desktop. */
    val exampleHtml: String
        get() = listOf(exampleEnglish, exampleJyutping, exampleChinese)
            .filter { it.isNotBlank() }
            .joinToString("<br>")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Card) return false
        return front == other.front &&
                chinese == other.chinese &&
                jyutping == other.jyutping &&
                measureWord == other.measureWord &&
                exampleEnglish == other.exampleEnglish &&
                exampleJyutping == other.exampleJyutping &&
                exampleChinese == other.exampleChinese &&
                imageBytes.contentEqualsOrBoth(other.imageBytes) &&
                audioBytes.contentEqualsOrBoth(other.audioBytes)
    }

    override fun hashCode(): Int {
        var result = front.hashCode()
        result = 31 * result + chinese.hashCode()
        result = 31 * result + jyutping.hashCode()
        result = 31 * result + measureWord.hashCode()
        result = 31 * result + exampleEnglish.hashCode()
        result = 31 * result + exampleJyutping.hashCode()
        result = 31 * result + exampleChinese.hashCode()
        result = 31 * result + (imageBytes?.contentHashCode() ?: 0)
        result = 31 * result + (audioBytes?.contentHashCode() ?: 0)
        return result
    }
}

private fun ByteArray?.contentEqualsOrBoth(other: ByteArray?): Boolean =
    if (this == null && other == null) true
    else if (this != null && other != null) this.contentEquals(other)
    else false
