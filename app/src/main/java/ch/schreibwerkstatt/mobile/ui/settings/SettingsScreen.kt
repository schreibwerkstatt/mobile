package ch.schreibwerkstatt.mobile.ui.settings

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.schreibwerkstatt.mobile.BuildConfig
import ch.schreibwerkstatt.mobile.R
import ch.schreibwerkstatt.mobile.data.prefs.ThemeMode
import ch.schreibwerkstatt.mobile.locator
import ch.schreibwerkstatt.mobile.update.UpdateState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.locator))
    val state by vm.state.collectAsStateWithLifecycle()
    val online by context.locator.syncCoordinator.online.collectAsStateWithLifecycle()
    val pendingCount by context.locator.syncCoordinator.pendingCount.collectAsStateWithLifecycle()
    val syncing by context.locator.syncCoordinator.syncing.collectAsStateWithLifecycle()

    var showSignOutDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            // === Darstellung ===
            SectionHeader(stringResource(R.string.settings_section_appearance))
            ThemeSelector(
                selected = state.themeMode,
                onSelect = vm::setThemeMode,
            )
            HorizontalDivider()

            // === Editor ===
            SectionHeader(stringResource(R.string.settings_section_editor))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_spellcheck)) },
                supportingContent = {
                    Text(
                        stringResource(
                            if (state.spellcheckAvailable == false) R.string.settings_spellcheck_unavailable
                            else R.string.settings_spellcheck_hint
                        )
                    )
                },
                trailingContent = {
                    Switch(
                        checked = state.spellcheck,
                        onCheckedChange = vm::setSpellcheck,
                        // Bietet der Server LanguageTool nicht an, bleibt der Schalter
                        // wirkungslos — dann gar nicht erst bedienbar machen.
                        enabled = state.spellcheckAvailable != false,
                    )
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_swipe_history)) },
                supportingContent = { Text(stringResource(R.string.settings_swipe_history_hint)) },
                trailingContent = {
                    Switch(
                        checked = state.swipeToHistory,
                        onCheckedChange = vm::setSwipeToHistory,
                    )
                },
            )
            HorizontalDivider()

            // === Synchronisierung ===
            SectionHeader(stringResource(R.string.settings_section_sync))
            ListItem(
                leadingContent = {
                    Icon(
                        if (online) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = if (online) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                headlineContent = {
                    Text(
                        stringResource(
                            if (online) R.string.settings_sync_online else R.string.settings_sync_offline
                        )
                    )
                },
                supportingContent = {
                    Text(
                        if (pendingCount == 0) stringResource(R.string.settings_sync_all_synced)
                        else pluralStringResource(R.plurals.settings_sync_pending, pendingCount, pendingCount)
                    )
                },
                trailingContent = {
                    OutlinedButton(
                        onClick = { context.locator.syncCoordinator.syncAllNow() },
                        enabled = online && !syncing,
                    ) {
                        Text(
                            stringResource(
                                if (syncing) R.string.settings_sync_running else R.string.settings_sync_now
                            )
                        )
                    }
                },
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_background_sync)) },
                supportingContent = { Text(stringResource(R.string.settings_background_sync_hint)) },
                trailingContent = {
                    Switch(
                        checked = state.backgroundSync,
                        onCheckedChange = vm::setBackgroundSync,
                    )
                },
            )
            HorizontalDivider()

            // === Konto & Gerät ===
            SectionHeader(stringResource(R.string.settings_section_account))
            ListItem(
                headlineContent = { Text(stringResource(R.string.pairing_server_label)) },
                supportingContent = { Text(state.serverUrl.ifBlank { "—" }) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.pairing_device_label)) },
                supportingContent = { Text(state.deviceName) },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_stt_status)) },
                supportingContent = { Text(stringResource(state.sttStatus.label)) },
            )
            HorizontalDivider()

            // === Über die App ===
            SectionHeader(stringResource(R.string.settings_section_about))
            // Der Play-Build aktualisiert sich über Play, nicht über GitHub — dort bleibt
            // nur die Versionsanzeige (siehe CLAUDE.md „Vertriebskanäle").
            if (BuildConfig.SELF_UPDATE) {
                VersionWithUpdateCheckItem()
            } else {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_version)) },
                    supportingContent = { Text(BuildConfig.VERSION_NAME) },
                )
            }
            HorizontalDivider()
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_diagnostics)) },
                leadingContent = { Icon(Icons.Filled.Share, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { shareDiagnostics(context, state, online, pendingCount) },
            )
            HorizontalDivider()

            // === Abmelden ===
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { showSignOutDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.settings_signout))
                }
                Text(
                    stringResource(R.string.settings_signout_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text(stringResource(R.string.settings_signout_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_signout_confirm_message))
                    // Live-Zähler: sinkt, während «Erst synchronisieren» läuft.
                    if (pendingCount > 0) {
                        Text(
                            pluralStringResource(R.plurals.settings_signout_pending, pendingCount, pendingCount),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSignOutDialog = false
                        vm.signOut()
                        onSignedOut()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.settings_signout_confirm))
                }
            },
            dismissButton = {
                Row {
                    if (pendingCount > 0) {
                        TextButton(
                            onClick = { context.locator.syncCoordinator.syncAllNow() },
                            enabled = online && !syncing,
                        ) {
                            Text(
                                stringResource(
                                    if (syncing) R.string.settings_sync_running else R.string.settings_signout_sync_first
                                )
                            )
                        }
                    }
                    TextButton(onClick = { showSignOutDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            },
        )
    }
}

/**
 * Versionszeile mit „Nach Update suchen"-Button. Nur aus dem github-Flavor erreichbar —
 * hält den [UpdateManager] aus dem Play-Build heraus (R8 schneidet den Zweig weg).
 */
@Composable
private fun VersionWithUpdateCheckItem() {
    val context = LocalContext.current
    val updateState by context.locator.updateManager.state.collectAsStateWithLifecycle()
    val checking = updateState is UpdateState.Checking
    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_version)) },
        supportingContent = {
            // Die Version bleibt immer sichtbar; der Prüf-Status hängt nur an.
            val version = BuildConfig.VERSION_NAME
            when (val s = updateState) {
                is UpdateState.Error -> Text(
                    stringResource(R.string.settings_version_update_error, version, s.message),
                    color = MaterialTheme.colorScheme.error,
                )
                else -> Text(
                    when {
                        checking -> stringResource(R.string.settings_version_checking, version)
                        s is UpdateState.UpToDate -> stringResource(R.string.settings_version_current, version)
                        else -> version
                    }
                )
            }
        },
        trailingContent = {
            OutlinedButton(
                onClick = { context.locator.updateManager.checkNow() },
                enabled = !checking,
            ) {
                Text(stringResource(R.string.settings_check_update))
            }
        },
    )
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        // Als Überschrift markiert, damit TalkBack per Überschriften-Navigation
        // zwischen den Einstellungs-Sektionen springen kann.
        modifier = Modifier
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSelector(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
) {
    val options = listOf(
        ThemeMode.SYSTEM to R.string.settings_theme_system,
        ThemeMode.LIGHT to R.string.settings_theme_light,
        ThemeMode.DARK to R.string.settings_theme_dark,
    )
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            stringResource(R.string.settings_theme),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (mode, labelRes) ->
                SegmentedButton(
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(stringResource(labelRes))
                }
            }
        }
    }
}

/** Baut eine kurze Klartext-Diagnose und öffnet das Android-Share-Sheet. Enthält
 *  bewusst KEIN Token — nur Version, Gerät, Server-URL und Sync-Status für Support. */
private fun shareDiagnostics(
    context: android.content.Context,
    state: SettingsUiState,
    online: Boolean,
    pendingCount: Int,
) {
    // Bewusst sprachneutrale Schlüssel statt @string-Texten: Die Diagnose ist ein
    // Support-Text, den der Entwickler liest — maschinenlesbar und in jeder App-Sprache
    // gleich, statt deutsch/englisch gemischt. Nur Betreff und Chooser sind lokalisiert.
    val text = buildString {
        appendLine("Schreibwerkstatt Android")
        appendLine("app_version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("client=${BuildConfig.CLIENT_VERSION}")
        appendLine("android=${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("device=${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("device_name=${state.deviceName}")
        appendLine("server=${state.serverUrl.ifBlank { "-" }}")
        appendLine("device_id=${state.deviceId.ifBlank { "-" }}")
        appendLine("stt=${state.sttStatus.name.lowercase()}")
        appendLine("status=${if (online) "online" else "offline"}")
        appendLine("pending=$pendingCount")
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_diagnostics_subject))
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(
        Intent.createChooser(send, context.getString(R.string.settings_diagnostics_chooser))
    )
}
