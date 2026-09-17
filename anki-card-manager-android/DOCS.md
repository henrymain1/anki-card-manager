# Anki Card Manager — Android

Living developer documentation. Update this file whenever you add a feature, introduce a new package, or make an architectural decision worth remembering.

---

## 1. Development Philosophy

The point of these rules is to keep the codebase small, readable, and easy to extend a year from now. They are guidelines — not rituals. If a rule fights the right answer in a specific case, change the rule (and update this doc).

### 1.1 One concern per file

A Kotlin file should describe one thing. A screen Composable, a ViewModel, a repository, a data class. If you find yourself scrolling, split.

- ✅ `CardGeneratorScreen.kt` contains the screen and its sub-Composables specific to that screen.
- ✅ `CardPreview.kt` lives in `ui/common/` because it is reused.
- ❌ Don't define a ViewModel and three Composables and a data class in the same file.

### 1.2 MVVM, strictly layered

Data flows **down**, events flow **up**. The layers are:

```
Composable  →  ViewModel  →  Repository  →  Data source (API / DataStore / ContentProvider)
```

Rules:

- **Composables** never call repositories or networking code directly. They observe `StateFlow` and forward events to the ViewModel.
- **ViewModels** never reference Android UI types (no `Context`, no `View`). They orchestrate repositories and expose a `StateFlow<UiState>`.
- **Repositories** are the only layer that knows about Retrofit, DataStore, ContentProviders, etc. They return clean Kotlin types (domain models or `Result<T>`).
- **Domain models** (`domain/model/`) are pure Kotlin, no framework dependencies. They are the lingua franca between layers.

### 1.3 State is a single immutable data class per screen

Each screen has a `XxxUiState` data class. The ViewModel exposes exactly one `StateFlow<XxxUiState>`. Updates use `_uiState.update { it.copy(...) }`.

Why: easy to reason about, easy to render, easy to test, free undo/redo if we ever want it.

### 1.4 Coroutines for async, Flow for streams

- One-shot operations (network call, DataStore write) → `suspend fun` returning `Result<T>`.
- Continuous streams (DataStore reads, observable state) → `Flow<T>`.
- ViewModel work uses `viewModelScope.launch { ... }` — never `GlobalScope`.

### 1.5 Errors surface as state, not crashes

Repositories return `Result<T>`. ViewModels handle `.onFailure { }` and put the message into UI state. The user sees a snackbar; the app keeps running.

Reserve thrown exceptions for genuine programmer errors (e.g. illegal state).

### 1.6 No premature abstraction

Add an interface, a generic, or a base class only when there are at least two real implementations. A single-implementation `interface AnkiClient` adds noise without value — `class AnkiDroidRepository` is enough until we add a second backend.

### 1.7 Comments

Comments explain **why**, not **what**. Names explain what. If a name needs a comment, rename first.

The exceptions:
- KDoc on every public class and on non-obvious public functions.
- Inline `// TODO:` for known holes — include the reason and (if relevant) a tracking issue.

### 1.8 Adding a new feature — checklist

1. Add or extend a domain model in `domain/model/` if the data shape is new.
2. Add a method to the relevant repository (or a new repository under `data/`).
3. Wire it in `di/AppContainer.kt` if it's a new repository.
4. Create a new screen package under `ui/<feature>/` containing:
   - `XxxScreen.kt` (Composable)
   - `XxxViewModel.kt` (with `companion object factory`)
   - `XxxUiState.kt` (data class)
5. Add a route constant + composable entry in `ui/AppNavHost.kt`.
6. Update Section 2 of this doc with the new files.

---

## 2. Project Structure (Index)

```
anki-card-manager-android/
├── DOCS.md                      ← you are here
├── README.md                    ← user-facing setup + run instructions
├── settings.gradle.kts          ← top-level Gradle module list
├── build.gradle.kts             ← root build script (declares plugins)
├── gradle.properties            ← Gradle/Kotlin/Android global flags
├── gradle/
│   └── libs.versions.toml       ← single source of truth for all dependency versions
└── app/
    ├── build.gradle.kts         ← app module: SDK levels, deps, Compose config
    ├── proguard-rules.pro       ← R8/ProGuard rules (kotlinx.serialization keepers)
    └── src/main/
        ├── AndroidManifest.xml  ← permissions, activity, AnkiDroid <queries>
        ├── res/values/          ← strings.xml, themes.xml
        └── java/com/borderless/ankicards/
            ├── AnkiCardsApp.kt              ← Application subclass; constructs AppContainer
            ├── MainActivity.kt              ← single Activity; hosts Compose
            │
            ├── di/
            │   └── AppContainer.kt          ← manual DI: shared repos, OkHttp, Retrofit, Json
            │
            ├── domain/
            │   └── model/
            │       └── Card.kt              ← pure Kotlin domain types
            │
            ├── data/
            │   ├── settings/
            │   │   └── SettingsRepository.kt    ← DataStore-backed user prefs
            │   ├── gemini/
            │   │   ├── GeminiApi.kt             ← Retrofit interface
            │   │   ├── GeminiRepository.kt      ← prompt + parse + Card output
            │   │   └── dto/
            │   │       └── GeminiDtos.kt        ← request/response DTOs
            │   └── anki/
            │       └── AnkiDroidRepository.kt   ← AnkiDroid ContentProvider wrapper (stub)
            │
            └── ui/
                ├── AppNavHost.kt            ← Navigation graph (routes + composables)
                ├── theme/
                │   ├── Color.kt             ← design tokens (light + dark)
                │   ├── Type.kt              ← typography scale
                │   └── Theme.kt             ← MaterialTheme wrapper
                ├── common/
                │   └── CardPreview.kt       ← reusable styled Card preview
                ├── generator/
                │   ├── GeneratorUiState.kt
                │   ├── CardGeneratorViewModel.kt
                │   └── CardGeneratorScreen.kt
                └── settings/
                    ├── SettingsUiState.kt
                    ├── SettingsViewModel.kt
                    └── SettingsScreen.kt
```

### Where to put a new file

| If you're adding...                       | Put it in                                   |
|-------------------------------------------|---------------------------------------------|
| A new screen                              | `ui/<feature-name>/`                        |
| A widget reused across screens            | `ui/common/`                                |
| A persisted user preference               | `data/settings/SettingsRepository.kt`       |
| A network client for a new external API   | `data/<service-name>/` (mirror Gemini)      |
| A pure data type used across layers       | `domain/model/`                             |
| A shared singleton dependency             | `di/AppContainer.kt` (and construct it there) |
| A theme value (color, font, shape)        | `ui/theme/`                                 |

---

## 3. Design Guide

The app's look should feel calm, fast, and unambiguous. We use **Material 3** with a small custom palette.

### 3.1 Visual principles

- **Clarity over decoration.** Every screen should answer "what can I do here?" within 1 second of opening it.
- **One primary action per screen.** Generate, then Approve. Never two equally-weighted buttons competing.
- **Generous spacing.** 16dp horizontal padding, 16dp between major elements, 8dp between tightly related ones.
- **Restrained color.** Primary blue is for actions and emphasis only. Backgrounds stay neutral.

### 3.2 Color tokens — defined in `ui/theme/Color.kt`

| Token             | Light      | Dark       | Use                                           |
|-------------------|------------|------------|-----------------------------------------------|
| `primary`         | `#1F6FEB`  | `#6CA8FF`  | Primary buttons, active states, selected tabs |
| `onPrimary`       | `#FFFFFF`  | `#002457`  | Text/icons on primary                         |
| `surface`         | `#FAFAFA`  | `#121212`  | Screen background                             |
| `onSurface`       | `#1A1A1A`  | `#E6E6E6`  | Primary text                                  |
| `surfaceVariant`  | `#EDEDED`  | `#1E1E1E`  | Card backgrounds, elevated containers         |
| `outline`         | `#D0D0D0`  | `#3A3A3A`  | Borders, dividers, disabled state             |

Always reference colors via `MaterialTheme.colorScheme.X`. Never hardcode a `Color(0x...)` inside a screen.

### 3.3 Typography — defined in `ui/theme/Type.kt`

| Style              | Size | Weight    | Use                                 |
|--------------------|------|-----------|-------------------------------------|
| `headlineMedium`   | 24   | SemiBold  | Card front (English word)           |
| `titleLarge`       | 18   | Medium    | Chinese characters, screen titles   |
| `bodyLarge`        | 16   | Normal    | Jyutping, primary body              |
| `bodyMedium`       | 14   | Normal    | Examples, secondary body            |
| `labelLarge`       | 14   | Medium    | Buttons                             |

Use `MaterialTheme.typography.X`. Don't apply ad-hoc `fontSize = X.sp` in a Composable.

### 3.4 Spacing scale

`4dp · 8dp · 12dp · 16dp · 24dp · 32dp` — pick from this list, don't invent values like `13dp`.

- 4 / 8: tight intra-component
- 12 / 16: between related elements
- 24 / 32: between unrelated sections, screen edges

### 3.5 Components

- **Buttons**: filled `Button` for the primary action, `OutlinedButton` for secondary, `TextButton` for tertiary/destructive.
- **Inputs**: always `OutlinedTextField` with a `label`. Single-line unless multi-line is justified.
- **Cards**: use Material 3 `Card` with `surfaceVariant` background. No custom shadows.
- **Loading**: inline `CircularProgressIndicator` inside the button that triggered the work, not a full-screen spinner.
- **Errors / success**: snackbars via `SnackbarHostState`, dismissed automatically.

### 3.6 Motion

- Default Compose animations only (e.g. `animateContentSize`). No bespoke spring tuning until a screen genuinely needs it.
- Never animate something the user is trying to read.

### 3.7 Accessibility

- Every `IconButton` and `Icon` must have a `contentDescription`. Decorative-only icons get `null` explicitly.
- Tap targets: at least 48dp.
- Don't rely on color alone to convey state — pair with text or an icon.

---

## 4. Decision Log

A short append-only log of architectural choices and the reasoning. New entries at the top.

- **2026-04-25** — Manual DI via `AppContainer` instead of Hilt. Reason: app is small, Hilt adds compile-time complexity (KSP, Gradle plugin, generated code) that isn't worth it yet. Revisit if the container exceeds ~15 dependencies.
- **2026-04-25** — `AnkiDroidRepository` shipped as a stub. Reason: the AnkiDroid API is `ContentProvider`-based and needs runtime permission handling + a small dependency on `com.ichi2.anki.api`. Wiring the rest of the app first lets us prove the Gemini path end-to-end before bringing AnkiDroid in.
- **2026-04-25** — Single-Activity, Compose-only, Navigation-Compose. No Fragments, no XML layouts. Reason: simpler mental model, modern stack.
- **2026-04-25** — `kotlinx.serialization` over Moshi/Gson. Reason: first-party Kotlin, no reflection, plays well with multiplatform if we ever want it.
