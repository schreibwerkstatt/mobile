package ch.schreibwerkstatt.mobile.data.repo

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.schreibwerkstatt.mobile.data.db.AppDatabase
import ch.schreibwerkstatt.mobile.data.db.ResearchOutboxEntity
import ch.schreibwerkstatt.mobile.data.net.NetworkClient
import ch.schreibwerkstatt.mobile.data.net.ResearchApi
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import ch.schreibwerkstatt.mobile.data.prefs.TokenStore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Recherche-Erfassen über die Outbox gegen einen MockWebServer, der `routes/research.js`
 * nachbildet: anlegen, Bild hochladen, Text nachladen; `409 DUPLICATE_URL` wird zur
 * Rückfrage, offline bleibt die Erfassung liegen, eine Abweisung lässt das Formular offen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ResearchOutboxTest {

    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var fake: FakeResearchServer
    private lateinit var outbox: ResearchOutbox
    private lateinit var repo: ResearchRepository
    private var scheduled = 0

    private val bookId = 7L

    @Before fun setUp() = runTest {
        ctx = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        fake = FakeResearchServer()
        server = MockWebServer().apply { dispatcher = fake; start() }
        val settings = SettingsStore(ctx).apply { setServerBaseUrl("http://test") }
        val net = ResearchNetworkClient(ctx, server)
        outbox = ResearchOutbox(db, net, settings, scheduleBackground = { scheduled++ })
        repo = ResearchRepository(net, settings, db, outbox, ctx)
    }

    @After fun tearDown() {
        db.close()
        server.shutdown()
    }

    private fun draft(url: String = "", title: String = "", note: String = "", image: Uri? = null) =
        ResearchDraft(bookId = bookId, url = url, title = title, note = note, imageUri = image, imageMime = "image/png")

    @Test fun `link without note is created as link and its text is fetched`() = runTest {
        val r = repo.capture(draft(url = "https://example.org/a"))

        assertEquals(CaptureOutcome.Saved, r)
        val create = fake.creates.single()
        assertEquals("link", create["kind"]!!.jsonPrimitive.content)
        assertEquals("https://example.org/a", create["urls"]!!.jsonArray.single().jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals(listOf("/research/1/scrape"), fake.scrapes)
        assertTrue(db.researchOutboxDao().pending().isEmpty())
        assertEquals(1, scheduled)
    }

    @Test fun `note without link is a note and is not scraped`() = runTest {
        assertEquals(CaptureOutcome.Saved, repo.capture(draft(title = "Fakt", note = "Leuchttürme sind rot.")))

        assertEquals("note", fake.creates.single()["kind"]!!.jsonPrimitive.content)
        assertTrue(fake.scrapes.isEmpty())
    }

    @Test fun `link with own note is not scraped`() = runTest {
        repo.capture(draft(url = "https://example.org/a", note = "Eigene Notiz"))
        assertTrue(fake.scrapes.isEmpty())
    }

    @Test fun `shared image is uploaded as raw bytes and the local copy removed`() = runTest {
        val src = File(ctx.cacheDir, "shared.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        assertEquals(CaptureOutcome.Saved, repo.capture(draft(title = "Bild", image = Uri.fromFile(src))))

        val upload = fake.uploads.single()
        assertEquals("/research/1/image", upload.first)
        assertTrue(upload.second.startsWith("image/png"))
        assertEquals(4, upload.third)
        assertTrue("lokale Kopie gelöscht", File(ctx.filesDir, "outbox-media").listFiles().orEmpty().isEmpty())
    }

    @Test fun `duplicate url asks and save anyway sends allow_duplicate`() = runTest {
        fake.duplicateTitle = "Schon da"

        val r = repo.capture(draft(url = "https://example.org/a"))

        assertTrue("erwartet Duplicate, war $r", r is CaptureOutcome.Duplicate)
        assertEquals("Schon da", (r as CaptureOutcome.Duplicate).existingTitle)
        assertFalse("allow_duplicate" in fake.creates.single())
        assertTrue("Dublette blockiert nicht die Queue", db.researchOutboxDao().pending().isEmpty())

        val kept = repo.resolveDuplicate(r.localId, keep = true)

        assertEquals(CaptureOutcome.Saved, kept)
        assertTrue(fake.creates.last()["allow_duplicate"]!!.jsonPrimitive.boolean)
        assertNull(db.researchOutboxDao().byId(r.localId))
    }

    @Test fun `duplicate cancelled is discarded without a second request`() = runTest {
        fake.duplicateTitle = "Schon da"
        val r = repo.capture(draft(url = "https://example.org/a")) as CaptureOutcome.Duplicate

        assertNull(repo.resolveDuplicate(r.localId, keep = false))

        assertEquals(1, fake.creates.size)
        assertNull(db.researchOutboxDao().byId(r.localId))
    }

    @Test fun `offline capture is queued and sent by the next flush`() = runTest {
        fake.failNext = true

        assertEquals(CaptureOutcome.Queued, repo.capture(draft(title = "Funkloch")))
        assertEquals(1, db.researchOutboxDao().pending().size)

        assertEquals(ResearchOutbox.FlushStop.DONE, outbox.flush())
        assertTrue(db.researchOutboxDao().pending().isEmpty())
        assertEquals("Funkloch", fake.creates.last()["title"]!!.jsonPrimitive.content)
    }

    @Test fun `server error keeps the capture queued`() = runTest {
        fake.statusNext = 503
        assertEquals(CaptureOutcome.Queued, repo.capture(draft(title = "Später")))
        assertEquals(1, db.researchOutboxDao().pending().size)
    }

    @Test fun `rejection is reported and nothing stays queued`() = runTest {
        fake.statusNext = 403
        fake.errorCodeNext = "INSUFFICIENT_ROLE"

        assertEquals(CaptureOutcome.Rejected("INSUFFICIENT_ROLE"), repo.capture(draft(title = "Nur lesen")))
        assertNull(db.researchOutboxDao().byId(1))
    }

    @Test fun `failed image upload keeps the created item and the row for a retry`() = runTest {
        val src = File(ctx.cacheDir, "bad.png").apply { writeBytes(byteArrayOf(9)) }
        fake.rejectImage = true

        assertEquals(CaptureOutcome.Saved, repo.capture(draft(title = "Bild", image = Uri.fromFile(src))))

        val row = db.researchOutboxDao().byId(1)!!
        assertEquals(ResearchOutboxEntity.STATUS_FAILED, row.status)
        assertEquals("IMAGE_INVALID", row.note)
        assertEquals("angelegt, nur das Bild fehlt", ResearchOutboxEntity.STEP_IMAGE, row.step)
        assertEquals(1, fake.creates.size)
    }

    @Test fun `list asks the server for its maximum`() = runTest {
        repo.list(bookId).getOrThrow()
        assertTrue(fake.lists.single().contains("limit=${ResearchApi.LIST_LIMIT}"))
    }
}

/** Minimaler `routes/research.js`-Nachbau. `failNext`/`statusNext` gelten für den nächsten Request. */
private class FakeResearchServer : Dispatcher() {
    val creates = mutableListOf<JsonObject>()
    val scrapes = mutableListOf<String>()
    val lists = mutableListOf<String>()
    /** (Pfad, Content-Type, Bytes) */
    val uploads = mutableListOf<Triple<String, String, Int>>()
    var failNext = false
    var statusNext: Int? = null
    var errorCodeNext: String? = null
    var duplicateTitle: String? = null
    var rejectImage = false
    private var nextId = 1L

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        if (failNext) {
            failNext = false
            return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        }
        statusNext?.let { code ->
            statusNext = null
            val err = errorCodeNext ?: "UPSTREAM"
            errorCodeNext = null
            return MockResponse().setResponseCode(code).setBody("""{"error_code":"$err"}""")
        }
        return when {
            request.method == "GET" && path.startsWith("/research?") -> {
                lists += path
                ok("[]")
            }
            request.method == "POST" && path == "/research" -> {
                val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
                creates += body
                val forced = body["allow_duplicate"]?.jsonPrimitive?.boolean == true
                val dup = duplicateTitle
                if (dup != null && !forced && "urls" in body) {
                    MockResponse().setResponseCode(409).setBody(
                        """{"error_code":"DUPLICATE_URL","existing_id":42,"params":{"existing_id":42,"title":"$dup"}}"""
                    )
                } else {
                    ok("""{"id":${nextId++},"book_id":7,"kind":"link","urls":[]}""")
                }
            }
            request.method == "POST" && path.endsWith("/image") -> {
                uploads += Triple(path, request.getHeader("Content-Type").orEmpty(), request.bodySize.toInt())
                if (rejectImage) MockResponse().setResponseCode(400).setBody("""{"error_code":"IMAGE_INVALID"}""")
                else ok("""{"id":1,"kind":"image","has_image":true}""")
            }
            request.method == "POST" && path.endsWith("/scrape") -> {
                scrapes += path
                ok("""{"item":{"id":1},"filled":["title"],"skipped":[]}""")
            }
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun ok(json: String) = MockResponse().setResponseCode(200).setBody(json)
}

private class ResearchNetworkClient(context: Context, server: MockWebServer) :
    NetworkClient(TokenStore(context), debug = false) {
    private val api: ResearchApi by lazy {
        Retrofit.Builder()
            .baseUrl(server.url("/").newBuilder().host("127.0.0.1").build())
            .client(OkHttpClient.Builder().retryOnConnectionFailure(false).readTimeout(5, TimeUnit.SECONDS).build())
            .addConverterFactory(jsonParser.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ResearchApi::class.java)
    }

    override fun research(baseUrl: String): ResearchApi = api
}
