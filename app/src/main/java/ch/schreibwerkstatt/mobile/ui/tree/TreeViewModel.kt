package ch.schreibwerkstatt.mobile.ui.tree

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.schreibwerkstatt.mobile.ServiceLocator
import ch.schreibwerkstatt.mobile.data.db.PageContentHit
import ch.schreibwerkstatt.mobile.data.net.dto.ChapterNodeDto
import ch.schreibwerkstatt.mobile.data.net.dto.TreeDto
import ch.schreibwerkstatt.mobile.data.repo.BUCHTYP_TAGEBUCH
import ch.schreibwerkstatt.mobile.data.repo.ContentRepository
import ch.schreibwerkstatt.mobile.data.repo.SyncCoordinator
import ch.schreibwerkstatt.mobile.data.repo.diaryDateOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth

sealed interface TreeRow {
    val depth: Int
    data class Chapter(val name: String, override val depth: Int) : TreeRow
    data class Page(val id: Long, val name: String, override val depth: Int) : TreeRow
}

data class TreeUiState(
    val bookId: Long,
    val rows: List<TreeRow> = emptyList(),
    /** Server-`updated_at` (ISO-8601) je Seite aus dem lokalen Cache; Quelle für „zuletzt geändert". */
    val pageUpdatedAt: Map<Long, String?> = emptyMap(),
    val loading: Boolean = true,
    /** true, während ein Pull-to-Refresh (Delta-Pull + Baum-Neuladen) läuft. */
    val refreshing: Boolean = false,
    val error: String? = null,
    /** true = Buch ist ein Tagebuch (`buchtyp='tagebuch'`) → Kalender-Modus verfügbar. */
    val isDiary: Boolean = false,
    /** true = Kalender statt Liste anzeigen (nur für Tagebücher relevant). */
    val calendarMode: Boolean = false,
    /** ISO-Tag `YYYY-MM-DD` → Page-ID für vorhandene Tagebuch-Einträge. */
    val diaryEntries: Map<String, Long> = emptyMap(),
    /** Aktuell im Kalender angezeigter Monat. */
    val calendarMonth: YearMonth = YearMonth.now(),
    /** true, während ein Eintrag serverseitig angelegt wird. */
    val creatingEntry: Boolean = false,
    /** Einmalige Fehlermeldung (Snackbar); nach Anzeige via [TreeViewModel.consumeMessage] löschen. */
    val message: String? = null,
    /** Treffer der Inhalts-Volltextsuche zur aktuellen Query (leer = keine/inaktiv). */
    val contentHits: List<PageContentHit> = emptyList(),
)

class TreeViewModel(
    private val repo: ContentRepository,
    private val coordinator: SyncCoordinator,
    private val handle: SavedStateHandle,
    private val bookId: Long,
) : ViewModel() {

    // Über Process Death gerettete Kalender-Sicht (siehe SavedStateHandle-Keys unten).
    private val savedMonth: YearMonth =
        handle.get<String>(KEY_CAL_MONTH)?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
            ?: YearMonth.now()

    private val _state = MutableStateFlow(TreeUiState(bookId = bookId, calendarMonth = savedMonth))
    val state: StateFlow<TreeUiState> = _state.asStateFlow()

    /**
     * Tagebuch-Einträge aus dem Room-Cache (Seitennamen `YYYY-MM-DD…`). Fallback für
     * den Kalender, solange der Server-Baum nicht geladen ist (offline/Fehler) — sonst
     * liessen sich lokal vorhandene Einträge offline nicht öffnen.
     */
    @Volatile private var cachedDiaryEntries: Map<String, Long> = emptyMap()

    init {
        // Buchtyp aus dem Cache → entscheidet, ob der Kalender-Modus angeboten wird.
        // Default-Modus = Tagebuch ⇒ Kalender, sofern der Nutzer ihn nicht bewusst
        // umgeschaltet hat (dann gewinnt der gespeicherte Wert).
        viewModelScope.launch {
            val diary = repo.bookById(bookId)?.buchtyp == BUCHTYP_TAGEBUCH
            val mode = handle.get<Boolean>(KEY_CAL_MODE) ?: diary
            _state.update { it.copy(isDiary = diary, calendarMode = mode) }
        }
        load()
        // Im Hintergrund Offline-Cache der Seiten auffrischen (Delta-Pull).
        viewModelScope.launch { repo.syncBook(bookId) }
        // „Zuletzt geändert" je Seite reaktiv aus dem Room-Cache spiegeln
        // (wird vom Delta-Pull aktualisiert).
        viewModelScope.launch {
            repo.observePages(bookId).collect { pages ->
                val cached = LinkedHashMap<String, Long>()
                pages.forEach { p -> diaryDateOf(p.name)?.let { cached.putIfAbsent(it, p.id) } }
                cachedDiaryEntries = cached
                _state.update {
                    it.copy(
                        pageUpdatedAt = pages.associate { p -> p.id to p.updatedAt },
                        // Ohne geladenen Baum trägt der Cache den Kalender.
                        diaryEntries = if (it.rows.isEmpty()) cached else it.diaryEntries,
                    )
                }
            }
        }
        // Bei (wiederhergestellter) Verbindung erneut pullen + Struktur neu laden.
        // drop(1): den Startwert überspringen (init macht das schon oben).
        viewModelScope.launch {
            coordinator.online.drop(1).collect { isOnline ->
                if (isOnline) {
                    repo.syncBook(bookId)
                    load()
                }
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            fetchTree()
            _state.update { it.copy(loading = false) }
        }
    }

    /**
     * Pull-to-Refresh: erst den Offline-Cache per Delta-Pull auffrischen, dann den
     * Baum neu laden. Eigener [TreeUiState.refreshing]-Indikator (statt [loading]),
     * damit das Skeleton nicht erneut aufblitzt, während Inhalt schon sichtbar ist.
     */
    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            repo.syncBook(bookId)
            fetchTree()
            _state.update { it.copy(refreshing = false) }
        }
    }

    /** Baum vom Repository holen und Zeilen/Tagebuch-Einträge im State aktualisieren. */
    private suspend fun fetchTree() {
        repo.tree(bookId)
            .onSuccess { tree ->
                val rows = flattenTree(tree)
                _state.update {
                    it.copy(rows = rows, diaryEntries = diaryEntriesOf(rows), error = null)
                }
            }
            .onFailure { e ->
                _state.update {
                    it.copy(
                        error = e.message,
                        diaryEntries = if (it.rows.isEmpty()) cachedDiaryEntries else it.diaryEntries,
                    )
                }
            }
    }

    private var searchJob: Job? = null

    /**
     * Inhalts-Volltextsuche zur aktuellen Such-Query (debounced). Die Titel-Filterung
     * läuft weiterhin sofort im UI über die bereits geladenen Zeilen; diese Suche
     * ergänzt nur die Treffer im Seiten-*Inhalt* aus dem Room-FTS-Index.
     */
    fun search(query: String) {
        searchJob?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            if (_state.value.contentHits.isNotEmpty()) _state.update { it.copy(contentHits = emptyList()) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(200) // Debounce gegen Tippen
            val hits = repo.searchPageContent(bookId, q)
            _state.update { it.copy(contentHits = hits) }
        }
    }

    fun setCalendarMode(enabled: Boolean) {
        handle[KEY_CAL_MODE] = enabled
        _state.update { it.copy(calendarMode = enabled) }
    }

    fun stepMonth(delta: Long) = _state.update {
        val month = it.calendarMonth.plusMonths(delta)
        handle[KEY_CAL_MONTH] = month.toString()
        it.copy(calendarMonth = month)
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    /**
     * Eintrag für `dateIso` (`YYYY-MM-DD`) öffnen: existiert er, direkt; sonst
     * serverseitig anlegen. Nach Erfolg wird der Baum neu geladen und [onOpen]
     * mit der Page-ID aufgerufen (für die Editor-Navigation).
     */
    fun openOrCreateEntry(dateIso: String, onOpen: (pageId: Long, pageName: String) -> Unit) {
        _state.value.diaryEntries[dateIso]?.let { onOpen(it, dateIso); return }
        if (_state.value.creatingEntry) return
        viewModelScope.launch {
            _state.update { it.copy(creatingEntry = true) }
            repo.createDiaryEntry(bookId, dateIso)
                .onSuccess { pageId ->
                    repo.syncBook(bookId)
                    load()
                    _state.update { it.copy(creatingEntry = false) }
                    onOpen(pageId, dateIso)
                }
                .onFailure { e ->
                    _state.update { it.copy(creatingEntry = false, message = e.error()) }
                }
        }
    }

    private fun Throwable.error(): String = message ?: this::class.simpleName ?: "Fehler"

    private fun diaryEntriesOf(rows: List<TreeRow>): Map<String, Long> {
        val out = LinkedHashMap<String, Long>()
        rows.filterIsInstance<TreeRow.Page>().forEach { page ->
            diaryDateOf(page.name)?.let { date -> out.putIfAbsent(date, page.id) }
        }
        return out
    }

    companion object {
        /** SavedStateHandle-Keys: Kalender-Sicht über Process Death hinweg bewahren. */
        private const val KEY_CAL_MODE = "tree_calendar_mode"
        private const val KEY_CAL_MONTH = "tree_calendar_month"

        fun factory(locator: ServiceLocator, bookId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                TreeViewModel(locator.repository, locator.syncCoordinator, createSavedStateHandle(), bookId)
            }
        }
    }
}

/**
 * Flacht den [TreeDto] in die angezeigte Zeilenreihenfolge (Kapitel + Seiten mit
 * Tiefe). Top-Level, damit auch der Editor diese Reihenfolge nutzen kann (Wisch
 * zur vorigen/nächsten Seite). Vereinheitlicht die zwei Server-Formen: verschachtelt
 * (`subchapters`) ODER flach (Hierarchie nur über `parent_chapter_id`).
 */
fun flattenTree(tree: TreeDto): List<TreeRow> {
    val rows = mutableListOf<TreeRow>()
    tree.topPages.forEach { rows += TreeRow.Page(it.id, it.name, depth = 0) }
    val ids = tree.chapters.map { it.id }.toSet()
    val childrenOf = tree.chapters
        .filter { it.parent_chapter_id != null && it.parent_chapter_id in ids }
        .groupBy { it.parent_chapter_id!! }
    tree.chapters
        .filter { it.parent_chapter_id == null || it.parent_chapter_id !in ids }
        .forEach { walk(it, depth = 0, into = rows, childrenOf = childrenOf) }
    return rows
}

/** Nur die Seiten des Baums in Anzeige-Reihenfolge (für Vor/Zurück-Navigation). */
fun orderedPages(tree: TreeDto): List<TreeRow.Page> =
    flattenTree(tree).filterIsInstance<TreeRow.Page>()

private fun walk(
    chapter: ChapterNodeDto,
    depth: Int,
    into: MutableList<TreeRow>,
    childrenOf: Map<Long, List<ChapterNodeDto>>,
) {
    into += TreeRow.Chapter(chapter.name, depth)
    chapter.pages.forEach { into += TreeRow.Page(it.id, it.name, depth + 1) }
    // Verschachtelte Form: Unterkapitel hängen direkt am Knoten.
    chapter.subchapters.forEach { walk(it, depth + 1, into, childrenOf) }
    // Flache Form: Unterkapitel referenzieren diesen Knoten per parent_chapter_id.
    childrenOf[chapter.id]?.forEach { walk(it, depth + 1, into, childrenOf) }
}
