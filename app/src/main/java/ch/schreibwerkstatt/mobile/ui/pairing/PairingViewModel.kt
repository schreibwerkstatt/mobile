package ch.schreibwerkstatt.mobile.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.schreibwerkstatt.mobile.BuildConfig
import ch.schreibwerkstatt.mobile.ServiceLocator
import ch.schreibwerkstatt.mobile.data.net.NetworkClient
import ch.schreibwerkstatt.mobile.data.net.VerifyResult
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import ch.schreibwerkstatt.mobile.data.prefs.TokenStore
import ch.schreibwerkstatt.mobile.data.repo.ContentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

const val DEFAULT_SERVER_URL = "https://schreibwerkstatt.app"
const val TOKEN_PREFIX = "swd_"

/**
 * Lokalisierbarer Pairing-Fehler. Das ViewModel emittiert nur den Typ; der
 * [PairingScreen] löst ihn über `stringResource` auf (dynamische Detailtexte wie
 * die Server-Fehlermeldung reisen als Argument mit).
 */
sealed interface PairingError {
    data object UrlScheme : PairingError
    data class TokenPrefix(val prefix: String) : PairingError
    data object Unauthorized : PairingError
    data class Unreachable(val detail: String) : PairingError
}

data class PairingUiState(
    val serverUrl: String = DEFAULT_SERVER_URL,
    val token: String = "",
    val deviceName: String = "",
    val error: PairingError? = null,
    val busy: Boolean = false,
    /**
     * Wechsel auf einen anderen Server würde so viele noch nicht übertragene lokale
     * Änderungen verwerfen → der Screen fragt nach, bevor [PairingViewModel.couple]
     * mit `discardConfirmed = true` erneut läuft.
     */
    val confirmDiscard: Int? = null,
)

/**
 * Manuelles Pairing wie beim Mac-Client: der Nutzer gibt die Server-Adresse ein
 * und fügt ein am Server (Einstellungen → Geräte) vorab erzeugtes Device-Token
 * (`swd_…`) ein. Das Token wird gegen `GET …/config` verifiziert und erst bei
 * Erfolg verschlüsselt im [TokenStore] abgelegt.
 */
class PairingViewModel(
    private val settings: SettingsStore,
    private val tokenStore: TokenStore,
    private val network: NetworkClient,
    private val repo: ContentRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(PairingUiState(deviceName = android.os.Build.MODEL ?: "Android"))
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            settings.serverBaseUrlOnce()?.let { url ->
                _state.value = _state.value.copy(serverUrl = url)
            }
        }
    }

    /**
     * Ist ein Demo-Zugang einkompiliert (`demo.properties` beim Build vorhanden)?
     * Nur dann zeigt der [PairingScreen] den Demo-Button.
     */
    val demoAvailable: Boolean =
        BuildConfig.DEMO_SERVER_URL.isNotBlank() && BuildConfig.DEMO_DEVICE_TOKEN.isNotBlank()

    /**
     * Koppelt gegen die Demo-Instanz: füllt Adresse + Token sichtbar in die Felder
     * und läuft danach durch denselben [couple]-Pfad wie die manuelle Eingabe
     * (Verifikation gegen `GET …/config`, Speichern erst bei Erfolg).
     */
    fun useDemo(onPaired: () -> Unit) {
        if (!demoAvailable) return
        _state.value = _state.value.copy(
            serverUrl = BuildConfig.DEMO_SERVER_URL,
            token = BuildConfig.DEMO_DEVICE_TOKEN,
            error = null,
        )
        couple(onPaired)
    }

    fun dismissDiscard() { _state.value = _state.value.copy(confirmDiscard = null) }

    fun onServerUrlChange(v: String) { _state.value = _state.value.copy(serverUrl = v, error = null) }
    fun onTokenChange(v: String) { _state.value = _state.value.copy(token = v, error = null) }
    fun onDeviceNameChange(v: String) { _state.value = _state.value.copy(deviceName = v) }

    /**
     * URL + Token prüfen, Token gegen den Server verifizieren und bei Erfolg
     * speichern. [onPaired] wird nur bei erfolgreichem Pairing aufgerufen.
     *
     * Die Server-URL wird erst nach erfolgreicher Verifikation übernommen. Ist es ein
     * ANDERER Server als bisher, wird der lokale Cache samt Queue geleert (Seiten-IDs
     * sind serverlokal) — liegen dort noch ungesendete Änderungen, erst nach
     * Rückfrage ([PairingUiState.confirmDiscard]).
     */
    fun couple(onPaired: () -> Unit, discardConfirmed: Boolean = false) {
        val rawUrl = _state.value.serverUrl.trim()
        val token = _state.value.token.trim()

        if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
            _state.value = _state.value.copy(error = PairingError.UrlScheme)
            return
        }
        if (!token.startsWith(TOKEN_PREFIX)) {
            _state.value = _state.value.copy(error = PairingError.TokenPrefix(TOKEN_PREFIX))
            return
        }

        val normalized = SettingsStore.normalizeBaseUrl(rawUrl)
        _state.value = _state.value.copy(serverUrl = normalized, busy = true, error = null, confirmDiscard = null)

        viewModelScope.launch {
            when (val r = network.verifyToken(normalized, token)) {
                is VerifyResult.Ok -> {
                    val previous = settings.serverBaseUrlOnce()
                    if (previous != null && previous != normalized) {
                        val unsynced = repo.unsyncedCount()
                        if (unsynced > 0 && !discardConfirmed) {
                            _state.value = _state.value.copy(busy = false, confirmDiscard = unsynced)
                            return@launch
                        }
                        repo.clearLocalData()
                    }
                    settings.setServerBaseUrl(normalized)
                    val label = _state.value.deviceName.ifBlank { "Android" }
                    tokenStore.save(token, label, null)
                    _state.value = _state.value.copy(busy = false)
                    onPaired()
                }
                is VerifyResult.Unauthorized ->
                    _state.value = _state.value.copy(busy = false, error = PairingError.Unauthorized)
                is VerifyResult.Failed ->
                    _state.value = _state.value.copy(busy = false, error = PairingError.Unreachable(r.message ?: ""))
            }
        }
    }

    companion object {
        fun factory(locator: ServiceLocator): ViewModelProvider.Factory = viewModelFactory {
            initializer { PairingViewModel(locator.settings, locator.tokenStore, locator.network, locator.repository) }
        }
    }
}
