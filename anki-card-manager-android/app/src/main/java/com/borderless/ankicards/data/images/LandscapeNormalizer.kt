package com.borderless.ankicards.data.images

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream

/**
 * Center-crops arbitrary photo bytes to a uniform 16:9 landscape frame.
 *
 * Generated images arrive 16:9 straight from Gemini (`imageConfig.aspectRatio`),
 * so every card had the same picture shape. Searched stock photos don't —
 * Pixabay serves each photo at its native ratio (a 640px-capped `webformatURL`
 * is 640×360, 640×427, 640×480, …), and its API can't filter by aspect ratio,
 * only by `orientation`. Without normalization, searched cards would each be a
 * different shape.
 *
 * This is the "enforce a specific aspect on the device side" helper the image
 * pipeline docs point at — the same center-crop generated images used to get
 * before Gemini rendered 16:9 natively. Because search already asks for
 * horizontal results, the crop trims little.
 */
object LandscapeNormalizer {

    // 16:9 at a size that's plenty for a phone flashcard and keeps files small.
    // Not upscaled beyond this; a ~640px source barely moves.
    private const val TARGET_WIDTH = 640
    private const val TARGET_HEIGHT = 360
    private const val JPEG_QUALITY = 85

    /**
     * Returns [bytes] re-framed to exactly [TARGET_WIDTH]×[TARGET_HEIGHT] as
     * JPEG. On any decode failure returns the original bytes unchanged — a
     * correctly-shaped card is nice-to-have, a rendered card is essential.
     */
    fun to16x9(bytes: ByteArray): ByteArray {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
        try {
            val targetRatio = TARGET_WIDTH.toFloat() / TARGET_HEIGHT
            val srcRatio = src.width.toFloat() / src.height

            val (cropW, cropH) = if (srcRatio > targetRatio) {
                // Wider than 16:9 — trim left and right.
                (src.height * targetRatio).toInt() to src.height
            } else {
                // Taller than (or equal to) 16:9 — trim top and bottom.
                src.width to (src.width / targetRatio).toInt()
            }
            val cropX = (src.width - cropW) / 2
            val cropY = (src.height - cropH) / 2

            val cropped = Bitmap.createBitmap(src, cropX, cropY, cropW, cropH)
            val scaled = if (cropped.width != TARGET_WIDTH || cropped.height != TARGET_HEIGHT) {
                Bitmap.createScaledBitmap(cropped, TARGET_WIDTH, TARGET_HEIGHT, /* filter = */ true)
            } else {
                cropped
            }

            return ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                if (scaled !== cropped) scaled.recycle()
                cropped.recycle()
                out.toByteArray()
            }
        } catch (_: Throwable) {
            return bytes
        } finally {
            src.recycle()
        }
    }
}
