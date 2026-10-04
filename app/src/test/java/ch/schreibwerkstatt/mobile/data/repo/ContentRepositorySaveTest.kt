package ch.schreibwerkstatt.mobile.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.schreibwerkstatt.mobile.data.db.AppDatabase
import ch.schreibwerkstatt.mobile.data.db.PageEntity
import ch.schreibwerkstatt.mobile.data.db.PendingWriteEntity
import ch.schreibwerkstatt.mobile.data.net.dto.PageDto
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import androidx.room.withTransaction
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import retrofit2.Response
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

/**
 * Editor-Save-Pfad des [ContentRepository]: lokal persistieren → Pending-Write
 * queuen → Online-Flush. Prüft die vier [SaveResult]-Ausgänge und die daraus
 * folgenden Queue-Status (pending/conflict/locked/failed) sowie das Dirty-Flag.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ContentRepositorySaveTest {

    private lateinit var db: AppDatabase
    private lateinit var api: FakeContentApi
    private lateinit var repo: ContentRepository

    private val pageId = 10L
    private val bookId = 1L

    @Before fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        api = FakeContentApi()
        val settings = SettingsStore(ctx).apply { setServerBaseUrl("http://test") }
        repo = ContentRepository(db, FakeNetworkClient(ctx, api), settings)
        // Ausgangs-Seite mit bekanntem Server-Stand (Basis für expected_updated_at).
        db.pageDao().upsert(
            PageEntity(id = pageId, bookId = bookId, name = "S", html = "<p>old</p>", updatedAt = "t0", dirty = false)
        )
    }

    @After fun tearDown() = db.close()

    @Test fun `online save returns Saved, clears dirty and drains the queue`() = runTest {
        api.savePageResponder = {
            Response.success(PageDto(id = pageId, name = "S", html = "<p>server</p>", updated_at = "t1"))
        }

        val res = repo.savePage(pageId, bookId, "<p>local</p>")

        assertTrue(res is SaveResult.Saved)
        db.pageDao().byId(pageId)!!.let {
            assertFalse(it.dirty)
            assertEquals("<p>server</p>", it.html)
            assertEquals("t1", it.updatedAt)
        }
        assertTrue(db.pendingWriteDao().byStatus(PendingWriteEntity.STATUS_PENDING).isEmpty())
        // expected_updated_at trug den Stand VOR der lokalen Mutation.
        assertEquals("t0", api.lastSaveRequest?.expected_updated_at)
    }

    @Test fun `409 returns Conflict and parks the write as conflict`() = runTest {
        api.savePageResponder = {
            FakeContentApi.error(
                409,
                """{"error_code":"PAGE_CONFLICT","server_updated_at":"t2","server_editor_name":"Lektor"}""",
            )
        }

        val res = repo.savePage(pageId, bookId, "<p>local</p>")

        assertTrue(res is SaveResult.Conflict)
        assertEquals("Lektor", (res as SaveResult.Conflict).serverEditorName)
        assertTrue(db.pendingWriteDao().byStatus(PendingWriteEntity.STATUS_PENDING).isEmpty())
        db.pendingWriteDao().byStatus(PendingWriteEntity.STATUS_CONFLICT).single().let {
            assertEquals("Lektor", it.note)
        }
        // Lokale Änderung bleibt dirty, bis der Nutzer den Konflikt auflöst.
        assertTrue(db.pageDao().byId(pageId)!!.dirty)
    }

    @Test fun `423 returns Locked and parks the write as locked`() = runTest {
        api.savePageResponder = {
            FakeContentApi.error(
                423,
                """{"error_code":"PAGE_LOCKED","locked_by_email":"lektor@x.ch","expires_at":"t3"}""",
            )
        }

        val res = repo.savePage(pageId, bookId, "<p>local</p>")

        assertTrue(res is SaveResult.Locked)
        assertEquals("lektor@x.ch", (res as SaveResult.Locked).lockedByEmail)
        db.pendingWriteDao().byStatus(PendingWriteEntity.STATUS_LOCKED).single().let {
            assertEquals("lektor@x.ch", it.note)
        }
    }

    @Test fun `network failure returns Queued and keeps a pending write`() = runTest {
        api.savePageResponder = { throw java.io.IOException("offline") }

        val res = repo.savePage(pageId, bookId, "<p>local</p>")

        assertTrue(res is SaveResult.Queued)
        db.pageDao().byId(pageId)!!.let {
            assertTrue(it.dirty)
            assertEquals("<p>local</p>", it.html) // lokal erhalten
        }
        assertEquals(1, db.pendingWriteDao().byStatus(PendingWriteEntity.STATUS_PENDING).size)
    }

    @Test fun `transient server error 500 keeps the write pending for retry`() = runTest {
        api.savePageResponder = { FakeContentApi.error(500, """{"error_code":"INTERNAL"}""") }

        val res = repo.savePage(pageId, bookId, "<p>local</p>")

        assertTrue(res is SaveResult.Queued)
        // 5xx ist transient → pending LASSEN, damit flushPending erneut versucht
        // (sonst friert die dirty-Seite dauerhaft ein).
        assertTrue(db.pendingWriteDao().byStatus(PendingWriteEntity.STATUS_FAILED).isEmpty())
        db.pendingWriteDao().byStatus(PendingWriteEntity.STATUS_PENDING).single().let {
            assertEquals("INTERNAL", it.note)
        }
    }

    @Test fun `non-transient client error 400 marks the write failed`() = runTest {
        api.savePageResponder = { FakeContentApi.error(400, """{"error_code":"BAD_REQUEST"}""") }

        val res = repo.savePage(pageId, bookId, "<p>local</p>")

        assertTrue(res is SaveResult.Queued)
        db.pendingWriteDao().byStatus(PendingWriteEntity.STATUS_FAILED).single().let {
            assertEquals("BAD_REQUEST", it.note)
        }
    }

    @Test fun `pending write parked by 500 is retried and confirmed by flushPending`() = runTest {
        // Erster Save → 500 → bleibt pending.
        api.savePageResponder = { FakeContentApi.error(500, """{"error_code":"INTERNAL"}""") }
        repo.savePage(pageId, bookId, "<p>local</p>")
        assertEquals(1, db.pendingWriteDao().byStatus(PendingWriteEntity.STATUS_PENDING).size)

        // Server wieder gesund → flushPending drainiert die Queue.
        api.savePageResponder = { Response.success(PageDto(id = pageId, html = "<p>server</p>", updated_at = "t1")) }
        val drained = repo.flushPending().getOrThrow()

        assertEquals(1, drained)
        assertTrue(db.pendingWriteDao().byStatus(PendingWriteEntity.STATUS_PENDING).isEmpty())
        assertFalse(db.pageDao().byId(pageId)!!.dirty)
    }

    @Test fun `flushPending drains many writes and does not abort on a conflict`() = runTest {
        val p2 = 20L
        db.pageDao().upsert(PageEntity(id = p2, bookId = bookId, name = "S2", html = "<p>o2</p>", updatedAt = "t0"))
        // Beide Seiten offline speichern (IOException → pending).
        api.savePageResponder = { throw java.io.IOException("offline") }
        repo.savePage(pageId, bookId, "<p>a</p>")
        repo.savePage(p2, bookId, "<p>b</p>")
        assertEquals(2, db.pendingWriteDao().byStatus(PendingWriteEntity.STATUS_PENDING).size)

        // Beim Flush: pageId → Konflikt, p2 → Saved. Der Konflikt darf p2 nicht blockieren.
        api.savePageResponder = { req ->
            if (req.html == "<p>a</p>") FakeContentApi.error(409, """{"error_code":"PAGE_CONFLICT","server_editor_name":"X"}""")
            else Response.success(PageDto(id = p2, html = req.html, updated_at = "t1"))
        }
        val ok = repo.flushPending().getOrThrow()

        assertEquals(1, ok) // p2 bestätigt
        assertEquals(1, db.pendingWriteDao().byStatus(PendingWriteEntity.STATUS_CONFLICT).size)
    }

    @Test fun `loadPage returns dirty cache without hitting the server`() = runTest {
        db.pageDao().updateHtml(pageId, "<p>dirty local</p>", "dirty local", dirty = true)
        var serverHit = false
        api.pageResponder = { serverHit = true; PageDto(id = it, html = "<p>server</p>") }

        val page = repo.loadPage(pageId, bookId).getOrThrow()

        assertFalse(serverHit) // dirty → Server NICHT angefragt
        assertEquals("<p>dirty local</p>", page.html)
    }

    @Test fun `refreshBooks with empty server response does not wipe the cache`() = runTest {
        // FakeContentApi.books() liefert leere Liste → deleteMissing darf NICHT alles löschen.
        repo.refreshBooks().getOrThrow()

        assertEquals("S", db.pageDao().byId(pageId)?.name) // Seite unberührt
        assertEquals(0, db.bookDao().all().count { it.id == 999L })
        // Vorab ein Buch anlegen, dann erneut mit leerer Antwort refreshen:
        db.bookDao().upsertAll(listOf(ch.schreibwerkstatt.mobile.data.db.BookEntity(id = 999L, name = "B")))
        repo.refreshBooks().getOrThrow()
        assertEquals(1, db.bookDao().all().count { it.id == 999L }) // Buch bleibt erhalten
    }

    @Test fun `401 keeps the write pending and aborts the flush run`() = runTest {
        val p2 = 20L
        db.pageDao().upsert(PageEntity(id = p2, bookId = bookId, name = "S2", html = "<p>o2</p>", updatedAt = "t0"))
        api.savePageResponder = { throw java.io.IOException("offline") }
        repo.savePage(pageId, bookId, "<p>a</p>")
        repo.savePage(p2, bookId, "<p>b</p>")

        // Token widerrufen: jeder PUT → 401.
        var puts = 0
        api.savePageResponder = { puts++; FakeContentApi.error(401, """{"error_code":"UNAUTHORIZED"}""") }
        val res = repo.flushPending()

        assertTrue(res.isFailure)
        assertEquals(1, puts) // Lauf nach dem ersten 401 abgebrochen
        // Beide Writes bleiben pending → nach dem Neu-Koppeln erneut geflusht.
        assertEquals(2, db.pendingWriteDao().byStatus(PendingWriteEntity.STATUS_PENDING).size)
        assertEquals(0, db.pendingWriteDao().byStatus(PendingWriteEntity.STATUS_FAILED).size)
    }

    @Test fun `401 on editor save returns Queued and keeps the write pending`() = runTest {
        api.savePageResponder = { FakeContentApi.error(401, """{"error_code":"UNAUTHORIZED"}""") }

        val res = repo.savePage(pageId, bookId, "<p>local</p>")

        assertEquals(SaveResult.Queued, res)
        assertEquals(PendingWriteEntity.STATUS_PENDING, db.pendingWriteDao().latestForPage(pageId)?.status)
    }

    @Test fun `newer save queued during a flush is rebased instead of clearing dirty`() = runTest {
        // Während der PUT von A läuft, persistiert der Editor lokal B (Basis noch t0).
        api.savePageResponder = { req ->
            if (req.html == "<p>A</p>") {
                runBlocking {
                    db.withTransaction {
                        db.pageDao().updateHtml(pageId, "<p>B</p>", "B", dirty = true)
                        db.pendingWriteDao().deletePendingForPage(pageId)
                        db.pendingWriteDao().insert(
                            PendingWriteEntity(pageId = pageId, bookId = bookId, html = "<p>B</p>",
                                deviceId = "d", createdAt = 2, baseUpdatedAt = "t0")
                        )
                    }
                }
                Response.success(PageDto(id = pageId, html = "<p>A</p>", updated_at = "t1"))
            } else {
                Response.success(PageDto(id = pageId, html = req.html, updated_at = "t2"))
            }
        }

        repo.savePage(pageId, bookId, "<p>A</p>")

        // B wartet weiter: Seite bleibt dirty mit B, nicht „sauber" mit A.
        val page = db.pageDao().byId(pageId)!!
        assertTrue(page.dirty)
        assertEquals("<p>B</p>", page.html)
        // B baut jetzt auf dem eben bestätigten Stand auf → kein 409 gegen den eigenen Save.
        val b = db.pendingWriteDao().latestForPage(pageId)!!
        assertEquals("t1", b.baseUpdatedAt)

        repo.flushPending().getOrThrow()
        assertEquals("t1", api.lastSaveRequest?.expected_updated_at)
        assertFalse(db.pageDao().byId(pageId)!!.dirty)
        assertNull(db.pendingWriteDao().latestForPage(pageId))
    }

    @Test fun `successful save clears a stale conflict row of the page`() = runTest {
        api.savePageResponder = { FakeContentApi.error(409, """{"error_code":"PAGE_CONFLICT"}""") }
        repo.savePage(pageId, bookId, "<p>a</p>")
        api.savePageResponder = { req -> Response.success(PageDto(id = pageId, html = req.html, updated_at = "t1")) }

        repo.savePage(pageId, bookId, "<p>b</p>")

        assertNull(repo.openConflict(pageId))
    }

    @Test fun `loadPage does not overwrite a page that became dirty during the fetch`() = runTest {
        api.pageResponder = {
            // Close-Save der vorherigen Editor-Instanz landet, während der GET läuft.
            runBlocking { db.pageDao().updateHtml(pageId, "<p>just typed</p>", "just typed", dirty = true) }
            PageDto(id = it, html = "<p>server</p>", updated_at = "t1")
        }

        val page = repo.loadPage(pageId, bookId).getOrThrow()

        assertEquals("<p>just typed</p>", page.html)
        assertTrue(db.pageDao().byId(pageId)!!.dirty)
    }
}
