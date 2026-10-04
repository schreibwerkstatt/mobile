package ch.schreibwerkstatt.mobile.ui.research

import android.content.ActivityNotFoundException
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
import ch.schreibwerkstatt.mobile.data.net.dto.ResearchItemDto
import ch.schreibwerkstatt.mobile.data.net.dto.ResearchUrlDto
import ch.schreibwerkstatt.mobile.locator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

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

    // Bei jedem Sichtbarwerden neu laden — auch nach der Rückkehr aus „+ Erfassen",
    // sonst fehlt das gerade gespeicherte Element (das VM lebt im Backstack weiter).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val snackbarHostState = remember { SnackbarHostState() }
    // Ausgewähltes Element für die Detail-Ansicht (Klick auf eine Zeile).
    var detail by remember { mutableStateOf<ResearchItemDto?>(null) }

    val errorTemplate = stringResource(R.string.research_load_error)
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(errorTemplate.format(it))
            vm.clearError()
        }
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
            if (items.isEmpty() && loaded && loadFailed) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.research_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
            } else if (items.isEmpty() && loaded) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
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
                    items(items, key = { it.id }) { item ->
                        ResearchRow(item, onClick = { detail = item })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    detail?.let { item ->
        ResearchDetailSheet(
            item = item,
            onOpenUrl = { openUrl(context, it) },
            onDismiss = { detail = null },
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
        ?: item.body?.takeIf { it.isNotBlank() }
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

            item.body?.takeIf { it.isNotBlank() }?.let { body ->
                DetailSection(stringResource(R.string.research_detail_note)) {
                    Text(body, style = MaterialTheme.typography.bodyLarge)
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
                    ?.let { stringResource(R.string.research_detail_created, it) },
                item.updated_at?.takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.research_detail_updated, it) },
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
        ?: item.body?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.research_untitled)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            displayTitle,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
        item.body?.takeIf { it.isNotBlank() }?.let {
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

private fun openUrl(context: android.content.Context, url: String) {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return
    runCatching {
        CustomTabsIntent.Builder().build().launchUrl(context, uri)
    }.recoverCatching {
        // Kein Custom-Tabs-fähiger Browser → generischer VIEW-Intent.
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
    }.onFailure {
        if (it !is ActivityNotFoundException) throw it
    }
}
