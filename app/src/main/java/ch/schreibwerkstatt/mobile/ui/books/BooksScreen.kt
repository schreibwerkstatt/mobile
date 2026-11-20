package ch.schreibwerkstatt.mobile.ui.books

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.schreibwerkstatt.mobile.R
import ch.schreibwerkstatt.mobile.data.db.BookEntity
import ch.schreibwerkstatt.mobile.locator
import ch.schreibwerkstatt.mobile.ui.components.SearchField
import ch.schreibwerkstatt.mobile.ui.components.SkeletonList
import ch.schreibwerkstatt.mobile.ui.components.SyncStatusBar
import ch.schreibwerkstatt.mobile.ui.components.text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BooksScreen(
    onOpenBook: (BookEntity) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val coordinator = remember(context) { context.locator.syncCoordinator }
    val vm: BooksViewModel = viewModel(factory = BooksViewModel.factory(context.locator))
    val cachedBooks by vm.books.collectAsStateWithLifecycle()
    val books = cachedBooks.orEmpty()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val online by coordinator.online.collectAsStateWithLifecycle()
    val pendingCount by coordinator.pendingCount.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    var query by rememberSaveable { mutableStateOf("") }
    // Zurück leert zuerst die Suche, statt den Screen zu verlassen.
    BackHandler(enabled = query.isNotEmpty()) { query = "" }
    val filteredBooks by remember(books) {
        derivedStateOf {
            val q = query.trim()
            if (q.isEmpty()) books
            else books.filter { it.name.contains(q, ignoreCase = true) }
        }
    }

    // Snackbar nur, wenn Bücher sichtbar sind — ohne Cache zeigt der Leer-Zustand den Fehler.
    val messageText = message?.text()
    val loadErrorTemplate = stringResource(R.string.books_load_error)
    LaunchedEffect(message) {
        if (message != null) {
            if (books.isNotEmpty() && messageText != null) {
                snackbarHostState.showSnackbar(loadErrorTemplate.format(messageText))
            }
            vm.consumeMessage()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                TopAppBar(
                    title = { Text(stringResource(R.string.books_title)) },
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
                SyncStatusBar(online = online, pendingCount = pendingCount)
                if (books.isNotEmpty()) {
                    SearchField(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = stringResource(R.string.books_search_hint),
                    )
                }
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { vm.refresh(); coordinator.flushNow() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (cachedBooks == null || (books.isEmpty() && refreshing)) {
                SkeletonList(Modifier.fillMaxSize())
            } else if (books.isEmpty() && loadError != null) {
                // Offline/Fehler ohne Cache: kein „Keine Bücher", sondern „nicht geladen".
                CenteredState(
                    icon = Icons.Filled.CloudOff,
                    title = stringResource(R.string.books_not_loaded),
                    detail = loadError?.text(),
                    onRetry = { vm.refresh(); coordinator.flushNow() },
                )
            } else if (books.isEmpty()) {
                CenteredState(
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    title = stringResource(R.string.books_empty),
                    detail = stringResource(R.string.books_empty_hint),
                )
            } else if (filteredBooks.isEmpty()) {
                CenteredState(detail = stringResource(R.string.search_no_results, query.trim()))
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .consumeWindowInsets(padding)
                        .imePadding(),
                ) {
                    items(filteredBooks, key = { it.id }) { book ->
                        ListItem(
                            headlineContent = { Text(book.name) },
                            supportingContent = roleLabel(book.role)?.let { { Text(it) } },
                            leadingContent = {
                                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                            },
                            modifier = Modifier.clickable(
                                onClickLabel = stringResource(R.string.a11y_action_open),
                                role = Role.Button,
                            ) { onOpenBook(book) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** Lokalisierte Rolle eines geteilten Buchs; Eigentümer und unbekannte Rollen → nichts. */
@Composable
private fun roleLabel(role: String?): String? = when (role) {
    "editor" -> stringResource(R.string.role_editor)
    "lektor" -> stringResource(R.string.role_lektor)
    "viewer" -> stringResource(R.string.role_viewer)
    else -> null
}

/**
 * Zentrierter Leer-/Fehlerzustand als scrollbare Liste mit einem Eintrag — nur so
 * nimmt die umgebende PullToRefreshBox die Zieh-Geste an (sie braucht Nested Scroll).
 */
@Composable
private fun CenteredState(
    detail: String?,
    icon: ImageVector? = null,
    title: String? = null,
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
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (title != null) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (detail != null) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (onRetry != null) {
                        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                    }
                }
            }
        }
    }
}
