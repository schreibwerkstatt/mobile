package ch.schreibwerkstatt.mobile.ui.research

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.schreibwerkstatt.mobile.ServiceLocator
import ch.schreibwerkstatt.mobile.data.net.dto.ResearchItemDto
import ch.schreibwerkstatt.mobile.data.repo.ResearchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel der Recherche-Liste eines Buchs. **Online-first**: lädt bei jedem
 * Sichtbarwerden des Screens (auch nach „+ Erfassen" zurück) und bei
 * Pull-to-Refresh frisch vom Server (kein lokaler Cache in v1).
 */
class ResearchListViewModel(
    private val bookId: Long,
    private val research: ResearchRepository,
) : ViewModel() {

    private val _items = MutableStateFlow<List<ResearchItemDto>>(emptyList())
    val items: StateFlow<List<ResearchItemDto>> = _items.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** True, sobald mindestens einmal (egal ob erfolgreich) geladen wurde. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /**
     * Letzter Ladeversuch ist gescheitert. Ohne lokalen Cache hiesse eine leere Liste
     * dann NICHT „keine Recherche" — der Screen zeigt stattdessen den Fehlerzustand.
     */
    private val _loadFailed = MutableStateFlow(false)
    val loadFailed: StateFlow<Boolean> = _loadFailed.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _loading.value = true
            research.list(bookId)
                .onSuccess {
                    _items.value = it
                    _loadFailed.value = false
                }
                .onFailure {
                    _loadFailed.value = true
                    _error.value = it.message
                }
            _loading.value = false
            _loaded.value = true
        }
    }

    fun clearError() { _error.value = null }

    companion object {
        fun factory(locator: ServiceLocator, bookId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer { ResearchListViewModel(bookId, locator.researchRepository) }
        }
    }
}
