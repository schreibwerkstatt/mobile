package ch.schreibwerkstatt.mobile.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.schreibwerkstatt.mobile.data.db.AppDatabase
import ch.schreibwerkstatt.mobile.data.db.PageEntity
import ch.schreibwerkstatt.mobile.data.db.PendingWriteEntity
import ch.schreibwerkstatt.mobile.data.net.ContentApi
import ch.schreibwerkstatt.mobile.data.net.NetworkClient
import ch.schreibwerkstatt.mobile.data.net.dto.PageDto
import ch.schreibwerkstatt.mobile.data.net.dto.SavePageRequest
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import ch.schreibwerkstatt.mobile.data.prefs.TokenStore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Save-Kennungen (`save_id`/`own_save_ids`) gegen einen MockWebServer, der den
 * Server-Vertrag nachbildet: Ein Speichern, dessen Antwort unterwegs verloren ging,
 * darf beim nächsten Flush nicht als Konflikt gegen sich selbst enden — ein
 * zwischenzeitlicher Fremd-Write aber weiterhin schon.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ContentRepositorySaveIdTest {

    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var fake: ContractServer
    private lateinit var repo: ContentRepository

    private val pageId = 10L
    private val bookId = 1L

    @Before fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        fake = ContractServer(pageId)
        server = MockWebServer().apply { dispatcher = fake; start() }
        val settings = SettingsStore(ctx).apply { setServerBaseUrl("http://test") }
        repo = ContentRepository(db, MockServerNetworkClient(ctx, server), settings)
        db.pageDao().upsert(
            PageEntity(id = pageId, bookId = bookId, name = "S", html = "<p>old</p>", updatedAt = "t0")
        )
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private suspend fun statuses(): List<String> =
        listOf(PendingWriteEntity.STATUS_PENDING, PendingWriteEntity.STATUS_CONFLICT,
            PendingWriteEntity.STATUS_LOCKED, PendingWriteEntity.STATUS_FAILED)
            .flatMap { s -> db.pendingWriteDao().byStatus(s).map { it.status } }

    @Test fun `lost response then a newer save is no conflict (own_save_ids)`() = runTest {
        fake.dropNextPutResponse = true
        assertEquals(SaveResult.Queued, repo.savePage(pageId, bookId, "<p>A</p>"))
        val first = fake.puts.single()
        assertEquals("<p>A</p>", fake.html) // Server hat geschrieben, nur die Antwort fehlt

        val res = repo.savePage(pageId, bookId, "<p>B</p>")

        assertTrue("erwartet Saved, war $res", res is SaveResult.Saved)
        val second = fake.puts.last()
        assertEquals(listOf(first.save_id), second.own_save_ids)
        assertTrue(second.save_id != first.save_id)
        assertEquals("own", fake.lastMatch)
        assertEquals("<p>B</p>", fake.html)
        assertTrue(statuses().isEmpty()) // insbesondere kein STATUS_CONFLICT
        db.pageDao().byId(pageId)!!.let {
            assertFalse(it.dirty)
            assertEquals(fake.stamp(), it.updatedAt)
        }
    }

    @Test fun `retry of the same write carries the same save_id (replay)`() = runTest {
        fake.dropNextPutResponse = true
        repo.savePage(pageId, bookId, "<p>A</p>")
        val pending = db.pendingWriteDao().latestForPage(pageId)!!
        assertNotNull(pending.sentAt) // vor dem Request als gesendet markiert
        val versionAfterFirst = fake.version

        assertEquals(1, repo.flushPending().getOrThrow())

        assertEquals(2, fake.puts.size)
        assertEquals(fake.puts[0].save_id, fake.puts[1].save_id)
        assertEquals(pending.saveId, fake.puts[1].save_id)
        assertEquals("replay", fake.lastMatch)
        assertEquals(versionAfterFirst, fake.version) // nichts doppelt geschrieben
        assertTrue(statuses().isEmpty())
        assertFalse(db.pageDao().byId(pageId)!!.dirty)
    }

    @Test fun `foreign write without save_id in between stays a conflict`() = runTest {
        fake.dropNextPutResponse = true
        repo.savePage(pageId, bookId, "<p>A</p>")
        fake.foreignWrite(saveId = null) // Web/Job ohne Kennung → last_save_id = null

        val res = repo.savePage(pageId, bookId, "<p>B</p>")

        assertTrue("erwartet Conflict, war $res", res is SaveResult.Conflict)
        assertEquals(listOf(PendingWriteEntity.STATUS_CONFLICT), statuses())
        assertEquals("<p>foreign</p>", fake.html) // fremder Stand nicht überschrieben
        assertTrue(db.pageDao().byId(pageId)!!.dirty)
    }

    @Test fun `foreign write with its own save_id in between stays a conflict`() = runTest {
        fake.dropNextPutResponse = true
        repo.savePage(pageId, bookId, "<p>A</p>")
        fake.foreignWrite(saveId = "6f1c2b9e-0000-4000-8000-0000000000aa") // anderes Gerät

        val res = repo.savePage(pageId, bookId, "<p>B</p>")

        assertTrue(res is SaveResult.Conflict)
        assertEquals("<p>foreign</p>", fake.html)
        assertFalse(fake.puts.last().own_save_ids.orEmpty().contains(fake.lastSaveId))
    }

    @Test fun `409 safety net resends when last_save_id is one of our own`() = runTest {
        // Server, der own_save_ids nicht auswertet, last_save_id aber liefert: Der 409
        // muss per GET als eigener Stand erkannt und mit dem Server-Stempel neu gesendet werden.
        fake.honorOwnSaveIds = false
        fake.dropNextPutResponse = true
        repo.savePage(pageId, bookId, "<p>A</p>")
        val stampAfterA = fake.stamp()

        val res = repo.savePage(pageId, bookId, "<p>B</p>")

        assertTrue("erwartet Saved, war $res", res is SaveResult.Saved)
        assertEquals(stampAfterA, fake.puts.last().expected_updated_at)
        assertEquals("<p>B</p>", fake.html)
        assertTrue(statuses().isEmpty())
    }

    @Test fun `own_save_ids never carry ids from another section`() = runTest {
        val other = 20L
        db.pageDao().upsert(PageEntity(id = other, bookId = bookId, name = "S2", html = "<p>o</p>", updatedAt = "t0"))
        fake.dropNextPutResponse = true
        repo.savePage(pageId, bookId, "<p>A</p>")

        repo.savePage(other, bookId, "<p>X</p>")

        assertTrue(fake.puts.last().own_save_ids.orEmpty().isEmpty())
    }

    @Test fun `4xx rejection drops the save_id from what a successor inherits`() = runTest {
        fake.lockNextPut = true
        assertTrue(repo.savePage(pageId, bookId, "<p>A</p>") is SaveResult.Locked)
        val locked = db.pendingWriteDao().latestForPage(pageId)!!
        assertEquals(null, locked.sentAt)
        assertTrue(locked.saveIdsForSuccessor().isEmpty())
    }

    @Test fun `own_save_ids are capped at the newest 16`() = runTest {
        repeat(20) {
            fake.dropNextPutResponse = true
            repo.savePage(pageId, bookId, "<p>v$it</p>")
        }
        val w = db.pendingWriteDao().latestForPage(pageId)!!
        assertEquals(PendingWriteEntity.MAX_OWN_SAVE_IDS, w.ownSaveIds.size)
        // Älteste zuerst, die neuesten 16 der 19 Vorgänger.
        assertEquals(fake.puts.dropLast(1).takeLast(16).map { it.save_id }, w.ownSaveIds)

        assertTrue(repo.savePage(pageId, bookId, "<p>final</p>") is SaveResult.Saved)
        assertEquals("own", fake.lastMatch)
    }
}

/**
 * Nachbau des Server-Vertrags für `PUT`/`GET content/pages/{id}` (Save-Kennung):
 * `save_id` = `last_save_id` → 200 replay; Stempel passt → 200; Stempel veraltet, aber
 * `last_save_id` ∈ `own_save_ids` → 200 own; sonst 409.
 */
private class ContractServer(private val pageId: Long) : Dispatcher() {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Volatile var html = "<p>old</p>"
    @Volatile var version = 0
    @Volatile var lastSaveId: String? = null
    @Volatile var lastMatch: String? = null
    @Volatile var dropNextPutResponse = false
    @Volatile var lockNextPut = false
    @Volatile var honorOwnSaveIds = true
    val puts: MutableList<SavePageRequest> = java.util.Collections.synchronizedList(mutableListOf())

    fun stamp() = "t$version"

    fun foreignWrite(saveId: String?) = synchronized(this) {
        html = "<p>foreign</p>"; version++; lastSaveId = saveId
    }

    override fun dispatch(request: RecordedRequest): MockResponse = synchronized(this) {
        if (request.path != "/content/pages/$pageId" && request.path?.startsWith("/content/pages/") == true) {
            // Andere Abschnitte: einfach annehmen (eigener Zustand irrelevant).
            if (request.method == "PUT") puts += decode(request)
            return ok(PageDto(id = 0, updated_at = "x1"))
        }
        when (request.method) {
            "GET" -> ok(page())
            "PUT" -> {
                val req = decode(request)
                puts += req
                lastMatch = null
                val resp = when {
                    lockNextPut -> {
                        lockNextPut = false
                        MockResponse().setResponseCode(423)
                            .setBody("""{"error_code":"PAGE_LOCKED","locked_by_email":"x@y.ch"}""")
                    }
                    req.save_id != null && req.save_id == lastSaveId -> {
                        lastMatch = "replay"; ok(page("replay"))
                    }
                    req.expected_updated_at == null || req.expected_updated_at == stamp() -> {
                        write(req); ok(page())
                    }
                    honorOwnSaveIds && lastSaveId != null && lastSaveId in req.own_save_ids.orEmpty() -> {
                        write(req); lastMatch = "own"; ok(page("own"))
                    }
                    else -> MockResponse().setResponseCode(409).setBody(
                        """{"error_code":"PAGE_CONFLICT","server_updated_at":"${stamp()}"}"""
                    )
                }
                if (dropNextPutResponse) {
                    // Server hat verarbeitet, die Verbindung reisst vor der Antwort ab.
                    dropNextPutResponse = false
                    MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                } else resp
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun write(req: SavePageRequest) {
        html = req.html; version++; lastSaveId = req.save_id
    }

    private fun page(match: String? = null) = PageDto(
        id = pageId, name = "S", html = html, updated_at = stamp(),
        last_save_id = lastSaveId, save_match = match,
    )

    private fun decode(r: RecordedRequest): SavePageRequest =
        json.decodeFromString(SavePageRequest.serializer(), r.body.readUtf8())

    private fun ok(dto: PageDto) = MockResponse().setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(json.encodeToString(PageDto.serializer(), dto))
}

/**
 * Echter Retrofit-Stack gegen den MockWebServer, ohne [TokenStore]-Auth. Kein
 * stilles OkHttp-Retry, damit die abgerissene Verbindung beim Repository ankommt.
 */
private class MockServerNetworkClient(context: Context, server: MockWebServer) :
    NetworkClient(TokenStore(context), debug = false) {
    private val api: ContentApi by lazy {
        Retrofit.Builder()
            // IP statt "localhost": nach dem Abriss wiche OkHttp sonst auf ::1 aus,
            // wo der MockWebServer nicht lauscht.
            .baseUrl(server.url("/").newBuilder().host("127.0.0.1").build())
            .client(
                OkHttpClient.Builder()
                    .retryOnConnectionFailure(false)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(jsonParser.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ContentApi::class.java)
    }

    override fun content(baseUrl: String): ContentApi = api
}
