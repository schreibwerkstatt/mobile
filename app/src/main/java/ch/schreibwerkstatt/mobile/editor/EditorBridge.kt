package ch.schreibwerkstatt.mobile.editor

import android.util.Log
import android.webkit.JavascriptInterface
import ch.schreibwerkstatt.mobile.BuildConfig
import ch.schreibwerkstatt.mobile.data.repo.ContentRepository
import ch.schreibwerkstatt.mobile.data.repo.SaveResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * JS↔native Brücke für den eingebetteten Focus-Editor. Wird unter dem Namen
 * `SWHost` in die WebView injiziert (siehe host.html). Die Methoden laufen auf
 * einem Binder-Thread; Ergebnisse werden asynchron über
 * `window.__sw.resolve/reject` (per [evalJs], auf dem UI-Thread) zurückgereicht.
 *
 * Lade-/Speicher-Calls gehen über das [ContentRepository] (geteilter Room-Cache),
 * NICHT direkt gegen den Server.
 *
 * Speichern läuft bewusst im [saveScope] (App-Scope), nicht im an die WebView/das
 * ViewModel gebundenen [scope]: Sobald der Editor einen Save an die Bridge übergibt,
 * muss Persist + best-effort Flush auch dann durchlaufen, wenn die WebView gerade
 * abgeräumt und das EditorViewModel gecancelt wird (Schliessen/Navigieren). Der
 * Close-Pfad triggert den letzten Save und wartet via [onSaveStarted] nur auf diese
 * Übergabe — nicht auf den (ggf. langsamen) Netzwerk-Flush.
 */
class EditorBridge(
    private val repo: ContentRepository,
    private val scope: CoroutineScope,
    private val saveScope: CoroutineScope,
    private val pageId: Long,
    private val bookId: Long,
    /** Führt JS auf dem UI-Thread aus (WebView.evaluateJavascript). */
    private val evalJs: (String) -> Unit,
    /** Native UI-Events (Snackbar etc.). */
    private val onEvent: (EditorEvent) -> Unit,
    /**
     * Wird synchron aufgerufen, sobald ein Save an die Bridge übergeben wurde
     * (vor Persist/Flush). Gibt den Close-Barrier frei, der vor dem WebView-`destroy()`
     * auf die Übergabe wartet.
     */
    private val onSaveStarted: () -> Unit,
    /**
     * Aktivitäts-Signal aus dem contenteditable (`input`, in host.html debounced).
     * Setzt die Idle-Uhr des Schreibzeit-Trackers zurück. Läuft auf einem Binder-Thread.
     */
    private val onActivity: () -> Unit = {},
    /** Dark-Mode-Wunsch (folgt isSystemInDarkTheme); steuert `data-theme` in der WebView. */
    private val darkTheme: Boolean,
    /**
     * Rechtschreibprüfung über den Server-Proxy. null = Feature nicht verdrahtet
     * (`ltCheck`/`ltAddWord` rejecten dann); das An/Aus steuert host.html über
     * `window.__sw.setSpellcheck`.
     */
    private val spellcheck: SpellcheckClient? = null,
    /**
     * Übersetzungen für die Spellcheck-UI im WebView (Badge/Popover). Kommen aus
     * den `@string/`-Ressourcen, damit auch der WebView-Teil lokalisiert bleibt;
     * Schlüssel = i18n-Keys des Controllers (`spellcheck.status.*` etc.).
     */
    private val spellcheckStrings: Map<String, String> = emptyMap(),
) {
    private val json = Json { explicitNulls = false }

    private companion object {
        const val TAG = "EditorBridge"
    }

    @Serializable
    private data class BridgePage(val id: Long, val name: String?, val html: String?)

    @Serializable
    private data class SavePayload(val id: Long? = null, val name: String? = null, val html: String = "")

    @JavascriptInterface
    fun loadPage(reqId: String) {
        scope.launch {
            repo.loadPage(pageId, bookId)
                .onSuccess { p ->
                    val payload = json.encodeToString(
                        BridgePage.serializer(),
                        BridgePage(p.id, p.name, p.html ?: "<p><br></p>")
                    )
                    evalJs("window.__sw.resolve('${reqId.esc()}', ${jsString(payload)});")
                }
                .onFailure { e ->
                    evalJs("window.__sw.reject('${reqId.esc()}', ${jsString(e.message ?: "load failed")});")
                }
        }
    }

    @JavascriptInterface
    fun savePage(reqId: String, payloadJson: String) {
        // Übergabe ans Native erfolgt: Close-Barrier freigeben, BEVOR der
        // (potenziell langsame, weil netzgebundene) Persist/Flush startet.
        onSaveStarted()
        // App-Scope: überlebt das Abräumen von WebView/EditorViewModel beim
        // Schliessen/Navigieren, damit ein angestossener Save nie verloren geht.
        saveScope.launch {
            val payload = runCatching { json.decodeFromString(SavePayload.serializer(), payloadJson) }.getOrNull()
            if (payload == null) {
                evalJs("window.__sw.reject('${reqId.esc()}', 'bad payload');")
                return@launch
            }
            when (val res = repo.savePage(pageId, bookId, payload.html)) {
                is SaveResult.Saved -> {
                    val out = json.encodeToString(
                        BridgePage.serializer(),
                        BridgePage(res.page.id, res.page.name, res.page.html)
                    )
                    evalJs("window.__sw.resolve('${reqId.esc()}', ${jsString(out)});")
                    if (res.merged) {
                        // Server hat fremde Änderungen eingearbeitet: wie einen frischen
                        // Server-Stand still einspielen. host.html tut das nur, wenn seit
                        // dem Absenden nicht weitergetippt wurde (sonst mergt der nächste
                        // Save erneut auf dem Server).
                        evalJs("window.__sw && window.__sw.applyServerPage(${jsString(out)});")
                        onEvent(EditorEvent.Merged)
                    }
                }
                is SaveResult.Queued -> {
                    // Lokal gespeichert (offline) — Editor darf weitermachen.
                    evalJs("window.__sw.resolve('${reqId.esc()}', null);")
                    onEvent(EditorEvent.SavedOffline)
                }
                is SaveResult.Conflict -> {
                    evalJs("window.__sw.resolve('${reqId.esc()}', null);")
                    onEvent(EditorEvent.Conflict(res.serverEditorName, res.serverUpdatedAt))
                }
                is SaveResult.Locked -> {
                    evalJs("window.__sw.resolve('${reqId.esc()}', null);")
                    onEvent(EditorEvent.Locked(res.lockedByEmail))
                }
            }
        }
    }

    @Serializable
    private data class LtCheckPayload(val text: String = "", val language: String? = null)

    @Serializable
    private data class LtAddWordPayload(val word: String = "", val lang: String? = null)

    @Serializable
    private data class LtAddWordResult(val ok: Boolean)

    /**
     * Rechtschreibprüfung: Text aus dem Editor → `POST /languagetool/check` (mit
     * Auth-Header, gegen die echte Server-Basis-URL). Buch/Seite kennt die Bridge
     * selbst — JS schickt nur den Text, damit die Prüfung nicht an einer fremden
     * Seiten-ID hängen kann. Aufgelöst wird der rohe JSON-Body der Antwort
     * (`{ matches: […] }` bzw. `{ disabled: true }`).
     */
    @JavascriptInterface
    fun ltCheck(reqId: String, payloadJson: String) {
        val client = spellcheck ?: run {
            evalJs("window.__sw.reject('${reqId.esc()}', 'spellcheck unavailable');")
            return
        }
        scope.launch {
            val payload = runCatching { json.decodeFromString(LtCheckPayload.serializer(), payloadJson) }.getOrNull()
            if (payload == null) {
                evalJs("window.__sw.reject('${reqId.esc()}', 'bad payload');")
                return@launch
            }
            client.check(payload.text, payload.language, bookId, pageId)
                .onSuccess { body -> evalJs("window.__sw.resolve('${reqId.esc()}', ${jsString(body)});") }
                .onFailure { e ->
                    evalJs("window.__sw.reject('${reqId.esc()}', ${jsString(e.message ?: "lt failed")});")
                }
        }
    }

    /** „Zum Wörterbuch" aus dem Spellcheck-Popover → `POST /dictionary`. */
    @JavascriptInterface
    fun ltAddWord(reqId: String, payloadJson: String) {
        val client = spellcheck ?: run {
            evalJs("window.__sw.reject('${reqId.esc()}', 'spellcheck unavailable');")
            return
        }
        scope.launch {
            val payload = runCatching { json.decodeFromString(LtAddWordPayload.serializer(), payloadJson) }.getOrNull()
            if (payload == null || payload.word.isBlank()) {
                evalJs("window.__sw.reject('${reqId.esc()}', 'bad payload');")
                return@launch
            }
            val ok = client.addWord(payload.word, payload.lang).getOrDefault(false)
            val out = json.encodeToString(LtAddWordResult.serializer(), LtAddWordResult(ok))
            evalJs("window.__sw.resolve('${reqId.esc()}', ${jsString(out)});")
        }
    }

    /** i18n-Map der Spellcheck-UI (Badge/Popover) als JSON — synchroner Boot-Call. */
    @JavascriptInterface
    fun spellcheckStrings(): String =
        json.encodeToString(
            MapSerializer(String.serializer(), String.serializer()),
            spellcheckStrings,
        )

    /** Synchroner Boot-Call aus host.html: setzt `data-theme` ohne Hell-Flash. */
    @JavascriptInterface
    fun prefersDark(): Boolean = darkTheme

    @JavascriptInterface
    fun onReady() = onEvent(EditorEvent.Ready)

    /** Tipp-Aktivität im Editor (debounced) — Idle-Uhr des Schreibzeit-Trackers zurücksetzen. */
    @JavascriptInterface
    fun notifyActivity() = onActivity()

    @JavascriptInterface
    fun notifyError(msg: String) = onEvent(EditorEvent.Error(msg))

    @JavascriptInterface
    fun log(msg: String) {
        // Editor-interne JS-Logs nur in Debug-Builds nach Logcat spiegeln —
        // in Release bleibt die WebView still (keine PII/Inhalte ins Log).
        if (BuildConfig.DEBUG) Log.d(TAG, msg)
    }

    /** JS-/JSON-String-Literal (Quoting/Escaping) für die evaluateJavascript-Einbettung. */
    private fun jsString(value: String): String = json.encodeToString(String.serializer(), value)

    private fun String.esc(): String = replace("\\", "\\\\").replace("'", "\\'")
}

sealed interface EditorEvent {
    data object Ready : EditorEvent
    data object SavedOffline : EditorEvent
    /** Save gelandet, der Server hat ihn mit einem fremden Stand zusammengeführt. */
    data object Merged : EditorEvent
    data class Conflict(val serverEditorName: String?, val serverUpdatedAt: String?) : EditorEvent
    data class Locked(val lockedByEmail: String?) : EditorEvent
    data class Error(val message: String) : EditorEvent
}
