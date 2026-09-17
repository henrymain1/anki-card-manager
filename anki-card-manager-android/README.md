# Anki Card Manager (Android)

An Android companion app for **AnkiDroid** that makes designing custom card types and generating filled-in flashcards fast and visual. Type a word, get an AI-generated card with image + audio + romanization + definition + example, review it, save it straight into one of your AnkiDroid decks.

The app does not replace AnkiDroid — it sits on top of it via AnkiDroid's `FlashCardsContract` content provider. Decks, notes, and note types live in AnkiDroid and sync via your existing AnkiWeb account. We store only the AI-generation metadata Anki itself doesn't know about (field descriptions, prompts, image styles, deck-language bindings, last-used-card-type-per-deck).

Supports Cantonese, Mandarin, Japanese, Thai, Spanish, French, German, Italian, and a language-agnostic Generic preset out of the box. New languages are mostly a presets-file change.

## Tech stack

**Language & build**
| Tool | Version | Notes |
|------|---------|-------|
| Kotlin | 2.0.21 | |
| Android Gradle Plugin | 8.7.3 | Bumped from 8.5.2 to clear `lifecycle 2.9.0`'s AAR-metadata minimum. |
| Gradle wrapper | 8.9 | Required by AGP 8.7. |
| KSP | 2.0.21-1.0.27 | Must track the Kotlin version. |
| Min SDK | 26 (Android 8.0) | |
| Target / compile SDK | 35 | |
| Java | 17 | source / target / jvmTarget |

**UI**
| Library | Version | Notes |
|---------|---------|-------|
| Compose BOM | 2025.05.01 | Pins Compose 1.8.x — gives us `Modifier.animateBounds`, LookaheadScope, newer Material3. |
| Material3 | (via BOM) | |
| Material icons extended | (via BOM) | |
| activity-compose | 1.10.1 | Co-bumped so it doesn't pin Compose back to 1.7. |
| lifecycle-runtime-ktx / lifecycle-viewmodel-compose | 2.9.0 | Same reason. |
| navigation-compose | 2.8.9 | Last 2.8.x patch; staying out of 2.9 beta. |
| coil-compose | 2.7.0 | Image loading (used sparingly — most images are raw bytes from Gemini). |

**Data & networking**
| Library | Version | Notes |
|---------|---------|-------|
| kotlinx-coroutines-android | 1.9.0 | |
| kotlinx-serialization-json | 1.7.3 | Used for both Gemini DTOs and persisted card-type recipes. |
| Retrofit | 2.11.0 | + `converter-kotlinx-serialization` |
| OkHttp | 4.12.0 | + logging interceptor |
| Room | 2.6.1 | Local SQLite for card types, deck-language bindings, last-used-card-type-per-deck. |
| DataStore (Preferences) | 1.1.1 | Settings (proxy URL + token, theme, model choices, deck/note-type names). |

**External services** — all reached through a personal proxy (see [`../anki-card-manager-proxy`](../anki-card-manager-proxy)). The app itself holds no API keys; it stores only a proxy URL + bearer token, entered once in Settings.
- **Gemini** (via Vertex AI) for text (structured JSON output) and image generation.
- **Pixabay** for stock-photo search — an alternative to AI image generation, switchable per card.
- **Google Translate TTS** for per-language audio. The app sends `(text, languageCode)` and gets MP3 bytes back.
- **AnkiDroid `FlashCardsContract`** for everything Anki-side: deck list/create, note-type push (fields + CSS + qfmt/afmt), media collection inserts, note upsert.

## Code map

Top-level layout under `app/src/main/java/com/borderless/ankicards/`:

```
domain/                  # Pure Kotlin — no Android, no I/O.
  model/Card.kt          #   Legacy hardcoded Cantonese card. Kept alive by the queue path; retires with queue migration.
  recipe/                #   Phase 0.5+ — the recipe data model.
    CardType.kt          #     A designed card type: name, language, fields, placements.
    CardField.kt         #     One field on a card type.
    FieldGenerator.kt    #     Sealed: UserInput / Dictionary / Llm / Tts / ImageGen.
    FieldLayout.kt       #     12×16 grid coords.
    FieldPlacement.kt    #     A field placed on a face at (row, col, w, h).
    CardFace.kt          #     FRONT / BACK.
    LanguagePresets.kt   #     The six preset card types (yue/cmn/ja/es/fr/Generic).
    FieldPresets.kt      #     Reusable field building blocks.
    AnkiTemplateExporter.kt  # CardType → (qfmt, afmt, css, fieldOrder). Flow layout, per-field-key CSS selectors.
    CardGenerationRequest.kt # The input to generation.
    GenerationSchema.kt  #     Builds the LLM prompt + JSON schema for a card type.
    RecipeJson.kt        #     Round-trip serialization config.
  deck/DeckLanguage.kt   #   yue / cmn / ja / es / fr / Generic.

data/                    # Repositories. The bridge between domain and external systems.
  anki/
    FlashCardsContract.kt        # Mirror of AnkiDroid's ContentProvider constants (no JitPack dep).
    AnkiDroidRepository.kt       # Deck list/create, model push, note upsert, media insert.
  gemini/
    GeminiApi.kt                 # Retrofit interface.
    GeminiRepository.kt          # generateStructured + generateImage.
    CardGenerator.kt             # Phase 2 orchestrator — one LLM call, then parallel TTS/image.
    dto/GeminiDtos.kt
  tts/TtsRepository.kt           # Speaks via the Vercel proxy, returns MP3 bytes.
  recipe/                        # Room persistence for card types.
    AnkiCardsDatabase.kt
    CardTypeEntity.kt / Dao.kt / Repository.kt
  deck/                          # Room persistence for deck-language bindings + last-used card type.
    DeckLanguageEntity.kt / Dao.kt / Repository.kt
    DeckCardTypeChoiceEntity.kt / Dao.kt / Repository.kt
  settings/
    SettingsRepository.kt        # DataStore wrapper.
    ThemeMode.kt                 # Light / Dark / Follow system.
  queue/                         # Legacy share-intent staging queue. Still on the old Card model.
    QueueItem.kt / QueueRepository.kt / QueuePersistence.kt
  wordlist/WordlistRepository.kt # Imported wordlists for batch generation.
  network/ProxyAuthInterceptor.kt

di/AppContainer.kt       # Hand-rolled DI. One container, constructed in AnkiCardsApp.

ui/
  MainScaffold.kt        # Bottom nav (Decks · Generate · Designs · Settings). Hides itself on deep flows.
  AppNavHost.kt          # All routes + factory wiring.
  theme/                 # Material3 theme, color tokens, typography.
  decoration/TriangleMeshBackground.kt   # Opt-in ambient mesh; auto-skips on light theme.
  common/                # Reusable composables.
    GameButton.kt                # The chunky 3D primary action.
    GeneratedCardSurface.kt      # Generator preview surface (grid-positioned for now).
    GeneratedFieldEditorSheet.kt # Bottom sheet to edit / regenerate one field.
    AudioPlayButton.kt           # MediaPlayer-backed play control.
    MiniCardPreview.kt           # Thumbnail used by Designs tab & generator picker.
    CardPreview.kt               # Legacy preview for the queue path.
  decks/                 # Phase 4 — deck list (hierarchical tree), creation, language picker.
  builder/               # Phase 1 — visual card designer + Designs tab list.
    CardDesignerScreen.kt        # Drag-and-drop designer with 3D face flip.
    CardTypeListScreen.kt        # The Designs tab.
  generator/             # Phase 2 — the main "type a word, get a card" screen.
  settings/              # Settings screen (proxy URL + token, theme, model choices).
  staging/               # Legacy queue UI (StagingScreen + InboxDetailScreen).
  wordlist/              # Imported-wordlist UI.

notifications/Notifier.kt  # Background-queue progress notifications.
MainActivity.kt            # Hosts MainScaffold + reads theme mode.
AnkiCardsApp.kt            # Application class — builds AppContainer.
ShareReceiverActivity.kt   # Entry point for the system Share sheet.
```

## Setup

### Prerequisites
1. [Android Studio](https://developer.android.com/studio) (Ladybug or later — anything that supports AGP 8.7).
2. [AnkiDroid](https://play.google.com/store/apps/details?id=com.ichi2.anki) installed on the device or emulator.
3. A deployed proxy — follow [`../anki-card-manager-proxy/README.md`](../anki-card-manager-proxy/README.md) to stand one up on Vercel's free tier. It holds the Gemini/Pixabay keys and gives you a **proxy URL** and **proxy token**.

### First run
1. Open this folder in Android Studio.
2. Let Gradle sync (the first sync downloads AGP + dependencies — can take several minutes).
3. Plug in an Android device with USB debugging enabled (or start an emulator with AnkiDroid installed).
4. Click **Run ▶**.
5. In the app: **Settings** → paste your **Proxy URL** and **Proxy Token** (from the proxy setup above).
6. **Decks** → create a deck (or pick an existing one) → pick a language.
7. **Generate** → type a word → tap **Generate** → review → **Save**.
8. The note is written to AnkiDroid; open AnkiDroid to verify the card looks right.

### When something looks wrong in AnkiDroid
A surprisingly large amount of debugging time on this project has been "we pushed the template but AnkiDroid didn't update it." The pattern: open AnkiDroid → Manage Note Types → find the model → Cards (templates) and Styling. Confirm against what `AnkiTemplateExporter.toAnkiTemplate()` should have produced. Most "the CSS isn't working" reports turn out to be the model in AnkiDroid still holding stale templates from before a fix landed — re-save the card type in the designer to re-push.

## Conventions worth knowing before contributing

- **Check `gradle/libs.versions.toml` before reaching for a new API.** Compose specifically has lots of churn between minor versions — an API from the docs may not exist in the BOM we're pinned to.
- **`runCatching` is unsafe in coroutines** — it swallows `CancellationException`. Use `runCatchingCancellable` (defined in `data/gemini/CardGenerator.kt`) or an explicit `try/catch` that rethrows `CancellationException`.
- **No new dependencies without a reason.** The existing stack already covers Compose, networking, persistence, image loading, serialization. When a dep stops being used, remove it rather than leaving it dead.
- **The deck-language binding is immutable in the UI.** Fix mistakes by deleting and recreating the deck. There's a hidden `forceReassign` for tests/data repair.
- **Anki round-trip is the constraint that shaped Phase 1.** The visual designer outputs flow-layout HTML, not absolute positioning, because Anki re-renders templates at arbitrary widths on arbitrary devices — absolute coordinates from the designer canvas don't survive the trip. Keep that in mind before changing the exporter.
