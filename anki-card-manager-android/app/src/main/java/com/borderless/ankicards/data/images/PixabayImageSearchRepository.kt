package com.borderless.ankicards.data.images

import com.borderless.ankicards.data.settings.ImageSourcePreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The slice of settings this repository needs. Separate interface (rather
 * than depending on `SettingsRepository`) so tests can fake it without
 * DataStore — same reasoning as `GeminiSettings`.
 */
interface ImageSearchSettings {
    suspend fun getProxyUrl(): String
    suspend fun getImageSource(): ImageSourcePreference
}

/** One photo in Pixabay's search response. Only the fields we actually read. */
@Serializable
private data class PixabayHit(
    val id: Long = 0,
    /** Comma-separated, e.g. `"crossroads, decision, signpost"`. Our quality gate reads this. */
    val tags: String = "",
    val pageURL: String = "",
    /** 640px on the long edge. Plenty for a phone flashcard, and far smaller than a 1K PNG. */
    val webformatURL: String = "",
    val largeImageURL: String = ""
)

@Serializable
private data class PixabaySearchResponse(
    val total: Int = 0,
    /** Hits accessible through the API — the number the quality gate uses. */
    @SerialName("totalHits") val totalHits: Int = 0,
    val hits: List<PixabayHit> = emptyList()
)

/**
 * Stock-photo lookup for card images, via the user's Vercel proxy.
 *
 * ## Why the query must be keywords, not prose
 *
 * Pixabay matches loosely rather than ANDing terms. Measured against the live
 * index while designing this:
 *
 * | Query                                                    | Results | Character |
 * |----------------------------------------------------------|---------|-----------|
 * | `hesitate` (the bare vocabulary word)                     | 46      | question marks, rope knots, puzzle pieces |
 * | `person standing at a fork in the road looking uncertain` | 37,349  | diluted — generic "person"/"road" stock |
 * | `crossroads decision`                                     | 1,856   | signposts, and a real photo of a girl at a crossroads |
 *
 * So the LLM is asked for two or three concrete keywords naming a
 * *photographable scene* (see `GenerationSchema.IMAGE_QUERY_KEY`), not an
 * image-generation prompt. Prose dilutes; the bare word fails on anything
 * abstract; keywords focus.
 *
 * ## The quality gate
 *
 * A returned photo only counts if it clears [MIN_TOTAL_HITS] and its tags
 * match at least [requiredMatches] of the query's meaningful tokens. The gate
 * is deliberately biased strict: a false reject just means we generate the
 * image as before (slower, costs money — but correct), whereas a false accept
 * puts a wrong picture on a flashcard silently. Prefer falling back.
 *
 * ## Licensing
 *
 * Pixabay's API terms *require* downloading rather than hotlinking
 * ("permanent hotlinking of images ... is not allowed"), which is exactly
 * what pushing bytes into the AnkiDroid media collection does. Attribution is
 * requested but not required, which matters because an exported Anki note has
 * nowhere sensible to put a credit line. Unsplash was rejected for the
 * opposite reason — it *mandates* hotlinking, so its images can't live in an
 * offline Anki collection at all.
 */
class PixabayImageSearchRepository(
    /**
     * Client for the proxy call. Carries `ProxyAuthInterceptor`, so it must
     * only ever be pointed at the user's proxy host.
     */
    private val proxyClient: OkHttpClient,
    /**
     * Separate, interceptor-free client for pulling the actual photo bytes
     * off Pixabay's CDN. **Do not** reuse [proxyClient] here — it would
     * attach the user's proxy bearer token to a third-party request.
     */
    private val downloadClient: OkHttpClient,
    private val settings: ImageSearchSettings
) : ImageSearchClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun mode(): ImageSourcePreference = settings.getImageSource()

    override suspend fun search(query: String): ImageSearchOutcome = withContext(Dispatchers.IO) {
        val mode = settings.getImageSource()
        if (!mode.usesSearch) return@withContext ImageSearchOutcome.Miss

        // Every failure below funnels through this so a dead proxy route or a
        // malformed response degrades into "generate it instead" rather than a
        // card with no image. `Exhausted` is only correct when the user has
        // explicitly forbidden generation.
        val miss = if (mode == ImageSourcePreference.SearchOnly) {
            ImageSearchOutcome.Exhausted
        } else {
            ImageSearchOutcome.Miss
        }

        val tokens = meaningfulTokens(query)
        if (tokens.isEmpty()) return@withContext miss

        val t0 = System.currentTimeMillis()
        try {
            val response = fetchResults(query) ?: return@withContext miss

            if (response.totalHits < MIN_TOTAL_HITS) {
                android.util.Log.d(
                    LOG_TAG,
                    "  · Image search MISS · \"$query\" — only ${response.totalHits} hits"
                )
                return@withContext miss
            }

            val required = requiredMatches(tokens.size)
            val best = response.hits
                .map { hit -> hit to tagMatchScore(hit, tokens) }
                .filter { (_, score) -> score >= required }
                .maxByOrNull { (_, score) -> score }
                ?.first

            if (best == null) {
                android.util.Log.d(
                    LOG_TAG,
                    "  · Image search MISS · \"$query\" — ${response.hits.size} hits, " +
                        "none matched $required/${tokens.size} tokens"
                )
                return@withContext miss
            }

            val bytes = download(best.webformatURL) ?: return@withContext miss
            android.util.Log.d(
                LOG_TAG,
                "✓ Image search · \"$query\" — ${System.currentTimeMillis() - t0}ms · " +
                    "${bytes.size}B · ${best.pageURL}"
            )
            ImageSearchOutcome.Found(bytes = bytes, sourceUrl = best.pageURL)
        } catch (c: CancellationException) {
            // Structured concurrency: a cancelled pipeline must unwind, not be
            // swallowed into a Miss. Same reasoning as `runCatchingCancellable`.
            throw c
        } catch (t: Throwable) {
            android.util.Log.w(
                LOG_TAG,
                "  · Image search FAILED · \"$query\" — ${t.message ?: t::class.simpleName}"
            )
            miss
        }
    }

    /** `GET <proxy>/api/images/search?...`. Returns null on any non-2xx or unparseable body. */
    private suspend fun fetchResults(query: String): PixabaySearchResponse? {
        val base = settings.getProxyUrl().trim().trimEnd('/')
        if (base.isBlank()) return null

        val url = "$base$SEARCH_PATH".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("q", query)
            ?.addQueryParameter("per_page", PER_PAGE.toString())
            // Policy lives here, not in the proxy — the proxy stays a dumb
            // passthrough that only injects the Pixabay key.
            ?.addQueryParameter("image_type", "photo")
            ?.addQueryParameter("orientation", "horizontal")
            ?.addQueryParameter("safesearch", "true")
            ?.build()
            ?: return null

        proxyClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                android.util.Log.w(
                    LOG_TAG,
                    "  · Image search HTTP ${resp.code} — is /api/images/search deployed on the proxy?"
                )
                return null
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return null
            return json.decodeFromString(PixabaySearchResponse.serializer(), body)
        }
    }

    private fun download(url: String): ByteArray? {
        if (url.isBlank()) return null
        downloadClient.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val raw = resp.body?.bytes()?.takeIf { it.isNotEmpty() } ?: return null
            // Normalize to 16:9 so searched photos match the uniform shape of
            // generated ones. Pixabay can't filter by aspect ratio, so every
            // hit is a different shape until we crop it here.
            return LandscapeNormalizer.to16x9(raw)
        }
    }

    private companion object {
        const val LOG_TAG = "AnkiCardsAI"
        const val SEARCH_PATH = "/api/images/search"
        const val PER_PAGE = 8

        /**
         * Below this, the index effectively has nothing for the query and the
         * few hits present are loose matches. `hesitate` returns 46 results of
         * rope knots and question marks, so this threshold alone doesn't save
         * us — the tag gate does the real work. This just short-circuits the
         * obviously-empty case before we bother scoring.
         */
        const val MIN_TOTAL_HITS = 5

        /** Tokens too short or too common to carry meaning in a tag match. */
        val STOPWORDS = setOf(
            "the", "and", "for", "with", "from", "into", "onto", "that", "this",
            "his", "her", "its", "their", "someone", "something", "person", "people"
        )

        /**
         * Require both keywords to match when the LLM gave us two or more, and
         * the single keyword when it gave us one. Two-of-two is what rejects
         * the dilution case, where a loose match hits only one term.
         */
        fun requiredMatches(tokenCount: Int): Int = minOf(2, tokenCount)

        fun meaningfulTokens(query: String): Set<String> =
            query.lowercase()
                .split(' ', ',', '.', '/', '-', '(', ')', '"', '\'', '\n', '\t')
                .map { it.trim() }
                .filter { it.length >= 3 && it !in STOPWORDS }
                .toSet()

        fun tagMatchScore(hit: PixabayHit, tokens: Set<String>): Int {
            val tags = hit.tags.lowercase()
            // Substring rather than exact token match so "crossroad" matches a
            // "crossroads" tag and vice versa. Loose, but the count threshold
            // is what carries the strictness.
            return tokens.count { token -> tags.contains(token) }
        }
    }
}
