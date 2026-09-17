package com.borderless.ankicards.data.gemini

import com.borderless.ankicards.data.gemini.dto.Candidate
import com.borderless.ankicards.data.gemini.dto.Content
import com.borderless.ankicards.data.gemini.dto.GenerateContentRequest
import com.borderless.ankicards.data.gemini.dto.GenerateContentResponse
import com.borderless.ankicards.data.gemini.dto.InlineData
import com.borderless.ankicards.data.gemini.dto.Part
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests around model-name plumbing and URL construction.
 *
 * Splits into two layers:
 *   - Pure-function tests on [composeGeminiProxyUrl] for the URL shape.
 *   - End-to-end-ish tests using [RecordingGeminiApi] and [FakeGeminiSettings]
 *     to verify the model id from settings actually reaches the API call URL.
 */
class GeminiRepositoryTest {

    // ── Pure URL builder ───────────────────────────────────────────────

    @Test
    fun `composeGeminiProxyUrl includes the model id and base URL verbatim`() {
        val url = composeGeminiProxyUrl(
            baseUrl = "https://example.com",
            modelId = "gemini-test-model"
        )
        assertEquals(
            "https://example.com/api/v1beta/models/gemini-test-model:generateContent",
            url
        )
    }

    @Test
    fun `composeGeminiProxyUrl strips trailing slash on base URL`() {
        val url = composeGeminiProxyUrl(
            baseUrl = "https://example.com/",
            modelId = "x"
        )
        assertEquals("https://example.com/api/v1beta/models/x:generateContent", url)
    }

    @Test
    fun `composeGeminiProxyUrl rejects a blank base URL`() {
        assertThrows(IllegalArgumentException::class.java) {
            composeGeminiProxyUrl(baseUrl = "   ", modelId = "x")
        }
    }

    @Test
    fun `composeGeminiProxyUrl rejects a blank model id`() {
        assertThrows(IllegalArgumentException::class.java) {
            composeGeminiProxyUrl(baseUrl = "https://example.com", modelId = "")
        }
    }

    // ── Settings → URL wiring ──────────────────────────────────────────

    @Test
    fun `generateStructured uses the text model from settings`() = runTest {
        val api = RecordingGeminiApi(
            structuredResponse = """{"word":"苍蝇"}"""
        )
        val settings = FakeGeminiSettings(
            proxyUrl = "https://example.com",
            textModel = "gemini-test-text-model-xyz",
            imageModel = "should-not-be-used-here"
        )
        val repo = GeminiRepository(
            api = api,
            httpClient = okhttp3.OkHttpClient(),
            settings = settings
        )

        repo.generateStructured(
            prompt = "test",
            schema = buildJsonObject { put("type", "object") }
        ).getOrThrow()

        val recordedUrl = api.lastUrl ?: error("expected the API to be called")
        assertTrue(
            "URL should contain the text model id, was: $recordedUrl",
            "gemini-test-text-model-xyz" in recordedUrl
        )
        // Make sure the wrong model didn't leak through.
        assertTrue(
            "URL should not include the image model id, was: $recordedUrl",
            "should-not-be-used-here" !in recordedUrl
        )
    }

    @Test
    fun `generateImage uses the image model from settings`() = runTest {
        // No `inlineData` in the response → the function errors with
        // "Gemini response contained no image data" inside its runCatching,
        // which is fine. We're not checking the returned bytes; we're
        // checking that the URL the API was called with includes the
        // image model id from settings. That URL is captured by the fake
        // BEFORE the function inspects the response.
        //
        // This path also avoids hitting `android.util.Base64` /
        // `BitmapFactory`, both of which are Android-only and throw
        // "Stub!" exceptions in pure JVM unit tests.
        val api = RecordingGeminiApi(imageResponseBase64 = null)
        val settings = FakeGeminiSettings(
            proxyUrl = "https://example.com",
            textModel = "should-not-be-used-here",
            imageModel = "gemini-test-image-model-abc"
        )
        val repo = GeminiRepository(
            api = api,
            httpClient = okhttp3.OkHttpClient(),
            settings = settings
        )

        repo.generateImage("hello")  // result is failure, but we don't care

        val recordedUrl = api.lastUrl ?: error("expected the API to be called")
        assertTrue(
            "URL should contain the image model id, was: $recordedUrl",
            "gemini-test-image-model-abc" in recordedUrl
        )
    }
}

// ── Fakes ──────────────────────────────────────────────────────────────

private class FakeGeminiSettings(
    private val proxyUrl: String,
    private val textModel: String,
    private val imageModel: String
) : GeminiSettings {
    override suspend fun getProxyUrl(): String = proxyUrl
    override suspend fun getTextModel(): String = textModel
    override suspend fun getImageModel(): String = imageModel
}

/**
 * Records the URL Retrofit was asked to POST to, returns a canned response.
 * For structured responses, [structuredResponse] is wrapped as the text of
 * the first candidate's first part. For image responses, [imageResponseBase64]
 * is wrapped as inlineData on the first part.
 */
private class RecordingGeminiApi(
    private val structuredResponse: String = "{}",
    private val imageResponseBase64: String? = null
) : GeminiApi {
    var lastUrl: String? = null

    override suspend fun generateContent(
        url: String,
        request: GenerateContentRequest
    ): GenerateContentResponse {
        lastUrl = url
        val part = if (imageResponseBase64 != null) {
            // Image branch — return inlineData on the response.
            Part(inlineData = InlineData(mimeType = "image/png", data = imageResponseBase64))
        } else {
            // Text branch — return the JSON as text.
            Part(text = structuredResponse)
        }
        return GenerateContentResponse(
            candidates = listOf(
                Candidate(
                    content = Content(parts = listOf(part))
                )
            )
        )
    }
}
