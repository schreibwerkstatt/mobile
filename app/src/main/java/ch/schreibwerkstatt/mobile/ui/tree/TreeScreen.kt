package ch.schreibwerkstatt.mobile.ui.tree

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.schreibwerkstatt.mobile.R
import ch.schreibwerkstatt.mobile.locator
import ch.schreibwerkstatt.mobile.ui.components.SearchField
import ch.schreibwerkstatt.mobile.ui.components.SkeletonList
import ch.schreibwerkstatt.mobile.ui.components.SyncStatusBar
import ch.schreibwerkstatt.mobile.ui.components.text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TreeScreen(
    bookId: Long,
    bookTitle: String,
    onBack: () -> Unit,
    onOpenPage: (pageId: Long, pageName: String) -> Unit,
    onOpenHistory: (pageId: Long, pageName: String) -> Unit,
    onOpenResearch: () -> Unit,
) {
    val context = LocalContext.current
    val coordinator = remember(context) { context.locator.syncCoordinator }
    val vm: TreeViewModel = viewModel(factory = TreeViewModel.factory(context.locator, bookId))
    val state by vm.state.collectAsStateWithLifecycle()
    val online by coordinator.online.collectAsStateWithLifecycle()
    val pendingCount by coordinator.pendingCount.collectAsStateWithLifecycle()
    val swipeToHistory by remember(context) { context.locator.settings.swipeToHistory }
        .collectAsStateWithLifecycle(initialValue = true)
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    var query by rememberSaveable { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    // Kalender ist aktiv, wenn Buch ein Tagebuch ist UND der Kalender-Modus läuft.
    val calendar = state.isDiary && state.calendarMode
    val q = query.trim()
    val searchActive = q.isNotEmpty()

    // Zurück leert zuerst die Suche, statt den Screen zu verlassen.
    BackHandler(enabled = query.isNotEmpty()) { query = "" }

    // Öffnen-Ereignisse des ViewModels (Tagebuch-Eintrag geöffnet/angelegt).
    val currentOnOpenPage by rememberUpdatedState(onOpenPage)
    LaunchedEffect(vm) {
        vm.openPage.collect { currentOnOpenPage(it.pageId, it.pageName) }
    }

    // Inhalts-Volltextsuche (Room-FTS) anstossen; Titel-Filterung bleibt sofort im UI.
    LaunchedEffect(q) { vm.search(q) }

    // Bei aktiver Suche flach: Titeltreffer (sofort) + Inhaltstreffer (FTS) zusammenführen,
    // ohne Duplikate; Titeltreffer zuerst. Snippet kommt vom Inhaltstreffer, falls vorhanden.
    val searchHits by remember(state.rows, state.contentHits, q) {
        derivedStateOf {
            if (!searchActive) emptyList()
            else {
                val snippetById = state.contentHits.associate { it.id to it.snippet }
                val merged = LinkedHashMap<Long, SearchHit>()
                state.rows.filterIsInstance<TreeRow.Page>()
                    .filter { it.name.contains(q, ignoreCase = true) }
                    .forEach { merged[it.id] = SearchHit(it.id, it.name, snippetById[it.id]) }
                state.contentHits.forEach { hit ->
                    if (!merged.containsKey(hit.id)) {
                        merged[hit.id] = SearchHit(hit.id, hit.name.orEmpty(), hit.snippet)
                    }
                }
                merged.values.toList()
            }
        }
    }

    // Einmalige Meldung: Eintrag nicht angelegt, oder Neuladen fehlgeschlagen und es
    // bleibt beim zuletzt geladenen Stand (Inhalt bleibt sichtbar, kein Vollbild-Fehler).
    val messageText = when (val m = state.message) {
        is TreeMessage.CreateFailed -> stringResource(R.string.calendar_create_error)
        is TreeMessage.StaleTree -> stringResource(R.string.tree_cached_hint, m.error.text())
        null -> null
    }
    LaunchedEffect(state.message) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            vm.consumeMessage()
        }
    }
    val errorText = state.error?.text()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = { Text(bookTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenResearch) {
                            Icon(
                                Icons.Filled.TravelExplore,
                                contentDescription = stringResource(R.string.research_title),
                            )
                        }
                        if (state.isDiary) {
                            IconButton(onClick = { vm.setCalendarMode(!state.calendarMode) }) {
                                if (state.calendarMode) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ViewList,
                                        contentDescription = stringResource(R.string.calendar_show_list),
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.CalendarMonth,
                                        contentDescription = stringResource(R.string.calendar_show),
                                    )
                                }
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                SyncStatusBar(online = online, pendingCount = pendingCount)
                if (!calendar && state.rows.isNotEmpty()) {
                    SearchField(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = stringResource(R.string.tree_search_hint),
                    )
                }
            }
        },
    ) { padding ->
        when {
            // Kalender hat eigene Monatsnavigation + Scroll → kein Pull-to-Refresh/Skeleton.
            calendar -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                DiaryCalendar(
                    month = state.calendarMonth,
                    entries = state.diaryEntries,
                    creating = state.creatingEntry,
                    loading = state.loading,
                    error = errorText,
                    onRetry = { vm.load() },
                    onPrevMonth = { vm.stepMonth(-1) },
                    onNextMonth = { vm.stepMonth(1) },
                    // Während der Baum lädt, ist ein leerer Tag noch keine Aussage — ein Tipp
                    // liefe sonst in den Anlege-Pfad. Bekannte (gecachte) Einträge öffnen geht.
                    onDayClick = { dateIso ->
                        if (!state.loading || dateIso in state.diaryEntries) {
                            vm.openOrCreateEntry(dateIso)
                        }
                    },
                    onTodayClick = {
                        val todayIso = java.time.LocalDate.now().toString()
                        if (!state.loading || todayIso in state.diaryEntries) vm.openOrCreateEntry(todayIso)
                    },
                )
            }

            else -> PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = { vm.refresh(); coordinator.flushNow() },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when {
                    state.loading && state.rows.isEmpty() ->
                        SkeletonList(Modifier.fillMaxSize())

                    state.error != null && state.rows.isEmpty() -> CenteredState(
                        icon = Icons.Filled.ErrorOutline,
                        text = stringResource(R.string.tree_load_error, errorText.orEmpty()),
                        isError = true,
                        onRetry = { vm.load() },
                    )

                    // Leeres Buch: geladen, ohne Fehler, aber ohne Kapitel/Abschnitte.
                    !state.loading && state.rows.isEmpty() -> CenteredState(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        text = stringResource(R.string.tree_empty),
                    )

                    searchActive && searchHits.isEmpty() -> CenteredState(
                        text = stringResource(R.string.search_no_results, q),
                    )

                    // Suche aktiv: flache Trefferliste (Titel + Inhalt) mit Snippet.
                    searchActive -> LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .consumeWindowInsets(padding)
                            .imePadding(),
                    ) {
                        items(searchHits, key = { "p${it.id}" }) { hit ->
                            SwipeablePageRow(
                                row = TreeRow.Page(hit.id, hit.name, depth = 0),
                                indent = 0.dp,
                                updatedIso = state.pageUpdatedAt[hit.id],
                                snippet = hit.snippet,
                                onOpenPage = onOpenPage,
                                onOpenHistory = onOpenHistory,
                                swipeEnabled = swipeToHistory,
                            )
                        }
                    }

                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        itemsIndexed(state.rows, key = { i, row -> rowKey(row, i) }) { _, row ->
                            val indent = (row.depth * 16).dp
                            when (row) {
                                is TreeRow.Chapter -> ListItem(
                                    headlineContent = {
                                        Text(row.name, fontWeight = FontWeight.SemiBold)
                                    },
                                    modifier = Modifier.padding(start = indent),
                                )
                                is TreeRow.Page -> SwipeablePageRow(
                                    row = row,
                                    indent = indent,
                                    updatedIso = state.pageUpdatedAt[row.id],
                                    onOpenPage = onOpenPage,
                                    onOpenHistory = onOpenHistory,
                                    swipeEnabled = swipeToHistory,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Anteil der Zeilenbreite, der für „Verlauf öffnen" gezogen werden muss. */
private const val HISTORY_SWIPE_FRACTION = 0.45f

/** Ein Suchtreffer (Titel- oder Inhaltstreffer) als flache Zeile mit optionalem Snippet. */
private data class SearchHit(val id: Long, val name: String, val snippet: String?)

/** Stabiler LazyColumn-Key je Zeile (Seiten über ID, Kapitel über Index+Name). */
private fun rowKey(row: TreeRow, index: Int): String = when (row) {
    is TreeRow.Page -> "p${row.id}"
    is TreeRow.Chapter -> "c$index-${row.name}"
}

/**
 * Seitenzeile mit Wisch-Geste: nach links wischen legt die „Verlauf"-Aktion frei
 * und öffnet die Versionshistorie der Seite. Es wird nie wirklich „dismissed" –
 * nach Auslösen schnappt die Zeile zurück (Reveal-Aktion, kein Löschen).
 *
 * Die Geste löst bewusst schwer aus: erst ab [HISTORY_SWIPE_FRACTION] der Zeilenbreite,
 * und ein schneller kurzer Wisch allein genügt nicht (der Velocity-Pfad des
 * Material-`SwipeToDismissBox` wird über die tatsächlich gezogene Strecke abgefangen),
 * damit ein schräges Scrollen nicht im Verlauf landet. Über das Settings-Flag
 * `swipeToHistory` ([swipeEnabled]) ist sie ganz abschaltbar; der Verlauf bleibt
 * dann über den Editor erreichbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeablePageRow(
    row: TreeRow.Page,
    indent: Dp,
    updatedIso: String?,
    onOpenPage: (pageId: Long, pageName: String) -> Unit,
    onOpenHistory: (pageId: Long, pageName: String) -> Unit,
    swipeEnabled: Boolean,
    /** Markup-freier Treffer-Snippet (nur im Suchmodus); ersetzt dann die „zuletzt geändert"-Zeile. */
    snippet: String? = null,
) {
    var rowWidthPx by remember { mutableFloatStateOf(0f) }
    // confirmValueChange wird nur einmal gemerkt — aktuelle Zeile/Callback darum über
    // rememberUpdatedState lesen (die Zeile kann bei gleichem Key neu benannt werden).
    val currentRow by rememberUpdatedState(row)
    val currentOnOpenHistory by rememberUpdatedState(onOpenHistory)
    // confirmValueChange braucht den eigenen State (Offset), der erst danach existiert.
    val stateRef = remember { arrayOfNulls<SwipeToDismissBoxState>(1) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                val dragged = stateRef[0]?.let { runCatching { -it.requireOffset() }.getOrNull() } ?: 0f
                if (rowWidthPx > 0f && dragged >= rowWidthPx * HISTORY_SWIPE_FRACTION) {
                    currentOnOpenHistory(currentRow.id, currentRow.name)
                }
            }
            false
        },
        positionalThreshold = { total -> total * HISTORY_SWIPE_FRACTION },
    )
    stateRef[0] = dismissState
    SwipeToDismissBox(
        state = dismissState,
        modifier = Modifier.onSizeChanged { rowWidthPx = it.width.toFloat() },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = swipeEnabled,
        gesturesEnabled = swipeEnabled,
        backgroundContent = {
            Row(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.secondaryContainer)
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Icon(
                    Icons.Filled.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.history_title),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        },
    ) {
        val snip = snippet?.takeIf { it.isNotBlank() }
        val updated = formatUpdatedAt(updatedIso, rememberUpdatedAtFormatter())
        val historyLabel = stringResource(R.string.a11y_action_open_history)
        ListItem(
            headlineContent = { Text(row.name) },
            supportingContent = {
                Text(
                    snip
                        ?: updated?.let { stringResource(R.string.tree_page_updated, it) }
                        ?: stringResource(R.string.tree_page_updated_unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            leadingContent = {
                Icon(Icons.AutoMirrored.Filled.Article, contentDescription = null)
            },
            modifier = Modifier
                .padding(start = indent)
                .clickable(
                    onClickLabel = stringResource(R.string.a11y_action_open),
                    role = Role.Button,
                ) { onOpenPage(row.id, row.name) }
                // TalkBack hat keine Wisch-Geste: Verlauf als eigene Aktion anbieten.
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(historyLabel) {
                            onOpenHistory(row.id, row.name)
                            true
                        }
                    )
                },
        )
    }
}

/**
 * Formatter für „zuletzt geändert", an die aktuelle Konfiguration gebunden: ein
 * Sprachwechsel zur Laufzeit (App-Sprache/System) baut ihn neu, statt die Locale
 * des Prozessstarts einzufrieren.
 */
@Composable
private fun rememberUpdatedAtFormatter(): DateTimeFormatter {
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        val locale = ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault()
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(ZoneId.systemDefault())
    }
}

/** Lokalisiertes Anzeigedatum aus dem ISO-8601-`updated_at` des Servers; null = unbekannt/unparsebar. */
private fun formatUpdatedAt(iso: String?, formatter: DateTimeFormatter): String? =
    iso?.let { runCatching { formatter.format(Instant.parse(it)) }.getOrNull() }

/**
 * Zentrierter Leer-/Fehlerzustand als scrollbare Liste mit einem Eintrag — nur so
 * nimmt die umgebende PullToRefreshBox die Zieh-Geste an (sie braucht Nested Scroll).
 */
@Composable
private fun CenteredState(
    text: String,
    icon: ImageVector? = null,
    isError: Boolean = false,
    onRetry: (() -> Unit)? = null,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(
                Modifier.fillParentMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (icon != null) {
                        Icon(
                            icon,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = if (isError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    if (onRetry != null) {
                        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                    }
                }
            }
        }
    }
}
