package ch.schreibwerkstatt.mobile.ui.research

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.schreibwerkstatt.mobile.ServiceLocator
import ch.schreibwerkstatt.mobile.data.net.dto.ResearchItemDto
import ch.schreibwerkstatt.mobile.data.db.ResearchOutboxEntity
import ch.schreibwerkstatt.mobile.data.repo.QueuedResearch
import ch.schreibwerkstatt.mobile.data.repo.ResearchRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ch.schreibwerkstatt.mobile.ui.components.UiError
import ch.schreibwerkstatt.mobile.ui.components.toUiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel der Recherche-Liste eines Buchs. Die Liste lädt bei jedem Sichtbarwerden
 * des Screens (auch nach „+ Erfassen" zurück) und bei Pull-to-Refresh frisch vom
 * Server (kein Lese-Cache). Darüber stehen die vorgemerkten Erfassungen aus der
 * Outbox; sobald eine davon durch ist, lädt die Liste neu.
 */
class ResearchListViewModel(
    private val bookId: Long,
    private val research: ResearchRepository,
) : ViewModel() {

    private val _items = MutableStateFlow<List<ResearchItemDto>>(emptyList())
    val items: StateFlow<List<ResearchItemDto>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<UiError?>(null)
    val error: StateFlow<UiError?> = _error.asStateFlow()

    /** True, sobald mindestens einmal (egal ob erfolgreich) geladen wurde. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /**
     * Letzter Ladeversuch ist gescheitert. Ohne lokalen Cache hiesse eine leere Liste
     * dann NICHT „keine Recherche" — der Screen zeigt stattdessen den Fehlerzustand.
     */
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    /** Vorgemerkte Erfassungen (wartend, Dublette, abgewiesen), neueste zuerst. */
    val queued: StateFlow<List<QueuedResearch>> = research.observeQueued(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Ist eine Erfassung durch (Zeile weg oder angelegt), steht sie jetzt am Server.
        viewModelScope.launch {
            var lastDone = -1
            research.observeQueued(bookId)
                .map { rows -> rows.count { it.status == ResearchOutboxEntity.STATUS_PENDING && !it.created } }
                .distinctUntilChanged()
                .collect { waiting ->
                    if (lastDone >= 0 && waiting < lastDone) refresh()
                    lastDone = waiting
                }
        }
    }

    private var refreshJob: Job? = null

    /** Neu laden; läuft schon ein Abruf, wird kein zweiter gestartet (sonst überholt ein älterer den neueren). */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            _loading.value = true
            research.list(bookId)
                .onSuccess {
                    _items.value = it
                    _loadFailed.value = false
                }
                .onFailure {
                    _loadFailed.value = true
                    _error.value = it.toUiError()
                }
            _loading.value = false
            _loaded.value = true
        }
    }

    fun clearError() { _error.value = null }

    fun discardQueued(localId: Long) {
        viewModelScope.launch { research.discard(localId) }
    }

    fun retryQueued(localId: Long) {
        viewModelScope.launch { research.retry(localId) }
    }

    /** Dublette trotzdem speichern (`allow_duplicate`). */
    fun keepDuplicate(localId: Long) {
        viewModelScope.launch { research.resolveDuplicate(localId, keep = true) }
    }

    companion object {
        fun factory(locator: ServiceLocator, bookId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer { ResearchListViewModel(bookId, locator.researchRepository) }
        }
    }
}
