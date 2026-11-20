package ch.schreibwerkstatt.mobile.ui.research

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.schreibwerkstatt.mobile.ServiceLocator
import ch.schreibwerkstatt.mobile.data.db.BookEntity
import ch.schreibwerkstatt.mobile.data.repo.ContentRepository
import ch.schreibwerkstatt.mobile.data.repo.ResearchRepository
import ch.schreibwerkstatt.mobile.ui.components.UiError
import ch.schreibwerkstatt.mobile.ui.components.toUiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel des Recherche-Capture-Formulars. Liefert die (gecachte) Buchliste für
 * die Auswahl und speichert das Item **online** über [ResearchRepository]. Die
 * Buchliste kommt aus dem Room-Cache von [ContentRepository] und wird beim Öffnen
 * still aufgefrischt (der Share kann aus jeder App kommen, auch nach Kaltstart).
 */
class ResearchCaptureViewModel(
    private val content: ContentRepository,
    private val research: ResearchRepository,
) : ViewModel() {

    val books: StateFlow<List<BookEntity>> = content.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    /** Einmalig konsumierbares Ergebnis (Erfolg → Screen schliessen; Fehler → Snackbar). */
    private val _result = MutableStateFlow<CaptureResult?>(null)
    val result: StateFlow<CaptureResult?> = _result.asStateFlow()

    /**
     * Letztes Auffrischen der Buchliste gescheitert. Nur relevant, solange der Cache
     * leer ist — dann zeigt der Screen einen Hinweis mit «Erneut versuchen», sonst
     * bliebe Speichern mangels Buchauswahl für immer gesperrt.
     */
    private val _booksFailed = MutableStateFlow(false)
    val booksFailed: StateFlow<Boolean> = _booksFailed.asStateFlow()

    private val _booksLoading = MutableStateFlow(false)
    val booksLoading: StateFlow<Boolean> = _booksLoading.asStateFlow()

    init {
        refreshBooks() // still; Cache genügt offline
    }

    fun refreshBooks() {
        if (_booksLoading.value) return
        viewModelScope.launch {
            _booksLoading.value = true
            _booksFailed.value = content.refreshBooks().isFailure
            _booksLoading.value = false
        }
    }

    fun save(bookId: Long, url: String, title: String, note: String) {
        if (_saving.value) return
        viewModelScope.launch {
            _saving.value = true
            research.createLink(bookId, url = normalizeResearchUrl(url), title = title, note = note)
                .onSuccess { _result.value = CaptureResult.Saved }
                .onFailure { _result.value = CaptureResult.Error(it.toUiError()) }
            _saving.value = false
        }
    }

    fun consumeResult() { _result.value = null }

    companion object {
        fun factory(locator: ServiceLocator): ViewModelProvider.Factory = viewModelFactory {
            initializer { ResearchCaptureViewModel(locator.repository, locator.researchRepository) }
        }
    }
}

sealed interface CaptureResult {
    data object Saved : CaptureResult
    data class Error(val error: UiError) : CaptureResult
}
