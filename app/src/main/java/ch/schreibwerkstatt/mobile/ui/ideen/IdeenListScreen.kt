package ch.schreibwerkstatt.mobile.ui.ideen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.schreibwerkstatt.mobile.R
import ch.schreibwerkstatt.mobile.data.db.PendingIdeaEntity
import ch.schreibwerkstatt.mobile.data.net.dto.IdeeDto
import ch.schreibwerkstatt.mobile.data.repo.IdeeView
import ch.schreibwerkstatt.mobile.ui.components.message
import ch.schreibwerkstatt.mobile.locator

/**
 * Ideen eines Buchs als Liste nach Stufen (offen, in Arbeit; erledigte und verworfene
 * auf Wunsch). Vorgemerkte, noch nicht gesendete Ideen stehen obenan. Tippen öffnet
 * das Bearbeiten-Sheet (Text, Stufe, Ort, Löschen); das Häkchen hakt ab. Alles wirkt
 * sofort und geht ohne Netz vorgemerkt raus. Board-Funktionen des Webs (Reihenfolge
 * ziehen, Verknüpfen, Zusammenführen, Ideen-Chat) bleiben bewusst dort.
 *
 * Aus dem Editor geöffnet ([sectionId]/[sectionName] gesetzt) filtert die Liste auf
 * diesen Abschnitt, und „+" legt die neue Idee dort an.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeenListScreen(
    bookId: Long,
    bookTitle: String,
    sectionId: Long? = null,
    sectionName: String = "",
    onBack: () -> Unit,
    onAdd: (bookId: Long, section: IdeePlace.Page?) -> Unit,
) {
    val context = LocalContext.current
    val vm: IdeenListViewModel = viewModel(factory = IdeenListViewModel.factory(context.locator, bookId, sectionId))
    val state by vm.state.collectAsStateWithLifecycle()
    val boardView by vm.board.collectAsStateWithLifecycle()
    val allPending by vm.pending.collectAsStateWithLifecycle()
    val filterSection = sectionId != null && state.onlySection
    val pending = if (filterSection) allPending.filter { it.pageId == sectionId } else allPending
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<IdeeDto?>(null) }
    var confirmDiscard by remember { mutableStateOf<PendingIdeaEntity?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    val loadErrTpl = stringResource(R.string.ideen_load_error)
    val editErrTpl = stringResource(R.string.ideen_edit_error)
    val stuckTpl = stringResource(R.string.ideen_stuck)
    val queuedMsg = stringResource(R.string.ideen_queued)
    LaunchedEffect(state.message) {
        val text = when (val m = state.message) {
            is IdeenMsg.LoadError -> loadErrTpl.format(m.error.message(context.resources))
            is IdeenMsg.EditError -> editErrTpl.format(m.code)
            is IdeenMsg.Stuck -> stuckTpl.format(m.code)
            IdeenMsg.Queued -> queuedMsg
            null -> null
        }
        if (text != null) {
            vm.consumeMessage()
            snackbar.showSnackbar(text)
        }
    }
    LaunchedEffect(state.editDone) {
        if (state.editDone) {
            editing = null
            vm.consumeEditDone()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.ideen_title))
                        if (bookTitle.isNotBlank()) {
                            Text(
                                bookTitle,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                onAdd(bookId, sectionId?.takeIf { filterSection }?.let { IdeePlace.Page(it, sectionName) })
            }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.ideen_add))
            }
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = vm::refresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            val board = boardView?.board
            val all = boardView?.items.orEmpty()
                .filter { !filterSection || it.idee.page_id == sectionId }
            val order = (board?.statuses?.takeIf { it.isNotEmpty() } ?: IDEE_STATUS_ORDER)
            // Unbekannte Stufen wie `offen` behandeln (Vertrag), damit nichts aus der Liste fällt.
            fun statusOf(idee: IdeeDto) = idee.status.takeIf { it in order } ?: IdeeDto.STATUS_OFFEN
            val visibleStatuses = order.filter {
                state.showClosed || it == IdeeDto.STATUS_OFFEN || it == IdeeDto.STATUS_IN_ARBEIT
            }
            val closedCount = all.count { !it.idee.isOpen }

            Column(Modifier.fillMaxSize()) {
            if (sectionId != null) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.onlySection,
                        onClick = { vm.setOnlySection(true) },
                        label = {
                            Text(
                                stringResource(R.string.ideen_filter_section, sectionName),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    FilterChip(
                        selected = !state.onlySection,
                        onClick = { vm.setOnlySection(false) },
                        label = { Text(stringResource(R.string.ideen_filter_book)) },
                    )
                }
            }
            when {
                board == null && pending.isEmpty() && state.loadFailed -> CenterMessage {
                    Text(
                        stringResource(R.string.ideen_load_failed),
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }
                board != null && all.isEmpty() && pending.isEmpty() && filterSection -> CenterMessage {
                    Icon(
                        Icons.Filled.Lightbulb, contentDescription = null,
                        modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.ideen_empty_section), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.ideen_empty_section_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                board != null && all.isEmpty() && pending.isEmpty() -> CenterMessage {
                    Icon(
                        Icons.Filled.Lightbulb, contentDescription = null,
                        modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(stringResource(R.string.ideen_empty), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.ideen_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(pending, key = { "p${it.localId}" }) { p ->
                        PendingRow(
                            idea = p,
                            onRetry = { vm.retryPending(p.localId) },
                            onCopy = { copyToClipboard(context, p.content) },
                            onDiscard = { confirmDiscard = p },
                        )
                        HorizontalDivider()
                    }
                    for (status in visibleStatuses) {
                        val group = all.filter { statusOf(it.idee) == status }
                        if (group.isEmpty()) continue
                        item(key = "h$status") { SectionHeader(status, group.size) }
                        items(group, key = { it.idee.id }) { view ->
                            IdeeRow(
                                view = view,
                                onToggleDone = { vm.toggleDone(view.idee) },
                                onClick = { if (!view.deleting) editing = view.idee },
                                onRetry = { vm.retryChange(view.idee.id) },
                                onDiscard = { vm.discardChange(view.idee.id) },
                            )
                            HorizontalDivider()
                        }
                    }
                    if (closedCount > 0 || state.showClosed) {
                        item(key = "closed-toggle") {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.setShowClosed(!state.showClosed) }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${stringResource(R.string.ideen_show_closed)} ($closedCount)",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Switch(checked = state.showClosed, onCheckedChange = vm::setShowClosed)
                            }
                        }
                    }
                }
            }
            }
        }
    }

    editing?.let { idee ->
        IdeeEditSheet(
            idee = idee,
            bookId = bookId,
            activeStages = boardView?.board?.stages.orEmpty(),
            busy = false,
            onSave = { content, status, place -> vm.save(idee, content, status, place) },
            onDelete = { vm.delete(idee) },
            onDismiss = { editing = null },
        )
    }

    confirmDiscard?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDiscard = null },
            text = { Text(stringResource(R.string.ideen_pending_discard_confirm)) },
            confirmButton = {
                TextButton(onClick = { vm.discardPending(p.localId); confirmDiscard = null }) {
                    Text(stringResource(R.string.ideen_pending_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun CenterMessage(content: @Composable () -> Unit) {
    // In eine scrollbare Spalte, damit Pull-to-Refresh auch im Leerzustand greift.
    Box(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

@Composable
private fun SectionHeader(status: String, count: Int) {
    Text(
        stringResource(R.string.ideen_section_count, statusLabel(status), count).uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun IdeeRow(
    view: IdeeView,
    onToggleDone: () -> Unit,
    onClick: () -> Unit,
    onRetry: () -> Unit,
    onDiscard: () -> Unit,
) {
    val idee = view.idee
    val enabled = !view.deleting
    val closed = !idee.isOpen
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = idee.status == IdeeDto.STATUS_ERLEDIGT,
            onCheckedChange = { onToggleDone() },
            enabled = enabled && idee.status != IdeeDto.STATUS_VERWORFEN,
        )
        Column(Modifier.weight(1f).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                idee.content,
                style = MaterialTheme.typography.bodyLarge,
                color = if (closed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            val place = IdeePlace.of(idee)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    placeIcon(place), contentDescription = null,
                    modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    placeLabel(place),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (view.error != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        Icons.Filled.ErrorOutline, contentDescription = null,
                        modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        stringResource(
                            if (view.deleting) R.string.ideen_change_delete_stuck else R.string.ideen_change_stuck,
                            view.error,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.ideen_pending_retry)) }
                    TextButton(onClick = onDiscard) { Text(stringResource(R.string.ideen_change_discard)) }
                }
            } else if (view.pending) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        Icons.Filled.CloudOff, contentDescription = null,
                        modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.ideen_change_waiting),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (idee.check_hint != null && idee.isOpen) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        Icons.Filled.TaskAlt, contentDescription = null,
                        modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        stringResource(R.string.ideen_has_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun PendingRow(idea: PendingIdeaEntity, onRetry: () -> Unit, onCopy: () -> Unit, onDiscard: () -> Unit) {
    val stuck = idea.status == PendingIdeaEntity.STATUS_STUCK
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(idea.content, style = MaterialTheme.typography.bodyLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                if (stuck) Icons.Filled.ErrorOutline else Icons.Filled.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (stuck) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (stuck) stringResource(R.string.ideen_pending_stuck, idea.error.orEmpty())
                else stringResource(R.string.ideen_pending_waiting),
                style = MaterialTheme.typography.labelSmall,
                color = if (stuck) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (stuck) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRetry) { Text(stringResource(R.string.ideen_pending_retry)) }
                TextButton(onClick = onCopy) { Text(stringResource(R.string.ideen_pending_copy)) }
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.ideen_pending_discard)) }
            }
        }
    }
}

/** Bearbeiten: Text, Stufe, Ort, Löschen. Ohne Netz vorgemerkt — Abweisungen zeigt die Liste an der Idee. */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun IdeeEditSheet(
    idee: IdeeDto,
    bookId: Long,
    activeStages: List<String>,
    busy: Boolean,
    onSave: (content: String, status: String, place: IdeePlace) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(idee.id) { mutableStateOf(idee.content) }
    var status by rememberSaveable(idee.id) { mutableStateOf(idee.status) }
    var place by remember(idee.id) { mutableStateOf(IdeePlace.of(idee)) }
    var pickPlace by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val tooLong = text.trim().length > IdeeDto.MAX_CONTENT
    // Angeboten werden die aktiven Stufen des Buchs plus die aktuelle (auch abgeschaltete).
    val stages = IDEE_STATUS_ORDER.filter { it in activeStages || it == idee.status }
        .ifEmpty { IDEE_STATUS_ORDER }
    // Der Server verschiebt nur Ideen, die vorher UND nachher offen sind.
    val canMove = idee.isOpen && (status == IdeeDto.STATUS_OFFEN || status == IdeeDto.STATUS_IN_ARBEIT)

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.ideen_edit_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                isError = tooLong,
                supportingText = if (tooLong) {
                    { Text(stringResource(R.string.ideen_too_long, IdeeDto.MAX_CONTENT)) }
                } else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            Label(stringResource(R.string.ideen_edit_status))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                stages.forEach { s ->
                    FilterChip(
                        selected = status == s,
                        onClick = { status = s },
                        label = { Text(statusLabel(s)) },
                    )
                }
            }

            Label(stringResource(R.string.ideen_edit_place))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(placeIcon(place), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(placeLabel(place), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { pickPlace = true }, enabled = canMove) {
                    Text(stringResource(R.string.ideen_edit_move))
                }
            }
            if (!canMove) {
                Text(
                    stringResource(R.string.ideen_edit_closed_move),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            idee.check_hint?.let { hint ->
                Label(stringResource(R.string.ideen_hint_title))
                listOfNotNull(hint.beleg, hint.begruendung, hint.page_name?.takeIf { it.isNotBlank() })
                    .filter { it.isNotBlank() }
                    .forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                    Text(stringResource(R.string.ideen_edit_delete), color = MaterialTheme.colorScheme.error)
                }
                Box(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                OutlinedButton(
                    onClick = { onSave(text, status, place) },
                    enabled = !busy && text.isNotBlank() && !tooLong,
                ) { Text(stringResource(R.string.ideen_save)) }
            }
        }
    }

    if (pickPlace) {
        PlacePickerSheet(
            bookId = bookId,
            current = place,
            onPick = { place = it; pickPlace = false },
            onDismiss = { pickPlace = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            text = { Text(stringResource(R.string.ideen_edit_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.action_delete_ideen), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
}

private fun copyToClipboard(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("idee", text))
    Toast.makeText(context, context.getString(R.string.ideen_copied), Toast.LENGTH_SHORT).show()
}
