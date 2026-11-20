package ch.schreibwerkstatt.mobile.ui.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.schreibwerkstatt.mobile.R
import ch.schreibwerkstatt.mobile.ServiceLocator
import ch.schreibwerkstatt.mobile.data.net.NetworkClient
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import ch.schreibwerkstatt.mobile.data.prefs.ThemeMode
import ch.schreibwerkstatt.mobile.data.prefs.TokenStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** STT-Verfügbarkeit als lokalisierbarer Zustand (Label erst in der UI aufgelöst). */
enum class SttStatus(@StringRes val label: Int) {
    LOADING(R.string.settings_stt_loading),
    ENABLED(R.string.settings_stt_enabled),
    DISABLED(R.string.settings_stt_disabled),
    UNKNOWN(R.string.settings_stt_unknown),
}

data class SettingsUiState(
    val serverUrl: String = "",
    val deviceName: String = "",
    val sttStatus: SttStatus = SttStatus.LOADING,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val deviceId: String = "",
    val backgroundSync: Boolean = true,
    /** Nutzer-Wunsch für die Rechtschreibprüfung im Editor. */
    val spellcheck: Boolean = true,
    /** Bietet der Server LanguageTool an? null = noch unbekannt/nicht erreichbar. */
    val spellcheckAvailable: Boolean? = null,
    /** Wischen nach links auf Abschnittszeilen öffnet den Verlauf. */
    val swipeToHistory: Boolean = true,
)

class SettingsViewModel(
    private val settings: SettingsStore,
    private val tokenStore: TokenStore,
    private val network: NetworkClient,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(deviceName = tokenStore.deviceName() ?: "—"))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val base = settings.serverBaseUrlOnce().orEmpty()
            _state.update { it.copy(serverUrl = base, deviceId = settings.deviceId()) }
            if (base.isNotBlank()) {
                val cfg = runCatching { network.config(base).config() }
                val status = cfg
                    .map { if (it.stt?.enabled == true) SttStatus.ENABLED else SttStatus.DISABLED }
                    .getOrElse { SttStatus.UNKNOWN }
                _state.update {
                    it.copy(
                        sttStatus = status,
                        spellcheckAvailable = cfg.map { c -> c.languagetool?.enabled == true }.getOrNull(),
                    )
                }
            }
        }
        viewModelScope.launch {
            settings.spellcheck.collect { on -> _state.update { it.copy(spellcheck = on) } }
        }
        viewModelScope.launch {
            settings.themeMode.collect { mode -> _state.update { it.copy(themeMode = mode) } }
        }
        viewModelScope.launch {
            settings.backgroundSync.collect { on -> _state.update { it.copy(backgroundSync = on) } }
        }
        viewModelScope.launch {
            settings.swipeToHistory.collect { on -> _state.update { it.copy(swipeToHistory = on) } }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settings.setThemeMode(mode) }
    }

    fun setBackgroundSync(enabled: Boolean) {
        viewModelScope.launch { settings.setBackgroundSync(enabled) }
    }

    fun setSpellcheck(enabled: Boolean) {
        viewModelScope.launch { settings.setSpellcheck(enabled) }
    }

    fun setSwipeToHistory(enabled: Boolean) {
        viewModelScope.launch { settings.setSwipeToHistory(enabled) }
    }

    /** Lokales Abmelden: nur den Token verwerfen (kein Server-Revoke). */
    fun signOut() = tokenStore.clear()

    companion object {
        fun factory(locator: ServiceLocator): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(locator.settings, locator.tokenStore, locator.network) }
        }
    }
}
