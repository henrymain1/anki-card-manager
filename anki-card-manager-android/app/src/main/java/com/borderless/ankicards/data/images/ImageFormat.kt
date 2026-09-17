package com.borderless.ankicards.data.images

/**
 * Image container formats we might hold bytes for.
 *
 * Before stock-photo search everything came from Gemini as PNG, so both the
 * AnkiDroid media write and the WebView preview simply hardcoded PNG. Pixabay
 * serves JPEG (and sometimes WebP), and a JPEG written to `card_x.png` and
 * declared as `data:image/png` only renders because browsers sniff content
 * rather than trust the label. That's luck, not design — detect the real
 * format instead.
 */
enum class ImageFormat(val extension: String, val mimeType: String) {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    WEBP("webp", "image/webp"),
    GIF("gif", "image/gif");

    companion object {
        /**
         * Sniff the container from the leading magic bytes, defaulting to
         * [PNG] for anything unrecognised — that was the historical
         * assumption, so an unknown format behaves no worse than before.
         */
        fun detect(bytes: ByteArray): ImageFormat = when {
            bytes.startsWith(0x89, 0x50, 0x4E, 0x47) -> PNG
            bytes.startsWith(0xFF, 0xD8, 0xFF) -> JPEG
            // RIFF....WEBP — the 4-byte file size sits between the two tags.
            bytes.startsWith(0x52, 0x49, 0x46, 0x46) &&
                bytes.size > 11 &&
                bytes.startsWith(0x57, 0x45, 0x42, 0x50, offset = 8) -> WEBP
            bytes.startsWith(0x47, 0x49, 0x46, 0x38) -> GIF
            else -> PNG
        }

        private fun ByteArray.startsWith(vararg magic: Int, offset: Int = 0): Boolean {
            if (size < offset + magic.size) return false
            return magic.withIndex().all { (i, b) -> this[offset + i] == b.toByte() }
        }
    }
}
