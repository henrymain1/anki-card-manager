package com.borderless.ankicards.di

import android.content.Context
import com.borderless.ankicards.data.anki.AnkiDroidRepository
import com.borderless.ankicards.data.gemini.CardGenerator
import com.borderless.ankicards.data.gemini.GeminiApi
import com.borderless.ankicards.data.gemini.GeminiRepository
import com.borderless.ankicards.data.images.PixabayImageSearchRepository
import com.borderless.ankicards.data.network.ProxyAuthInterceptor
import com.borderless.ankicards.data.deck.DeckNoteTypeChoiceRepository
import com.borderless.ankicards.data.deck.DeckLanguageRepository
import com.borderless.ankicards.data.queue.QueuePersistence
import com.borderless.ankicards.data.queue.QueueRepository
import com.borderless.ankicards.data.recipe.AnkiCardsDatabase
import com.borderless.ankicards.data.recipe.NoteTypeRepository
import com.borderless.ankicards.data.settings.SettingsRepository
import com.borderless.ankicards.data.tts.TtsRepository
import com.borderless.ankicards.data.wordlist.WordlistRepository
import com.borderless.ankicards.notifications.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container. We construct repositories and shared clients here
 * once at app start, then hand them out to ViewModels via the ViewModelFactory.
 *
 * We intentionally avoid Hilt/Dagger to keep the build simple. If this app grows
 * past ~15 ViewModels, revisit and consider Hilt.
 */
class AppContainer(
    appContext: Context,
    applicationScope: CoroutineScope
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // Omit null-valued fields from request bodies. Without this,
        // kotlinx.serialization emits e.g. `"personGeneration": null`
        // inside `imageConfig`, and Gemini's API rejects the whole
        // request as `Unknown name "personGeneration"`. Several Gemini
        // fields are "set this to a value or don't include the key at
        // all" — null is a third state that gets rejected. Safer to
        // never send a null than to whitelist every nullable field.
        explicitNulls = false
    }

    val settingsRepository: SettingsRepository = SettingsRepository(appContext)

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        // Gemini image generation routinely takes 15–40s; default 10s is too tight.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .addInterceptor(ProxyAuthInterceptor(settingsRepository))
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()

    /**
     * Interceptor-free client for pulling media bytes off third-party hosts
     * (Pixabay's CDN). Deliberately NOT [okHttp]: that one carries
     * [ProxyAuthInterceptor], which would attach the user's proxy bearer token
     * to a request bound for someone else's server. Timeouts are short because
     * this only ever fetches a ~640px photo — nothing here is slow the way
     * image *generation* is.
     *
     * Shares the connection pool and dispatcher with [okHttp] so the second
     * client is cheap rather than a whole parallel HTTP stack.
     *
     * Clearing the interceptors also drops `HttpLoggingInterceptor` — which is
     * at BODY level and would otherwise dump every downloaded JPEG into
     * logcat.
     */
    private val downloadHttp: OkHttpClient = okHttp.newBuilder()
        .apply { interceptors().clear() }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    // baseUrl is a placeholder — every Retrofit call uses @Url with a full
    // URL built from the user's proxy URL setting.
    private val geminiRetrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://placeholder.invalid/")
        .client(okHttp)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    private val geminiApi: GeminiApi = geminiRetrofit.create(GeminiApi::class.java)

    val geminiRepository: GeminiRepository =
        GeminiRepository(geminiApi, okHttp, settingsRepository)
    val ttsRepository: TtsRepository = TtsRepository(okHttp, settingsRepository)
    val imageSearchRepository: PixabayImageSearchRepository = PixabayImageSearchRepository(
        proxyClient = okHttp,
        downloadClient = downloadHttp,
        settings = settingsRepository
    )
    val cardGenerator: CardGenerator =
        CardGenerator(geminiRepository, ttsRepository, imageSearchRepository)
    val wordlistRepository: WordlistRepository = WordlistRepository(appContext, applicationScope)
    val ankiDroidRepository: AnkiDroidRepository = AnkiDroidRepository(appContext, settingsRepository)

    private val database: AnkiCardsDatabase = AnkiCardsDatabase.build(appContext)
    val noteTypeRepository: NoteTypeRepository = NoteTypeRepository(
        dao = database.noteTypeDao(),
        applicationScope = applicationScope
    )
    val deckLanguageRepository: DeckLanguageRepository = DeckLanguageRepository(
        dao = database.deckLanguageDao()
    )
    val deckNoteTypeChoiceRepository: DeckNoteTypeChoiceRepository =
        DeckNoteTypeChoiceRepository(dao = database.deckNoteTypeChoiceDao())
    val notifier: Notifier = Notifier(appContext)
    private val queuePersistence: QueuePersistence = QueuePersistence(appContext)
    val queueRepository: QueueRepository = QueueRepository(
        applicationScope = applicationScope,
        gemini = geminiRepository,
        tts = ttsRepository,
        anki = ankiDroidRepository,
        notifier = notifier,
        persistence = queuePersistence
    )
}
