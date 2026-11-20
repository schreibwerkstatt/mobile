package ch.schreibwerkstatt.mobile.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.schreibwerkstatt.mobile.data.db.AppDatabase
import ch.schreibwerkstatt.mobile.data.db.PageEntity
import ch.schreibwerkstatt.mobile.data.net.dto.PageDto
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/**
 * Editor-Öffnen über [ContentRepository.loadPage]: Server-Stand wenn schnell verfügbar,
 * sonst der Cache — ohne auf Netz-Timeouts zu warten, und nie über dirty hinweg.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ContentRepositoryLoadPageTest {

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
        api.pageResponder = { PageDto(id = it, name = "S", html = "<p>server</p>", updated_at = "t1") }
    }

    @After fun tearDown() = db.close()

    private suspend fun cache(dirty: Boolean = false) = db.pageDao().upsert(
        PageEntity(id = pageId, bookId = bookId, name = "S", html = "<p>cached</p>",
            updatedAt = "t0", dirty = dirty, baseHtml = "<p>cached</p>")
    )

    @Test fun `fast server answer replaces the cache`() = runTest {
        cache()
        val page = repo.loadPage(pageId, bookId).getOrThrow()
        assertEquals("<p>server</p>", page.html)
        assertEquals("t1", db.pageDao().byId(pageId)!!.updatedAt)
    }

    @Test fun `hanging network falls back to the cache within the budget`() = runTest {
        cache()
        api.pageDelayMs = 60_000

        val page = repo.loadPage(pageId, bookId).getOrThrow()

        assertEquals("<p>cached</p>", page.html)
        assertTrue("wartete ${currentTime} ms", currentTime <= LOAD_PAGE_NETWORK_BUDGET_MS)
        // Cache und Save-Basis bleiben unverändert und zueinander stimmig.
        db.pageDao().byId(pageId)!!.let {
            assertEquals("t0", it.updatedAt)
            assertEquals("<p>cached</p>", it.baseHtml)
        }
    }

    @Test fun `without cache the editor waits for the server`() = runTest {
        api.pageDelayMs = 10_000
        val page = repo.loadPage(pageId, bookId).getOrThrow()
        assertEquals("<p>server</p>", page.html)
    }

    @Test fun `network error falls back to the cache`() = runTest {
        cache()
        api.pageResponder = { throw IOException("offline") }
        assertEquals("<p>cached</p>", repo.loadPage(pageId, bookId).getOrThrow().html)
    }

    @Test fun `dirty page never asks the server`() = runTest {
        cache(dirty = true)
        api.pageResponder = { error("darf nicht aufgerufen werden") }
        assertEquals("<p>cached</p>", repo.loadPage(pageId, bookId).getOrThrow().html)
    }
}
