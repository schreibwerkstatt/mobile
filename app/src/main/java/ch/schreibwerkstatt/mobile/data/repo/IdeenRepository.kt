package ch.schreibwerkstatt.mobile.data.repo

import ch.schreibwerkstatt.mobile.data.db.AppDatabase
import androidx.room.withTransaction
import ch.schreibwerkstatt.mobile.data.db.IdeenCacheEntity
import ch.schreibwerkstatt.mobile.data.db.PendingIdeaChangeEntity
import ch.schreibwerkstatt.mobile.data.net.ApiJson
import ch.schreibwerkstatt.mobile.data.net.withApiErrors
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import ch.schreibwerkstatt.mobile.data.db.PendingIdeaEntity
import ch.schreibwerkstatt.mobile.data.net.NetworkClient
import ch.schreibwerkstatt.mobile.data.net.dto.ApiErrorDto
import ch.schreibwerkstatt.mobile.data.net.dto.CreateIdeeRequest
import ch.schreibwerkstatt.mobile.data.net.dto.IdeeDto
import ch.schreibwerkstatt.mobile.data.net.dto.IdeenBoardDto
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody
import retrofit2.Response
import kotlin.coroutines.cancellation.CancellationException

/**
 * Ideen-Board (`routes/ideen.js`). Bewusst **eigenständig** neben [ContentRepository]:
 * Ideen sind user-private Notizen, kein Buchinhalt — die Regel „ContentRepository ist
 * der einzige Content-Eintrittspunkt" betrifft sie nicht (wie [ResearchRepository]).
 *
 * - **Lesen mit Cache.** Das Board wird bei jedem Abruf in `ideen_cache` abgelegt;
 *   [observeBoard] liefert den Cache mit allen vorgemerkten Änderungen darübergelegt —
 *   offline zeigt die Liste den letzten Stand plus das eigene Ungesendete.
 * - **Anlegen ist offline-fähig.** Jede neue Idee landet ZUERST in `pending_ideas`
 *   (übersteht App-Kill), dann wird sie sofort zu senden versucht. Ohne Netz bleibt
 *   sie liegen und [onQueued] plant den Nachversand (WorkManager, `CONNECTED`). Jeder
 *   Versuch trägt denselben `Idempotency-Key` — eine verlorene Antwort legt beim
 *   Nachspielen nicht doppelt an (`lib/idempotency.js`).
 * - **Abgewiesen** (4xx ausser 401): einmal als Buch-Idee ohne Anker wiederholen (der
 *   Abschnitt kann inzwischen gelöscht sein), sonst `stuck` mit Fehlercode — nie still
 *   verwerfen. Gleiche Regel wie die Web-Queue (`public/js/repo/ideen-queue.js`).
 * - **Bearbeiten, Stufe, Umziehen, Löschen sind ebenfalls offline-fähig**
 *   (`pending_idea_changes`): höchstens eine Zeile je Idee, weitere Änderungen werden
 *   in ihren PATCH-Body zusammengeführt, Löschen ersetzt sie. Gesendet wird nach den
 *   Anlagen. `404 IDEE_NOT_FOUND` heisst „gibt es nicht mehr" → erledigt; andere
 *   Abweisungen (`IDEE_CLOSED`, `IDEE_STATUS_INACTIVE` …) bleiben `stuck` an der Idee
 *   stehen, bis der Nutzer sie verwirft oder erneut sendet.
 */
class IdeenRepository(
    private val db: AppDatabase,
    private val net: NetworkClient,
    private val settings: SettingsStore,
    private val now: () -> Long = System::currentTimeMillis,
    /** Nachversand planen — in der App ein [ch.schreibwerkstatt.mobile.sync.CaptureFlushWorker]. */
    private val onQueued: () -> Unit = {},
) {
    private val dao get() = db.pendingIdeaDao()
    private val changeDao get() = db.pendingIdeaChangeDao()
    private val cacheDao get() = db.ideenCacheDao()
    private val json = ApiJson
    private val flushMutex = Mutex()

    private suspend fun baseUrl(): String =
        settings.serverBaseUrlOnce() ?: error("Keine Server-URL konfiguriert")

    // ── Lesen ───────────────────────────────────────────────────────────────

    /**
     * Board frisch laden und als Cache ablegen. Unter dem Sende-Mutex, damit eine
     * gleichzeitig bestätigte Anlage/Änderung nicht von einem älteren Abruf überschrieben wird.
     */
    suspend fun refresh(bookId: Long): Result<IdeenBoardDto> = runCatching {
        flushMutex.withLock {
            val board = withApiErrors { net.ideen(baseUrl()).board(bookId) }
            cacheDao.upsert(IdeenCacheEntity(bookId, json.encodeToString(IdeenBoardDto.serializer(), board), now()))
            board
        }
    }

    /**
     * Board aus dem Cache mit den vorgemerkten Änderungen darüber. `null`-Board =
     * noch nie geladen (dann weiss die Liste nicht, ob es Ideen gibt).
     */
    fun observeBoard(bookId: Long): Flow<IdeenBoardView> =
        combine(cacheDao.observe(bookId), changeDao.observeForBook(bookId)) { cache, changes ->
            val board = cache?.json?.let { runCatching { json.decodeFromString(IdeenBoardDto.serializer(), it) }.getOrNull() }
            IdeenBoardView(board = board, items = board?.let { overlay(it.ideen, changes) }.orEmpty())
        }

    /** Vorgemerkte (und hängengebliebene) neue Ideen eines Buchs, neueste zuerst. */
    fun observePending(bookId: Long): Flow<List<PendingIdeaEntity>> = dao.observeForBook(bookId)

    /** Alle noch nicht zum Server durchgedrungenen neuen Ideen (jeder Status). */
    fun observePendingCount(): Flow<Int> = dao.observeCount()

    suspend fun hasPending(): Boolean = dao.count() > 0 || changeDao.pendingCount() > 0

    // ── Anlegen (offline-fähig) ─────────────────────────────────────────────

    /**
     * Neue Idee. [pageId] ODER [chapterId] oder keins (Buch-Idee). Liefert
     * [CreateIdeaResult.Saved] (am Server), [CreateIdeaResult.Queued] (vorgemerkt,
     * Nachversand geplant) oder [CreateIdeaResult.Invalid] (lokal abgelehnt, nichts
     * vorgemerkt) bzw. [CreateIdeaResult.Stuck] (vom Server abgewiesen, liegt mit Text vor).
     */
    suspend fun create(
        bookId: Long,
        content: String,
        pageId: Long? = null,
        chapterId: Long? = null,
    ): CreateIdeaResult {
        val text = content.trim()
        if (text.isEmpty()) return CreateIdeaResult.Invalid(CODE_CONTENT_REQ)
        if (text.length > IdeeDto.MAX_CONTENT) return CreateIdeaResult.Invalid(CODE_CONTENT_TOO_LONG)
        require(pageId == null || chapterId == null) { "page_id und chapter_id schliessen sich aus" }

        val localId = dao.insert(
            PendingIdeaEntity(
                bookId = bookId,
                content = text,
                pageId = pageId,
                chapterId = chapterId,
                createdAt = now(),
            )
        )
        return when (val r = flushMutex.withLock { sendOne(localId) }) {
            is SendOutcome.Sent -> CreateIdeaResult.Saved(r.idee)
            is SendOutcome.Rejected -> CreateIdeaResult.Stuck(r.code)
            SendOutcome.Transient, SendOutcome.Unauthorized, SendOutcome.Gone -> {
                onQueued()
                CreateIdeaResult.Queued
            }
        }
    }

    /**
     * Alle vorgemerkten Ideen der Reihe nach senden. Ein Netz-/Serverfehler hält den
     * Lauf an (die übrigen kämen gerade auch nicht durch), ebenso 401.
     */
    suspend fun flushPending(): FlushOutcome = flushMutex.withLock {
        var sent = 0
        for (idea in dao.byStatus()) {
            when (sendOne(idea.localId)) {
                is SendOutcome.Sent -> sent++
                is SendOutcome.Rejected, SendOutcome.Gone -> Unit
                SendOutcome.Transient -> return@withLock FlushOutcome.RetryLater(sent)
                SendOutcome.Unauthorized -> return@withLock FlushOutcome.Unauthorized
            }
        }
        // Änderungen erst nach den Anlagen: so trifft keine auf eine Idee, die der
        // Server noch gar nicht kennt.
        for (change in changeDao.pending()) {
            when (sendChange(change)) {
                is SendOutcome.Sent, SendOutcome.Gone -> sent++
                is SendOutcome.Rejected -> Unit
                SendOutcome.Transient -> return@withLock FlushOutcome.RetryLater(sent)
                SendOutcome.Unauthorized -> return@withLock FlushOutcome.Unauthorized
            }
        }
        FlushOutcome.Done(sent)
    }

    /** Hängengebliebene Idee (ggf. mit korrigiertem Text) erneut einreihen und senden. */
    suspend fun retryStuck(localId: Long, content: String? = null): CreateIdeaResult {
        val text = content?.trim()
        if (text != null) {
            if (text.isEmpty()) return CreateIdeaResult.Invalid(CODE_CONTENT_REQ)
            if (text.length > IdeeDto.MAX_CONTENT) return CreateIdeaResult.Invalid(CODE_CONTENT_TOO_LONG)
            dao.setContent(localId, text)
        }
        dao.setStatus(localId, PendingIdeaEntity.STATUS_PENDING, null)
        return when (val r = flushMutex.withLock { sendOne(localId) }) {
            is SendOutcome.Sent -> CreateIdeaResult.Saved(r.idee)
            is SendOutcome.Rejected -> CreateIdeaResult.Stuck(r.code)
            SendOutcome.Transient, SendOutcome.Unauthorized, SendOutcome.Gone -> {
                onQueued()
                CreateIdeaResult.Queued
            }
        }
    }

    /** Vorgemerkte Idee verwerfen (nur auf ausdrücklichen Wunsch des Nutzers). */
    suspend fun discardPending(localId: Long) = dao.delete(localId)

    /**
     * Einen Eintrag senden. Läuft immer unter [flushMutex], damit Worker und
     * Vordergrund dieselbe Zeile nicht parallel abschicken.
     */
    private suspend fun sendOne(localId: Long): SendOutcome {
        var idea = dao.byId(localId) ?: return SendOutcome.Gone
        if (idea.status != PendingIdeaEntity.STATUS_PENDING) return SendOutcome.Gone
        repeat(2) { attempt ->
            val resp: Response<IdeeDto> = try {
                net.ideen(baseUrl()).create(
                    idempotencyKey = idea.idempotencyKey,
                    body = CreateIdeeRequest(
                        book_id = idea.bookId,
                        content = idea.content,
                        page_id = idea.pageId,
                        chapter_id = idea.chapterId,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Netz weg (IOException), keine Server-URL, aber auch eine abgerissene oder
                // fremde Antwort (Captive-Portal-HTML mit 200 → Dekodierfehler): liegen
                // lassen. Hat der Server doch angelegt, fängt der Idempotency-Key das ab.
                return SendOutcome.Transient
            }
            val code = resp.code()
            when {
                resp.isSuccessful -> {
                    val body = resp.body() ?: return SendOutcome.Transient
                    db.withTransaction {
                        patchCache(body.book_id) { list -> listOf(body) + list.filter { it.id != body.id } }
                        dao.delete(localId)
                    }
                    return SendOutcome.Sent(body)
                }
                code == 401 -> return SendOutcome.Unauthorized
                isTransient(code) -> return SendOutcome.Transient
                else -> {
                    val errorCode = errorCode(resp.errorBody()) ?: "HTTP $code"
                    val anchored = idea.pageId != null || idea.chapterId != null
                    if (attempt == 0 && anchored) {
                        // Anker abgewiesen (Abschnitt gelöscht, Buch gewechselt …) →
                        // Einfall wenigstens als Buch-Idee retten.
                        dao.dropAnchor(localId)
                        idea = idea.copy(pageId = null, chapterId = null)
                    } else {
                        dao.setStatus(localId, PendingIdeaEntity.STATUS_STUCK, errorCode)
                        return SendOutcome.Rejected(errorCode)
                    }
                }
            }
        }
        return SendOutcome.Transient // nicht erreichbar
    }

    // ── Bearbeiten, Stufe, Umziehen, Löschen (offline-fähig) ─────────────────

    suspend fun setStatus(bookId: Long, id: Long, status: String): ChangeResult =
        enqueueChange(bookId, id, JsonObject(mapOf("status" to JsonPrimitive(status))), placeName = null)

    /**
     * Text, Stufe und Ort in EINER Änderung (Bearbeiten-Sheet). Unveränderte Felder als
     * null weglassen; [place] null = Ort bleibt, [placeName] ist nur für die Anzeige,
     * bis der Server antwortet. Der Server prüft beim Umziehen die END-Stufe
     * (`IDEE_CLOSED`, wenn sie erledigt/verworfen ist).
     */
    suspend fun update(
        bookId: Long,
        id: Long,
        content: String?,
        status: String?,
        place: Anchor? = null,
        placeName: String? = null,
    ): ChangeResult {
        val fields = mutableMapOf<String, JsonElement>()
        content?.trim()?.let {
            if (it.isEmpty()) return ChangeResult.Invalid(CODE_CONTENT_REQ)
            if (it.length > IdeeDto.MAX_CONTENT) return ChangeResult.Invalid(CODE_CONTENT_TOO_LONG)
            fields["content"] = JsonPrimitive(it)
        }
        status?.let { fields["status"] = JsonPrimitive(it) }
        place?.let { fields.putAll(anchorBody(it.pageId, it.chapterId)) }
        if (fields.isEmpty()) return ChangeResult.Invalid("NO_FIELDS")
        return enqueueChange(bookId, id, JsonObject(fields), placeName)
    }

    suspend fun delete(bookId: Long, id: Long): ChangeResult {
        val existing = changeDao.forIdee(id)
        changeDao.upsert(
            PendingIdeaChangeEntity(
                localId = existing?.localId ?: 0,
                bookId = bookId,
                ideeId = id,
                remove = true,
                createdAt = existing?.createdAt ?: now(),
            )
        )
        return sendChangeNow(id)
    }

    /** Hängengebliebene Änderung erneut senden. */
    suspend fun retryChange(ideeId: Long): ChangeResult {
        val row = changeDao.forIdee(ideeId) ?: return ChangeResult.Saved
        changeDao.setStatus(row.localId, PendingIdeaEntity.STATUS_PENDING, null)
        return sendChangeNow(ideeId)
    }

    /** Vorgemerkte Änderung verwerfen — die Idee zeigt wieder den Server-Stand. */
    suspend fun discardChange(ideeId: Long) {
        changeDao.forIdee(ideeId)?.let { changeDao.delete(it.localId) }
    }

    /** Neue Felder in die (einzige) vorgemerkte Änderung dieser Idee führen und senden. */
    private suspend fun enqueueChange(bookId: Long, id: Long, fields: JsonObject, placeName: String?): ChangeResult {
        val existing = changeDao.forIdee(id)
        if (existing?.remove == true) return ChangeResult.Queued // wird ohnehin gelöscht
        val merged = existing?.patch
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            .orEmpty()
            .let { base ->
                // Ein neuer Ort ersetzt den alten vollständig (page_id/chapter_id gehören zusammen).
                val anchorKeys = setOf("page_id", "chapter_id")
                val keep = if (fields.keys.any { it in anchorKeys }) base.filterKeys { it !in anchorKeys } else base
                JsonObject(keep + fields)
            }
        changeDao.upsert(
            PendingIdeaChangeEntity(
                localId = existing?.localId ?: 0,
                bookId = bookId,
                ideeId = id,
                patch = merged.toString(),
                placeName = if (fields.keys.any { it == "page_id" || it == "chapter_id" }) placeName else existing?.placeName,
                createdAt = existing?.createdAt ?: now(),
            )
        )
        return sendChangeNow(id)
    }

    private suspend fun sendChangeNow(ideeId: Long): ChangeResult {
        val outcome = flushMutex.withLock {
            val row = changeDao.forIdee(ideeId) ?: return@withLock SendOutcome.Gone
            sendChange(row)
        }
        return when (outcome) {
            is SendOutcome.Sent -> ChangeResult.Saved
            SendOutcome.Gone -> ChangeResult.Saved
            is SendOutcome.Rejected -> ChangeResult.Stuck(outcome.code)
            SendOutcome.Transient, SendOutcome.Unauthorized -> {
                onQueued()
                ChangeResult.Queued
            }
        }
    }

    /** Eine Änderung senden. Nur unter [flushMutex] aufrufen. */
    private suspend fun sendChange(row: PendingIdeaChangeEntity): SendOutcome {
        if (row.status != PendingIdeaEntity.STATUS_PENDING) return SendOutcome.Gone
        val api = try {
            net.ideen(baseUrl())
        } catch (e: IllegalStateException) {
            return SendOutcome.Transient
        }
        val resp: Response<*> = try {
            if (row.remove) api.delete(row.ideeId)
            else api.update(row.ideeId, json.parseToJsonElement(row.patch).jsonObject)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SendOutcome.Transient
        }
        val code = resp.code()
        val gone = code == 404
        return when {
            resp.isSuccessful || gone -> {
                val updated = (resp.body() as? IdeeDto).takeIf { !row.remove && !gone }
                if (!row.remove && !gone && updated == null) return SendOutcome.Transient
                db.withTransaction {
                    patchCache(row.bookId) { list ->
                        if (updated != null) list.map { if (it.id == row.ideeId) updated else it }
                        else list.filter { it.id != row.ideeId }
                    }
                    changeDao.deleteIfUnchanged(row.localId, row.patch, row.remove)
                }
                if (updated != null) SendOutcome.Sent(updated) else SendOutcome.Gone
            }
            code == 401 -> SendOutcome.Unauthorized
            isTransient(code) -> SendOutcome.Transient
            else -> {
                val errorCode = errorCode(resp.errorBody()) ?: "HTTP $code"
                changeDao.setStatusIfUnchanged(row.localId, row.patch, row.remove, PendingIdeaEntity.STATUS_STUCK, errorCode)
                SendOutcome.Rejected(errorCode)
            }
        }
    }

    /** Bestätigten Stand in den Board-Cache übernehmen (ohne Cache: nichts — der nächste Abruf bringt ihn). */
    private suspend fun patchCache(bookId: Long, change: (List<IdeeDto>) -> List<IdeeDto>) {
        val cache = cacheDao.byBook(bookId) ?: return
        val board = runCatching { json.decodeFromString(IdeenBoardDto.serializer(), cache.json) }.getOrNull() ?: return
        val next = board.copy(ideen = change(board.ideen))
        cacheDao.upsert(cache.copy(json = json.encodeToString(IdeenBoardDto.serializer(), next)))
    }

    private fun errorCode(body: ResponseBody?): String? = body?.use {
        runCatching { net.jsonParser.decodeFromString(ApiErrorDto.serializer(), it.string()).code }.getOrNull()
    }

    companion object {
        const val CODE_CONTENT_REQ = "CONTENT_REQ"
        const val CODE_CONTENT_TOO_LONG = "CONTENT_TOO_LONG"

        /**
         * Vorgemerkte Änderungen über die Ideen des Caches legen: PATCH-Felder anwenden,
         * Gelöschtes ausblenden (ausser die Löschung hängt — dann mit Fehler sichtbar).
         */
        internal fun overlay(ideen: List<IdeeDto>, changes: List<PendingIdeaChangeEntity>): List<IdeeView> {
            val byIdee = changes.associateBy { it.ideeId }
            return ideen.mapNotNull { idee ->
                val change = byIdee[idee.id] ?: return@mapNotNull IdeeView(idee)
                val stuck = change.status == PendingIdeaEntity.STATUS_STUCK
                if (change.remove) {
                    return@mapNotNull if (stuck) IdeeView(idee, pending = true, deleting = true, error = change.error) else null
                }
                val patch = runCatching { ApiJson.parseToJsonElement(change.patch).jsonObject }.getOrNull()
                    ?: return@mapNotNull IdeeView(idee, pending = true, error = change.error)
                IdeeView(applyPatch(idee, patch, change.placeName), pending = true, error = change.error)
            }
        }

        internal fun applyPatch(idee: IdeeDto, patch: JsonObject, placeName: String?): IdeeDto {
            var out = idee
            (patch["content"] as? JsonPrimitive)?.contentOrNull?.let { out = out.copy(content = it) }
            (patch["status"] as? JsonPrimitive)?.contentOrNull?.let { out = out.copy(status = it) }
            val hasPage = "page_id" in patch
            val hasChapter = "chapter_id" in patch
            if (hasPage || hasChapter) {
                val pageId = (patch["page_id"] as? JsonPrimitive)?.longOrNull
                val chapterId = (patch["chapter_id"] as? JsonPrimitive)?.longOrNull
                out = out.copy(
                    page_id = pageId,
                    page_name = if (pageId != null) placeName else null,
                    chapter_id = if (pageId == null) chapterId else null,
                    chapter_name = if (pageId == null && chapterId != null) placeName else null,
                )
            }
            return out
        }

        /** Zeitüberschreitung, Drosselung, Serverfehler: später erneut. */
        internal fun isTransient(code: Int): Boolean = code == 408 || code == 429 || code >= 500

        /**
         * PATCH-Body für den Anker. Beide `null` explizit = Buch-Idee; sonst nur das
         * gesetzte Feld (der Server leert das andere selbst).
         */
        internal fun anchorBody(pageId: Long?, chapterId: Long?): JsonObject = when {
            pageId != null -> JsonObject(mapOf("page_id" to JsonPrimitive(pageId)))
            chapterId != null -> JsonObject(mapOf("chapter_id" to JsonPrimitive(chapterId)))
            else -> JsonObject(mapOf("page_id" to JsonNull, "chapter_id" to JsonNull))
        }
    }
}

/** Ziel-Ort einer Idee: höchstens einer gesetzt, beide null = Buch-Idee. */
data class Anchor(val pageId: Long?, val chapterId: Long?) {
    init { require(pageId == null || chapterId == null) { "page_id und chapter_id schliessen sich aus" } }
}

sealed interface CreateIdeaResult {
    data class Saved(val idee: IdeeDto) : CreateIdeaResult
    /** Ohne Netz vorgemerkt; der Nachversand ist geplant. */
    data object Queued : CreateIdeaResult
    /** Vom Server abgewiesen; liegt mit Text als `stuck` in der Queue. */
    data class Stuck(val code: String) : CreateIdeaResult
    /** Lokal abgelehnt (leer / zu lang) — nichts vorgemerkt. */
    data class Invalid(val code: String) : CreateIdeaResult
}

/** Ergebnis einer Änderung (Bearbeiten, Stufe, Ort, Löschen). */
sealed interface ChangeResult {
    /** Am Server. */
    data object Saved : ChangeResult
    /** Ohne Netz vorgemerkt; der Nachversand ist geplant. */
    data object Queued : ChangeResult
    /** Vom Server abgewiesen; bleibt mit Fehlercode an der Idee stehen. */
    data class Stuck(val code: String) : ChangeResult
    /** Lokal abgelehnt (leer / zu lang) — nichts vorgemerkt. */
    data class Invalid(val code: String) : ChangeResult
}

/** Eine Idee in der Liste: Server-Stand mit vorgemerkter Änderung darüber. */
data class IdeeView(
    val idee: IdeeDto,
    /** Eine Änderung wartet noch auf den Server. */
    val pending: Boolean = false,
    /** Die wartende Änderung ist ein Löschen (nur sichtbar, wenn es hängt). */
    val deleting: Boolean = false,
    /** Abweisung der Änderung (`error_code`), sonst null. */
    val error: String? = null,
)

/** Board für die Liste: [board] null = noch nie geladen. */
data class IdeenBoardView(
    val board: IdeenBoardDto?,
    val items: List<IdeeView>,
)

sealed interface FlushOutcome {
    data class Done(val sent: Int) : FlushOutcome
    data class RetryLater(val sent: Int) : FlushOutcome
    data object Unauthorized : FlushOutcome
}

private sealed interface SendOutcome {
    data class Sent(val idee: IdeeDto) : SendOutcome
    data class Rejected(val code: String) : SendOutcome
    data object Transient : SendOutcome
    data object Unauthorized : SendOutcome
    /** Zeile weg oder nicht mehr `pending` (verworfen / parallel erledigt). */
    data object Gone : SendOutcome
}
