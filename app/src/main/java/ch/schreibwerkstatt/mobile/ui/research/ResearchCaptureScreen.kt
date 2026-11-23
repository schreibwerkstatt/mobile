package ch.schreibwerkstatt.mobile.ui.research

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.schreibwerkstatt.mobile.R
import ch.schreibwerkstatt.mobile.locator
import ch.schreibwerkstatt.mobile.ui.components.message
import android.widget.Toast

/**
 * Formular „Recherche festhalten". Erreichbar über den Share-Empfang (mit
 * vorbelegten Feldern aus [shared], auch ein geteiltes Bild) oder das „+"-FAB der
 * Liste (mit vorausgewähltem [preselectBookId]). Ohne Verbindung wird vorgemerkt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResearchCaptureScreen(
    shared: SharedResearch?,
    preselectBookId: Long?,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val vm: ResearchCaptureViewModel = viewModel(factory = ResearchCaptureViewModel.factory(context.locator))
    val books by vm.books.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val result by vm.result.collectAsStateWithLifecycle()
    val booksFailed by vm.booksFailed.collectAsStateWithLifecycle()
    val booksLoading by vm.booksLoading.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var url by rememberSaveable { mutableStateOf(shared?.url.orEmpty()) }
    var title by rememberSaveable { mutableStateOf(shared?.title.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(shared?.note.orEmpty()) }
    var imageUri by rememberSaveable { mutableStateOf(shared?.imageUri) }
    val imageMime = shared?.imageMime
    var selectedBookId by rememberSaveable { mutableStateOf<Long?>(preselectBookId) }
    var menuExpanded by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var duplicate by remember { mutableStateOf<CaptureResult.Duplicate?>(null) }
    val urlValid = isValidResearchUrl(normalizeResearchUrl(url))

    // Zurück (System und Pfeil oben): während des Speicherns blockiert (das Ergebnis
    // schliesst den Screen ohnehin), mit Eingaben erst nach «Verwerfen?». Beendet wird
    // immer über onDone — sonst bleibt der geteilte Inhalt im Activity-State hängen
    // (erneutes Teilen desselben Links wirkte nicht, nach dem Neu-Koppeln öffnete sich
    // das Formular ungefragt wieder).
    val hasInput = url.isNotBlank() || title.isNotBlank() || note.isNotBlank() || imageUri != null
    val onBackRequest: () -> Unit = {
        if (!saving) {
            if (hasInput) confirmDiscard = true else onDone()
        }
    }
    BackHandler(onBack = onBackRequest)

    // Sinnvolle Vorauswahl, sobald die Bücher geladen sind: explizit gewünschtes
    // Buch, sonst das einzige vorhandene. Bei mehreren bleibt die Auswahl offen.
    LaunchedEffect(books, preselectBookId) {
        if (selectedBookId == null) {
            selectedBookId = preselectBookId?.takeIf { id -> books.any { it.id == id } }
                ?: books.singleOrNull()?.id
        }
    }

    val savedMsg = stringResource(R.string.research_saved)
    val queuedMsg = stringResource(R.string.research_queued)
    val errorTemplate = stringResource(R.string.research_save_error)
    LaunchedEffect(result) {
        when (val r = result) {
            CaptureResult.Saved, CaptureResult.Queued -> {
                vm.consumeResult()
                // Toast statt Snackbar: der Screen schliesst sich sofort.
                val msg = if (r == CaptureResult.Queued) queuedMsg else savedMsg
                Toast.makeText(context, msg, if (r == CaptureResult.Queued) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                onDone()
            }
            is CaptureResult.Duplicate -> {
                vm.consumeResult()
                duplicate = r
            }
            is CaptureResult.Rejected -> {
                vm.consumeResult()
                snackbarHostState.showSnackbar(errorTemplate.format(rejectionText(context.resources, r.code)))
            }
            is CaptureResult.Error -> {
                vm.consumeResult()
                snackbarHostState.showSnackbar(errorTemplate.format(r.error.message(context.resources)))
            }
            null -> {}
        }
    }

    val selectedBook = books.firstOrNull { it.id == selectedBookId }
    val canSave = selectedBookId != null && !saving && urlValid && hasInput

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.research_capture_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackRequest, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 16.dp).size(24.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        TextButton(
                            onClick = {
                                selectedBookId?.let { vm.save(it, url, title, note, imageUri, imageMime) }
                            },
                            enabled = canSave,
                        ) { Text(stringResource(R.string.research_save)) }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                // Edge-to-edge: Tastatur-Inset vor dem Scroll, sonst verdeckt sie das Notizfeld.
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Buch-Auswahl (Pflicht – Recherche ist buchbezogen).
            ExposedDropdownMenuBox(
                expanded = menuExpanded,
                onExpandedChange = { menuExpanded = it },
            ) {
                OutlinedTextField(
                    value = selectedBook?.name ?: stringResource(R.string.research_book_placeholder),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.research_book_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    books.forEach { book ->
                        DropdownMenuItem(
                            text = { Text(book.name) },
                            onClick = {
                                selectedBookId = book.id
                                menuExpanded = false
                            },
                        )
                    }
                }
            }

            // Ohne Bücher im Cache (Kaltstart offline / Abruf gescheitert) ist keine Auswahl
            // möglich — Hinweis mit Retry statt eines dauerhaft gesperrten Speichern-Knopfs.
            if (books.isEmpty() && (booksFailed || !booksLoading)) {
                Text(
                    stringResource(R.string.research_books_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                OutlinedButton(onClick = vm::refreshBooks, enabled = !booksLoading) {
                    Text(stringResource(R.string.action_retry))
                }
            }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.research_url_label)) },
                singleLine = true,
                isError = !urlValid,
                supportingText = if (!urlValid) {
                    { Text(stringResource(R.string.research_url_invalid, RESEARCH_URL_MAX)) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.research_title_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(stringResource(R.string.research_note_label)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            imageUri?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Image,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.research_image_attached),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                    IconButton(onClick = { imageUri = null }, enabled = !saving) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.research_image_remove))
                    }
                }
            }
            Text(
                stringResource(R.string.research_capture_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    duplicate?.let { d ->
        AlertDialog(
            onDismissRequest = { duplicate = null; vm.resolveDuplicate(d.localId, keep = false) },
            title = { Text(stringResource(R.string.research_duplicate_title)) },
            text = {
                Text(
                    d.existingTitle?.let { stringResource(R.string.research_duplicate_text_named, it) }
                        ?: stringResource(R.string.research_duplicate_text)
                )
            },
            confirmButton = {
                TextButton(onClick = { duplicate = null; vm.resolveDuplicate(d.localId, keep = true) }) {
                    Text(stringResource(R.string.research_duplicate_keep))
                }
            },
            dismissButton = {
                TextButton(onClick = { duplicate = null; vm.resolveDuplicate(d.localId, keep = false) }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.research_discard_title)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDone() }) {
                    Text(stringResource(R.string.research_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** Server-Abweisung beim Erfassen als Satz (bekannte `error_code`s, sonst mit Code). */
internal fun rejectionText(res: android.content.res.Resources, code: String): String = when (code) {
    "INVALID_URL" -> res.getString(R.string.research_url_invalid, RESEARCH_URL_MAX)
    "INSUFFICIENT_ROLE" -> res.getString(R.string.error_read_only)
    "NO_BOOK_ACCESS" -> res.getString(R.string.error_unauthorized)
    "EMPTY" -> res.getString(R.string.research_error_empty)
    "IMAGE_INVALID", "NO_IMAGE" -> res.getString(R.string.research_error_image)
    "IMAGE_TOO_LARGE" -> res.getString(R.string.research_error_image_large)
    else -> res.getString(R.string.research_error_rejected, code)
}
