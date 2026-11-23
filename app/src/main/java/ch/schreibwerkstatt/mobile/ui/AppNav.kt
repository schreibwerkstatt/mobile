package ch.schreibwerkstatt.mobile.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ch.schreibwerkstatt.mobile.BuildConfig
import ch.schreibwerkstatt.mobile.R
import ch.schreibwerkstatt.mobile.ServiceLocator
import ch.schreibwerkstatt.mobile.ui.books.BooksScreen
import ch.schreibwerkstatt.mobile.ui.components.UpdateDialog
import ch.schreibwerkstatt.mobile.ui.editor.EditorScreen
import ch.schreibwerkstatt.mobile.ideen.IdeaLaunch
import ch.schreibwerkstatt.mobile.ui.history.HistoryScreen
import ch.schreibwerkstatt.mobile.ui.ideen.IdeeCaptureScreen
import ch.schreibwerkstatt.mobile.ui.ideen.IdeePlace
import ch.schreibwerkstatt.mobile.ui.ideen.IdeenListScreen
import ch.schreibwerkstatt.mobile.ui.pairing.PairingScreen
import ch.schreibwerkstatt.mobile.ui.research.ResearchCaptureScreen
import ch.schreibwerkstatt.mobile.ui.research.ResearchListScreen
import ch.schreibwerkstatt.mobile.ui.research.SharedResearch
import ch.schreibwerkstatt.mobile.ui.settings.SettingsScreen
import ch.schreibwerkstatt.mobile.ui.tree.TreeScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private object Routes {
    const val PAIRING = "pairing"
    const val BOOKS = "books"
    const val TREE = "tree/{bookId}?title={title}"
    const val EDITOR = "editor/{bookId}/{pageId}?title={title}"
    const val HISTORY = "history/{bookId}/{pageId}?title={title}"
    const val SETTINGS = "settings"
    const val RESEARCH = "research/{bookId}?title={title}"
    // bookId=-1 → keine Vorauswahl (Share-Flow, Buch wird im Formular gewählt).
    // share = Id des Shares, dessen Vorbelegung dieses Formular trägt ("" = keiner).
    const val RESEARCH_CAPTURE = "research_capture?bookId={bookId}&share={share}"
    // pageId=-1 → ganzes Buch; sonst aus dem Editor: auf den Abschnitt gefiltert.
    const val IDEEN = "ideen/{bookId}?title={title}&pageId={pageId}&pageName={pageName}"
    // bookId=-1 → Buch wählt der Screen (zuletzt benutztes); Shortcut/Kachel/Teilen.
    // pageId>0 → Ort vorbelegt (Abschnitt aus dem Editor).
    const val IDEE_CAPTURE = "idee_capture?bookId={bookId}&pageId={pageId}&pageName={pageName}"

    fun tree(bookId: Long, title: String) = "tree/$bookId?title=${Uri.encode(title)}"
    fun editor(bookId: Long, pageId: Long, title: String) =
        "editor/$bookId/$pageId?title=${Uri.encode(title)}"
    fun history(bookId: Long, pageId: Long, title: String) =
        "history/$bookId/$pageId?title=${Uri.encode(title)}"
    fun research(bookId: Long, title: String) = "research/$bookId?title=${Uri.encode(title)}"
    fun researchCapture(bookId: Long = -1L, share: String = "") =
        "research_capture?bookId=$bookId&share=${Uri.encode(share)}"
    fun ideen(bookId: Long, title: String, pageId: Long = -1L, pageName: String = "") =
        "ideen/$bookId?title=${Uri.encode(title)}&pageId=$pageId&pageName=${Uri.encode(pageName)}"
    fun ideeCapture(bookId: Long = -1L, pageId: Long = -1L, pageName: String = "") =
        "idee_capture?bookId=$bookId&pageId=$pageId&pageName=${Uri.encode(pageName)}"
}

@Composable
fun AppNav(
    locator: ServiceLocator,
    sharedResearch: SharedResearch? = null,
    onSharedConsumed: (shareId: String) -> Unit = {},
    ideaLaunch: IdeaLaunch? = null,
    onIdeaLaunchConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val isPaired by locator.tokenStore.isPaired.collectAsStateWithLifecycle()

    // Start-Ziel nur einmal festlegen: ein Wechsel von isPaired darf den NavHost-Graphen
    // nicht neu aufbauen (Backstack/ViewModels gingen verloren). Pairing → Bücher läuft
    // über die onPaired-Navigation, Token-Verlust über den Effekt unten.
    val start = remember { if (isPaired) Routes.BOOKS else Routes.PAIRING }

    // Token-Verlust (401 / Abmelden) → zurück zum Pairing, Backstack leeren.
    LaunchedEffect(isPaired) {
        if (!isPaired) {
            if (navController.currentDestination?.route == Routes.PAIRING) return@LaunchedEffect
            navController.navigate(Routes.PAIRING) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
        } else if (BuildConfig.SELF_UPDATE) {
            // Beim Start einmal still nach einem neuen Release prüfen (nur github-Flavor).
            locator.updateManager.checkOnLaunch()
        }
    }

    // Geteilter Inhalt (ACTION_SEND) → Capture-Screen. Nur wenn gekoppelt; kommt der
    // Share vor dem Pairing an, bleibt er im State und wird nachgeholt, sobald
    // isPaired true wird (Effekt läuft dann erneut).
    //
    // Jeder Share bekommt ein eigenes Formular (Route mit Share-Id) — auch wenn schon eines
    // offen ist; ein offenes «+»-Formular mit Eingaben bleibt darunter erhalten. Die zuletzt
    // geöffnete Share-Id überlebt Konfigurationswechsel, damit derselbe Share nicht doppelt
    // aufgeht.
    var openedShareId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(sharedResearch, isPaired) {
        val share = sharedResearch ?: return@LaunchedEffect
        if (isPaired && share.id != openedShareId) {
            openedShareId = share.id
            navController.navigate(Routes.researchCapture(share = share.id))
        }
    }

    // Ideen-Einstieg von aussen (Shortcut, Kachel, Teilen) → Schnell-Erfassen. Wie beim
    // Recherche-Share: vor dem Pairing bleibt er liegen und wird danach nachgeholt.
    LaunchedEffect(ideaLaunch, isPaired) {
        if (ideaLaunch != null && isPaired) {
            navController.navigate(Routes.ideeCapture()) { launchSingleTop = true }
        }
    }

    // Material-„Shared Axis X": Das Fade trägt den Wechsel, ein kleiner horizontaler
    // Versatz (~30dp) gibt nur die Richtung an (vorwärts/zurück). Kein Slide über die
    // volle Bildschirmbreite mehr — der wirkte zusammen mit dem Fade unruhig.
    val slideDuration = 280
    val slidePx = with(LocalDensity.current) { 30.dp.roundToPx() }
    NavHost(
        navController = navController,
        startDestination = start,
        // Eigener Hintergrund, damit während der Übergänge (Fade) kein Window-Grund durchscheint.
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        enterTransition = {
            slideInHorizontally(tween(slideDuration)) { slidePx } + fadeIn(tween(slideDuration))
        },
        exitTransition = {
            slideOutHorizontally(tween(slideDuration)) { -slidePx } + fadeOut(tween(slideDuration))
        },
        popEnterTransition = {
            slideInHorizontally(tween(slideDuration)) { -slidePx } + fadeIn(tween(slideDuration))
        },
        popExitTransition = {
            slideOutHorizontally(tween(slideDuration)) { slidePx } + fadeOut(tween(slideDuration))
        },
    ) {
        composable(Routes.PAIRING) {
            PairingScreen(onPaired = {
                navController.navigate(Routes.BOOKS) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            })
        }

        composable(Routes.BOOKS) { entry ->
            BooksScreen(
                onOpenBook = { book -> entry.ifResumed { navController.navigate(Routes.tree(book.id, book.name)) } },
                onOpenSettings = { entry.ifResumed { navController.navigate(Routes.SETTINGS) } },
            )
        }

        composable(
            Routes.TREE,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val bookId = entry.arguments?.getLong("bookId") ?: return@composable
            val title = entry.arguments?.getString("title").orEmpty()
            TreeScreen(
                bookId = bookId,
                bookTitle = title,
                onBack = { entry.ifResumed { navController.navigateUp() } },
                onOpenPage = { pageId, pageName ->
                    entry.ifResumed { navController.navigate(Routes.editor(bookId, pageId, pageName)) }
                },
                onOpenHistory = { pageId, pageName ->
                    entry.ifResumed { navController.navigate(Routes.history(bookId, pageId, pageName)) }
                },
                onOpenResearch = { entry.ifResumed { navController.navigate(Routes.research(bookId, title)) } },
                onOpenIdeen = { entry.ifResumed { navController.navigate(Routes.ideen(bookId, title)) } },
            )
        }

        composable(
            Routes.EDITOR,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("pageId") { type = NavType.LongType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val bookId = entry.arguments?.getLong("bookId") ?: return@composable
            val pageId = entry.arguments?.getLong("pageId") ?: return@composable
            val title = entry.arguments?.getString("title").orEmpty()
            EditorScreen(
                bookId = bookId,
                pageId = pageId,
                pageTitle = title.ifBlank { stringResource(R.string.editor_page_fallback_title) },
                onBack = { entry.ifResumed { navController.navigateUp() } },
                onOpenHistory = { historyPageId, pageName ->
                    entry.ifResumed { navController.navigate(Routes.history(bookId, historyPageId, pageName)) }
                },
                onOpenIdeen = { ideenPageId, pageName ->
                    entry.ifResumed { navController.navigate(Routes.ideen(bookId, "", ideenPageId, pageName)) }
                },
                // Wisch zum Nachbar-Abschnitt ersetzt den aktuellen Editor statt ihn zu
                // stapeln — sonst führt Zurück durch jeden durchgeblätterten Abschnitt.
                // Der Schliess-Save des alten Editors läuft wie bei Zurück im onDispose
                // (applicationScope).
                onNavigateToPage = { targetPageId, pageName ->
                    entry.ifResumed {
                        navController.navigate(Routes.editor(bookId, targetPageId, pageName)) {
                            popUpTo(Routes.EDITOR) { inclusive = true }
                        }
                    }
                },
            )
        }

        composable(
            Routes.HISTORY,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("pageId") { type = NavType.LongType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val bookId = entry.arguments?.getLong("bookId") ?: return@composable
            val pageId = entry.arguments?.getLong("pageId") ?: return@composable
            val title = entry.arguments?.getString("title").orEmpty()
            HistoryScreen(
                bookId = bookId,
                pageId = pageId,
                pageTitle = title,
                onBack = { entry.ifResumed { navController.navigateUp() } },
                // Ergebnis eines Netzwerk-Aufrufs, kein Klick: nur prüfen, dass der Verlauf
                // noch oben liegt (RESUMED wäre zu streng, wenn die App kurz im Hintergrund war).
                onRestored = { restoredPageId -> if (navController.currentBackStackEntry == entry) {
                    navController.popBackStack()
                    // Kam der Verlauf aus dem Editor derselben Seite, diesen alten Eintrag
                    // ebenfalls entfernen — sonst liegt die Seite doppelt im Backstack und
                    // Zurück führt auf den veralteten Stand statt in den Baum. Er wird dabei
                    // nicht neu komponiert, löst also keinen Schliess-Save aus.
                    val below = navController.currentBackStackEntry
                    if (below?.destination?.route == Routes.EDITOR &&
                        below.arguments?.getLong("pageId") == restoredPageId
                    ) {
                        navController.popBackStack()
                    }
                    navController.navigate(Routes.editor(bookId, restoredPageId, title))
                } },
            )
        }

        composable(Routes.SETTINGS) { entry ->
            SettingsScreen(
                onBack = { entry.ifResumed { navController.navigateUp() } },
                onSignedOut = { /* isPaired-LaunchedEffect navigiert automatisch */ },
            )
        }

        composable(
            Routes.RESEARCH,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val bookId = entry.arguments?.getLong("bookId") ?: return@composable
            val title = entry.arguments?.getString("title").orEmpty()
            ResearchListScreen(
                bookId = bookId,
                bookTitle = title,
                onBack = { entry.ifResumed { navController.navigateUp() } },
                onAdd = { addBookId -> entry.ifResumed { navController.navigate(Routes.researchCapture(addBookId)) } },
            )
        }

        composable(
            Routes.RESEARCH_CAPTURE,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("share") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val preselect = entry.arguments?.getLong("bookId")?.takeIf { it > 0 }
            val shareId = entry.arguments?.getString("share").orEmpty()
            // Nur das Formular dieses Shares bekommt dessen Vorbelegung.
            val payload = sharedResearch?.takeIf { shareId.isNotEmpty() && it.id == shareId }
            ResearchCaptureScreen(
                shared = payload,
                preselectBookId = preselect,
                // Auch nach dem (asynchronen) Speichern: nur den eigenen Eintrag schliessen.
                onDone = { if (navController.currentBackStackEntry == entry) {
                    if (shareId.isNotEmpty()) onSharedConsumed(shareId)
                    if (navController.previousBackStackEntry != null) {
                        navController.popBackStack()
                    } else {
                        // Share-Kaltstart: kein Backstack → auf die Bücher-Liste.
                        navController.navigate(Routes.BOOKS) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                } },
            )
        }

        composable(
            Routes.IDEEN,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("title") { type = NavType.StringType; defaultValue = "" },
                navArgument("pageId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("pageName") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val bookId = entry.arguments?.getLong("bookId") ?: return@composable
            val title = entry.arguments?.getString("title").orEmpty()
            val sectionId = entry.arguments?.getLong("pageId")?.takeIf { it > 0 }
            val sectionName = entry.arguments?.getString("pageName").orEmpty()
            IdeenListScreen(
                bookId = bookId,
                bookTitle = title,
                sectionId = sectionId,
                sectionName = sectionName,
                onBack = { entry.ifResumed { navController.navigateUp() } },
                onAdd = { addBookId, section ->
                    entry.ifResumed {
                        navController.navigate(
                            Routes.ideeCapture(addBookId, section?.id ?: -1L, section?.name.orEmpty())
                        )
                    }
                },
            )
        }

        composable(
            Routes.IDEE_CAPTURE,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("pageId") { type = NavType.LongType; defaultValue = -1L },
                navArgument("pageName") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val preselect = entry.arguments?.getLong("bookId")?.takeIf { it > 0 }
            val pageId = entry.arguments?.getLong("pageId")?.takeIf { it > 0 }
            val pageName = entry.arguments?.getString("pageName").orEmpty()
            // Nur der Einstieg von aussen (ohne Vorauswahl) bekommt die Vorbelegung.
            val initial = if (preselect == null) ideaLaunch?.text.orEmpty() else ""
            IdeeCaptureScreen(
                preselectBookId = preselect,
                initialText = initial,
                initialPlace = pageId?.let { IdeePlace.Page(it, pageName) } ?: IdeePlace.Book,
                onDone = {
                    if (navController.currentBackStackEntry == entry) {
                        if (preselect == null) onIdeaLaunchConsumed()
                        if (!navController.popBackStack()) {
                            navController.navigate(Routes.BOOKS) { popUpTo(0) { inclusive = true } }
                        }
                    }
                },
            )
        }
    }

    // Update-Dialog über allen Screens (Auto-Check beim Start + manueller Check in Settings).
    // Nur im github-Flavor; im Play-Build ist SELF_UPDATE eine Compile-Zeit-Konstante `false`,
    // sodass R8 den gesamten Update-Pfad aus dem Artefakt wirft.
    if (BuildConfig.SELF_UPDATE) {
        UpdateHost(locator)
    }
}

/**
 * Führt [block] nur aus, solange dieser Eintrag RESUMED ist. Fängt Doppel-Taps und
 * Klicks während einer laufenden Übergangs-Animation ab, die sonst doppelt
 * navigieren bzw. mehr als einen Eintrag poppen (bis hin zum leeren Backstack).
 */
private inline fun NavBackStackEntry.ifResumed(block: () -> Unit) {
    if (lifecycle.currentState == Lifecycle.State.RESUMED) block()
}

/** Sammelt den Update-State und zeigt den Dialog. Nur aus dem github-Flavor erreichbar. */
@Composable
private fun UpdateHost(locator: ServiceLocator) {
    val updateState by locator.updateManager.state.collectAsStateWithLifecycle()
    UpdateDialog(
        state = updateState,
        onDownload = {
            (updateState as? ch.schreibwerkstatt.mobile.update.UpdateState.Available)?.let {
                locator.updateManager.download(it.release)
            }
        },
        onRetryInstall = { locator.updateManager.retryInstall() },
        onOpenPermissionSettings = { locator.updateManager.openInstallPermissionSettings() },
        onDismiss = { locator.updateManager.dismiss() },
    )
}
