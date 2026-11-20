package ch.schreibwerkstatt.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.schreibwerkstatt.mobile.R
import ch.schreibwerkstatt.mobile.update.UpdateState

/**
 * Zentraler Update-Dialog. Reagiert auf den [UpdateState] des UpdateManagers und
 * wird sowohl beim Auto-Check (AppNav) als auch beim manuellen Check (Settings)
 * verwendet. `Idle`/`Checking` zeigen nichts an.
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onDownload: () -> Unit,
    onRetryInstall: () -> Unit,
    onOpenPermissionSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                ) {
                    Text(stringResource(R.string.update_available_message, state.release.versionName))
                    if (state.release.notes.isNotBlank()) {
                        Text(
                            state.release.notes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDownload) { Text(stringResource(R.string.update_download)) } },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) } },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_available_title)) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.update_downloading, (state.progress * 100).toInt()))
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            // dismiss() bricht den laufenden Download ab — darum «Abbrechen», nicht «Später».
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )

        is UpdateState.NeedsInstallPermission -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.update_permission_title)) },
            text = { Text(stringResource(R.string.update_permission_message)) },
            confirmButton = {
                TextButton(onClick = onRetryInstall) { Text(stringResource(R.string.update_install)) }
            },
            dismissButton = {
                TextButton(onClick = onOpenPermissionSettings) {
                    Text(stringResource(R.string.update_permission_open))
                }
            },
        )

        is UpdateState.Error -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.update_error_title)) },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
        )

        UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate -> Unit
    }
}
