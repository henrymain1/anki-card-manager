package com.borderless.ankicards.data.gemini

import com.borderless.ankicards.data.gemini.dto.GenerateContentRequest
import com.borderless.ankicards.data.gemini.dto.GenerateContentResponse
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Retrofit interface for the Gemini API.
 *
 * Uses `@Url` so the full URL is supplied per-call by [GeminiRepository], which
 * reads the user's proxy base URL from settings. Auth is handled by
 * [com.borderless.ankicards.data.network.ProxyAuthInterceptor], not query
 * params — the proxy injects the real Gemini API key server-side.
 */
interface GeminiApi {

    @POST
    suspend fun generateContent(
        @Url url: String,
        @Body request: GenerateContentRequest
    ): GenerateContentResponse
}
