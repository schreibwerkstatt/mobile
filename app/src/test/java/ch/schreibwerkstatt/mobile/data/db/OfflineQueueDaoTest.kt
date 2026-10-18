package ch.schreibwerkstatt.mobile.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
 * Verträge der Offline-Schreib-Queue + des Dirty-Flags auf einer In-Memory-Room-DB.
 * Das ist die fehleranfällige Kernlogik des Offline-first-Clients (Pending-Writes,
 * Status-Übergänge bei 409/423, Konsolidierung) — hier ohne Netzwerk/Crypto isoliert.
 */
// Plain Application statt der manifest-deklarierten App: deren onCreate baut den
// ServiceLocator (verschlüsselter TokenStore → Android-Keystore), den Robolectric
// nicht bereitstellt. Für reine DAO-Tests irrelevant.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OfflineQueueDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var pending: PendingWriteDao
    private lateinit var pages: PageDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        pending = db.pendingWriteDao()
        pages = db.pageDao()
    }

    @After fun tearDown() = db.close()

    private fun write(pageId: Long, createdAt: Long, status: String = PendingWriteEntity.STATUS_PENDING) =
        PendingWriteEntity(
            pageId = pageId, bookId = 1, html = "<p>$createdAt</p>",
            deviceId = "dev", createdAt = createdAt, status = status,
        )

    @Test fun `inserted write is pending by default`() = runTest {
        pending.insert(write(pageId = 10, createdAt = 1))
        val p = pending.byStatus()
        assertEquals(1, p.size)
        assertEquals(10L, p.first().pageId)
        assertEquals(PendingWriteEntity.STATUS_PENDING, p.first().status)
    }

    @Test fun `deletePendingForPage coalesces only that page's pending writes`() = runTest {
        pending.insert(write(pageId = 10, createdAt = 1))
        pending.insert(write(pageId = 10, createdAt = 2))
        pending.insert(write(pageId = 20, createdAt = 3))
        // Konflikt-Status darf NICHT mitgelöscht werden (kein offener Pending mehr).
        pending.insert(write(pageId = 10, createdAt = 4, status = PendingWriteEntity.STATUS_CONFLICT))

        pending.deletePendingForPage(10)

        val stillPending = pending.byStatus()
        assertEquals(setOf(20L), stillPending.map { it.pageId }.toSet())
        // Der Konflikt-Eintrag der Seite 10 überlebt.
        assertEquals(1, pending.byStatus(PendingWriteEntity.STATUS_CONFLICT).size)
    }

    @Test fun `setStatus moves a write out of the pending set`() = runTest {
        val id = pending.insert(write(pageId = 10, createdAt = 1))
        pending.setStatus(id, PendingWriteEntity.STATUS_LOCKED, note = "lektor@x.ch")

        assertTrue(pending.byStatus().isEmpty())
        val locked = pending.byStatus(PendingWriteEntity.STATUS_LOCKED).single()
        assertEquals("lektor@x.ch", locked.note)
    }

    @Test fun `observePending reflects live count`() = runTest {
        assertEquals(0, pending.observePending().first().size)
        val id = pending.insert(write(pageId = 10, createdAt = 1))
        assertEquals(1, pending.observePending().first().size)
        pending.delete(id)
        assertEquals(0, pending.observePending().first().size)
    }

    @Test fun `latestForPage returns the newest write`() = runTest {
        pending.insert(write(pageId = 10, createdAt = 1))
        pending.insert(write(pageId = 10, createdAt = 9))
        pending.insert(write(pageId = 10, createdAt = 5))
        assertEquals("<p>9</p>", pending.latestForPage(10)?.html)
        assertNull(pending.latestForPage(999))
    }

    @Test fun `updateHtml marks the page dirty and applyServerVersion clears it`() = runTest {
        pages.upsert(
            PageEntity(id = 10, bookId = 1, name = "S", html = "<p>old</p>", updatedAt = "t0", dirty = false)
        )

        pages.updateHtml(10, "<p>local</p>", plain = "local", dirty = true)
        pages.byId(10)!!.let {
            assertTrue(it.dirty)
            assertEquals("<p>local</p>", it.html)
            assertEquals("t0", it.updatedAt) // updateHtml lässt den Server-Stand unberührt
        }

        pages.applyServerVersion(10, "<p>server</p>", "server", "t1", "S2", baseHtml = "<p>server</p>")
        pages.byId(10)!!.let {
            assertFalse(it.dirty)
            assertEquals("<p>server</p>", it.html)
            assertEquals("t1", it.updatedAt)
            assertEquals("S2", it.name)
            assertEquals("<p>server</p>", it.baseHtml)
        }
    }
}
