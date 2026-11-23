package ch.schreibwerkstatt.mobile.ui.ideen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.schreibwerkstatt.mobile.ServiceLocator
import ch.schreibwerkstatt.mobile.data.db.PendingIdeaEntity
import ch.schreibwerkstatt.mobile.data.net.dto.IdeeDto
import ch.schreibwerkstatt.mobile.data.repo.Anchor
import ch.schreibwerkstatt.mobile.data.repo.ChangeResult
import ch.schreibwerkstatt.mobile.data.repo.CreateIdeaResult
import ch.schreibwerkstatt.mobile.data.repo.IdeenBoardView
import ch.schreibwerkstatt.mobile.data.repo.IdeenRepository
import ch.schreibwerkstatt.mobile.ui.components.UiError
import ch.schreibwerkstatt.mobile.ui.components.toUiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IdeenListState(
    val loading: Boolean = false,
    /** Letzter Ladeversuch gescheitert — ohne Cache heisst leer NICHT „keine Ideen". */
    val loadFailed: Boolean = false,
    val showClosed: Boolean = false,
    /** Nur die Ideen des Abschnitts zeigen, aus dem die Liste geöffnet wurde. */
    val onlySection: Boolean = true,
    /** Bearbeiten-Sheet schliessen (gespeichert, vorgemerkt oder abgewiesen). */
    val editDone: Boolean = false,
    val message: IdeenMsg? = null,
)

sealed interface IdeenMsg {
    data class LoadError(val error: UiError) : IdeenMsg
    data class EditError(val code: String) : IdeenMsg
    data class Stuck(val code: String) : IdeenMsg
    data object Queued : IdeenMsg
}

/**
 * Ideen eines Buchs. Das Board kommt aus dem Cache von [IdeenRepository] mit den
 * vorgemerkten Änderungen darüber und wird bei jedem Sichtbarwerden und per
 * Pull-to-Refresh frisch geladen (vorher wird die Queue nachgesendet). Bearbeiten,
 * Stufe, Ort und Löschen wirken sofort in der Liste und gehen offline vorgemerkt raus.
 *
 * [sectionId] gesetzt (Einstieg aus dem Editor): die Liste filtert auf diesen
 * Abschnitt, bis der Nutzer auf „Ganzes Buch" umschaltet.
 */
class IdeenListViewModel(
    private val bookId: Long,
    val sectionId: Long?,
    private val ideen: IdeenRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(IdeenListState())
    val state: StateFlow<IdeenListState> = _state.asStateFlow()

    val board: StateFlow<IdeenBoardView?> = ideen.observeBoard(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val pending: StateFlow<List<PendingIdeaEntity>> = ideen.observePending(bookId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refresh() {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            if (ideen.hasPending()) ideen.flushPending()
            ideen.refresh(bookId)
                .onSuccess { _state.update { it.copy(loadFailed = false) } }
                .onFailure { e -> _state.update { it.copy(loadFailed = true, message = IdeenMsg.LoadError(e.toUiError())) } }
            _state.update { it.copy(loading = false) }
        }
    }

    fun setShowClosed(show: Boolean) = _state.update { it.copy(showClosed = show) }
    fun setOnlySection(only: Boolean) = _state.update { it.copy(onlySection = only) }

    /** Abhaken / wieder öffnen direkt aus der Liste. */
    fun toggleDone(idee: IdeeDto) {
        val target = if (idee.status == IdeeDto.STATUS_ERLEDIGT) IdeeDto.STATUS_OFFEN else IdeeDto.STATUS_ERLEDIGT
        change { ideen.setStatus(bookId, idee.id, target) }
    }

    /** Bearbeiten-Sheet: nur geänderte Felder gehen raus. */
    fun save(original: IdeeDto, content: String, status: String, place: IdeePlace) {
        val newContent = content.trim().takeIf { it != original.content.trim() }
        val newStatus = status.takeIf { it != original.status }
        val moved = place.pageId != original.page_id || place.chapterId != original.chapter_id
        if (newContent == null && newStatus == null && !moved) {
            _state.update { it.copy(editDone = true) }
            return
        }
        val placeName = when (place) {
            is IdeePlace.Page -> place.name
            is IdeePlace.Chapter -> place.name
            IdeePlace.Book -> null
        }
        change(closeSheet = true) {
            ideen.update(
                bookId, original.id, newContent, newStatus,
                place = if (moved) Anchor(place.pageId, place.chapterId) else null,
                placeName = placeName,
            )
        }
    }

    fun delete(idee: IdeeDto) = change(closeSheet = true) { ideen.delete(bookId, idee.id) }

    fun retryChange(ideeId: Long) = change { ideen.retryChange(ideeId) }

    fun discardChange(ideeId: Long) {
        viewModelScope.launch { ideen.discardChange(ideeId) }
    }

    private fun change(closeSheet: Boolean = false, call: suspend () -> ChangeResult) {
        viewModelScope.launch {
            val r = call()
            _state.update {
                it.copy(
                    editDone = (closeSheet && r !is ChangeResult.Invalid) || it.editDone,
                    message = when (r) {
                        ChangeResult.Saved -> it.message
                        ChangeResult.Queued -> IdeenMsg.Queued
                        is ChangeResult.Stuck -> IdeenMsg.EditError(r.code)
                        is ChangeResult.Invalid -> IdeenMsg.EditError(r.code)
                    },
                )
            }
        }
    }

    // ── Vorgemerkte neue Ideen ──────────────────────────────────────────────

    fun retryPending(localId: Long) {
        viewModelScope.launch {
            when (val r = ideen.retryStuck(localId)) {
                is CreateIdeaResult.Saved -> Unit // der Cache trägt die Idee schon
                CreateIdeaResult.Queued -> _state.update { it.copy(message = IdeenMsg.Queued) }
                is CreateIdeaResult.Stuck -> _state.update { it.copy(message = IdeenMsg.Stuck(r.code)) }
                is CreateIdeaResult.Invalid -> _state.update { it.copy(message = IdeenMsg.EditError(r.code)) }
            }
        }
    }

    fun discardPending(localId: Long) {
        viewModelScope.launch { ideen.discardPending(localId) }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }
    fun consumeEditDone() = _state.update { it.copy(editDone = false) }

    companion object {
        fun factory(locator: ServiceLocator, bookId: Long, sectionId: Long?): ViewModelProvider.Factory = viewModelFactory {
            initializer { IdeenListViewModel(bookId, sectionId, locator.ideenRepository) }
        }
    }
}
