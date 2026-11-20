package ch.schreibwerkstatt.mobile.data.repo

import ch.schreibwerkstatt.mobile.data.net.ContentApi
import ch.schreibwerkstatt.mobile.data.net.DevicePingRequest
import ch.schreibwerkstatt.mobile.data.net.NetworkClient
import ch.schreibwerkstatt.mobile.data.net.WritingTimeRequest
import ch.schreibwerkstatt.mobile.data.net.dto.BookDto
import ch.schreibwerkstatt.mobile.data.net.dto.ChapterNodeDto
import ch.schreibwerkstatt.mobile.data.net.dto.CreateChapterRequest
import ch.schreibwerkstatt.mobile.data.net.dto.CreatePageRequest
import ch.schreibwerkstatt.mobile.data.net.dto.PageDto
import ch.schreibwerkstatt.mobile.data.net.dto.RestoreResponse
import ch.schreibwerkstatt.mobile.data.net.dto.RevisionDetailResponse
import ch.schreibwerkstatt.mobile.data.net.dto.RevisionListResponse
import ch.schreibwerkstatt.mobile.data.net.dto.SavePageRequest
import ch.schreibwerkstatt.mobile.data.net.dto.SyncResponse
import ch.schreibwerkstatt.mobile.data.net.dto.TreeDto
import ch.schreibwerkstatt.mobile.data.prefs.TokenStore
import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * Programmierbarer [ContentApi]-Fake für SyncEngine-/Repository-Tests. Ersetzt die
 * Retrofit-gebaute API, sodass Server-Antworten (inkl. 409/423) deterministisch
 * gesteuert werden — ohne Netzwerk und ohne den verschlüsselten TokenStore.
 */
class FakeContentApi : ContentApi {

    /** Sequenz von Sync-Antworten (eine pro Seitenaufruf der Pull-Schleife). */
    val syncQueue: ArrayDeque<SyncResponse> = ArrayDeque()
    var syncCalls: Int = 0; private set
    val sinceArgs: MutableList<String?> = mutableListOf()
    val sinceIdArgs: MutableList<Long?> = mutableListOf()

    /** Antwort auf savePage; per Test gesetzt. */
    var savePageResponder: (SavePageRequest) -> Response<PageDto> = { Response.success(PageDto(id = 0)) }
    var lastSaveRequest: SavePageRequest? = null; private set

    /** Antwort auf page() (z.B. für Konfliktauflösung). */
    var pageResponder: (Long) -> PageDto = { PageDto(id = it) }

    /** Verzögerung vor der page()-Antwort (virtuelle Zeit unter runTest), z.B. „hängendes Netz". */
    var pageDelayMs: Long = 0

    override suspend fun books(): List<BookDto> = emptyList()

    /** Antwort auf tree(); Default wirft (z.B. „offline"). */
    var treeResponder: (Long) -> TreeDto = { throw NotImplementedError() }

    override suspend fun tree(bookId: Long): TreeDto = treeResponder(bookId)

    override suspend fun sync(bookId: Long, since: String?, sinceId: Long?, limit: Int): SyncResponse {
        syncCalls++
        sinceArgs += since
        sinceIdArgs += sinceId
        return syncQueue.removeFirstOrNull() ?: SyncResponse(now = "now", pages = emptyList(), has_more = false)
    }

    override suspend fun page(pageId: Long): PageDto {
        if (pageDelayMs > 0) kotlinx.coroutines.delay(pageDelayMs)
        return pageResponder(pageId)
    }

    override suspend fun savePage(pageId: Long, body: SavePageRequest): Response<PageDto> {
        lastSaveRequest = body
        return savePageResponder(body)
    }

    override suspend fun devicePing(bookId: Long, body: DevicePingRequest): Response<Unit> =
        Response.success(Unit)

    override suspend fun writingTime(body: WritingTimeRequest): Response<Unit> =
        Response.success(Unit)

    override suspend fun createPage(body: CreatePageRequest): Response<PageDto> =
        throw NotImplementedError()

    override suspend fun createChapter(body: CreateChapterRequest): Response<ChapterNodeDto> =
        throw NotImplementedError()

    override suspend fun revisions(pageId: Long, limit: Int): RevisionListResponse =
        throw NotImplementedError()

    override suspend fun revision(pageId: Long, revId: Long): RevisionDetailResponse =
        throw NotImplementedError()

    /** Antwort auf restoreRevision; per Test gesetzt. */
    var restoreResponder: (Long, Long) -> Response<RestoreResponse> = { _, _ -> throw NotImplementedError() }

    override suspend fun restoreRevision(pageId: Long, revId: Long): Response<RestoreResponse> =
        restoreResponder(pageId, revId)

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Baut eine Retrofit-Fehlerantwort mit JSON-Body (für 409/423/500). */
        fun <T> error(code: Int, json: String): Response<T> =
            Response.error(code, json.toResponseBody(JSON))
    }
}

/** NetworkClient, der [content] auf den übergebenen Fake umbiegt. */
class FakeNetworkClient(context: Context, private val api: ContentApi) :
    NetworkClient(TokenStore(context), debug = false) {
    override fun content(baseUrl: String): ContentApi = api
}
