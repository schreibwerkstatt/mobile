package ch.schreibwerkstatt.mobile.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.schreibwerkstatt.mobile.data.db.AppDatabase
import ch.schreibwerkstatt.mobile.data.db.PageEntity
import ch.schreibwerkstatt.mobile.data.net.dto.PageDto
import ch.schreibwerkstatt.mobile.data.net.dto.SyncPageDto
import ch.schreibwerkstatt.mobile.data.net.dto.SyncResponse
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
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
 * Sync-Pull in den offenen Editor. Kern-Garantie: Die Basis eines Saves
 * (`updatedAt`/`baseHtml`) rückt nie über den Stand hinaus, den der Editor zeigt —
 * sonst ginge der Save mit passendem Stempel raus und überschriebe einen fremden
 * Stand still, statt auf dem Server zu mergen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ContentRepositoryEditorPullTest {

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
        db.pageDao().upsert(
            PageEntity(id = pageId, bookId = bookId, name = "S", html = "<p>old</p>",
                updatedAt = "t0", dirty = false, baseHtml = "<p>old</p>")
        )
    }

    @After fun tearDown() = db.close()

    private fun queueForeignPull() {
        api.syncQueue += SyncResponse(
            now = "now",
            pages = listOf(SyncPageDto(page_id = pageId, page_name = "S", chapter_id = null,
                updated_at = "t1", html = "<p>foreign</p>")),
            has_more = false,
        )
    }

    @Test fun `delta pull leaves a page open in the editor untouched`() = runTest {
        repo.editorOpened(pageId)
        queueForeignPull()

        repo.syncBook(bookId).getOrThrow()

        db.pageDao().byId(pageId)!!.let {
            assertEquals("<p>old</p>", it.html)
            assertEquals("t0", it.updatedAt)
            assertEquals("<p>old</p>", it.baseHtml)
        }
    }

    @Test fun `save after a pull with the editor open still carries the shown base`() = runTest {
        repo.editorOpened(pageId)
        queueForeignPull()
        repo.syncBook(bookId).getOrThrow()
        api.savePageResponder = { Response.success(PageDto(id = pageId, html = "<p>merged</p>", updated_at = "t2", merged = true)) }

        repo.savePage(pageId, bookId, "<p>mine</p>")

        // Veralteter Stempel + Basis des angezeigten Stands → der Server mergt.
        assertEquals("t0", api.lastSaveRequest?.expected_updated_at)
        assertEquals("<p>old</p>", api.lastSaveRequest?.base_html)
    }

    @Test fun `pull updates the page again once the editor is closed`() = runTest {
        repo.editorOpened(pageId)
        repo.editorOpened(pageId)   // zweimal im Backstack
        repo.editorClosed(pageId)
        queueForeignPull()
        repo.syncBook(bookId).getOrThrow()
        assertEquals("t0", db.pageDao().byId(pageId)!!.updatedAt)

        repo.editorClosed(pageId)
        queueForeignPull()
        repo.syncBook(bookId).getOrThrow()

        assertEquals("t1", db.pageDao().byId(pageId)!!.updatedAt)
    }

    @Test fun `serverUpdateForEditor offers a newer server state without writing the cache`() = runTest {
        api.pageResponder = { PageDto(id = it, name = "S", html = "<p>foreign</p>", updated_at = "t1") }

        val offer = repo.serverUpdateForEditor(pageId, bookId)!!

        assertEquals("<p>foreign</p>", offer.html)
        assertEquals("t1", offer.updatedAt)
        assertEquals("<p>foreign</p>", offer.baseHtml)
        assertEquals("t0", db.pageDao().byId(pageId)!!.updatedAt)
    }

    @Test fun `serverUpdateForEditor offers nothing for an unchanged or dirty page`() = runTest {
        api.pageResponder = { PageDto(id = it, name = "S", html = "<p>old</p>", updated_at = "t0") }
        assertNull(repo.serverUpdateForEditor(pageId, bookId))

        api.pageResponder = { PageDto(id = it, name = "S", html = "<p>foreign</p>", updated_at = "t1") }
        db.pageDao().updateHtml(pageId, "<p>typing</p>", "typing", dirty = true)
        assertNull(repo.serverUpdateForEditor(pageId, bookId))
    }

    @Test fun `adopting an applied state moves the base, the next save sends it`() = runTest {
        api.pageResponder = { PageDto(id = it, name = "S", html = "<p>foreign</p>", updated_at = "t1") }
        val offer = repo.serverUpdateForEditor(pageId, bookId)!!

        assertTrue(repo.adoptServerPage(offer))
        api.savePageResponder = { Response.success(PageDto(id = pageId, html = "<p>foreign mine</p>", updated_at = "t2")) }
        repo.savePage(pageId, bookId, "<p>foreign mine</p>")

        assertEquals("t1", api.lastSaveRequest?.expected_updated_at)
        assertEquals("<p>foreign</p>", api.lastSaveRequest?.base_html)
    }

    @Test fun `adopting never overwrites a dirty page`() = runTest {
        api.pageResponder = { PageDto(id = it, name = "S", html = "<p>foreign</p>", updated_at = "t1") }
        val offer = repo.serverUpdateForEditor(pageId, bookId)!!
        db.pageDao().updateHtml(pageId, "<p>typing</p>", "typing", dirty = true)

        assertFalse(repo.adoptServerPage(offer))
        db.pageDao().byId(pageId)!!.let {
            assertEquals("<p>typing</p>", it.html)
            assertTrue(it.dirty)
            assertEquals("t0", it.updatedAt)
        }
    }
}
