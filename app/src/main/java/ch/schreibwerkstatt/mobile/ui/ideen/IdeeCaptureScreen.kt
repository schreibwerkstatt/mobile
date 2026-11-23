package ch.schreibwerkstatt.mobile.ui.ideen

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.schreibwerkstatt.mobile.R
import ch.schreibwerkstatt.mobile.data.net.dto.IdeeDto
import ch.schreibwerkstatt.mobile.data.repo.CreateIdeaResult
import ch.schreibwerkstatt.mobile.data.repo.IdeenRepository
import ch.schreibwerkstatt.mobile.locator

/**
 * „Idee festhalten": ein Textfeld mit Fokus und Tastatur sofort offen, Buch
 * vorgewählt (übergeben oder zuletzt benutzt), Ort optional (Default: ganzes Buch),
 * Diktat per Mikrofon. Nach dem Speichern schliesst der Screen und meldet per Toast,
 * ob die Idee am Server ist oder vorgemerkt wurde.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdeeCaptureScreen(
    preselectBookId: Long?,
    initialText: String,
    initialPlace: IdeePlace = IdeePlace.Book,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val vm: IdeeCaptureViewModel = viewModel(
        factory = IdeeCaptureViewModel.factory(context.locator, preselectBookId, initialText, initialPlace),
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var bookMenu by remember { mutableStateOf(false) }
    var pickPlace by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    BackHandler(onBack = onDone)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val savedMsg = stringResource(R.string.ideen_saved)
    val queuedMsg = stringResource(R.string.ideen_queued)
    val stuckTpl = stringResource(R.string.ideen_stuck)
    val tooLongMsg = stringResource(R.string.ideen_too_long, IdeeDto.MAX_CONTENT)
    LaunchedEffect(state.result) {
        when (val r = state.result) {
            is CreateIdeaResult.Saved -> {
                Toast.makeText(context, savedMsg, Toast.LENGTH_SHORT).show(); vm.consumeResult(); onDone()
            }
            CreateIdeaResult.Queued -> {
                Toast.makeText(context, queuedMsg, Toast.LENGTH_LONG).show(); vm.consumeResult(); onDone()
            }
            is CreateIdeaResult.Stuck -> {
                Toast.makeText(context, stuckTpl.format(r.code), Toast.LENGTH_LONG).show(); vm.consumeResult(); onDone()
            }
            is CreateIdeaResult.Invalid -> {
                if (r.code == IdeenRepository.CODE_CONTENT_TOO_LONG) snackbar.showSnackbar(tooLongMsg)
                vm.consumeResult()
            }
            null -> Unit
        }
    }
    val dictErrTpl = stringResource(R.string.ideen_dictate_error)
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(dictErrTpl.format(it)); vm.consumeMessage() }
    }

    val micDenied = stringResource(R.string.ideen_mic_denied)
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleDictation()
        else Toast.makeText(context, micDenied, Toast.LENGTH_SHORT).show()
    }
    val onMic = {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted || state.recording) vm.toggleDictation()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val tooLong = state.text.trim().length > IdeeDto.MAX_CONTENT
    val canSave = state.bookId != null && state.text.isNotBlank() && !tooLong && !state.saving && !state.transcribing
    val selectedBook = books.firstOrNull { it.id == state.bookId }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ideen_capture_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.padding(end = 16.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        TextButton(onClick = vm::save, enabled = canSave) {
                            Text(stringResource(R.string.ideen_save))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (books.isEmpty()) {
                Text(
                    stringResource(R.string.ideen_no_books),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            // Buch (Pflicht) — bei genau einem Buch ohne Auswahl-Aufwand.
            ExposedDropdownMenuBox(expanded = bookMenu, onExpandedChange = { bookMenu = it }) {
                OutlinedTextField(
                    value = selectedBook?.name ?: stringResource(R.string.ideen_capture_book_placeholder),
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    label = { Text(stringResource(R.string.ideen_capture_book)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bookMenu) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = bookMenu, onDismissRequest = { bookMenu = false }) {
                    books.forEach { book ->
                        DropdownMenuItem(
                            text = { Text(book.name) },
                            onClick = { vm.selectBook(book.id); bookMenu = false },
                        )
                    }
                }
            }

            // Ort (optional, Default ganzes Buch).
            if (state.bookId != null) {
                OutlinedCard(Modifier.fillMaxWidth().clickable { pickPlace = true }) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            placeIcon(state.place), contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.ideen_capture_place),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(placeLabel(state.place), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }

            OutlinedTextField(
                value = state.text,
                onValueChange = vm::setText,
                placeholder = { Text(stringResource(R.string.ideen_capture_hint)) },
                isError = tooLong,
                supportingText = {
                    val n = state.text.trim().length
                    if (tooLong || n > IdeeDto.MAX_CONTENT * 9 / 10) {
                        Text(stringResource(R.string.ideen_char_count, n, IdeeDto.MAX_CONTENT))
                    }
                },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                minLines = 5,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .focusRequester(focus),
            )

            // Diktat
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalIconButton(
                    onClick = onMic,
                    enabled = state.bookId != null && !state.transcribing && !state.saving,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        if (state.recording) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = stringResource(
                            if (state.recording) R.string.ideen_dictate_stop else R.string.ideen_dictate,
                        ),
                    )
                }
                when {
                    state.recording -> LinearProgressIndicator(
                        progress = { state.level },
                        modifier = Modifier.weight(1f),
                    )
                    state.transcribing -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.ideen_dictate_transcribing), style = MaterialTheme.typography.bodyMedium)
                    }
                    else -> Text(
                        stringResource(R.string.ideen_dictate),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val bookId = state.bookId
    if (pickPlace && bookId != null) {
        PlacePickerSheet(
            bookId = bookId,
            current = state.place,
            onPick = { vm.selectPlace(it); pickPlace = false },
            onDismiss = { pickPlace = false },
        )
    }
}
