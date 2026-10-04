package ch.schreibwerkstatt.mobile.ui.editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import ch.schreibwerkstatt.mobile.ServiceLocator
import ch.schreibwerkstatt.mobile.audio.DictationController
import ch.schreibwerkstatt.mobile.bundle.BundleManager
import ch.schreibwerkstatt.mobile.data.net.NetworkClient
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import ch.schreibwerkstatt.mobile.data.repo.ContentRepository
import ch.schreibwerkstatt.mobile.editor.EditorBridge
import ch.schreibwerkstatt.mobile.editor.EditorEvent
import ch.schreibwerkstatt.mobile.editor.SpellcheckClient
import ch.schreibwerkstatt.mobile.editor.WritingTimeTracker
import ch.schreibwerkstatt.mobile.ui.tree.orderedPages
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** Grund für einen fehlgeschlagenen Bundle-Load — Text erst in [EditorScreen] aufgelöst. */
enum class BundleError { NO_SERVER_URL, UNAVAILABLE, FAILED }

sealed interface BundleState {
    data object Loading : BundleState
    data class Ready(val dir: File) : BundleState
    /** [detail] = dynamischer Exception-Text (nur bei [BundleError.FAILED]), sonst null. */
    data class Error(val reason: BundleError, val detail: String? = null) : BundleState
}

/** Nachbarseite (für die Wisch-Navigation vor/zurück). */
data class PageRef(val id: Long, val name: String)

/**
 * Lokalisierbare Editor-Snackbar-Meldung. Das ViewModel hält keine Context-/
 * String-Ressourcen; es emittiert nur den Typ, den [EditorScreen] über
 * `stringResource` auflöst. Dynamische Detailtexte (Server-/Exception-Meldungen)
 * reisen als Argument mit.
 */
sealed interface EditorMsg {
    data object SavedOffline : EditorMsg
    /** Konflikt erkannt; [editorName] = Server-Editor (null → „jemand anderem"). */
    data class Conflict(val editorName: String?) : EditorMsg
    /** Seite fremd gesperrt; [lockedBy] = E-Mail (null → „Lektorat"). */
    data class Locked(val lockedBy: String?) : EditorMsg
    data class EditorError(val detail: String) : EditorMsg
    data class RecordFailed(val detail: String) : EditorMsg
    data object ServerResolved : EditorMsg
    data object LocalResolved : EditorMsg
    data class LoadFailed(val detail: String) : EditorMsg
    /** Bereits aufgelöster Text (fehlende Mikrofon-Berechtigung, Transkriptionsfehler). */
    data class Raw(val text: String) : EditorMsg
}

data class EditorUiState(
    val bundle: BundleState = BundleState.Loading,
    val sttEnabled: Boolean = false,
    /** Der Editor im WebView ist gemountet (host.html hat `onReady()` gemeldet). */
    val editorReady: Boolean = false,
    /** Rechtschreibprüfung aktiv = Server bietet LanguageTool an UND Nutzer will sie. */
    val spellcheckEnabled: Boolean = false,
    /** Tipp-Pause vor einer Prüfung (Server-Vorgabe aus `/config`). */
    val spellcheckDebounceMs: Long = 1500,
    val recording: Boolean = false,
    val transcribing: Boolean = false,
    /** Live-Pegel des Mikrofons (0..1) während der Aufnahme – treibt die Pegel-Anzeige. */
    val level: Float = 0f,
    val message: EditorMsg? = null,
    val conflict: EditorEvent.Conflict? = null,
    /**
     * Es existiert ein noch nicht aufgelöster Konflikt für diese Seite – auch wenn
     * der Dialog gerade weggetippt ist. Treibt den dauerhaften Topbar-Hinweis,
     * damit ein „klebender" lokaler Stand nicht unbemerkt bleibt.
     */
    val hasOpenConflict: Boolean = false,
    /** Lokale Fassung (Klartext) für die Konflikt-Vergleichsansicht; null = noch nicht geladen. */
    val conflictLocalText: String? = null,
    /** Server-Fassung (Klartext) für die Konflikt-Vergleichsansicht; null = noch nicht geladen. */
    val conflictServerText: String? = null,
    /** true, während die beiden Konflikt-Fassungen geladen werden. */
    val conflictLoading: Boolean = false,
    /** Vorige Seite im Buch (Wisch nach rechts); null = keine. */
    val prevPage: PageRef? = null,
    /** Nächste Seite im Buch (Wisch nach links); null = keine. */
    val nextPage: PageRef? = null,
)

class EditorViewModel(
    private val repo: ContentRepository,
    private val bundleManager: BundleManager,
    private val network: NetworkClient,
    private val settings: SettingsStore,
    private val dictation: DictationController,
    private val spellcheck: SpellcheckClient,
    /** App-Scope für den Save-Pfad; überdauert dieses ViewModel (Schliess-Save). */
    private val appScope: CoroutineScope,
    val bookId: Long,
    val pageId: Long,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    /** Zuletzt gesehener Konflikt – damit der Topbar-Hinweis den Dialog wieder öffnen kann. */
    private var lastConflict: EditorEvent.Conflict? = null

    /** Läuft, solange ein Diktat-Segment auf eine Sprechpause überwacht wird. */
    private var monitorJob: Job? = null

    /**
     * Ziel für erkannten Diktat-Text: die aktuell angezeigte WebView (vom Screen
     * registriert). Bewusst NICHT im Diktat-Aufruf eingefangen — nach Rotation o.ä.
     * gibt es eine neue WebView, die alte ist abgeräumt. Text, der ohne Ziel oder vor
     * `onReady` der neuen WebView ankommt, wartet in [pendingTexts]. Nur Main-Thread.
     */
    private var textSink: ((String) -> Unit)? = null
    private val pendingTexts = ArrayDeque<String>()

    /** Server bietet LanguageTool an (`/config`). */
    @Volatile private var serverSpellcheck = false

    /** Nutzer-Schalter aus den Einstellungen. */
    @Volatile private var userSpellcheck = true

    private fun syncSpellcheckFlag() {
        _state.update { st -> st.copy(spellcheckEnabled = serverSpellcheck && userSpellcheck) }
    }

    /**
     * Idle-gedeckelte Schreibzeit-Uhr. `notifyActivity()` kommt (debounced) aus dem
     * Editor über die Bridge; `tick()`/`resume()` treibt der vordergrund-gated
     * Heartbeat unten. Meldet nur positive Delta-Sekunden best effort ans Repository.
     */
    private val writingTime = WritingTimeTracker { seconds -> repo.writingTime(bookId, seconds) }

    /** Scope für UI-gebundene Bridge-Calls (Laden). */
    val bridgeScope: CoroutineScope get() = viewModelScope

    /**
     * Wird vom letzten Save (Schliessen/Navigieren) bewaffnet und von der Bridge
     * freigegeben, sobald der Editor den Save übergeben hat. Erlaubt dem Close-Pfad,
     * vor dem WebView-`destroy()` kurz auf die Übergabe zu warten (siehe [saveBeforeClose]).
     */
    @Volatile
    private var saveHandoff: CompletableDeferred<Unit>? = null

    init {
        // Beim Öffnen einen noch offenen Konflikt (409 beim letzten Save) erneut
        // anzeigen – sonst klebt die dirty-Seite stumm auf dem lokalen Stand.
        // Eigener Launch, damit der Hinweis nicht hinter dem Bundle-Download wartet.
        viewModelScope.launch {
            repo.openConflict(pageId)?.let { w ->
                val c = EditorEvent.Conflict(w.note, null)
                lastConflict = c
                _state.update { st -> st.copy(conflict = c, hasOpenConflict = true) }
                loadConflictPreviews()
            }
        }

        loadBundle()

        viewModelScope.launch {
            val base = settings.serverBaseUrlOnce() ?: return@launch
            // Server-Features prüfen: Mic-Button nur bei stt.enabled, Rechtschreib-
            // prüfung nur, wenn der Server den LanguageTool-Proxy anbietet.
            runCatching { network.config(base).config() }.onSuccess { cfg ->
                serverSpellcheck = cfg.languagetool?.enabled == true
                _state.update { st -> st.copy(
                    sttEnabled = cfg.stt?.enabled == true,
                    spellcheckDebounceMs = cfg.languagetool?.debounceMs ?: 1500,
                ) }
                syncSpellcheckFlag()
            }

            // Schreibendes Gerät als Buch-Präsenz registrieren (best effort).
            repo.devicePing(bookId, pageId)
        }

        // Nutzer-Schalter (Einstellungen) live nachziehen — der Editor darf offen
        // bleiben, während die Prüfung an-/abgeschaltet wird.
        viewModelScope.launch {
            settings.spellcheck.collect { on ->
                userSpellcheck = on
                syncSpellcheckFlag()
            }
        }

        // Nachbarseiten für die Wisch-Navigation aus dem Buchbaum bestimmen
        // (best effort; offline/Fehler → keine Wisch-Navigation).
        viewModelScope.launch {
            repo.tree(bookId).onSuccess { tree ->
                val pages = orderedPages(tree)
                val idx = pages.indexOfFirst { it.id == pageId }
                if (idx >= 0) {
                    val prev = pages.getOrNull(idx - 1)?.let { PageRef(it.id, it.name) }
                    val next = pages.getOrNull(idx + 1)?.let { PageRef(it.id, it.name) }
                    _state.update { st -> st.copy(prevPage = prev, nextPage = next) }
                }
            }
        }
    }

    /** Editor-Bundle laden (OTA-sicherstellen) und den [BundleState] setzen. */
    private fun loadBundle() {
        viewModelScope.launch {
            val base = settings.serverBaseUrlOnce()
            if (base == null) {
                _state.update { st -> st.copy(bundle = BundleState.Error(BundleError.NO_SERVER_URL)) }
                return@launch
            }
            bundleManager.ensureBundle(base)
                .onSuccess { ok ->
                    _state.update {
                        if (ok) it.copy(bundle = BundleState.Ready(bundleManager.bundleDir))
                        else it.copy(bundle = BundleState.Error(BundleError.UNAVAILABLE))
                    }
                }
                .onFailure { _state.update { st -> st.copy(bundle = BundleState.Error(BundleError.FAILED, it.message)) } }
        }
    }

    /**
     * Presence- und Schreibzeit-Heartbeat, solange DIESE Editor-Seite sichtbar ist.
     * Der Screen ruft das per `repeatOnLifecycle(RESUMED)` auf seinem Backstack-Eintrag
     * auf: Geht die App in den Hintergrund oder liegt die Seite (nach Wisch-Navigation
     * / Verlauf) verdeckt im Backstack, wird der Block gecancelt — sonst würden alle
     * Editor-VMs im Backstack parallel Schreibzeit verbuchen und Präsenz melden.
     * `resume()` beim (Wieder-)Eintritt beginnt ein frisches Segment, sodass die
     * Abwesenheitslücke nie als Schreibzeit mitzählt.
     */
    suspend fun runWhileVisible(): Unit = coroutineScope {
        launch {
            while (true) {
                delay(PING_INTERVAL_MS)
                repo.devicePing(bookId, pageId)
            }
        }
        writingTime.resume()
        while (true) {
            delay(WRITING_TIME_INTERVAL_MS)
            writingTime.tick()
        }
    }

    /**
     * Eine neue WebView wurde erzeugt (Erstaufbau, Rotation, Rückkehr aus dem
     * Backstack): Bis sie `onReady` meldet, ist der Editor NICHT bereit — sonst feuern
     * die an [EditorUiState.editorReady] hängenden Effekte (Spellcheck, Schreiblinie)
     * ins Leere und laufen nach dem echten `onReady` nicht erneut.
     */
    fun onWebViewCreated() {
        _state.update { it.copy(editorReady = false) }
    }

    /** Diktat-Ziel registrieren (Main-Thread); liefert gepufferten Text nach. */
    fun attachTextSink(sink: (String) -> Unit) {
        textSink = sink
        flushTexts()
    }

    fun detachTextSink(sink: (String) -> Unit) {
        if (textSink === sink) textSink = null
    }

    private fun deliverText(text: String) {
        pendingTexts.addLast(text)
        flushTexts()
    }

    private fun flushTexts() {
        val sink = textSink ?: return
        if (!_state.value.editorReady) return
        while (pendingTexts.isNotEmpty()) sink(pendingTexts.removeFirst())
    }

    /**
     * Seite wird verlassen (Wisch-Navigation, Verlauf), das VM bleibt aber im
     * Backstack: laufende Aufnahme verwerfen, damit das Mikrofon nicht im Hintergrund
     * weiterläuft (gleiches Verhalten wie beim Schliessen über Zurück → [onCleared]).
     */
    fun stopDictationOnLeave() {
        monitorJob?.cancel()
        monitorJob = null
        if (dictation.isRecording) {
            dictation.cancel()
            _state.update { it.copy(recording = false, level = 0f) }
        }
    }

    /** Bundle-Load erneut versuchen (Retry-Button im Fehlerzustand). */
    fun reloadBundle() {
        _state.update { st -> st.copy(bundle = BundleState.Loading) }
        loadBundle()
    }

    /**
     * Baut die WebView-Bridge. [spellcheckStrings] kommt aus den `@string/`-
     * Ressourcen (im Composable aufgelöst) und lokalisiert die Spellcheck-UI,
     * die im WebView gerendert wird.
     */
    fun newBridge(
        evalJs: (String) -> Unit,
        darkTheme: Boolean,
        spellcheckStrings: Map<String, String>,
    ): EditorBridge =
        EditorBridge(
            repo = repo,
            scope = bridgeScope,
            saveScope = appScope,
            pageId = pageId,
            bookId = bookId,
            evalJs = evalJs,
            onEvent = ::onEditorEvent,
            // Übergabe-Signal (Binder-Thread): gibt einen wartenden Close-Save frei.
            onSaveStarted = { saveHandoff?.complete(Unit) },
            // Tipp-Aktivität (Binder-Thread) → Idle-Uhr des Schreibzeit-Trackers zurücksetzen.
            onActivity = { writingTime.notifyActivity() },
            darkTheme = darkTheme,
            spellcheck = spellcheck,
            spellcheckStrings = spellcheckStrings,
        )

    /**
     * Finalen Save vor dem Schliessen/Navigieren anstossen und nur kurz warten, bis
     * der Editor ihn an die Bridge übergeben hat (danach läuft Persist/Flush im
     * [appScope] eigenständig weiter und überlebt das WebView-`destroy()`). Wartet
     * NICHT auf den Netzwerk-Flush — die Übergabe genügt, damit nichts verloren geht.
     * Bei nichts zu speichern (kein Bridge-Save) greift der [SAVE_HANDOFF_TIMEOUT_MS].
     */
    suspend fun saveBeforeClose(evalJs: (String) -> Unit) {
        val signal = CompletableDeferred<Unit>()
        saveHandoff = signal
        evalJs("window.__sw && window.__sw.save();")
        withTimeoutOrNull(SAVE_HANDOFF_TIMEOUT_MS) { signal.await() }
        saveHandoff = null
    }

    private fun onEditorEvent(event: EditorEvent) {
        when (event) {
            // Editor gemountet: erst jetzt greifen JS-Aufrufe von aussen
            // (z.B. das Ein-/Ausschalten der Rechtschreibprüfung).
            is EditorEvent.Ready -> {
                _state.update { st -> st.copy(editorReady = true) }
                // Kommt vom Binder-Thread → auf Main gepufferten Diktat-Text nachliefern.
                viewModelScope.launch { flushTexts() }
            }
            is EditorEvent.SavedOffline -> _state.update { st -> st.copy(message = EditorMsg.SavedOffline) }
            is EditorEvent.Conflict -> {
                lastConflict = event
                _state.update { st -> st.copy(
                    conflict = event,
                    hasOpenConflict = true,
                    message = EditorMsg.Conflict(event.serverEditorName),
                ) }
                loadConflictPreviews()
            }
            is EditorEvent.Locked -> _state.update { st -> st.copy(message = EditorMsg.Locked(event.lockedByEmail)) }
            is EditorEvent.Error -> _state.update { st -> st.copy(message = EditorMsg.EditorError(event.message)) }
        }
    }

    fun consumeMessage() { _state.update { st -> st.copy(message = null) } }

    /** Zeigt eine bereits aufgelöste Hinweis-Snackbar (z.B. fehlende Mikrofon-Berechtigung). */
    fun notify(message: String) { _state.update { st -> st.copy(message = EditorMsg.Raw(message)) } }

    // ── Diktat ────────────────────────────────────────────────────────────────

    /**
     * Startet/stoppt die Aufnahme. Beim Start überwacht ein Monitor-Job die
     * Amplitude und beendet das Segment automatisch nach einer Sprechpause
     * (oder an der Maximaldauer). Manuelles Antippen stoppt sofort. In beiden
     * Fällen wird transkribiert und der erkannte Text an die aktuelle WebView
     * geliefert (siehe [attachTextSink]).
     */
    fun toggleDictation() {
        if (!dictation.isRecording) {
            dictation.startRecording()
                .onSuccess {
                    _state.update { st -> st.copy(recording = true, level = 0f) }
                    monitorJob = viewModelScope.launch { monitorSilence() }
                }
                .onFailure { _state.update { st -> st.copy(message = EditorMsg.RecordFailed(it.message ?: "")) } }
        } else {
            monitorJob?.cancel()
            monitorJob = null
            transcribeCurrentSegment()
        }
    }

    /**
     * Pollt die Amplitude und beendet das Segment, sobald nach erkannter Sprache
     * eine Pause von [SILENCE_HANGOVER_MS] vorliegt oder [MAX_SEGMENT_MS]
     * erreicht ist. Wird der Job (manueller Stopp) gecancelt, bricht [delay] ab.
     */
    private suspend fun monitorSilence() {
        var spoke = false
        var silentMs = 0L
        var elapsedMs = 0L
        while (dictation.isRecording) {
            delay(POLL_MS)
            elapsedMs += POLL_MS
            val amplitude = dictation.currentAmplitude()
            // Live-Pegel für die UI normalisieren (0..1). Geglättet, damit die
            // Anzeige nicht flackert: neuer Wert zieht den alten anteilig nach.
            val target = (amplitude / LEVEL_FULL_SCALE).coerceIn(0f, 1f)
            _state.update { it.copy(level = it.level + (target - it.level) * LEVEL_SMOOTHING) }
            if (amplitude >= SPEECH_AMPLITUDE) {
                spoke = true
                silentMs = 0L
            } else {
                silentMs += POLL_MS
            }
            val pauseAfterSpeech = spoke && silentMs >= SILENCE_HANGOVER_MS
            if (pauseAfterSpeech || elapsedMs >= MAX_SEGMENT_MS) {
                monitorJob = null
                transcribeCurrentSegment()
                return
            }
        }
    }

    private fun transcribeCurrentSegment() {
        if (!dictation.isRecording) return
        _state.update { st -> st.copy(recording = false, transcribing = true, level = 0f) }
        viewModelScope.launch {
            dictation.stopAndTranscribe(bookId, pageId)
                .onSuccess { text ->
                    _state.update { st -> st.copy(transcribing = false) }
                    if (text.isNotBlank()) deliverText(text)
                }
                .onFailure {
                    _state.update { st -> st.copy(
                        transcribing = false,
                        message = it.message?.let { m -> EditorMsg.Raw(m) },
                    ) }
                }
        }
    }

    /**
     * Beide Konflikt-Fassungen (lokal vs. Server) als Klartext für die
     * Vergleichsansicht im Dialog laden. Best effort: schlägt das Laden fehl,
     * bleiben die Vorschau-Felder null (Dialog zeigt dann nur die Aktionen).
     */
    private fun loadConflictPreviews() {
        _state.update { st -> st.copy(conflictLoading = true) }
        viewModelScope.launch {
            repo.conflictPreview(pageId)
                .onSuccess { p ->
                    _state.update { st -> st.copy(
                        conflictLocalText = p.local,
                        conflictServerText = p.server,
                        conflictLoading = false,
                    ) }
                }
                .onFailure { _state.update { st -> st.copy(conflictLoading = false) } }
        }
    }

    fun resolveConflictWithServer(onApplied: (html: String) -> Unit) {
        viewModelScope.launch {
            repo.resolveWithServerVersion(pageId, bookId)
                .onSuccess { page ->
                    lastConflict = null
                    _state.update { st -> st.copy(
                        conflict = null,
                        hasOpenConflict = false,
                        conflictLocalText = null,
                        conflictServerText = null,
                        message = EditorMsg.ServerResolved,
                    ) }
                    onApplied(page.html ?: "<p><br></p>")
                }
                .onFailure { _state.update { st -> st.copy(message = EditorMsg.LoadFailed(it.message ?: "")) } }
        }
    }

    /**
     * Konflikt zugunsten der lokalen Fassung auflösen: den Server-Stand bewusst
     * überschreiben (siehe [ContentRepository.resolveWithLocalVersion]). Der lokale
     * Editor-Inhalt bleibt unverändert; nur der Konflikt-Zustand wird geräumt.
     */
    fun resolveConflictWithLocal() {
        viewModelScope.launch {
            repo.resolveWithLocalVersion(pageId, bookId)
                .onSuccess {
                    lastConflict = null
                    _state.update { st -> st.copy(
                        conflict = null,
                        hasOpenConflict = false,
                        conflictLocalText = null,
                        conflictServerText = null,
                        message = EditorMsg.LocalResolved,
                    ) }
                }
                .onFailure { _state.update { st -> st.copy(message = EditorMsg.LoadFailed(it.message ?: "")) } }
        }
    }

    /** Dialog schliessen, aber den offenen Konflikt-Hinweis (Topbar) bewusst behalten. */
    fun dismissConflict() { _state.update { st -> st.copy(conflict = null) } }

    /** Den weggetippten Konflikt-Dialog über den Topbar-Hinweis erneut öffnen. */
    fun reopenConflict() {
        lastConflict?.let {
            _state.update { st -> st.copy(conflict = it) }
            if (_state.value.conflictServerText == null && !_state.value.conflictLoading) loadConflictPreviews()
        }
    }

    override fun onCleared() {
        monitorJob?.cancel()
        dictation.cancel()
        super.onCleared()
    }

    companion object {
        /** Presence-Heartbeat-Intervall. */
        private const val PING_INTERVAL_MS = 30_000L

        /** Schreibzeit-Heartbeat-Intervall (vordergrund-gated). */
        private const val WRITING_TIME_INTERVAL_MS = 15_000L

        /**
         * Obergrenze, wie lange der Close-Pfad auf die Save-Übergabe an die Bridge
         * wartet. Nur die (schnelle) JS→native-Übergabe, kein Netzwerk-Flush.
         */
        private const val SAVE_HANDOFF_TIMEOUT_MS = 2_000L

        // ── VAD-Parameter (Stille-Erkennung) ──
        /** Poll-Intervall der Amplitude. */
        private const val POLL_MS = 150L
        /** Ab dieser Spitzenamplitude (0..32767) gilt das Segment als „Sprache". */
        private const val SPEECH_AMPLITUDE = 1_500
        /** Stille-Dauer nach Sprache, die ein Segment automatisch beendet. */
        private const val SILENCE_HANGOVER_MS = 2_000L
        /** Amplitude (0..32767), die in der Pegel-Anzeige als Vollausschlag gilt. */
        private const val LEVEL_FULL_SCALE = 12_000f
        /** Glättungsfaktor (0..1) der Pegel-Anzeige; höher = reaktiver. */
        private const val LEVEL_SMOOTHING = 0.4f
        /** Harte Obergrenze pro Segment (Schutz vor dem 5-MB-Server-Limit). */
        private const val MAX_SEGMENT_MS = 120_000L

        fun factory(locator: ServiceLocator, bookId: Long, pageId: Long): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    EditorViewModel(
                        repo = locator.repository,
                        bundleManager = locator.bundleManager,
                        network = locator.network,
                        settings = locator.settings,
                        dictation = locator.dictationController(),
                        spellcheck = locator.spellcheckClient(),
                        appScope = locator.applicationScope,
                        bookId = bookId,
                        pageId = pageId,
                    )
                }
            }
    }
}
