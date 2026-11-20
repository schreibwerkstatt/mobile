package ch.schreibwerkstatt.mobile.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.schreibwerkstatt.mobile.ServiceLocator
import ch.schreibwerkstatt.mobile.data.net.dto.RevisionDto
import ch.schreibwerkstatt.mobile.data.repo.ContentRepository
import ch.schreibwerkstatt.mobile.ui.components.UiError
import ch.schreibwerkstatt.mobile.ui.components.toUiError
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
    val error: UiError? = null,
    /** Aktuell offene Vorschau (Dialog), sonst null. */
    val preview: RevisionPreview? = null,
    /** true, während ein Restore (inkl. vorgeschaltetem Flush) läuft. */
    val restoring: Boolean = false,
    /** Einmalige Fehler-Snackbar; nach Anzeige via [consumeMessage] löschen. */
    val message: UiError? = null,
    /**
     * Fehler einer Aktion in der offenen Vorschau (Restore, Diff). Wird IM Dialog
     * angezeigt — eine Snackbar läge hinter dem Vollbild-Dialog.
     */
    val previewError: UiError? = null,
    /**
     * Revision, deren Restore auf die Bestätigung «Nicht synchronisierte Änderungen
     * gehen verloren» wartet (der Flush davor ist gescheitert), sonst null.
     */
    val discardWarningRevId: Long? = null,
    /** Einmaliges Ereignis: Restore erfolgreich; Screen navigiert, dann [consumeRestored]. */
    val restoredPageId: Long? = null,
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
                .onFailure { e -> _state.update { it.copy(error = e.toUiError(), loading = false) } }
        }
    }

    /** Vorschau öffnen und den vollen HTML-Body der Revision nachladen. */
    fun openPreview(rev: RevisionDto) {
        _state.update { it.copy(preview = RevisionPreview(rev), previewError = null) }
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
                            s.copy(preview = null, message = e.toUiError())
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
                                previewError = e.toUiError(),
                            )
                        } else s
                    }
                }
        }
    }

    fun closePreview() {
        if (_state.value.restoring) return
        _state.update { it.copy(preview = null, previewError = null, discardWarningRevId = null) }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun consumePreviewError() = _state.update { it.copy(previewError = null) }

    fun consumeRestored() = _state.update { it.copy(restoredPageId = null) }

    /**
     * Revision wiederherstellen (nach der ersten Bestätigung). Das Wiederherstellen
     * verwirft lokale, noch nicht gesyncte Änderungen — darum erst diesen Abschnitt
     * flushen: Gelingt das, liegen die Änderungen als eigene Revision am Server und
     * gehen nicht verloren. Scheitert es (offline, Konflikt, Sperre), wird nichts
     * verworfen, sondern [HistoryUiState.discardWarningRevId] gesetzt; erst
     * [confirmDiscardAndRestore] stellt dann trotzdem wieder her.
     * Erfolg meldet [HistoryUiState.restoredPageId].
     */
    fun restore(revId: Long) {
        if (_state.value.restoring) return
        _state.update { it.copy(restoring = true, previewError = null) }
        viewModelScope.launch {
            if (repo.hasUnsyncedChanges(pageId) && !repo.flushPage(pageId)) {
                _state.update { it.copy(restoring = false, discardWarningRevId = revId) }
                return@launch
            }
            doRestore(revId)
        }
    }

    /** «Trotzdem wiederherstellen»: lokale, nicht gesyncte Änderungen bewusst verwerfen. */
    fun confirmDiscardAndRestore() {
        val revId = _state.value.discardWarningRevId ?: return
        if (_state.value.restoring) return
        _state.update { it.copy(restoring = true, discardWarningRevId = null, previewError = null) }
        viewModelScope.launch { doRestore(revId) }
    }

    fun dismissDiscardWarning() = _state.update { it.copy(discardWarningRevId = null) }

    private suspend fun doRestore(revId: Long) {
        repo.restoreRevision(pageId, revId, bookId)
            .onSuccess {
                _state.update { it.copy(restoring = false, preview = null, restoredPageId = pageId) }
            }
            .onFailure { e ->
                _state.update { it.copy(restoring = false, previewError = e.toUiError()) }
            }
    }

    companion object {
        fun factory(locator: ServiceLocator, bookId: Long, pageId: Long): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { HistoryViewModel(locator.repository, bookId, pageId) }
            }
    }
}
