package com.borderless.ankicards.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.borderless.ankicards.di.AppContainer
import com.borderless.ankicards.ui.builder.CardDesignerScreen
import com.borderless.ankicards.ui.builder.NoteTypeBuilderViewModel
import com.borderless.ankicards.ui.builder.NoteTypeListScreen
import com.borderless.ankicards.ui.builder.NoteTypeListViewModel
import com.borderless.ankicards.ui.decks.DeckListScreen
import com.borderless.ankicards.ui.decks.DeckListViewModel
import com.borderless.ankicards.ui.generator.CardGeneratorScreen
import com.borderless.ankicards.ui.generator.CardGeneratorViewModel
import com.borderless.ankicards.ui.settings.SettingsScreen
import com.borderless.ankicards.ui.settings.SettingsViewModel
import com.borderless.ankicards.ui.staging.InboxDetailScreen
import com.borderless.ankicards.ui.staging.StagingScreen
import com.borderless.ankicards.ui.staging.StagingViewModel
import com.borderless.ankicards.ui.wordlist.WordlistScreen
import com.borderless.ankicards.ui.wordlist.WordlistViewModel
import java.net.URLEncoder

object Routes {
    // GENERATOR accepts an optional ?word=... pre-fill from the wordlist screen.
    const val GENERATOR = "generator?word={word}"
    const val SETTINGS = "settings"
    const val INBOX = "inbox"
    const val INBOX_DETAIL = "inbox/{itemId}"
    const val WORDLIST = "wordlist"
    const val CARD_TYPES = "card-types"
    const val DECKS = "decks"
    // Builder edits ONE design (template) of a note type:
    //  - ?id=<noteTypeId>&template=<templateId>  → edit that design
    //  - ?id=<noteTypeId>                         → add a new design to it
    //  - ?preset=<preset-name>                    → create the note type for a
    //                                               language (its first design)
    // All null = blank new note type.
    const val CARD_BUILDER = "card-builder?id={id}&template={template}&preset={preset}"
    fun inboxDetail(itemId: String) = "inbox/$itemId"
    fun generatorForWord(word: String) =
        "generator?word=" + URLEncoder.encode(word, Charsets.UTF_8.name())
    /** Edit an existing design (template) within its note type. */
    fun editDesign(noteTypeId: String, templateId: String) =
        "card-builder?id=$noteTypeId&template=$templateId&preset="
    /** Add a new design to an existing note type. */
    fun newDesign(noteTypeId: String) = "card-builder?id=$noteTypeId&template=&preset="
    fun cardBuilderForPreset(preset: String) = "card-builder?id=&template=&preset=$preset"
    fun cardBuilderBlank() = "card-builder?id=&template=&preset="
}

@Composable
fun AppNavHost(
    navController: NavHostController,
    container: AppContainer,
    modifier: Modifier = Modifier
) {
    // ── Generator ViewModel: scoped to the activity so it survives tab
    // switches but dies on app restart (no SavedStateHandle, pure in-memory
    // MutableStateFlow). Previously scoped to the composable back-stack
    // entry, which meant navigating to another tab destroyed the VM and
    // the user lost a generated-but-not-yet-saved card.
    val generatorVm: CardGeneratorViewModel = viewModel(
        factory = CardGeneratorViewModel.factory(
            cardGenerator = container.cardGenerator,
            noteTypes = container.noteTypeRepository,
            anki = container.ankiDroidRepository,
            settings = container.settingsRepository,
            deckLanguages = container.deckLanguageRepository,
            deckNoteTypeChoice = container.deckNoteTypeChoiceRepository,
            wordlist = container.wordlistRepository
        )
    )

    // Snappy horizontal slide + light fade. Replaces the default cross-fade,
    // which made title bars overlap visibly during a transition.
    val durationMs = 180
    val slideFraction = 6 // smaller = subtler offset

    NavHost(
        navController = navController,
        modifier = modifier,
        startDestination = "generator",
        enterTransition = {
            slideInHorizontally(animationSpec = tween(durationMs)) { it / slideFraction } +
                fadeIn(animationSpec = tween(durationMs))
        },
        exitTransition = {
            slideOutHorizontally(animationSpec = tween(durationMs)) { -it / slideFraction } +
                fadeOut(animationSpec = tween(durationMs))
        },
        popEnterTransition = {
            slideInHorizontally(animationSpec = tween(durationMs)) { -it / slideFraction } +
                fadeIn(animationSpec = tween(durationMs))
        },
        popExitTransition = {
            slideOutHorizontally(animationSpec = tween(durationMs)) { it / slideFraction } +
                fadeOut(animationSpec = tween(durationMs))
        }
    ) {
        composable(
            route = Routes.GENERATOR,
            arguments = listOf(navArgument("word") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) { backStack ->
            // If opened from the wordlist with a pre-filled word, push it
            // into the (activity-scoped) VM. LaunchedEffect keyed on the
            // word ensures it only fires once per navigation, not on
            // recomposition.
            val initialWord = backStack.arguments?.getString("word")?.takeIf { it.isNotBlank() }
            LaunchedEffect(initialWord) {
                if (initialWord != null) {
                    generatorVm.prefillWord(initialWord)
                }
            }
            CardGeneratorScreen(
                viewModel = generatorVm,
                queueRepository = container.queueRepository,
                onNavigateToStaging = { navController.navigate(Routes.INBOX) },
                onNavigateToWordlist = { navController.navigate(Routes.WORDLIST) }
            )
        }
        composable(Routes.DECKS) {
            val vm: DeckListViewModel = viewModel(
                factory = DeckListViewModel.factory(
                    container.ankiDroidRepository,
                    container.settingsRepository,
                    container.deckLanguageRepository
                )
            )
            DeckListScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.CARD_TYPES) {
            val vm: NoteTypeListViewModel = viewModel(
                factory = NoteTypeListViewModel.factory(
                    noteTypes = container.noteTypeRepository,
                    anki = container.ankiDroidRepository,
                    deckLanguages = container.deckLanguageRepository,
                    settings = container.settingsRepository
                )
            )
            NoteTypeListScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onEditDesign = { noteTypeId, templateId ->
                    navController.navigate(Routes.editDesign(noteTypeId, templateId))
                },
                onAddDesign = { noteTypeId ->
                    navController.navigate(Routes.newDesign(noteTypeId))
                },
                onCreateBlank = { navController.navigate(Routes.cardBuilderBlank()) },
                onCreateFromPreset = { preset ->
                    navController.navigate(Routes.cardBuilderForPreset(preset))
                }
            )
        }
        composable(
            route = Routes.CARD_BUILDER,
            arguments = listOf(
                navArgument("id") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("template") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
                navArgument("preset") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStack ->
            val id = backStack.arguments?.getString("id")?.takeIf { it.isNotBlank() }
            val templateId = backStack.arguments?.getString("template")?.takeIf { it.isNotBlank() }
            val preset = backStack.arguments?.getString("preset")?.takeIf { it.isNotBlank() }
            val vm: NoteTypeBuilderViewModel = viewModel(
                key = "builder-${id ?: "new"}-${templateId ?: "first"}-${preset ?: "blank"}",
                factory = NoteTypeBuilderViewModel.factory(
                    repo = container.noteTypeRepository,
                    anki = container.ankiDroidRepository,
                    noteTypeId = id,
                    templateId = templateId,
                    presetName = preset
                )
            )
            CardDesignerScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.WORDLIST) {
            val vm: WordlistViewModel = viewModel(
                factory = WordlistViewModel.factory(
                    container.wordlistRepository,
                    container.geminiRepository
                )
            )
            WordlistScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onGenerateCard = { word ->
                    navController.navigate(Routes.generatorForWord(word))
                }
            )
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(
                factory = SettingsViewModel.factory(container.settingsRepository)
            )
            SettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() }
            )
        }
        composable(Routes.INBOX) {
            val vm: StagingViewModel = viewModel(
                factory = StagingViewModel.factory(
                    container.queueRepository,
                    container.ankiDroidRepository
                )
            )
            StagingScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onItemClick = { id -> navController.navigate(Routes.inboxDetail(id)) }
            )
        }
        composable(
            route = Routes.INBOX_DETAIL,
            arguments = listOf(navArgument("itemId") { type = NavType.StringType })
        ) { backStack ->
            val itemId = backStack.arguments?.getString("itemId").orEmpty()
            val vm: StagingViewModel = viewModel(
                factory = StagingViewModel.factory(
                    container.queueRepository,
                    container.ankiDroidRepository
                )
            )
            InboxDetailScreen(
                viewModel = vm,
                itemId = itemId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
