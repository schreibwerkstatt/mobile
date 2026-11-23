package ch.schreibwerkstatt.mobile.ui.ideen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.schreibwerkstatt.mobile.ServiceLocator
import ch.schreibwerkstatt.mobile.audio.DictationController
import ch.schreibwerkstatt.mobile.data.db.BookEntity
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import ch.schreibwerkstatt.mobile.data.repo.ContentRepository
import ch.schreibwerkstatt.mobile.data.repo.CreateIdeaResult
import ch.schreibwerkstatt.mobile.data.repo.IdeenRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class IdeeCaptureState(
    val text: String = "",
    val bookId: Long? = null,
    val place: IdeePlace = IdeePlace.Book,
    val saving: Boolean = false,
    val recording: Boolean = false,
    val transcribing: Boolean = false,
    /** Live-Pegel 0..1 während der Aufnahme. */
    val level: Float = 0f,
    val result: CreateIdeaResult? = null,
    /** Einmalige Fehlermeldung (Diktat). */
    val message: String? = null,
)

/**
 * Schnell-Erfassen einer Idee. Erreichbar aus der Ideen-Liste (Buch vorgewählt),
 * App-Shortcut, Schnelleinstellungs-Kachel und „Teilen → Als Idee" (Buch = das zuletzt
 * benutzte). Speichert über [IdeenRepository.create] — ohne Netz vorgemerkt, nie verloren.
 */
class IdeeCaptureViewModel(
    private val content: ContentRepository,
    private val ideen: IdeenRepository,
    private val settings: SettingsStore,
    private val dictation: DictationController,
    preselectBookId: Long?,
    initialText: String,
    /** Ort vorbelegt (Einstieg aus dem Editor: der offene Abschnitt). */
    initialPlace: IdeePlace = IdeePlace.Book,
) : ViewModel() {

    val books: StateFlow<List<BookEntity>> = content.observeBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _state = MutableStateFlow(IdeeCaptureState(text = initialText, bookId = preselectBookId, place = initialPlace))
    val state: StateFlow<IdeeCaptureState> = _state.asStateFlow()

    private var monitorJob: Job? = null

    init {
        viewModelScope.launch { content.refreshBooks() } // still; offline genügt der Cache
        if (preselectBookId == null) {
            viewModelScope.launch {
                val last = settings.lastIdeaBookId()
                // Erste nicht-leere Buchliste abwarten (Kaltstart über Shortcut/Kachel).
                val list = content.observeBooks().first { it.isNotEmpty() }
                if (_state.value.bookId == null) {
                    val pick = last?.takeIf { id -> list.any { it.id == id } } ?: list.singleOrNull()?.id
                    _state.update { it.copy(bookId = pick) }
                }
            }
        }
    }

    fun setText(text: String) = _state.update { it.copy(text = text) }

    fun selectBook(bookId: Long) = _state.update {
        // Ort gehört zum Buch — beim Wechsel zurück aufs ganze Buch.
        if (it.bookId == bookId) it else it.copy(bookId = bookId, place = IdeePlace.Book)
    }

    fun selectPlace(place: IdeePlace) = _state.update { it.copy(place = place) }

    fun save() {
        val s = _state.value
        val bookId = s.bookId ?: return
        if (s.saving || s.text.isBlank()) return
        if (dictation.isRecording) {
            monitorJob?.cancel()
            dictation.cancel()
        }
        _state.update { it.copy(saving = true, recording = false) }
        viewModelScope.launch {
            val r = ideen.create(bookId, s.text, s.place.pageId, s.place.chapterId)
            if (r !is CreateIdeaResult.Invalid) settings.setLastIdeaBookId(bookId)
            _state.update { it.copy(saving = false, result = r) }
        }
    }

    fun consumeResult() = _state.update { it.copy(result = null) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    // ── Diktat ──────────────────────────────────────────────────────────────

    fun toggleDictation() {
        if (!dictation.isRecording) {
            dictation.startRecording()
                .onSuccess {
                    _state.update { it.copy(recording = true, level = 0f) }
                    monitorJob = viewModelScope.launch { monitorSilence() }
                }
                .onFailure { e -> _state.update { it.copy(message = e.message ?: e.javaClass.simpleName) } }
        } else {
            monitorJob?.cancel()
            monitorJob = null
            transcribe()
        }
    }

    /** Segment nach einer Sprechpause oder an der Maximaldauer selbst beenden. */
    private suspend fun monitorSilence() {
        var spoke = false
        var silentMs = 0L
        var elapsedMs = 0L
        while (dictation.isRecording) {
            delay(POLL_MS)
            elapsedMs += POLL_MS
            val amplitude = dictation.currentAmplitude()
            val target = (amplitude / LEVEL_FULL_SCALE).coerceIn(0f, 1f)
            _state.update { it.copy(level = it.level + (target - it.level) * 0.4f) }
            if (amplitude >= SPEECH_AMPLITUDE) { spoke = true; silentMs = 0 } else silentMs += POLL_MS
            if ((spoke && silentMs >= SILENCE_HANGOVER_MS) || elapsedMs >= MAX_SEGMENT_MS) {
                monitorJob = null
                transcribe()
                return
            }
        }
    }

    private fun transcribe() {
        if (!dictation.isRecording) return
        val bookId = _state.value.bookId
        if (bookId == null) {
            dictation.cancel()
            _state.update { it.copy(recording = false, level = 0f) }
            return
        }
        _state.update { it.copy(recording = false, transcribing = true, level = 0f) }
        viewModelScope.launch {
            dictation.stopAndTranscribe(bookId, _state.value.place.pageId)
                .onSuccess { text ->
                    _state.update { st ->
                        val add = text.trim()
                        if (add.isEmpty()) st.copy(transcribing = false)
                        else st.copy(transcribing = false, text = appendDictated(st.text, add))
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(transcribing = false, message = e.message ?: e.javaClass.simpleName) }
                }
        }
    }

    override fun onCleared() {
        monitorJob?.cancel()
        if (dictation.isRecording) dictation.cancel()
    }

    companion object {
        private const val POLL_MS = 100L
        private const val SPEECH_AMPLITUDE = 2_500
        private const val SILENCE_HANGOVER_MS = 2_000L
        private const val MAX_SEGMENT_MS = 60_000L
        private const val LEVEL_FULL_SCALE = 12_000f

        /** Diktat an den bestehenden Text anhängen, durch ein Leerzeichen getrennt. */
        internal fun appendDictated(current: String, add: String): String = when {
            current.isBlank() -> add
            current.endsWith(" ") || current.endsWith("\n") -> current + add
            else -> "$current $add"
        }

        fun factory(
            locator: ServiceLocator,
            preselectBookId: Long?,
            initialText: String,
            initialPlace: IdeePlace = IdeePlace.Book,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    IdeeCaptureViewModel(
                        locator.repository,
                        locator.ideenRepository,
                        locator.settings,
                        locator.dictationController(),
                        preselectBookId,
                        initialText,
                        initialPlace,
                    )
                }
            }
    }
}
