package ch.schreibwerkstatt.mobile.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.schreibwerkstatt.mobile.ServiceLocator
import ch.schreibwerkstatt.mobile.data.net.dto.RevisionDto
import ch.schreibwerkstatt.mobile.data.repo.ContentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Vorschau einer einzelnen Revision (HTML wird nachgeladen). */
data class RevisionPreview(
    val revision: RevisionDto,
    val loadingHtml: Boolean = true,
    val html: String? = null,
    /** Unmittelbare Vorgängerin (Meta, aus `prev`), null bei der ältesten Revision. */
    val prev: RevisionDto? = null,
    /** true = Änderungen gegenüber [prev] statt des Volltexts zeigen. */
    val showDiff: Boolean = false,
    val loadingDiff: Boolean = false,
    /** Diff-HTML gegen [prev], sobald geladen. */
    val diffHtml: String? = null,
    /** true, wenn sich der sichtbare Text gegenüber [prev] nicht geändert hat. */
    val diffEmpty: Boolean = false,
)

data class HistoryUiState(
    val loading: Boolean = true,
    val revisions: List<RevisionDto> = emptyList(),
    val error: String? = null,
    /** Aktuell offene Vorschau (Dialog), sonst null. */
    val preview: RevisionPreview? = null,
    /** true, während ein Restore läuft. */
    val restoring: Boolean = false,
    /** Einmalige Fehler-Snackbar; nach Anzeige via [consumeMessage] löschen. */
    val message: String? = null,
)

class HistoryViewModel(
    private val repo: ContentRepository,
    private val bookId: Long,
    private val pageId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            repo.pageRevisions(pageId)
                .onSuccess { revs -> _state.update { it.copy(revisions = revs, loading = false) } }
                .onFailure { e -> _state.update { it.copy(error = e.message, loading = false) } }
        }
    }

    /** Vorschau öffnen und den vollen HTML-Body der Revision nachladen. */
    fun openPreview(rev: RevisionDto) {
        _state.update { it.copy(preview = RevisionPreview(rev)) }
        viewModelScope.launch {
            repo.pageRevision(pageId, rev.id)
                .onSuccess { (full, prev) ->
                    _state.update { s ->
                        // Nur übernehmen, wenn die Vorschau noch dieselbe Revision zeigt.
                        if (s.preview?.revision?.id == rev.id) {
                            s.copy(
                                preview = s.preview.copy(
                                    loadingHtml = false,
                                    html = full.body_html ?: "",
                                    prev = prev,
                                )
                            )
                        } else s
                    }
                }
                .onFailure { e ->
                    _state.update { s ->
                        if (s.preview?.revision?.id == rev.id) {
                            s.copy(preview = null, message = e.message)
                        } else s
                    }
                }
        }
    }

    /**
     * Zwischen Volltext und «Änderungen gegenüber der Vorgängerin» umschalten. Der Body
     * der Vorgängerin wird beim ersten Mal nachgeladen und der Diff einmal gerechnet.
     */
    fun toggleDiff() {
        val p = _state.value.preview ?: return
        val prev = p.prev ?: return
        val html = p.html ?: return
        val show = !p.showDiff
        _state.update { s -> s.copy(preview = s.preview?.copy(showDiff = show)) }
        if (!show || p.diffHtml != null || p.loadingDiff) return
        val revId = p.revision.id
        _state.update { s -> s.copy(preview = s.preview?.copy(loadingDiff = true)) }
        viewModelScope.launch {
            repo.pageRevision(pageId, prev.id)
                .mapCatching { (prevFull, _) ->
                    withContext(Dispatchers.Default) {
                        RevisionDiff.diff(prevFull.body_html ?: "", html)
                    }
                }
                .onSuccess { blocks ->
                    _state.update { s ->
                        if (s.preview?.revision?.id == revId) {
                            s.copy(
                                preview = s.preview.copy(
                                    loadingDiff = false,
                                    diffHtml = RevisionDiff.toHtml(blocks),
                                    diffEmpty = RevisionDiff.unchanged(blocks),
                                )
                            )
                        } else s
                    }
                }
                .onFailure { e ->
                    _state.update { s ->
                        if (s.preview?.revision?.id == revId) {
                            s.copy(
                                preview = s.preview.copy(loadingDiff = false, showDiff = false),
                                message = e.message,
                            )
                        } else s
                    }
                }
        }
    }

    fun closePreview() = _state.update { it.copy(preview = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /**
     * Revision wiederherstellen. Bei Erfolg [onRestored] mit der Page-ID aufrufen
     * (für die Navigation zurück in den frischen Editor-Stand).
     */
    fun restore(revId: Long, onRestored: (pageId: Long) -> Unit) {
        if (_state.value.restoring) return
        viewModelScope.launch {
            _state.update { it.copy(restoring = true) }
            repo.restoreRevision(pageId, revId, bookId)
                .onSuccess {
                    _state.update { it.copy(restoring = false, preview = null) }
                    onRestored(pageId)
                }
                .onFailure { e ->
                    _state.update { it.copy(restoring = false, message = e.message) }
                }
        }
    }

    companion object {
        fun factory(locator: ServiceLocator, bookId: Long, pageId: Long): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { HistoryViewModel(locator.repository, bookId, pageId) }
            }
    }
}
