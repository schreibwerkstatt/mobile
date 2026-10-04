package ch.schreibwerkstatt.mobile.data.repo

import ch.schreibwerkstatt.mobile.data.db.AppDatabase
import ch.schreibwerkstatt.mobile.data.db.BookEntity
import ch.schreibwerkstatt.mobile.data.db.PageContentHit
import ch.schreibwerkstatt.mobile.data.db.PageEntity
import ch.schreibwerkstatt.mobile.data.db.PendingWriteEntity
import ch.schreibwerkstatt.mobile.data.net.DevicePingRequest
import ch.schreibwerkstatt.mobile.data.net.NetworkClient
import ch.schreibwerkstatt.mobile.data.net.WritingTimeRequest
import ch.schreibwerkstatt.mobile.data.net.dto.ApiErrorDto
import ch.schreibwerkstatt.mobile.data.net.dto.BookDto
import ch.schreibwerkstatt.mobile.data.net.dto.ChapterNodeDto
import ch.schreibwerkstatt.mobile.data.net.dto.CreateChapterRequest
import ch.schreibwerkstatt.mobile.data.net.dto.CreatePageRequest
import ch.schreibwerkstatt.mobile.data.net.dto.PageConflictDto
import ch.schreibwerkstatt.mobile.data.net.dto.PageLockedDto
import ch.schreibwerkstatt.mobile.data.net.dto.RevisionDto
import ch.schreibwerkstatt.mobile.data.net.dto.SavePageRequest
import ch.schreibwerkstatt.mobile.data.net.dto.TreeDto
import ch.schreibwerkstatt.mobile.data.net.dto.TreePageDto
import java.util.Locale
import androidx.room.withTransaction
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import kotlin.coroutines.cancellation.CancellationException
import retrofit2.Response

/**
 * Zentrale Schreib-/Lese-Schicht. Native Navigation UND der WebView-Editor
 * laufen hierüber (NICHT direkt gegen den Server) — so teilen sie denselben
 * Room-Cache. Online-Flush + Delta-Pull stecken im [SyncEngine].
 */
class ContentRepository(
    private val db: AppDatabase,
    private val net: NetworkClient,
    private val settings: SettingsStore,
) {
    private suspend fun baseUrl(): String =
        settings.serverBaseUrlOnce() ?: error("Keine Server-URL konfiguriert")

    private val sync by lazy { SyncEngine(db, net, settings) }

    /**
     * Serialisiert alle Server-gerichteten Sync-Operationen (Push-Flush + Delta-Pull)
     * gegeneinander. Ohne das können Editor-Save, Connectivity-Auto-Flush, manueller
     * Voll-Sync und der stündliche Worker denselben Pending-Write gleichzeitig flushen
     * (Doppel-PUT) bzw. ein Pull eine gerade lokal dirty gewordene Seite überschreiben.
     * Die rein lokale Persistenz eines Saves läuft bewusst NICHT unter diesem Lock,
     * damit ein Save nie hinter einem laufenden Netzwerk-Pull warten muss.
     */
    private val syncMutex = Mutex()

    // ── Bücher ───────────────────────────────────────────────────────────────

    fun observeBooks(): Flow<List<BookEntity>> = db.bookDao().observeAll()

    suspend fun refreshBooks(): Result<Unit> = runCatching {
        val books = net.content(baseUrl()).books()
        db.bookDao().upsertAll(books.map { it.toEntity() })
        // Leere Antwort NICHT als „alles gelöscht" interpretieren: `deleteMissing(emptyList)`
        // würde zu `DELETE … WHERE id NOT IN ()` = immer wahr → gesamter Buch-Cache weg.
        // Ein 200-mit-leerem-Body (Serverfehler/Scope-Problem) darf den Cache nicht leeren.
        if (books.isNotEmpty()) db.bookDao().deleteMissing(books.map { it.id })
    }

    suspend fun tree(bookId: Long): Result<TreeDto> = runCatching {
        net.content(baseUrl()).tree(bookId)
    }

    /** Buch-Metadaten aus dem lokalen Cache (u.a. `buchtyp`). */
    suspend fun bookById(bookId: Long): BookEntity? = db.bookDao().byId(bookId)

    // ── Tagebuch: Eintrag für ein Datum anlegen/öffnen ────────────────────────

    /**
     * Liefert die Page-ID für den Tagebuch-Eintrag des Tages `dateIso`
     * (`YYYY-MM-DD`). Existiert bereits eine Seite mit diesem Datum, wird deren
     * ID zurückgegeben (keine Duplikate). Sonst wird die Seite serverseitig
     * angelegt — eingeordnet ins Jahr-Kapitel (`YYYY`) und, falls vorhanden,
     * ins passende Monats-Unterkapitel. Spiegelt die Logik des Web-Clients
     * (`public/js/book/diary-calendar.js`).
     *
     * **Online-only:** Das Anlegen braucht die vom Server vergebene ID; offline
     * schlägt der Aufruf fehl (kein Queuing). Editieren danach läuft normal
     * offline-fähig über [savePage].
     */
    suspend fun createDiaryEntry(bookId: Long, dateIso: String): Result<Long> = runCatching {
        require(DIARY_DATE_RE.matches(dateIso)) { "Ungültiges Datum: $dateIso" }
        val api = net.content(baseUrl())
        val tree = api.tree(bookId)

        // Schon vorhanden? → bestehende Seite öffnen statt duplizieren.
        collectPages(tree).firstOrNull { diaryDateOf(it.name) == dateIso }?.let {
            return@runCatching it.id
        }

        val year = dateIso.substring(0, 4).toInt()
        val month = dateIso.substring(5, 7).toInt()
        val yearChapterId = ensureYearChapter(api, bookId, tree, year)
        val chapterId = resolveMonthChapter(api, bookId, tree, yearChapterId, year, month)

        val resp = api.createPage(
            CreatePageRequest(book_id = bookId, chapter_id = chapterId, name = dateIso, html = "<p></p>")
        )
        if (!resp.isSuccessful) error("createPage HTTP ${resp.code()}")
        resp.body()?.id ?: error("createPage lieferte keine ID")
    }

    /** Top-Level-Jahr-Kapitel `YYYY` finden oder anlegen; liefert dessen ID. */
    private suspend fun ensureYearChapter(
        api: ch.schreibwerkstatt.mobile.data.net.ContentApi,
        bookId: Long,
        tree: TreeDto,
        year: Int,
    ): Long {
        val yearStr = year.toString()
        tree.chapters.firstOrNull { it.name == yearStr && it.parent_chapter_id == null }
            ?.let { return it.id }
        val resp = api.createChapter(
            CreateChapterRequest(book_id = bookId, name = yearStr, position = year)
        )
        if (!resp.isSuccessful) error("createChapter (Jahr) HTTP ${resp.code()}")
        return resp.body()?.id ?: error("createChapter (Jahr) lieferte keine ID")
    }

    /**
     * Kapitel-ID, in die der Eintrag gehört. Heuristik wie im Web:
     * - Jahr-Kapitel hat keine Unterkapitel → Jahr-Kapitel selbst.
     * - Unterkapitel da, aber keine monatsartigen → Jahr-Kapitel (fremdes Schema).
     * - Monatsartige Unterkapitel da, passender Monat dabei → dieses.
     * - Monatsartige da, Monat fehlt → neues Monats-Kapitel anlegen.
     */
    private suspend fun resolveMonthChapter(
        api: ch.schreibwerkstatt.mobile.data.net.ContentApi,
        bookId: Long,
        tree: TreeDto,
        yearChapterId: Long,
        year: Int,
        month: Int,
    ): Long {
        val subs = yearChapterSubs(tree, yearChapterId)
        if (subs.isEmpty()) return yearChapterId

        val monthSubs = subs.mapNotNull { sub ->
            val m = parseMonthName(sub.name)
                ?: sub.position?.takeIf { it in 1..12 }
            m?.let { it to sub }
        }
        if (monthSubs.isEmpty()) return yearChapterId

        monthSubs.firstOrNull { it.first == month }?.let { return it.second.id }

        val names = if (Locale.getDefault().language == "en") MONTH_NAMES_EN else MONTH_NAMES_DE
        val resp = api.createChapter(
            CreateChapterRequest(
                book_id = bookId,
                name = "$year ${names[month - 1]}",
                position = month,
                parent_chapter_id = yearChapterId,
            )
        )
        if (!resp.isSuccessful) error("createChapter (Monat) HTTP ${resp.code()}")
        return resp.body()?.id ?: error("createChapter (Monat) lieferte keine ID")
    }

    /** Unterkapitel eines Kapitels — verschachtelte UND flache Tree-Form. */
    private fun yearChapterSubs(tree: TreeDto, parentId: Long): List<ChapterNodeDto> {
        val nested = collectChapters(tree).firstOrNull { it.id == parentId }?.subchapters.orEmpty()
        val flat = tree.chapters.filter { it.parent_chapter_id == parentId }
        return nested + flat
    }

    // ── Seiten ─────────────────────────────────────────────────────────────

    fun observePages(bookId: Long): Flow<List<PageEntity>> = db.pageDao().observeForBook(bookId)

    /**
     * Lokale Volltextsuche im **Inhalt** der Seiten eines Buchs (Room-FTS über den
     * gestrippten Klartext). Deckt die Seiten ab, die der Delta-Pull bereits gecacht
     * hat — beim Betreten des Baums wird das ganze Buch gepullt, also typischerweise
     * vollständig. Leere/zeichenlose Query → keine Treffer.
     */
    suspend fun searchPageContent(bookId: Long, query: String): List<PageContentHit> {
        val match = toFtsMatch(query) ?: return emptyList()
        return db.pageDao().searchContent(bookId, match)
    }

    /**
     * Roh-Eingabe → FTS4-MATCH-Query: in Tokens zerlegen, Nicht-Alphanumerisches
     * (auch FTS-Sonderzeichen) entfernen und je Token Präfix-Match (`token*`)
     * UND-verknüpfen. Liefert null, wenn nichts Durchsuchbares übrig bleibt.
     */
    private fun toFtsMatch(raw: String): String? {
        val tokens = raw.trim().split(WHITESPACE_RE)
            .map { token -> token.filter(Char::isLetterOrDigit) }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    /**
     * Seite für den Editor laden. Reihenfolge:
     * 1. **Lokal-dirty** → immer der Cache: der Pending-Write hat Vorrang und darf
     *    nie mit Server-Stand überschrieben werden (siehe Offline-first-Regel).
     * 2. Sonst **best effort den frischen Server-Stand** holen und den Cache
     *    aktualisieren. So sieht der Editor beim Öffnen den aktuellen Inhalt –
     *    wichtig für Tagebuch-Einträge, die oft auch im Web bearbeitet werden und
     *    deren bereits gecachte (nicht-dirty) Seite sonst veraltet angezeigt würde.
     * 3. Schlägt der Server-Abruf fehl (offline / Serverfehler) → auf den Cache
     *    zurückfallen, damit das Öffnen offline weiterhin funktioniert.
     */
    suspend fun loadPage(pageId: Long, bookId: Long): Result<PageEntity> = runCatching {
        val cached = db.pageDao().byId(pageId)
        if (cached != null && cached.dirty) return@runCatching cached
        val dto = try {
            net.content(baseUrl()).page(pageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            cached ?: throw e
            return@runCatching cached
        }
        val entity = PageEntity(
            id = dto.id,
            bookId = bookId,
            chapterId = dto.chapter_id,
            name = dto.name,
            html = dto.html,
            plain = HtmlText.toPlain(dto.html),
            updatedAt = dto.updated_at,
            dirty = false,
        )
        // Dirty-Recheck + Upsert atomar: während des Netzabrufs kann ein Save (z.B. der
        // Close-Save der vorherigen Editor-Instanz bei Rotation) die Seite dirty gemacht
        // haben — die darf nie mit Server-Stand überschrieben werden.
        db.withTransaction {
            val current = db.pageDao().byId(pageId)
            if (current != null && current.dirty) {
                current
            } else {
                db.pageDao().upsert(entity)
                entity
            }
        }
    }

    /**
     * Editor-Save: lokal persistieren, Pending-Write queuen, dann (best effort)
     * online flushen. Liefert das Resultat des Online-Versuchs.
     */
    suspend fun savePage(pageId: Long, bookId: Long, html: String): SaveResult {
        val deviceId = settings.deviceId()
        // 1) Lokal persistieren (dirty) + Queue konsolidieren — ATOMAR, damit nach einem
        //    Prozess-Tod/Cancel nie eine dirty-Seite OHNE zugehörigen Pending-Write
        //    zurückbleibt (die sonst weder gepusht noch gepullt würde → eingefroren).
        val localId = db.withTransaction {
            // Basis-Stand in derselben Transaktion VOR der lokalen Mutation lesen:
            // updateHtml lässt updatedAt unberührt, hier steht also der zuletzt
            // bestätigte Server-Stand. Geht als expected_updated_at mit, damit der Server
            // einen Fremd-Save als 409 erkennt. Bestätigt ein gerade laufender Flush
            // danach einen älteren Write, hebt er diese Basis nach (siehe flushOne).
            val baseUpdatedAt = db.pageDao().byId(pageId)?.updatedAt
            db.pageDao().updateHtml(pageId, html, HtmlText.toPlain(html), dirty = true)
            db.pendingWriteDao().deletePendingForPage(pageId)
            db.pendingWriteDao().insert(
                PendingWriteEntity(
                    pageId = pageId,
                    bookId = bookId,
                    html = html,
                    deviceId = deviceId,
                    createdAt = nowMillis(),
                    baseUpdatedAt = baseUpdatedAt,
                )
            )
        }
        // 2) Online-Versuch (serialisiert gegen andere Flushes/Pulls).
        return syncMutex.withLock {
            try {
                flushOne(localId)
            } catch (e: AuthLostException) {
                SaveResult.Queued   // bleibt pending, wird nach dem Neu-Koppeln geflusht
            }
        }
    }

    /**
     * Einzelnen Pending-Write gegen den Server schicken. Lock-freie Kern-Primitive —
     * der Aufrufer hält bereits [syncMutex] (savePage / flushPending / resolve*).
     *
     * Der Write wird erst HIER (unter dem Lock) frisch aus der Queue gelesen: Ein
     * Snapshot von vor dem Lock könnte inzwischen von einem neueren Save ersetzt oder
     * von einem vorherigen Flush umbasiert worden sein. Ist er weg, wurde er überholt —
     * der neuere Write meldet sein eigenes Resultat.
     *
     * @param forceBase überschreibt `expected_updated_at` (bewusstes Überschreiben
     *   des Server-Stands bei „lokal gewinnt").
     * @throws AuthLostException bei 401 — der Write bleibt `pending`.
     */
    private suspend fun flushOne(localId: Long, forceBase: String? = null): SaveResult {
        val w = db.pendingWriteDao().byId(localId) ?: return SaveResult.Queued
        val pageId = w.pageId
        val resp: Response<ch.schreibwerkstatt.mobile.data.net.dto.PageDto> = try {
            net.content(baseUrl()).savePage(
                pageId,
                SavePageRequest(
                    html = w.html,
                    device_id = w.deviceId,
                    expected_updated_at = forceBase ?: w.baseUpdatedAt,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return SaveResult.Queued   // offline → bleibt in der Queue
        }
        return when {
            resp.isSuccessful -> {
                val dto = resp.body()
                val serverHtml = dto?.html ?: w.html
                db.withTransaction {
                    db.pendingWriteDao().delete(localId)
                    // Von diesem Erfolg überholte conflict/locked/failed-Zeilen weg, sonst
                    // meldet openConflict beim Wiederöffnen einen längst erledigten Konflikt.
                    db.pendingWriteDao().deleteSettledForPage(pageId)
                    if (db.pendingWriteDao().pendingForPage(pageId) == null) {
                        db.pageDao().applyServerVersion(pageId, serverHtml, HtmlText.toPlain(serverHtml), dto?.updated_at, dto?.name)
                    } else {
                        // Während des PUT kam ein neuerer lokaler Save dazu: Er baut auf
                        // diesem Write auf, also seine Basis auf den eben bestätigten
                        // Server-Stand heben (sonst 409 gegen den eigenen Save). Lokaler
                        // Inhalt und dirty bleiben — der neuere Write hat Vorrang.
                        db.pageDao().updateServerStamp(pageId, dto?.updated_at)
                        db.pendingWriteDao().rebasePending(pageId, dto?.updated_at)
                    }
                }
                db.pageDao().byId(pageId)?.let { SaveResult.Saved(it) } ?: SaveResult.Queued
            }
            resp.code() == 401 -> {
                // Token ungültig (der AuthInterceptor hat es schon verworfen). NICHT als
                // failed parken — sonst würde der Write nach dem Neu-Koppeln nie mehr
                // versucht und die dirty-Seite fröre ein.
                throw AuthLostException()
            }
            resp.code() == 409 -> {
                val c = parseError(resp.errorBody(), PageConflictDto.serializer())
                db.pendingWriteDao().setStatus(
                    localId, PendingWriteEntity.STATUS_CONFLICT, c?.server_editor_name
                )
                SaveResult.Conflict(c?.server_updated_at, c?.server_editor_name)
            }
            resp.code() == 423 -> {
                val l = parseError(resp.errorBody(), PageLockedDto.serializer())
                db.pendingWriteDao().setStatus(
                    localId, PendingWriteEntity.STATUS_LOCKED, l?.locked_by_email
                )
                SaveResult.Locked(l?.locked_by_email, l?.expires_at)
            }
            resp.code() >= 500 -> {
                // Transienter Serverfehler (500/502/503/504): pending LASSEN, damit der
                // nächste flushPending es erneut versucht. Als FAILED zu parken hiesse,
                // die dirty-Seite friert ein (Pull überspringt dirty, flushPending retryt
                // nur pending) — bis der Nutzer sie zufällig neu editiert.
                val e = parseError(resp.errorBody(), ApiErrorDto.serializer())
                db.pendingWriteDao().setStatus(
                    localId, PendingWriteEntity.STATUS_PENDING, e?.code ?: "HTTP ${resp.code()}"
                )
                SaveResult.Queued
            }
            else -> {
                // Nicht-transienter Client-Fehler (4xx ausser 409/423): erneuter Versuch
                // hilft nicht → als failed parken.
                val e = parseError(resp.errorBody(), ApiErrorDto.serializer())
                db.pendingWriteDao().setStatus(
                    localId, PendingWriteEntity.STATUS_FAILED, e?.code ?: "HTTP ${resp.code()}"
                )
                SaveResult.Queued
            }
        }
    }

    /**
     * Noch nicht aufgelöster Konflikt-Pending-Write der Seite (Status `conflict`),
     * sonst null. Der Editor macht damit beim Wiederöffnen einen beim letzten Save
     * aufgetretenen 409 erneut sichtbar — sonst bliebe die dirty-Seite stumm auf dem
     * lokalen Stand hängen (`loadPage` liefert dirty immer aus dem Cache, der
     * Sync-Pull überspringt dirty, und `flushPending` retryt nur `pending`).
     */
    suspend fun openConflict(pageId: Long): PendingWriteEntity? =
        db.pendingWriteDao().latestForPage(pageId)
            ?.takeIf { it.status == PendingWriteEntity.STATUS_CONFLICT }

    /**
     * Konflikt auflösen: Server-Version laden und lokal übernehmen (lokale
     * Änderung verwerfen). Für v1 die einfache „Server gewinnt"-Variante.
     */
    suspend fun resolveWithServerVersion(pageId: Long, bookId: Long): Result<PageEntity> = runCatching {
        syncMutex.withLock {
            val dto = net.content(baseUrl()).page(pageId)
            // Alle Pending-Writes der Seite verwerfen (auch verwaiste conflict/failed-Zeilen),
            // sonst maskiert eine liegen gebliebene Zeile den aufgelösten Konflikt.
            // Atomar mit dem Cache-Update: ein dazwischen fallender Save liesse sonst eine
            // dirty-Seite ohne Pending-Write zurück (eingefroren).
            applyServerVersionDiscardingQueue(pageId, dto.html, dto.updated_at, dto.name)
        }
    }

    private suspend fun applyServerVersionDiscardingQueue(
        pageId: Long, html: String?, updatedAt: String?, name: String?,
    ): PageEntity = db.withTransaction {
        db.pageDao().applyServerVersion(pageId, html, HtmlText.toPlain(html), updatedAt, name)
        db.pendingWriteDao().deleteAllForPage(pageId)
        db.pageDao().byId(pageId) ?: error("Seite $pageId nicht im Cache")
    }

    /**
     * Beide Konflikt-Fassungen als Klartext für die Vergleichsansicht: die lokale,
     * noch nicht durchgesetzte Änderung (aus dem dirty-Cache) und der aktuelle
     * Server-Stand (frisch geladen). Rein lesend — verändert weder Cache noch Queue.
     */
    suspend fun conflictPreview(pageId: Long): Result<ConflictPreview> = runCatching {
        val localHtml = db.pageDao().byId(pageId)?.html
        val serverHtml = net.content(baseUrl()).page(pageId).html
        ConflictPreview(
            local = HtmlText.toPlain(localHtml).orEmpty(),
            server = HtmlText.toPlain(serverHtml).orEmpty(),
        )
    }

    /**
     * Konflikt auflösen zugunsten der lokalen Fassung: den server­seitigen Stand
     * bewusst überschreiben. Dazu wird der aktuelle Server-`updated_at` als
     * `expected_updated_at` mitgeschickt, damit der erneute Save den 409-Guard
     * passiert. Erfolg ⇒ Pending-Write entfernt, Cache auf den (bestätigten)
     * Server-Stand gesetzt. Schlägt der Push fehl (offline/erneuter Fremd-Save),
     * bleibt der Konflikt-Pending-Write bestehen.
     */
    suspend fun resolveWithLocalVersion(pageId: Long, bookId: Long): Result<PageEntity> = runCatching {
        syncMutex.withLock {
            val pending = db.pendingWriteDao().latestForPage(pageId)
                ?: error("Kein lokaler Stand für Seite $pageId")
            val serverUpdatedAt = net.content(baseUrl()).page(pageId).updated_at
            when (val result = flushOne(pending.localId, forceBase = serverUpdatedAt)) {
                is SaveResult.Saved -> result.page
                is SaveResult.Conflict -> error("Erneuter Konflikt beim Überschreiben")
                is SaveResult.Locked -> error("Seite gesperrt")
                SaveResult.Queued -> error("Server nicht erreichbar")
            }
        }
    }

    // ── Seiten-Versionen (Revisions) ─────────────────────────────────────────

    /** Versionsliste einer Seite (Metadaten, ohne HTML-Body). */
    suspend fun pageRevisions(pageId: Long): Result<List<RevisionDto>> = runCatching {
        net.content(baseUrl()).revisions(pageId).revisions
    }

    /** Eine einzelne Revision inkl. vollem `body_html` (für die Vorschau). */
    suspend fun pageRevision(pageId: Long, revId: Long): Result<RevisionDto> = runCatching {
        net.content(baseUrl()).revision(pageId, revId).revision
            ?: error("Revision $revId nicht gefunden")
    }

    /**
     * Seite auf eine frühere Revision zurücksetzen. Der Server legt dabei eine neue
     * `main`-Revision an; danach holen wir den frischen Seitenstand und übernehmen ihn
     * in den lokalen Cache (und verwerfen evtl. Pending-Writes — „Server gewinnt").
     */
    suspend fun restoreRevision(pageId: Long, revId: Long, bookId: Long): Result<PageEntity> = runCatching {
        syncMutex.withLock {
            val api = net.content(baseUrl())
            val resp = api.restoreRevision(pageId, revId)
            if (!resp.isSuccessful) error("restore HTTP ${resp.code()}")
            val dto = api.page(pageId)
            applyServerVersionDiscardingQueue(pageId, dto.html, dto.updated_at, dto.name)
        }
    }

    // ── Sync (Delegation) ────────────────────────────────────────────────────

    suspend fun syncBook(bookId: Long): Result<Unit> =
        syncMutex.withLock { sync.pullBook(bookId, ::baseUrl) }

    /**
     * Voll-Sync über alle bekannten Bücher (für den periodischen Background-Pull
     * und „Jetzt synchronisieren"): erst die Pending-Queue flushen (Push), dann die
     * Buchliste auffrischen und für jedes Buch den Delta-Pull fahren. Delta-Pull ist
     * dirty-sicher (siehe [SyncEngine]) — lokale Pending-Writes bleiben unangetastet.
     * Schlägt ein Buch fehl, läuft der Rest trotzdem; der erste Fehler wird am Ende
     * als `Result.failure` gemeldet (für WorkManager-Retry).
     */
    suspend fun syncAllBooks(): Result<Unit> = runCatching {
        flushPending()
        refreshBooks().getOrThrow()
        var firstError: Throwable? = null
        for (book in db.bookDao().all()) {
            syncBook(book.id).onFailure { if (firstError == null) firstError = it }
        }
        firstError?.let { throw it }
    }

    /** Ein 401 bricht den Lauf ab (Result.failure); die Writes bleiben pending. */
    suspend fun flushPending(): Result<Int> =
        syncMutex.withLock { sync.flushPending { localId -> flushOne(localId) } }

    fun observePending() = db.pendingWriteDao().observePending()

    /** Anzahl noch nicht zum Server durchgedrungener Queue-Einträge (jeder Status). */
    suspend fun unsyncedCount(): Int = db.pendingWriteDao().count()

    /**
     * Lokalen Cache samt Queue und Sync-Cursorn leeren — beim Koppeln mit einem
     * ANDEREN Server: Seiten-IDs sind serverlokal, die alte Queue würde sonst fremde
     * Seiten-IDs gegen den neuen Server flushen.
     */
    suspend fun clearLocalData() = syncMutex.withLock {
        withContext(Dispatchers.IO) { db.clearAllTables() }
    }

    suspend fun devicePing(bookId: Long, pageId: Long?) = runCatching {
        net.content(baseUrl()).devicePing(
            bookId, DevicePingRequest(device_id = settings.deviceId(), page_id = pageId)
        )
    }

    /**
     * Schreibzeit-Heartbeat (best effort): meldet die seit dem letzten Ping
     * vergangenen Delta-Sekunden. Der Server addiert sie pro (User, Buch, Tag)
     * auf; die App speichert nichts davon. Fehler/Offline werden geschluckt.
     */
    suspend fun writingTime(bookId: Long, seconds: Int) = runCatching {
        net.content(baseUrl()).writingTime(
            WritingTimeRequest(book_id = bookId, seconds = seconds)
        )
    }

    // ── Helfer ────────────────────────────────────────────────────────────────

    private fun <T> parseError(body: ResponseBody?, serializer: kotlinx.serialization.KSerializer<T>): T? =
        body?.string()?.takeIf { it.isNotBlank() }?.let {
            runCatching { net.jsonParser.decodeFromString(serializer, it) }.getOrNull()
        }

    private fun nowMillis(): Long = System.currentTimeMillis()
}

/** 401 beim Flush: Token verworfen, der Write bleibt pending bis zum Neu-Koppeln. */
class AuthLostException : Exception("HTTP 401")

private fun BookDto.toEntity() = BookEntity(
    id = id, name = name, role = role, ownerEmail = owner_email, buchtyp = buchtyp,
)

// ── Tagebuch-Helfer (geteilt mit der Tree-/Kalender-UI) ──────────────────────

/** `buchtyp`-Wert für Tagebuch-Bücher (Server-Schema). */
const val BUCHTYP_TAGEBUCH = "tagebuch"

private val WHITESPACE_RE = Regex("""\s+""")
private val DIARY_DATE_RE = Regex("""\d{4}-\d{2}-\d{2}""")
private val DIARY_DATE_PREFIX_RE = Regex("""^(\d{4}-\d{2}-\d{2})\b""")

/** Extrahiert den ISO-Tag `YYYY-MM-DD` aus einem Seitennamen, sonst null. */
fun diaryDateOf(name: String?): String? =
    name?.let { DIARY_DATE_PREFIX_RE.find(it)?.groupValues?.get(1) }

/** Alle Kapitelknoten flach (verschachtelte `subchapters` mit eingeschlossen). */
private fun collectChapters(tree: TreeDto): List<ChapterNodeDto> {
    val out = mutableListOf<ChapterNodeDto>()
    fun rec(c: ChapterNodeDto) { out += c; c.subchapters.forEach(::rec) }
    tree.chapters.forEach(::rec)
    return out
}

/** Alle Seiten des Baums (Top-Level + in Kapiteln). */
internal fun collectPages(tree: TreeDto): List<TreePageDto> =
    tree.topPages + collectChapters(tree).flatMap { it.pages }

private val MONTH_NAMES_DE = arrayOf(
    "Januar", "Februar", "März", "April", "Mai", "Juni",
    "Juli", "August", "September", "Oktober", "November", "Dezember",
)
private val MONTH_NAMES_EN = arrayOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

// DE/EN-Monatsname → 1-12, diakritika-tolerant. Spiegel zu diary-calendar.js.
private val MONTH_TOKENS = mapOf(
    "januar" to 1, "jan" to 1, "jaenner" to 1, "january" to 1,
    "februar" to 2, "feb" to 2, "february" to 2,
    "maerz" to 3, "marz" to 3, "mar" to 3, "mrz" to 3, "march" to 3,
    "april" to 4, "apr" to 4,
    "mai" to 5, "may" to 5,
    "juni" to 6, "jun" to 6, "june" to 6,
    "juli" to 7, "jul" to 7, "july" to 7,
    "august" to 8, "aug" to 8,
    "september" to 9, "sep" to 9, "sept" to 9,
    "oktober" to 10, "okt" to 10, "oct" to 10, "october" to 10,
    "november" to 11, "nov" to 11,
    "dezember" to 12, "dez" to 12, "december" to 12,
)

private val MONTH_SPLIT_RE = Regex("""[\s.,;:_\-/]+""")

private fun parseMonthName(token: String?): Int? {
    if (token.isNullOrBlank()) return null
    val norm = java.text.Normalizer.normalize(token.lowercase(), java.text.Normalizer.Form.NFD)
        .replace(Regex("""\p{Mn}+"""), "")
    return norm.split(MONTH_SPLIT_RE).filter { it.isNotEmpty() }
        .firstNotNullOfOrNull { MONTH_TOKENS[it] }
}
