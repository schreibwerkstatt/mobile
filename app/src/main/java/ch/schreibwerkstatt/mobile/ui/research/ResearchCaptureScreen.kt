package ch.schreibwerkstatt.mobile.ui.research

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
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

/**
 * Formular „Recherche festhalten". Erreichbar über den Share-Empfang (mit
 * vorbelegten Feldern aus [shared]) oder das „+"-FAB der Liste (mit
 * vorausgewähltem [preselectBookId]). Speichert online über das ViewModel.
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
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var url by rememberSaveable { mutableStateOf(shared?.url.orEmpty()) }
    var title by rememberSaveable { mutableStateOf(shared?.title.orEmpty()) }
    var note by rememberSaveable { mutableStateOf(shared?.note.orEmpty()) }
    var selectedBookId by rememberSaveable { mutableStateOf<Long?>(preselectBookId) }
    var menuExpanded by remember { mutableStateOf(false) }

    // System-Zurück wie der Pfeil oben behandeln: sonst bleibt der geteilte Inhalt im
    // Activity-State hängen (erneutes Teilen desselben Links wirkte nicht, nach dem
    // Neu-Koppeln öffnete sich das Formular ungefragt wieder).
    BackHandler(onBack = onDone)

    // Sinnvolle Vorauswahl, sobald die Bücher geladen sind: explizit gewünschtes
    // Buch, sonst das einzige vorhandene. Bei mehreren bleibt die Auswahl offen.
    LaunchedEffect(books, preselectBookId) {
        if (selectedBookId == null) {
            selectedBookId = preselectBookId?.takeIf { id -> books.any { it.id == id } }
                ?: books.singleOrNull()?.id
        }
    }

    val savedMsg = stringResource(R.string.research_saved)
    val errorTemplate = stringResource(R.string.research_save_error)
    LaunchedEffect(result) {
        when (val r = result) {
            CaptureResult.Saved -> {
                vm.consumeResult()
                onDone()
            }
            is CaptureResult.Error -> {
                snackbarHostState.showSnackbar(errorTemplate.format(r.message))
                vm.consumeResult()
            }
            null -> {}
        }
    }

    val selectedBook = books.firstOrNull { it.id == selectedBookId }
    val canSave = selectedBookId != null && !saving &&
        (url.isNotBlank() || title.isNotBlank() || note.isNotBlank())

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.research_capture_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
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
                                selectedBookId?.let { vm.save(it, url, title, note) }
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

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text(stringResource(R.string.research_url_label)) },
                singleLine = true,
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
            Text(
                stringResource(R.string.research_capture_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
