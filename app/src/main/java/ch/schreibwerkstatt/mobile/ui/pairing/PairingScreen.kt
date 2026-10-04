package ch.schreibwerkstatt.mobile.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.schreibwerkstatt.mobile.R
import ch.schreibwerkstatt.mobile.locator

/**
 * Manuelles Pairing: Server-Adresse + ein am Server vorab erzeugtes Device-Token
 * (`swd_…`) eingeben. Kein WebView/OIDC-Flow — das Token wird verifiziert und
 * sicher abgelegt (siehe [PairingViewModel]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(onPaired: () -> Unit) {
    val context = LocalContext.current
    val vm: PairingViewModel = viewModel(factory = PairingViewModel.factory(context.locator))
    val state by vm.state.collectAsStateWithLifecycle()

    state.confirmDiscard?.let { n ->
        AlertDialog(
            onDismissRequest = vm::dismissDiscard,
            title = { Text(stringResource(R.string.pairing_discard_title)) },
            text = { Text(pluralStringResource(R.plurals.pairing_discard_text, n, n)) },
            confirmButton = {
                TextButton(onClick = { vm.couple(onPaired, discardConfirmed = true) }) {
                    Text(stringResource(R.string.pairing_discard_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissDiscard) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.pairing_title)) }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.pairing_intro))
            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = vm::onServerUrlChange,
                label = { Text(stringResource(R.string.pairing_server_label)) },
                placeholder = { Text(stringResource(R.string.pairing_server_hint)) },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.token,
                onValueChange = vm::onTokenChange,
                label = { Text(stringResource(R.string.pairing_token_label)) },
                placeholder = { Text(stringResource(R.string.pairing_token_hint)) },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.deviceName,
                onValueChange = vm::onDeviceNameChange,
                label = { Text(stringResource(R.string.pairing_device_label)) },
                singleLine = true,
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            )
            state.error?.let {
                Text(
                    pairingErrorText(it),
                    color = MaterialTheme.colorScheme.error,
                    // Fehler beim Koppeln sofort ansagen – sonst bleibt er für
                    // Screenreader-Nutzer unbemerkt.
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
            Button(
                onClick = { vm.couple(onPaired) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.pairing_login))
            }
            if (vm.demoAvailable) {
                HorizontalDivider()
                Text(
                    stringResource(R.string.pairing_demo_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = { vm.useDemo(onPaired) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.pairing_demo_button))
                }
            }
            if (state.busy) {
                CircularProgressIndicator()
                Text(stringResource(R.string.pairing_busy))
            }
        }
    }
}

/** Löst den lokalisierbaren [PairingError]-Typ in den anzuzeigenden Text auf. */
@Composable
private fun pairingErrorText(error: PairingError): String = when (error) {
    PairingError.UrlScheme -> stringResource(R.string.pairing_error_url_scheme)
    is PairingError.TokenPrefix -> stringResource(R.string.pairing_error_prefix, error.prefix)
    PairingError.Unauthorized -> stringResource(R.string.pairing_error_invalid)
    is PairingError.Unreachable -> stringResource(R.string.pairing_error_unreachable, error.detail)
}
