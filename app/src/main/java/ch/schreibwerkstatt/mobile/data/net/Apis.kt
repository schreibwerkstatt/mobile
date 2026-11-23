package ch.schreibwerkstatt.mobile.data.net

import ch.schreibwerkstatt.mobile.data.net.dto.BookDto
import ch.schreibwerkstatt.mobile.data.net.dto.ChapterNodeDto
import ch.schreibwerkstatt.mobile.data.net.dto.ConfigDto
import ch.schreibwerkstatt.mobile.data.net.dto.CreateChapterRequest
import ch.schreibwerkstatt.mobile.data.net.dto.CreatePageRequest
import ch.schreibwerkstatt.mobile.data.net.dto.CreateResearchRequest
import ch.schreibwerkstatt.mobile.data.net.dto.ResearchItemDto
import ch.schreibwerkstatt.mobile.data.net.dto.ResearchScrapeRequest
import ch.schreibwerkstatt.mobile.data.net.dto.ResearchScrapeResponse
import ch.schreibwerkstatt.mobile.data.net.dto.CreateIdeeRequest
import ch.schreibwerkstatt.mobile.data.net.dto.IdeeDto
import ch.schreibwerkstatt.mobile.data.net.dto.IdeenBoardDto
import ch.schreibwerkstatt.mobile.data.net.dto.IdeeDeleteResponse
import kotlinx.serialization.json.JsonObject
import ch.schreibwerkstatt.mobile.data.net.dto.PageDto
import ch.schreibwerkstatt.mobile.data.net.dto.RestoreResponse
import ch.schreibwerkstatt.mobile.data.net.dto.RevisionDetailResponse
import ch.schreibwerkstatt.mobile.data.net.dto.RevisionListResponse
import ch.schreibwerkstatt.mobile.data.net.dto.SavePageRequest
import ch.schreibwerkstatt.mobile.data.net.dto.SyncResponse
import ch.schreibwerkstatt.mobile.data.net.dto.TranscribeResponse
import ch.schreibwerkstatt.mobile.data.net.dto.TreeDto
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.PATCH
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface ConfigApi {
    @GET("config")
    suspend fun config(): ConfigDto
}

interface ContentApi {
    @GET("content/books")
    suspend fun books(): List<BookDto>

    @GET("content/books/{bookId}/tree")
    suspend fun tree(@Path("bookId") bookId: Long): TreeDto

    /** Delta-Pull. Erstaufruf ohne Cursor = Baseline. */
    @GET("content/books/{bookId}/sync")
    suspend fun sync(
        @Path("bookId") bookId: Long,
        @Query("since") since: String?,
        @Query("since_id") sinceId: Long?,
        @Query("limit") limit: Int = 200,
    ): SyncResponse

    @GET("content/pages/{pageId}")
    suspend fun page(@Path("pageId") pageId: Long): PageDto

    /** Neue Seite anlegen (z.B. Tagebuch-Eintrag mit Name `YYYY-MM-DD`). */
    @POST("content/pages")
    suspend fun createPage(@Body body: CreatePageRequest): Response<PageDto>

    /** Neues Kapitel anlegen (Jahr-/Monats-Kapitel für Tagebuch-Bücher). */
    @POST("content/chapters")
    suspend fun createChapter(@Body body: CreateChapterRequest): Response<ChapterNodeDto>

    /** 200 → PageDto · 409 PAGE_CONFLICT · 423 PAGE_LOCKED (Body via errorBody). */
    @PUT("content/pages/{pageId}")
    suspend fun savePage(
        @Path("pageId") pageId: Long,
        @Body body: SavePageRequest,
    ): Response<PageDto>

    @POST("content/books/{bookId}/device-ping")
    suspend fun devicePing(
        @Path("bookId") bookId: Long,
        @Body body: DevicePingRequest,
    ): Response<Unit>

    /** Schreibzeit-Heartbeat: Delta-Sekunden pro (User, Buch, Tag) aufaddieren. */
    @POST("history/writing-time")
    suspend fun writingTime(@Body body: WritingTimeRequest): Response<Unit>

    /** Versionsliste einer Seite (ohne `body_html`, nur Metadaten). */
    @GET("content/pages/{pageId}/revisions")
    suspend fun revisions(
        @Path("pageId") pageId: Long,
        @Query("limit") limit: Int = 100,
    ): RevisionListResponse

    /** Einzelne Revision inkl. vollem `body_html` (für die Vorschau). */
    @GET("content/pages/{pageId}/revisions/{revId}")
    suspend fun revision(
        @Path("pageId") pageId: Long,
        @Path("revId") revId: Long,
    ): RevisionDetailResponse

    /** Seite auf eine frühere Revision zurücksetzen (legt eine neue Revision an). */
    @POST("content/pages/{pageId}/revisions/{revId}/restore")
    suspend fun restoreRevision(
        @Path("pageId") pageId: Long,
        @Path("revId") revId: Long,
    ): Response<RestoreResponse>
}

@kotlinx.serialization.Serializable
data class DevicePingRequest(
    val device_id: String,
    val page_id: Long? = null,
)

@kotlinx.serialization.Serializable
data class WritingTimeRequest(
    val book_id: Long,
    val seconds: Int,
)

/**
 * Buchweites Recherche-/Wissensboard (`routes/research.js`, gilt hinter dem
 * Auth-Guard, Device-Token genügt). Die App nutzt Liste, Anlegen, Bild-Upload und
 * Text-Nachladen (Scrape); Anlegen/Upload/Scrape laufen über die Outbox.
 */
interface ResearchApi {
    /**
     * Nicht-archivierte Recherche-Items eines Buchs (angeheftete zuerst). Device-Token
     * bekommen die Client-Form und ohne [limit] höchstens 50 — darum immer
     * [LIST_LIMIT] (Server-Maximum) mitschicken.
     */
    @GET("research")
    suspend fun list(
        @Query("book_id") bookId: Long,
        @Query("limit") limit: Int = LIST_LIMIT,
        @Query("sort") sort: String = "updated",
    ): List<ResearchItemDto>

    /**
     * Neues Item anlegen. 200 → [ResearchItemDto] · 400 `EMPTY`/`BOOKID_REQ`/
     * `INVALID_URL`/`INVALID_FIELD` · 409 `DUPLICATE_URL` (übersteuerbar mit
     * `allow_duplicate`) · 401 · 403.
     */
    @POST("research")
    suspend fun create(@Body body: CreateResearchRequest): Response<ResearchItemDto>

    /** Bild anhängen: rohe Bytes mit `image/…`-Content-Type (z.B. `image/jpeg`), max. 12 MB. 400 `NO_IMAGE`/`IMAGE_INVALID`. */
    @POST("research/{id}/image")
    suspend fun uploadImage(@Path("id") id: Long, @Body image: RequestBody): Response<ResearchItemDto>

    /** Link serverseitig lesen und leere Felder füllen. 400 `NO_URL` · 422 `SCRAPE_EMPTY` · 502 `SCRAPE_FAILED`. */
    @POST("research/{id}/scrape")
    suspend fun scrape(@Path("id") id: Long, @Body body: ResearchScrapeRequest): Response<ResearchScrapeResponse>

    companion object {
        /** Server-Maximum für `limit` (`LIST_LIMIT_MAX`). */
        const val LIST_LIMIT = 200
    }
}

/**
 * Ideen-Board eines Buchs (`routes/ideen.js`, Vertrag api 7, hinter dem Auth-Guard; ein
 * Device-Token mit `content:write` genügt). Ideen sind user-privat und kein Buchinhalt —
 * sie laufen über [ch.schreibwerkstatt.mobile.data.repo.IdeenRepository], nicht über
 * ContentRepository. Fehler kommen als `{ error_code }` (z.B. `CONTENT_REQ`,
 * `CONTENT_TOO_LONG`, `BOOK_MISMATCH`, `IDEE_NOT_FOUND`, `IDEE_CLOSED`, `IDEE_STATUS_INACTIVE`).
 */
interface IdeenApi {
    /** Alle Ideen des Buchs + Stufen. */
    @GET("ideen/board")
    suspend fun board(@Query("book_id") bookId: Long): IdeenBoardDto

    /**
     * Anlegen. [idempotencyKey] bleibt über alle Wiederholungen derselben Idee gleich —
     * der Server liefert dann die erste Antwort erneut statt doppelt anzulegen.
     */
    @POST("ideen")
    suspend fun create(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body body: CreateIdeeRequest,
    ): Response<IdeeDto>

    /**
     * Felder einzeln optional: `content`, `status`, Anker (`page_id` ODER `chapter_id`;
     * beide explizit `null` = zurück zur Buch-Idee). Als [JsonObject], weil [ApiJson]
     * `null` sonst nicht schreibt (`explicitNulls = false`).
     */
    @PATCH("ideen/{id}")
    suspend fun update(@Path("id") id: Long, @Body body: JsonObject): Response<IdeeDto>

    /** 200 `{ ok: true }` · 404 `IDEE_NOT_FOUND` (beim Nachspielen = erledigt). */
    @DELETE("ideen/{id}")
    suspend fun delete(@Path("id") id: Long): Response<IdeeDeleteResponse>
}

/**
 * Rechtschreib-/Grammatikprüfung (`routes/languagetool.js`, `routes/dictionary.js`).
 * Beide liegen hinter dem Auth-Guard — das Device-Token genügt.
 *
 * Die Prüf-Antwort wird bewusst NICHT typisiert: die `matches[]` sind rohe
 * LanguageTool-Objekte (Regel-Metadaten, Kontext, Ersetzungen), die der
 * Spellcheck-Controller im WebView unverändert konsumiert. Ein DTO hier wäre
 * reine Drift-Quelle — der Body geht als JSON-String durch die Bridge.
 */
interface LanguageToolApi {
    /** 200 → `{ matches: [...] }` · 404 `languagetool_disabled` · 408/413/502. */
    @POST("languagetool/check")
    suspend fun check(@Body body: LtCheckRequest): Response<ResponseBody>

    /** Wort ins persönliche Wörterbuch (`bookId=0` = alle Bücher). 200 → `{ ok: true }`. */
    @POST("dictionary")
    suspend fun addWord(@Body body: DictionaryAddRequest): Response<Unit>
}

/**
 * `language` ist nur Fallback: ist `bookId` gesetzt, gewinnt serverseitig die
 * Buch-Locale. `pageId` aktiviert den Server-Cache (`page_languagetool_cache`).
 */
@kotlinx.serialization.Serializable
data class LtCheckRequest(
    val text: String,
    val language: String? = null,
    val bookId: Long? = null,
    val pageId: Long? = null,
)

@kotlinx.serialization.Serializable
data class DictionaryAddRequest(
    val word: String,
    val bookId: Long = 0,
    val lang: String = "*",
)

interface SttApi {
    /**
     * Rohe Audio-Bytes (kein Multipart). Content-Type trägt den Audio-Mime
     * (z.B. `audio/mp4`); [audio] muss mit derselben MediaType gebaut sein.
     * [pageId] ist optional (Ideen-Diktat hat keinen Abschnitt); null = nicht gesendet.
     */
    @POST("stt/transcribe")
    suspend fun transcribe(
        @Query("bookId") bookId: Long,
        @Query("pageId") pageId: Long?,
        @Body audio: RequestBody,
    ): Response<TranscribeResponse>
}
