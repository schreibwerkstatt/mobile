package ch.schreibwerkstatt.mobile.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.schreibwerkstatt.mobile.data.db.AppDatabase
import ch.schreibwerkstatt.mobile.data.db.PageEntity
import ch.schreibwerkstatt.mobile.data.net.dto.SyncCursorDto
import ch.schreibwerkstatt.mobile.data.net.dto.SyncDeletedDto
import ch.schreibwerkstatt.mobile.data.net.dto.SyncPageDto
import ch.schreibwerkstatt.mobile.data.net.dto.SyncResponse
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import kotlinx.coroutines.test.runTest
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

/**
 * Delta-Pull des [SyncEngine] auf einer In-Memory-Room-DB mit gefaktem ContentApi.
 * Kern-Garantie: lokal-dirty Seiten werden vom Server-Stand NIE überschrieben.
 * Ausserdem: Cursor-Persistenz und has_more-Pagination.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SyncEngineTest {

    private lateinit var db: AppDatabase
    private lateinit var api: FakeContentApi
    private lateinit var engine: SyncEngine

    private val bookId = 1L
    private val baseUrl: suspend () -> String = { "http://test" }

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        api = FakeContentApi()
        val net = FakeNetworkClient(ctx, api)
        engine = SyncEngine(db, net, SettingsStore(ctx))
    }

    @After fun tearDown() = db.close()

    private fun syncPage(id: Long, html: String) =
        SyncPageDto(page_id = id, page_name = "S$id", chapter_id = null, updated_at = "t-server", html = html)

    @Test fun `dirty local page is not overwritten by the pull`() = runTest {
        db.pageDao().upsert(
            PageEntity(id = 10, bookId = bookId, name = "S10", html = "<p>local</p>", updatedAt = "t0", dirty = true)
        )
        api.syncQueue += SyncResponse(now = "now", pages = listOf(syncPage(10, "<p>server</p>")), has_more = false)

        engine.pullBook(bookId, baseUrl).getOrThrow()

        db.pageDao().byId(10)!!.let {
            assertEquals("<p>local</p>", it.html)   // Pending-Write hat Vorrang
            assertTrue(it.dirty)
            assertEquals("t0", it.updatedAt)
        }
    }

    @Test fun `clean local page is updated by the pull`() = runTest {
        db.pageDao().upsert(
            PageEntity(id = 11, bookId = bookId, name = "S11", html = "<p>old</p>", updatedAt = "t0", dirty = false)
        )
        api.syncQueue += SyncResponse(now = "now", pages = listOf(syncPage(11, "<p>new</p>")), has_more = false)

        engine.pullBook(bookId, baseUrl).getOrThrow()

        db.pageDao().byId(11)!!.let {
            assertEquals("<p>new</p>", it.html)
            assertFalse(it.dirty)
            assertEquals("t-server", it.updatedAt)
        }
    }

    @Test fun `previously unknown page is inserted`() = runTest {
        api.syncQueue += SyncResponse(now = "now", pages = listOf(syncPage(12, "<p>fresh</p>")), has_more = false)

        engine.pullBook(bookId, baseUrl).getOrThrow()

        assertNotNull(db.pageDao().byId(12))
        assertEquals("<p>fresh</p>", db.pageDao().byId(12)!!.html)
    }

    @Test fun `cursor and last-synced timestamp are persisted`() = runTest {
        api.syncQueue += SyncResponse(
            now = "now-iso",
            pages = listOf(syncPage(13, "<p>x</p>")),
            has_more = false,
            cursor = SyncCursorDto(since = "t-cursor", since_id = 99),
        )

        engine.pullBook(bookId, baseUrl).getOrThrow()

        db.syncCursorDao().forBook(bookId)!!.let {
            assertEquals("t-cursor", it.since)
            assertEquals(99L, it.sinceId)
            assertEquals("now-iso", it.lastSyncedAt)
        }
    }

    @Test fun `pull terminates when server signals has_more but the cursor does not advance`() = runTest {
        // Fehlerhafter Server: has_more=true, aber kein Cursor-Fortschritt (cursor=null).
        // Ohne Schutz würde die identische Anfrage endlos wiederholt.
        api.syncQueue += SyncResponse(
            now = "now",
            pages = listOf(syncPage(30, "<p>x</p>")),
            has_more = true,
            cursor = null,
        )

        engine.pullBook(bookId, baseUrl).getOrThrow() // terminiert überhaupt = Beweis

        assertEquals(1, api.syncCalls) // genau eine Runde, dann Abbruch
        assertNotNull(db.pageDao().byId(30))
    }

    @Test fun `pagination follows has_more and forwards the cursor`() = runTest {
        api.syncQueue += SyncResponse(
            now = "now1",
            pages = listOf(syncPage(20, "<p>a</p>")),
            has_more = true,
            cursor = SyncCursorDto(since = "page1", since_id = 1),
        )
        api.syncQueue += SyncResponse(
            now = "now2",
            pages = listOf(syncPage(21, "<p>b</p>")),
            has_more = false,
            cursor = SyncCursorDto(since = "page2", since_id = 2),
        )

        engine.pullBook(bookId, baseUrl).getOrThrow()

        assertEquals(2, api.syncCalls)
        // Erste Abfrage ohne Cursor, zweite mit dem Cursor der ersten Antwort.
        assertEquals(listOf(null, "page1"), api.sinceArgs)
        assertEquals(listOf(null, 1L), api.sinceIdArgs)
        assertNotNull(db.pageDao().byId(20))
        assertNotNull(db.pageDao().byId(21))
        assertEquals("page2", db.syncCursorDao().forBook(bookId)!!.since)
    }

    @Test fun `server deletions remove clean pages but keep dirty ones`() = runTest {
        db.pageDao().upsert(PageEntity(id = 30, bookId = bookId, name = "gone", html = "<p>x</p>", dirty = false))
        db.pageDao().upsert(PageEntity(id = 31, bookId = bookId, name = "edited", html = "<p>y</p>", dirty = true))
        api.syncQueue += SyncResponse(
            now = "now",
            deleted = listOf(SyncDeletedDto(page_id = 30), SyncDeletedDto(page_id = 31)),
            has_more = false,
        )

        engine.pullBook(bookId, baseUrl).getOrThrow()

        assertEquals(null, db.pageDao().byId(30))
        assertNotNull(db.pageDao().byId(31)) // Pending-Write hat Vorrang
    }
}
