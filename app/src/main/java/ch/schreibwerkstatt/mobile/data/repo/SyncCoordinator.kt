package ch.schreibwerkstatt.mobile.data.repo

import android.content.Context
import ch.schreibwerkstatt.mobile.data.net.ConnectivityObserver
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import ch.schreibwerkstatt.mobile.data.prefs.TokenStore
import ch.schreibwerkstatt.mobile.sync.PeriodicSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * App-weiter Sync-Koordinator (lebt im Application-Scope, überdauert einzelne
 * ViewModels). Aufgaben:
 *
 *  1. **Auto-Flush:** Sobald die Verbindung (wieder) da ist und ein Token vorliegt,
 *     wird die Pending-Write-Queue best-effort geflusht ([ContentRepository.flushPending]),
 *     ebenso das offline Erfasste (Ideen, Recherche — [flushCaptures]).
 *  2. **Periodischer Pull:** Registriert/entfernt den [PeriodicSyncWorker]
 *     (WorkManager, stündlich) je nach Nutzer-Einstellung `backgroundSync`.
 *  3. **Manueller Voll-Sync:** [syncAllNow] stösst Push + Pull über alle Bücher an
 *     (z.B. Button „Jetzt synchronisieren").
 *  4. **Status für die UI:** [online], [pendingCount] und [syncing] als heisse
 *     StateFlows für Banner/Buttons.
 *
 * Der eigentliche Flush/Pull (inkl. 409/423-Übersetzung) steckt unverändert im
 * Repository/SyncEngine — hier wird er nur ausgelöst.
 */
class SyncCoordinator(
    private val appContext: Context,
    private val repository: ContentRepository,
    connectivity: ConnectivityObserver,
    private val tokenStore: TokenStore,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    /** Erfasstes nachsenden (Ideen + Recherche), ausserhalb der Abschnitts-Queue. */
    private val flushCaptures: suspend () -> Unit = {},
) {
    val online: StateFlow<Boolean> =
        connectivity.online.stateIn(scope, SharingStarted.Eagerly, true)

    val pendingCount: StateFlow<Int> =
        repository.observePending()
            .map { it.size }
            .stateIn(scope, SharingStarted.Eagerly, 0)

    private val _syncing = MutableStateFlow(false)

    /** True während ein manueller Voll-Sync ([syncAllNow]) läuft. */
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    /** Beim App-Start aufrufen (App.onCreate). Idempotent — nur einmal nötig. */
    fun start() {
        scope.launch {
            // online ist ein StateFlow → emittiert nur bei echtem Wechsel.
            // Der Startwert (true) löst einen Flush ggf. liegen gebliebener
            // Pending-Writes aus früheren Sessions aus (harmlos, wenn leer).
            online.collect { isOnline -> if (isOnline) flushNow() }
        }
        scope.launch {
            // Periodischen Pull an die Nutzer-Einstellung koppeln.
            settings.backgroundSync.collect { enabled ->
                if (enabled) PeriodicSyncWorker.schedule(appContext)
                else PeriodicSyncWorker.cancel(appContext)
            }
        }
    }

    /** Pending-Queue manuell anstossen (z.B. Pull-to-Refresh). Best effort. */
    fun flushNow() {
        if (tokenStore.token() == null) return
        scope.launch { repository.flushPending() }
        scope.launch { runCatching { flushCaptures() } }
    }

    /**
     * Manueller Voll-Sync über alle Bücher (Push + Pull). Setzt [syncing] für die
     * UI. No-op, wenn kein Token vorliegt oder bereits ein Voll-Sync läuft.
     */
    fun syncAllNow() {
        if (tokenStore.token() == null) return
        if (_syncing.value) return
        _syncing.value = true
        scope.launch {
            try {
                repository.syncAllBooks()
            } finally {
                _syncing.value = false
            }
        }
    }
}
