package ch.schreibwerkstatt.mobile.ui.research

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.schreibwerkstatt.mobile.R
import ch.schreibwerkstatt.mobile.data.db.ResearchOutboxEntity
import ch.schreibwerkstatt.mobile.data.net.ResearchApi
import ch.schreibwerkstatt.mobile.data.net.dto.ResearchItemDto
import ch.schreibwerkstatt.mobile.data.repo.QueuedResearch
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import ch.schreibwerkstatt.mobile.data.net.dto.ResearchUrlDto
import ch.schreibwerkstatt.mobile.locator
import ch.schreibwerkstatt.mobile.ui.components.formatServerTime
import ch.schreibwerkstatt.mobile.ui.components.message
import androidx.compose.material3.Button
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

/**
 * Recherche-Board eines Buchs (Liste). Öffnet Links im Browser (Custom Tab) und
 * bietet über das FAB die manuelle Erfassung eines neuen Items für dieses Buch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResearchListScreen(
    bookId: Long,
    bookTitle: String,
    onBack: () -> Unit,
    onAdd: (bookId: Long) -> Unit,
) {
    val context = LocalContext.current
    val vm: ResearchListViewModel = viewModel(factory = ResearchListViewModel.factory(context.locator, bookId))
    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val loadFailed by vm.loadFailed.collectAsStateWithLifecycle()
    val queued by vm.queued.collectAsStateWithLifecycle()

    // Bei jedem Sichtbarwerden neu laden — auch nach der Rückkehr aus „+ Erfassen",
    // sonst fehlt das gerade gespeicherte Element (das VM lebt im Backstack weiter).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val snackbarHostState = remember { SnackbarHostState() }
    // Ausgewähltes Element für die Detail-Ansicht (Klick auf eine Zeile) — als Id,
    // damit die offene Ansicht eine Drehung übersteht.
    var detailId by rememberSaveable { mutableStateOf<Long?>(null) }

    val errorTemplate = stringResource(R.string.research_load_error)
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(errorTemplate.format(it.message(context.resources)))
            vm.clearError()
        }
    }
    val scope = rememberCoroutineScope()
    val openFailedMsg = stringResource(R.string.research_open_failed)
    val onOpenUrl: (String) -> Unit = { url ->
        if (!openUrl(context, url)) scope.launch { snackbarHostState.showSnackbar(openFailedMsg) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(bookTitle.ifBlank { stringResource(R.string.research_title) }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onAdd(bookId) }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.research_add))
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = loading,
            onRefresh = { vm.refresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Fehler- und Leerzustand als scrollbare Liste: Pull-to-Refresh reagiert nur
            // auf scrollbaren Inhalt.
            if (items.isEmpty() && queued.isEmpty() && loaded && loadFailed) {
                CenteredScrollable {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.research_load_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = { vm.refresh() }, enabled = !loading) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }
            } else if (items.isEmpty() && queued.isEmpty() && loaded) {
                CenteredScrollable {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Filled.TravelExplore,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.research_empty),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            stringResource(R.string.research_empty_hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 88.dp),
                ) {
                    items(queued, key = { "q${it.localId}" }) { q ->
                        QueuedRow(
                            q,
                            onKeep = { vm.keepDuplicate(q.localId) },
                            onRetry = { vm.retryQueued(q.localId) },
                            onDiscard = { vm.discardQueued(q.localId) },
                        )
                        HorizontalDivider()
                    }
                    items(items, key = { it.id }) { item ->
                        ResearchRow(item, onClick = { detailId = item.id })
                        HorizontalDivider()
                    }
                    // Der Server liefert höchstens LIST_LIMIT — ältere fehlen dann hier.
                    if (items.size >= ResearchApi.LIST_LIMIT) {
                        item(key = "limit") {
                            Text(
                                stringResource(R.string.research_list_limited, ResearchApi.LIST_LIMIT),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    items.firstOrNull { it.id == detailId }?.let { item ->
        ResearchDetailSheet(
            item = item,
            onOpenUrl = onOpenUrl,
            onDismiss = { detailId = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResearchDetailSheet(
    item: ResearchItemDto,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val title = item.title?.takeIf { it.isNotBlank() }
        ?: item.urls.firstOrNull()?.url
        ?: item.displayBody
        ?: stringResource(R.string.research_untitled)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)

            if (item.pinned != 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        stringResource(R.string.research_detail_pinned),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }

            item.displayBody?.let { body ->
                DetailSection(stringResource(R.string.research_detail_note)) {
                    Text(body, style = MaterialTheme.typography.bodyLarge)
                    // Nur der Anriss (Client-Form ohne `body`): sagen, dass es mehr geben kann.
                    if (item.body.isNullOrBlank() && body.endsWith("…")) {
                        Text(
                            stringResource(R.string.research_detail_snippet),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (item.urls.isNotEmpty()) {
                DetailSection(stringResource(R.string.research_detail_links)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.urls.forEach { url -> UrlRow(url, onOpenUrl) }
                    }
                }
            }

            if (item.tags.isNotEmpty()) {
                DetailSection(stringResource(R.string.research_detail_tags)) {
                    Text(
                        item.tags.joinToString(" ") { "#$it" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item.source?.takeIf { it.isNotBlank() }?.let { source ->
                DetailSection(stringResource(R.string.research_detail_source)) {
                    Text(
                        source,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (item.has_image) {
                AttachmentRow(Icons.Filled.Image, stringResource(R.string.research_detail_has_image))
            }
            if (item.has_doc) {
                AttachmentRow(
                    Icons.Filled.AttachFile,
                    stringResource(R.string.research_detail_has_doc, item.doc_name.orEmpty()),
                )
            }

            val meta = listOfNotNull(
                item.created_at?.takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.research_detail_created, formatServerTime(it)) },
                item.updated_at?.takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.research_detail_updated, formatServerTime(it)) },
            )
            if (meta.isNotEmpty()) {
                Column {
                    meta.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        content()
    }
}

@Composable
private fun UrlRow(url: ResearchUrlDto, onOpenUrl: (String) -> Unit) {
    val label = url.label.takeIf { it.isNotBlank() } ?: url.url
    OutlinedButton(
        onClick = { onOpenUrl(url.url) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.OpenInNew,
            contentDescription = stringResource(R.string.research_detail_open),
            modifier = Modifier.size(18.dp),
        )
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 8.dp)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun AttachmentRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ResearchRow(item: ResearchItemDto, onClick: () -> Unit) {
    val firstUrl = item.urls.firstOrNull()?.url
    val displayTitle = item.title?.takeIf { it.isNotBlank() }
        ?: firstUrl
        ?: item.displayBody
        ?: stringResource(R.string.research_untitled)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                displayTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.pinned != 0) {
                Icon(
                    Icons.Filled.PushPin,
                    contentDescription = stringResource(R.string.research_detail_pinned),
                    modifier = Modifier.padding(start = 8.dp).size(18.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (firstUrl != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Link,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    firstUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        item.displayBody?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val tags = item.tags
        if (tags.isNotEmpty()) {
            Text(
                tags.joinToString(" ") { "#$it" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Vorgemerkte Erfassung: wartet, Dublette (Rückfrage) oder abgewiesen. */
@Composable
private fun QueuedRow(q: QueuedResearch, onKeep: () -> Unit, onRetry: () -> Unit, onDiscard: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            q.title?.takeIf { it.isNotBlank() } ?: q.url ?: q.note ?: stringResource(R.string.research_untitled),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        q.url?.takeIf { q.title != null }?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val (icon, text, isError) = when (q.status) {
            ResearchOutboxEntity.STATUS_DUPLICATE -> Triple(
                Icons.Filled.ErrorOutline,
                q.serverNote?.let { stringResource(R.string.research_queued_duplicate_named, it) }
                    ?: stringResource(R.string.research_queued_duplicate),
                true,
            )
            ResearchOutboxEntity.STATUS_FAILED -> Triple(
                Icons.Filled.ErrorOutline,
                rejectionText(context.resources, q.serverNote.orEmpty()),
                true,
            )
            else -> Triple(
                Icons.Filled.CloudOff,
                stringResource(if (q.created) R.string.research_queued_finishing else R.string.research_queued_waiting),
                false,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        when (q.status) {
            ResearchOutboxEntity.STATUS_DUPLICATE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onKeep) { Text(stringResource(R.string.research_duplicate_keep)) }
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.research_queued_discard)) }
            }
            ResearchOutboxEntity.STATUS_FAILED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.research_queued_discard)) }
            }
        }
    }
}

/**
 * Link im Browser öffnen (Custom Tab, sonst VIEW-Intent). Links ohne Schema bekommen
 * `https://` ([normalizeResearchUrl]). Wirft nie — false, wenn nichts öffnen konnte
 * (der Aufrufer zeigt dann eine Snackbar).
 */
private fun openUrl(context: android.content.Context, url: String): Boolean {
    val normalized = normalizeResearchUrl(url).takeIf { it.isNotEmpty() } ?: return false
    val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return false
    return runCatching {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    }.recoverCatching {
        // Kein Custom-Tabs-fähiger Browser → generischer VIEW-Intent.
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
    }.isSuccess
}

/** Vertikal zentrierter Inhalt in einer scrollbaren Liste (für Pull-to-Refresh). */
@Composable
private fun CenteredScrollable(content: @Composable () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Box(
                Modifier.fillParentMaxSize().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) { content() }
        }
    }
}
