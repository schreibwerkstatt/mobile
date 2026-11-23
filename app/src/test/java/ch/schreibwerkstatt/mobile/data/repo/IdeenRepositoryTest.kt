package ch.schreibwerkstatt.mobile.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.schreibwerkstatt.mobile.data.db.AppDatabase
import ch.schreibwerkstatt.mobile.data.db.PendingIdeaEntity
import ch.schreibwerkstatt.mobile.data.net.IdeenApi
import ch.schreibwerkstatt.mobile.data.net.NetworkClient
import ch.schreibwerkstatt.mobile.data.net.dto.IdeeDto
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import ch.schreibwerkstatt.mobile.data.prefs.TokenStore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Offline-Queue für neue Ideen gegen einen MockWebServer, der `POST /ideen` samt
 * `Idempotency-Key`-Replay nachbildet (`routes/ideen.js`, `lib/idempotency.js`):
 * eine spontane Idee geht nie verloren und entsteht nie doppelt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class IdeenRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var fake: FakeIdeenServer
    private lateinit var repo: IdeenRepository
    private var queuedCalls = 0

    private val bookId = 7L

    @Before fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        fake = FakeIdeenServer()
        server = MockWebServer().apply { dispatcher = fake; start() }
        val settings = SettingsStore(ctx).apply { setServerBaseUrl("http://test") }
        repo = IdeenRepository(db, IdeenNetworkClient(ctx, server), settings) { queuedCalls++ }
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private suspend fun queue(status: String = PendingIdeaEntity.STATUS_PENDING) =
        db.pendingIdeaDao().byStatus(status)

    // ── Anlegen ─────────────────────────────────────────────────────────────

    @Test fun `online create saves and leaves no queue entry`() = runTest {
        val r = repo.create(bookId, "  Szene im Regen  ")

        assertTrue("erwartet Saved, war $r", r is CreateIdeaResult.Saved)
        assertEquals("Szene im Regen", (r as CreateIdeaResult.Saved).idee.content)
        val post = fake.posts.single()
        assertTrue("Idempotency-Key fehlt/ungültig: ${post.key}", post.key.matches(Regex("^[A-Za-z0-9_-]{8,100}$")))
        assertEquals(bookId, post.body["book_id"]!!.jsonPrimitive.long)
        assertFalse("Buch-Idee schickt keinen Anker", "page_id" in post.body || "chapter_id" in post.body)
        assertTrue(queue().isEmpty())
        assertEquals(0, queuedCalls)
    }

    @Test fun `offline create is queued and later flushed with the same key`() = runTest {
        fake.failNext = SocketPolicy.DISCONNECT_AT_START
        val r = repo.create(bookId, "Einfall im Funkloch")

        assertEquals(CreateIdeaResult.Queued, r)
        assertEquals(1, queuedCalls)
        val pending = queue().single()
        assertEquals("Einfall im Funkloch", pending.content)

        val out = repo.flushPending()

        assertEquals(FlushOutcome.Done(1), out)
        assertTrue(queue().isEmpty())
        assertEquals(1, fake.created.size)
        assertEquals(pending.idempotencyKey, fake.posts.last().key)
    }

    @Test fun `lost response is replayed by key and never creates twice`() = runTest {
        fake.failNext = SocketPolicy.DISCONNECT_AFTER_REQUEST
        assertEquals(CreateIdeaResult.Queued, repo.create(bookId, "Doppelt?"))
        assertEquals("Server hat angelegt, nur die Antwort fehlt", 1, fake.created.size)

        assertEquals(FlushOutcome.Done(1), repo.flushPending())

        assertEquals(1, fake.created.size)
        assertEquals(2, fake.posts.size)
        assertEquals(fake.posts[0].key, fake.posts[1].key)
        assertTrue(queue().isEmpty())
    }

    @Test fun `server errors keep the idea queued for a later run`() = runTest {
        fake.statusNext = 503
        assertEquals(CreateIdeaResult.Queued, repo.create(bookId, "Später"))
        assertEquals(1, queue().size)

        fake.statusNext = 503
        assertEquals(FlushOutcome.RetryLater(0), repo.flushPending())
        assertEquals(1, queue().size)
    }

    @Test fun `401 stops the flush and keeps the idea`() = runTest {
        fake.statusNext = 401
        assertEquals(CreateIdeaResult.Queued, repo.create(bookId, "Nach dem Koppeln"))

        fake.statusNext = 401
        assertEquals(FlushOutcome.Unauthorized, repo.flushPending())
        assertEquals(1, queue().size)
    }

    @Test fun `rejected anchor is retried once as a book idea`() = runTest {
        fake.rejectAnchors = true
        val r = repo.create(bookId, "Zum gelöschten Abschnitt", pageId = 99)

        assertTrue("erwartet Saved, war $r", r is CreateIdeaResult.Saved)
        assertEquals(2, fake.posts.size)
        assertEquals(99L, fake.posts[0].body["page_id"]!!.jsonPrimitive.long)
        assertFalse("page_id" in fake.posts[1].body)
        assertNull((r as CreateIdeaResult.Saved).idee.page_id)
    }

    @Test fun `rejected book idea stays stuck with its text and is skipped by flush`() = runTest {
        fake.statusNext = 403
        val r = repo.create(bookId, "Fremdes Buch")

        assertEquals(CreateIdeaResult.Stuck("NO_BOOK_ACCESS"), r)
        val stuck = queue(PendingIdeaEntity.STATUS_STUCK).single()
        assertEquals("Fremdes Buch", stuck.content)
        assertEquals("NO_BOOK_ACCESS", stuck.error)

        val before = fake.posts.size
        assertEquals(FlushOutcome.Done(0), repo.flushPending())
        assertEquals("stuck wird nicht still nachgesendet", before, fake.posts.size)

        // Der Nutzer sendet erneut — diesmal nimmt der Server an.
        val retry = repo.retryStuck(stuck.localId)
        assertTrue(retry is CreateIdeaResult.Saved)
        assertTrue(queue(PendingIdeaEntity.STATUS_STUCK).isEmpty())
    }

    @Test fun `empty or too long content is rejected locally and never queued`() = runTest {
        assertEquals(CreateIdeaResult.Invalid(IdeenRepository.CODE_CONTENT_REQ), repo.create(bookId, "   "))
        assertEquals(
            CreateIdeaResult.Invalid(IdeenRepository.CODE_CONTENT_TOO_LONG),
            repo.create(bookId, "x".repeat(IdeeDto.MAX_CONTENT + 1)),
        )
        assertTrue(queue().isEmpty())
        assertTrue(fake.posts.isEmpty())
    }

    // ── Bearbeiten, Stufe, Ort, Löschen (offline-fähig) ──────────────────────

    /** Board einmal laden, damit der Cache (und damit das Overlay) existiert. */
    private suspend fun loadBoard() = repo.refresh(bookId).getOrThrow()

    private suspend fun items() = repo.observeBoard(bookId).first().items

    @Test fun `moving back to the book sends both anchors as explicit null`() = runTest {
        val saved = repo.create(bookId, "Wandert", pageId = 5) as CreateIdeaResult.Saved

        assertEquals(ChangeResult.Saved, repo.update(bookId, saved.idee.id, content = null, status = null, place = Anchor(null, null)))

        val body = fake.patches.single()
        assertEquals(JsonNull, body["page_id"])
        assertEquals(JsonNull, body["chapter_id"])
        assertFalse("content" in body || "status" in body)
    }

    @Test fun `update sends only changed fields in one patch and updates the cache`() = runTest {
        val saved = repo.create(bookId, "Alt") as CreateIdeaResult.Saved
        loadBoard()

        val r = repo.update(bookId, saved.idee.id, content = "Neu", status = IdeeDto.STATUS_IN_ARBEIT, place = Anchor(null, 3))

        assertEquals(ChangeResult.Saved, r)
        assertEquals(setOf("content", "status", "chapter_id"), fake.patches.single().keys)
        val view = items().single()
        assertEquals("Neu", view.idee.content)
        assertEquals(IdeeDto.STATUS_IN_ARBEIT, view.idee.status)
        assertFalse("bestätigt → nicht mehr vorgemerkt", view.pending)
        assertTrue(db.pendingIdeaChangeDao().pending().isEmpty())
    }

    @Test fun `patch rejection stays stuck at the idea with its error code`() = runTest {
        val saved = repo.create(bookId, "Erledigt") as CreateIdeaResult.Saved
        loadBoard()
        fake.statusNext = 400
        fake.errorCodeNext = "IDEE_CLOSED"

        val r = repo.update(bookId, saved.idee.id, content = null, status = null, place = Anchor(1, null), placeName = "Kap. 1")

        assertEquals(ChangeResult.Stuck("IDEE_CLOSED"), r)
        val view = items().single()
        assertEquals("IDEE_CLOSED", view.error)
        assertEquals(1L, view.idee.page_id)

        // Verwerfen → wieder der Server-Stand.
        repo.discardChange(saved.idee.id)
        assertNull(items().single().idee.page_id)
    }

    @Test fun `offline changes are merged into one patch and applied in the list at once`() = runTest {
        val saved = repo.create(bookId, "Alt") as CreateIdeaResult.Saved
        loadBoard()
        val id = saved.idee.id

        fake.failNext = SocketPolicy.DISCONNECT_AT_START
        assertEquals(ChangeResult.Queued, repo.setStatus(bookId, id, IdeeDto.STATUS_ERLEDIGT))
        fake.failNext = SocketPolicy.DISCONNECT_AT_START
        assertEquals(ChangeResult.Queued, repo.update(bookId, id, content = "Neu", status = null))
        val attempts = fake.patches.size

        val view = items().single()
        assertTrue(view.pending)
        assertEquals("Neu", view.idee.content)
        assertEquals(IdeeDto.STATUS_ERLEDIGT, view.idee.status)
        assertEquals(1, db.pendingIdeaChangeDao().pending().size)

        assertEquals(FlushOutcome.Done(1), repo.flushPending())

        assertEquals("ein PATCH für beide Änderungen", attempts + 1, fake.patches.size)
        assertEquals(setOf("status", "content"), fake.patches.last().keys)
        assertFalse(items().single().pending)
    }

    @Test fun `offline delete hides the idea and a replayed 404 counts as done`() = runTest {
        val saved = repo.create(bookId, "Weg damit") as CreateIdeaResult.Saved
        loadBoard()

        fake.failNext = SocketPolicy.DISCONNECT_AT_START
        assertEquals(ChangeResult.Queued, repo.delete(bookId, saved.idee.id))
        assertTrue("vorgemerktes Löschen blendet aus", items().isEmpty())

        fake.statusNext = 404
        fake.errorCodeNext = "IDEE_NOT_FOUND"
        assertEquals(FlushOutcome.Done(1), repo.flushPending())
        assertTrue(db.pendingIdeaChangeDao().pending().isEmpty())
        assertTrue(items().isEmpty())
    }

    @Test fun `created idea lands in the cached board without a reload`() = runTest {
        loadBoard()
        fake.failNext = SocketPolicy.DISCONNECT_AT_START
        assertEquals(CreateIdeaResult.Queued, repo.create(bookId, "Später da"))
        assertTrue(items().isEmpty())

        repo.flushPending()

        assertEquals("Später da", items().single().idee.content)
    }

    @Test fun `overlay applies a move with the picked place name`() {
        val idee = IdeeDto(id = 1, book_id = bookId, chapter_id = 4, chapter_name = "Alt", content = "x")
        val moved = IdeenRepository.applyPatch(
            idee,
            JsonObject(mapOf("page_id" to kotlinx.serialization.json.JsonPrimitive(9))),
            placeName = "Neuer Abschnitt",
        )
        assertEquals(9L, moved.page_id)
        assertEquals("Neuer Abschnitt", moved.page_name)
        assertNull(moved.chapter_id)
        assertNull(moved.chapter_name)
    }
}

/**
 * Minimaler `routes/ideen.js`-Nachbau: POST mit Idempotency-Replay, PATCH, Fehler auf
 * Zuruf. `failNext`/`statusNext` gelten für genau den nächsten Request.
 */
private class FakeIdeenServer : Dispatcher() {
    data class Post(val key: String, val body: JsonObject)

    val posts = mutableListOf<Post>()
    val patches = mutableListOf<JsonObject>()
    val created = mutableMapOf<Long, JsonObject>()
    private val byKey = mutableMapOf<String, Long>()
    private var nextId = 100L

    var failNext: SocketPolicy? = null
    var statusNext: Int? = null
    var errorCodeNext: String? = null
    var rejectAnchors = false

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        val body = request.body.readUtf8().takeIf { it.isNotBlank() }
            ?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        if (request.method == "POST" && path == "/ideen") {
            posts += Post(request.getHeader("Idempotency-Key").orEmpty(), body)
        }
        if (request.method == "PATCH") patches += body

        statusNext?.let { code ->
            statusNext = null
            val err = errorCodeNext ?: when (code) {
                401 -> "NOT_LOGGED_IN"; 403 -> "NO_BOOK_ACCESS"; else -> "UPSTREAM"
            }
            errorCodeNext = null
            return MockResponse().setResponseCode(code).setBody("""{"error_code":"$err"}""")
        }
        if (failNext == SocketPolicy.DISCONNECT_AT_START) {
            failNext = null
            return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        }

        return when {
            request.method == "POST" && path == "/ideen" -> {
                if (rejectAnchors && ("page_id" in body || "chapter_id" in body)) {
                    return MockResponse().setResponseCode(400).setBody("""{"error_code":"BOOK_MISMATCH"}""")
                }
                val key = request.getHeader("Idempotency-Key").orEmpty()
                val id = byKey[key] ?: (nextId++).also { newId ->
                    byKey[key] = newId
                    created[newId] = body
                }
                val resp = ok(row(id, created.getValue(id)))
                if (failNext == SocketPolicy.DISCONNECT_AFTER_REQUEST) {
                    failNext = null
                    resp.setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                } else resp
            }
            request.method == "GET" && path.startsWith("/ideen/board") -> ok(
                """{"statuses":["offen","in_arbeit","erledigt","verworfen"],"stages":["offen","in_arbeit","erledigt","verworfen"],"ideen":[""" +
                    created.keys.sortedDescending().joinToString(",") { row(it, created.getValue(it)) } + """]}"""
            )
            request.method == "DELETE" && path.startsWith("/ideen/") -> {
                val id = path.removePrefix("/ideen/").toLong()
                if (created.remove(id) == null) MockResponse().setResponseCode(404).setBody("""{"error_code":"IDEE_NOT_FOUND"}""")
                else ok("""{"ok":true}""")
            }
            request.method == "PATCH" && path.startsWith("/ideen/") -> {
                val id = path.removePrefix("/ideen/").toLong()
                val merged = JsonObject(created.getValue(id) + body)
                created[id] = merged
                ok(row(id, merged))
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun ok(json: String) = MockResponse().setResponseCode(200).setBody(json)

    private fun row(id: Long, b: JsonObject): String {
        fun v(k: String) = b[k]?.takeIf { it != JsonNull }?.toString() ?: "null"
        return """{"id":$id,"book_id":${v("book_id")},"page_id":${v("page_id")},"chapter_id":${v("chapter_id")},""" +
            """"content":${v("content")},"status":${b["status"]?.toString() ?: "\"offen\""},"links":[]}"""
    }
}

private class IdeenNetworkClient(context: Context, server: MockWebServer) :
    NetworkClient(TokenStore(context), debug = false) {
    private val api: IdeenApi by lazy {
        Retrofit.Builder()
            .baseUrl(server.url("/").newBuilder().host("127.0.0.1").build())
            .client(
                OkHttpClient.Builder()
                    .retryOnConnectionFailure(false)
                    .readTimeout(5, TimeUnit.SECONDS)
                    .build()
            )
            .addConverterFactory(jsonParser.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(IdeenApi::class.java)
    }

    override fun ideen(baseUrl: String): IdeenApi = api
}
