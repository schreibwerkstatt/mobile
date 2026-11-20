package ch.schreibwerkstatt.mobile.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import ch.schreibwerkstatt.mobile.data.db.AppDatabase
import ch.schreibwerkstatt.mobile.data.db.PageEntity
import ch.schreibwerkstatt.mobile.data.db.PendingWriteEntity
import ch.schreibwerkstatt.mobile.data.net.dto.PageDto
import ch.schreibwerkstatt.mobile.data.net.dto.RestoreResponse
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import retrofit2.HttpException
import retrofit2.Response

/**
 * Wiederherstellen einer Revision mit lokalen, noch nicht gesyncten Änderungen:
 * [ContentRepository.flushPage] bringt sie zuerst an den Server; scheitert das,
 * bleiben sie (und [ContentRepository.hasUnsyncedChanges] meldet sie), bis der
 * Nutzer das verwerfende [ContentRepository.restoreRevision] bewusst bestätigt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ContentRepositoryRestoreTest {

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
            PageEntity(id = pageId, bookId = bookId, name = "S", html = "<p>old</p>", updatedAt = "t0", dirty = false)
        )
    }

    @After fun tearDown() = db.close()

    /** Offline-Save: lässt einen dirty Abschnitt mit Pending-Write zurück. */
    private suspend fun queueOfflineSave() {
        api.savePageResponder = { throw java.io.IOException("offline") }
        repo.savePage(pageId, bookId, "<p>local</p>")
    }

    @Test fun `clean page has no unsynced changes`() = runTest {
        assertFalse(repo.hasUnsyncedChanges(pageId))
        assertTrue(repo.flushPage(pageId))
    }

    @Test fun `flushPage pushes the pending write so restore loses nothing`() = runTest {
        queueOfflineSave()
        assertTrue(repo.hasUnsyncedChanges(pageId))

        api.savePageResponder = {
            Response.success(PageDto(id = pageId, name = "S", html = "<p>local</p>", updated_at = "t1"))
        }
        assertTrue(repo.flushPage(pageId))
        assertEquals("<p>local</p>", api.lastSaveRequest?.html)
        assertFalse(repo.hasUnsyncedChanges(pageId))
    }

    @Test fun `flushPage offline keeps the local change and reports failure`() = runTest {
        queueOfflineSave()

        assertFalse(repo.flushPage(pageId))
        assertTrue(repo.hasUnsyncedChanges(pageId))
        assertEquals("<p>local</p>", db.pageDao().byId(pageId)!!.html)
        assertEquals(1, db.pendingWriteDao().byStatus(PendingWriteEntity.STATUS_PENDING).size)
    }

    @Test fun `flushPage conflict reports failure and keeps the conflict row`() = runTest {
        queueOfflineSave()
        api.savePageResponder = { FakeContentApi.error(409, """{"error_code":"PAGE_CONFLICT"}""") }

        assertFalse(repo.flushPage(pageId))
        assertTrue(repo.hasUnsyncedChanges(pageId))
        assertEquals(1, db.pendingWriteDao().byStatus(PendingWriteEntity.STATUS_CONFLICT).size)
    }

    @Test fun `confirmed restore discards the unsynced change`() = runTest {
        queueOfflineSave()
        api.restoreResponder = { _, _ -> Response.success(RestoreResponse(ok = true, restored_from = 5)) }
        api.pageResponder = { PageDto(id = it, name = "S", html = "<p>restored</p>", updated_at = "t2") }

        val page = repo.restoreRevision(pageId, 5, bookId).getOrThrow()

        assertEquals("<p>restored</p>", page.html)
        assertFalse(page.dirty)
        assertFalse(repo.hasUnsyncedChanges(pageId))
    }

    @Test fun `failed restore surfaces the HTTP status and keeps the local change`() = runTest {
        queueOfflineSave()
        api.restoreResponder = { _, _ -> FakeContentApi.error(423, """{"error_code":"PAGE_LOCKED"}""") }

        val err = repo.restoreRevision(pageId, 5, bookId).exceptionOrNull()

        assertEquals(423, (err as HttpException).code())
        assertTrue(repo.hasUnsyncedChanges(pageId))
    }
}
