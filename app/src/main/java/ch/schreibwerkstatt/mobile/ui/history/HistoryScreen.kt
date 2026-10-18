package ch.schreibwerkstatt.mobile.ui.history

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import ch.schreibwerkstatt.mobile.ui.theme.LocalAppDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.schreibwerkstatt.mobile.R
import ch.schreibwerkstatt.mobile.data.net.dto.RevisionDto
import ch.schreibwerkstatt.mobile.locator
import ch.schreibwerkstatt.mobile.ui.components.SkeletonList
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    bookId: Long,
    pageId: Long,
    pageTitle: String,
    onBack: () -> Unit,
    onRestored: (pageId: Long) -> Unit,
) {
    val context = LocalContext.current
    val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.factory(context.locator, bookId, pageId))
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val errorTemplate = stringResource(R.string.history_action_error)
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(errorTemplate.format(it))
            vm.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.history_title))
                        if (pageTitle.isNotBlank()) {
                            Text(
                                pageTitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> SkeletonList(Modifier.fillMaxSize())

                state.error != null -> Box(
                    Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            stringResource(R.string.history_load_error, state.error ?: ""),
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = { vm.load() }) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                }

                state.revisions.isEmpty() -> Box(
                    Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Filled.History,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            stringResource(R.string.history_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(state.revisions, key = { it.id }) { rev ->
                        RevisionRow(rev, onClick = { vm.openPreview(rev) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    // Vorschau + Wiederherstellen.
    state.preview?.let { preview ->
        RevisionPreviewDialog(
            preview = preview,
            restoring = state.restoring,
            onClose = vm::closePreview,
            onRestore = { vm.restore(preview.revision.id, onRestored) },
        )
    }
}

@Composable
private fun RevisionRow(rev: RevisionDto, onClick: () -> Unit) {
    val source = sourceLabel(rev.source)
    val client = rev.client?.takeIf { it.isNotBlank() }
    val author = rev.user_email?.takeIf { it.isNotBlank() }
    val pinned = (rev.pinned ?: 0) != 0
    // Benannte Revision: das Label ist der Titel, das Datum rückt in die Meta-Zeile.
    val title = rev.label?.trim()?.takeIf { pinned && it.isNotEmpty() }
    val meta = buildString {
        if (title != null) append(formatRevisionTime(rev.created_at)).append(" · ")
        append(source)
        if (client != null) append(" · ").append(client)
        if (author != null) append(" · ").append(author)
        rev.words?.let { append(" · ").append(stringResource(R.string.history_words, it)) }
    }
    ListItem(
        headlineContent = { Text(title ?: formatRevisionTime(rev.created_at), maxLines = 1) },
        supportingContent = {
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = {
            if (pinned) {
                Icon(Icons.Filled.PushPin, contentDescription = stringResource(R.string.history_pinned))
            } else {
                Icon(Icons.Filled.History, contentDescription = null)
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun RevisionPreviewDialog(
    preview: RevisionPreview,
    restoring: Boolean,
    onClose: () -> Unit,
    onRestore: () -> Unit,
) {
    var confirm by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                    }
                    val client = preview.revision.client?.takeIf { it.isNotBlank() }
                    Column(Modifier.weight(1f)) {
                        Text(
                            formatRevisionTime(preview.revision.created_at),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val subtitle = buildString {
                            append(sourceLabel(preview.revision.source))
                            if (client != null) append(" · ").append(client)
                        }
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { confirm = true }, enabled = !restoring && !preview.loadingHtml) {
                        Text(stringResource(R.string.history_restore))
                    }
                }
                HorizontalDivider()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (preview.loadingHtml) {
                        CircularProgressIndicator()
                    } else {
                        RevisionWebView(preview.html ?: "", Modifier.fillMaxSize())
                    }
                    if (restoring) CircularProgressIndicator()
                }
            }
        }
    }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.history_restore_confirm_title)) },
            text = { Text(stringResource(R.string.history_restore_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onRestore() }) {
                    Text(stringResource(R.string.history_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Read-only-WebView, die den HTML-Body einer Revision in lesbarem Layout zeigt. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RevisionWebView(html: String, modifier: Modifier = Modifier) {
    val dark = LocalAppDarkTheme.current
    val bg = if (dark) "#1A1F3A" else "#FAF7F2"
    val fg = if (dark) "#E8E8F0" else "#1A1A1A"
    val doc = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          body { font-family: sans-serif; line-height: 1.6; padding: 16px; margin: 0;
                 color: $fg; background: $bg; word-wrap: break-word; }
          img { max-width: 100%; height: auto; }
          hr { border: none; border-top: 1px solid #88888888; margin: 1.5em 0; }
        </style></head><body>$html</body></html>
    """.trimIndent()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(if (dark) 0xFF1A1F3A.toInt() else 0xFFFAF7F2.toInt())
                // Links im Revisions-HTML nicht in dieser read-only-WebView öffnen,
                // sondern extern (System-Browser); Navigation der WebView selbst blocken.
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView, request: WebResourceRequest,
                    ): Boolean {
                        if (request.url.scheme == "https" || request.url.scheme == "http") {
                            runCatching {
                                ctx.startActivity(
                                    Intent(Intent.ACTION_VIEW, request.url)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                        return true
                    }
                }
            }
        },
        update = { it.loadDataWithBaseURL(null, doc, "text/html", "utf-8", null) },
    )
}

@Composable
private fun sourceLabel(source: String?): String = when (source) {
    "focus" -> stringResource(R.string.history_source_focus)
    "main" -> stringResource(R.string.history_source_main)
    "macapp" -> stringResource(R.string.history_source_macapp)
    "chat-apply" -> stringResource(R.string.history_source_chat)
    "lektorat-apply" -> stringResource(R.string.history_source_lektorat)
    "import" -> stringResource(R.string.history_source_import)
    "conflict" -> stringResource(R.string.history_source_conflict)
    "restore" -> stringResource(R.string.history_source_restore)
    "bookstack-sync" -> stringResource(R.string.history_source_sync)
    else -> source ?: stringResource(R.string.history_source_unknown)
}

private val revisionTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())

/**
 * Server-`created_at` ist UTC ohne Zeitzonen-Suffix (`YYYY-MM-DD HH:MM:SS`,
 * via SQLite `datetime('now')`). Robust parsen: ISO-Instant ODER „SQLite-UTC".
 */
private fun formatRevisionTime(raw: String?): String {
    if (raw.isNullOrBlank()) return "—"
    val instant = runCatching { Instant.parse(raw) }.getOrNull()
        ?: runCatching {
            java.time.LocalDateTime
                .parse(raw.replace(' ', 'T'))
                .atZone(ZoneId.of("UTC"))
                .toInstant()
        }.getOrNull()
    return instant?.let { revisionTimeFormatter.format(it) } ?: raw
}
