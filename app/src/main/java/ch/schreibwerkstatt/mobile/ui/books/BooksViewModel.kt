package ch.schreibwerkstatt.mobile.ui.books

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.schreibwerkstatt.mobile.ServiceLocator
import ch.schreibwerkstatt.mobile.data.db.BookEntity
import ch.schreibwerkstatt.mobile.data.repo.ContentRepository
import ch.schreibwerkstatt.mobile.ui.components.UiError
import ch.schreibwerkstatt.mobile.ui.components.toUiError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BooksViewModel(private val repo: ContentRepository) : ViewModel() {

    /** Bücher aus dem Room-Cache; `null`, solange der Cache noch nicht gelesen ist. */
    val books: StateFlow<List<BookEntity>?> = repo.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * Fehler des letzten Abrufs; bleibt bis zum nächsten erfolgreichen Refresh stehen.
     * Ohne Bücher im Cache zeigt der Screen daraus den „nicht geladen"-Zustand mit
     * Wiederholen statt eines irreführenden „Keine Bücher gefunden".
     */
    private val _loadError = MutableStateFlow<UiError?>(null)
    val loadError: StateFlow<UiError?> = _loadError.asStateFlow()

    /** Einmalige Snackbar-Meldung (nur bei vom Nutzer ausgelöstem Refresh). */
    private val _message = MutableStateFlow<UiError?>(null)
    val message: StateFlow<UiError?> = _message.asStateFlow()

    // Automatischer Erst-Load: still, damit ein Offline-Kaltstart KEINE Fehler-Snackbar
    // zeigt, obwohl der Room-Cache (observeBooks) gültige Bücher liefert. Der Fehler
    // bleibt trotzdem in [loadError] — ist der Cache leer, zeigt der Screen ihn.
    init { refresh(silent = true) }

    fun refresh(silent: Boolean = false) {
        viewModelScope.launch {
            _refreshing.value = true
            repo.refreshBooks()
                .onSuccess { _loadError.value = null }
                .onFailure {
                    val error = it.toUiError()
                    _loadError.value = error
                    if (!silent) _message.value = error
                }
            _refreshing.value = false
        }
    }

    fun consumeMessage() { _message.value = null }

    companion object {
        fun factory(locator: ServiceLocator): ViewModelProvider.Factory = viewModelFactory {
            initializer { BooksViewModel(locator.repository) }
        }
    }
}
