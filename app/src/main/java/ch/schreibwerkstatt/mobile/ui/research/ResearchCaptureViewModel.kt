package ch.schreibwerkstatt.mobile.ui.research

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.schreibwerkstatt.mobile.ServiceLocator
import ch.schreibwerkstatt.mobile.data.db.BookEntity
import android.net.Uri
import ch.schreibwerkstatt.mobile.data.repo.CaptureOutcome
import ch.schreibwerkstatt.mobile.data.repo.ContentRepository
import ch.schreibwerkstatt.mobile.data.repo.ResearchDraft
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
 * die Auswahl und erfasst das Item über [ResearchRepository] — vorgemerkt in der
 * Outbox und sofort gesendet; ohne Netz geht es später raus. Die Buchliste kommt aus
 * dem Room-Cache von [ContentRepository] und wird beim Öffnen still aufgefrischt (der
 * Share kann aus jeder App kommen, auch nach Kaltstart).
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

    fun save(bookId: Long, url: String, title: String, note: String, imageUri: String?, imageMime: String?) {
        if (_saving.value) return
        viewModelScope.launch {
            _saving.value = true
            val outcome = runCatching {
                research.capture(
                    ResearchDraft(
                        bookId = bookId,
                        url = normalizeResearchUrl(url),
                        title = title,
                        note = note,
                        imageUri = imageUri?.let(Uri::parse),
                        imageMime = imageMime,
                    )
                )
            }
            _result.value = outcome.fold(::toResult) { CaptureResult.Error(it.toUiError()) }
            _saving.value = false
        }
    }

    /** Antwort auf die Dubletten-Rückfrage: [keep] = trotzdem speichern, sonst verwerfen (Formular bleibt). */
    fun resolveDuplicate(localId: Long, keep: Boolean) {
        if (_saving.value) return
        viewModelScope.launch {
            _saving.value = true
            val outcome = runCatching { research.resolveDuplicate(localId, keep) }
            _result.value = outcome.fold({ it?.let(::toResult) }) { CaptureResult.Error(it.toUiError()) }
            _saving.value = false
        }
    }

    private fun toResult(o: CaptureOutcome): CaptureResult = when (o) {
        CaptureOutcome.Saved -> CaptureResult.Saved
        CaptureOutcome.Queued -> CaptureResult.Queued
        is CaptureOutcome.Duplicate -> CaptureResult.Duplicate(o.localId, o.existingTitle)
        is CaptureOutcome.Rejected -> CaptureResult.Rejected(o.code)
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
    /** Ohne Verbindung vorgemerkt — geht automatisch raus. */
    data object Queued : CaptureResult
    /** Link schon im Buch erfasst — Rückfrage. */
    data class Duplicate(val localId: Long, val existingTitle: String?) : CaptureResult
    /** Vom Server abgewiesen (`error_code`) — Formular bleibt offen. */
    data class Rejected(val code: String) : CaptureResult
    data class Error(val error: UiError) : CaptureResult
}
