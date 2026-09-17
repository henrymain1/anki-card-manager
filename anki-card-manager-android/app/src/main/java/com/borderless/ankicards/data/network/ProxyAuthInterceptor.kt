package com.borderless.ankicards.data.network

import com.borderless.ankicards.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <PROXY_TOKEN>` to outgoing requests that target
 * our proxy. Reads the token from settings on each call so a token change in
 * the Settings screen takes effect immediately without rebuilding
 * Retrofit/OkHttp.
 *
 * **The host check is load-bearing.** This used to attach the header to every
 * request on the client regardless of destination — harmless while every
 * request went to the proxy, but stock-photo search added a third-party host
 * (Pixabay's CDN) to the app, and an unconditional header would have handed
 * the user's proxy bearer token to it. Media downloads also use a separate
 * interceptor-free client; both defences are deliberate. Don't remove either.
 *
 * `runBlocking` here is acceptable because:
 *   1. OkHttp interceptors run on a background thread.
 *   2. DataStore reads are essentially in-memory after the first cold read.
 */
class ProxyAuthInterceptor(
    private val settings: SettingsRepository
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val (token, proxyUrl) = runBlocking {
            settings.proxyToken.first() to settings.proxyUrl.first()
        }

        val proxyHost = proxyUrl.toHttpUrlOrNull()?.host
        val targetsProxy = proxyHost != null &&
            original.url.host.equals(proxyHost, ignoreCase = true)

        val request = if (token.isBlank() || !targetsProxy) {
            original
        } else {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }
}
