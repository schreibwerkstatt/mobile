package ch.schreibwerkstatt.mobile.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

/**
 * Room-Migrationen gegen die exportierten Schemas (app/schemas/). Die Offline-Queue
 * muss jeden Schema-Bump überleben — ein verlorener Pending-Write ist verlorener Text.
 *
 * Die Alt-Version wird direkt aus dem committeten Schema-JSON aufgebaut (AGP reicht
 * Test-Assets nicht an Unit-Tests durch, MigrationTestHelper fände sie nicht). Beim
 * Öffnen validiert Room das migrierte Schema gegen die aktuellen Entities und
 * scheitert laut, wenn die Migration nicht passt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class MigrationTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    @Before fun setUp() { ctx.deleteDatabase(dbName) }
    @After fun tearDown() { ctx.deleteDatabase(dbName) }

    @Test fun `3 to 4 keeps pending writes and gives each a fresh save id`() = runTest {
        createFromSchema(3).use { db ->
            db.execSQL(
                "INSERT INTO pending_writes (pageId, bookId, html, deviceId, createdAt, baseUpdatedAt, status, note) " +
                    "VALUES (10, 1, '<p>a</p>', 'dev', 1, 't0', 'pending', NULL)"
            )
            db.execSQL(
                "INSERT INTO pending_writes (pageId, bookId, html, deviceId, createdAt, baseUpdatedAt, status, note) " +
                    "VALUES (20, 1, '<p>b</p>', 'dev', 2, NULL, 'conflict', 'Lektor')"
            )
        }

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {
            val a = db.pendingWriteDao().latestForPage(10)!!
            val b = db.pendingWriteDao().latestForPage(20)!!
            assertEquals("<p>a</p>", a.html)
            assertEquals("t0", a.baseUpdatedAt)
            assertEquals(PendingWriteEntity.STATUS_PENDING, a.status)
            assertEquals(PendingWriteEntity.STATUS_CONFLICT, b.status)
            assertEquals("Lektor", b.note)
            for (w in listOf(a, b)) {
                assertEquals(4, UUID.fromString(w.saveId).version())
                assertTrue(w.ownSaveIds.isEmpty())
                assertNull(w.sentAt)
            }
            assertNotEquals(a.saveId, b.saveId)

            // Nach der Migration normal weiterschreibbar (Default-Werte der neuen Spalten).
            db.pendingWriteDao().markSent(a.localId, 5)
            assertEquals(5L, db.pendingWriteDao().byId(a.localId)!!.sentAt)
        } finally {
            db.close()
        }
    }

    @Test fun `4 to 5 keeps pages and pending writes, base html starts unknown`() = runTest {
        createFromSchema(4).use { db ->
            db.execSQL(
                "INSERT INTO pages (id, bookId, chapterId, name, html, plain, updatedAt, dirty) " +
                    "VALUES (10, 1, NULL, 'S', '<p>lokal</p>', 'lokal', 't0', 1)"
            )
            db.execSQL(
                "INSERT INTO pending_writes (pageId, bookId, html, deviceId, createdAt, baseUpdatedAt, status, note, saveId, ownSaveIds, sentAt) " +
                    "VALUES (10, 1, '<p>lokal</p>', 'dev', 1, 't0', 'pending', NULL, '6f1c2b9e-0000-4000-8000-0000000000aa', '[]', 3)"
            )
        }

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {
            val page = db.pageDao().byId(10)!!
            assertEquals("<p>lokal</p>", page.html)
            assertTrue(page.dirty)
            assertNull(page.baseHtml)
            val w = db.pendingWriteDao().latestForPage(10)!!
            assertEquals("t0", w.baseUpdatedAt)
            assertEquals(3L, w.sentAt)
            assertNull(w.baseHtml)

            // Neue Spalten sind normal beschreibbar.
            db.pendingWriteDao().rebase(w.localId, "t1", "<p>basis</p>")
            assertEquals("<p>basis</p>", db.pendingWriteDao().byId(w.localId)!!.baseHtml)
            db.pageDao().updateServerStamp(10, "t1", "<p>basis</p>")
            assertEquals("<p>basis</p>", db.pageDao().byId(10)!!.baseHtml)
        } finally {
            db.close()
        }
    }

    @Test fun `5 to 6 keeps pages and pending writes, adds an empty tree cache`() = runTest {
        createFromSchema(5).use { db ->
            db.execSQL(
                "INSERT INTO pages (id, bookId, chapterId, name, html, plain, updatedAt, dirty, baseHtml) " +
                    "VALUES (10, 1, NULL, 'S', '<p>lokal</p>', 'lokal', 't0', 1, '<p>basis</p>')"
            )
            db.execSQL(
                "INSERT INTO pending_writes (pageId, bookId, html, deviceId, createdAt, baseUpdatedAt, status, note, saveId, ownSaveIds, sentAt, baseHtml) " +
                    "VALUES (10, 1, '<p>lokal</p>', 'dev', 1, 't0', 'pending', NULL, '6f1c2b9e-0000-4000-8000-0000000000aa', '[]', NULL, '<p>basis</p>')"
            )
        }

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {
            assertEquals("<p>basis</p>", db.pageDao().byId(10)!!.baseHtml)
            assertEquals("<p>basis</p>", db.pendingWriteDao().latestForPage(10)!!.baseHtml)
            assertNull(db.treeCacheDao().byBook(1))

            db.treeCacheDao().upsert(TreeCacheEntity(bookId = 1, json = "{}", fetchedAt = 5))
            assertEquals("{}", db.treeCacheDao().byBook(1)!!.json)
        } finally {
            db.close()
        }
    }

    @Test fun `6 to 7 keeps the pending queue and adds the research and ideen queues`() = runTest {
        createFromSchema(6).use { db ->
            db.execSQL(
                "INSERT INTO pending_writes (pageId, bookId, html, deviceId, createdAt, baseUpdatedAt, status, note, saveId, ownSaveIds, sentAt, baseHtml) " +
                    "VALUES (10, 1, '<p>lokal</p>', 'dev', 1, 't0', 'pending', NULL, '6f1c2b9e-0000-4000-8000-0000000000aa', '[]', NULL, '<p>basis</p>')"
            )
        }

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbName)
            .addMigrations(*ALL_MIGRATIONS).allowMainThreadQueries().build()
        try {
            assertEquals("<p>lokal</p>", db.pendingWriteDao().latestForPage(10)!!.html)
            assertTrue(db.researchOutboxDao().pending().isEmpty())
            assertEquals(0, db.pendingIdeaDao().count())
            assertTrue(db.pendingIdeaChangeDao().pending().isEmpty())
            assertNull(db.ideenCacheDao().byBook(1))

            val id = db.pendingIdeaDao().insert(PendingIdeaEntity(bookId = 1, content = "Einfall", createdAt = 1))
            assertEquals(PendingIdeaEntity.STATUS_PENDING, db.pendingIdeaDao().byId(id)!!.status)
            db.researchOutboxDao().insert(ResearchOutboxEntity(bookId = 1, payload = "{}", createdAt = 1))
            assertEquals(1, db.researchOutboxDao().pendingCount())
            db.pendingIdeaChangeDao().upsert(PendingIdeaChangeEntity(bookId = 1, ideeId = 5, createdAt = 1))
            assertEquals(5L, db.pendingIdeaChangeDao().pending().single().ideeId)
            db.ideenCacheDao().upsert(IdeenCacheEntity(bookId = 1, json = "{}", fetchedAt = 5))
            assertEquals("{}", db.ideenCacheDao().byBook(1)!!.json)
        } finally {
            db.close()
        }
    }

    /** Legt die DB im Stand von [version] laut `app/schemas/…/<version>.json` an. */
    private fun createFromSchema(version: Int): SQLiteDatabase {
        val file = File("schemas/${AppDatabase::class.java.name}/$version.json")
        val schema = Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
        val db = SQLiteDatabase.openOrCreateDatabase(ctx.getDatabasePath(dbName), null)
        fun JsonObject.strings(key: String) =
            this[key]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        for (e in schema["entities"]!!.jsonArray.map { it.jsonObject }) {
            val table = e["tableName"]!!.jsonPrimitive.content
            db.execSQL(e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e["indices"]?.jsonArray?.forEach {
                db.execSQL(it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
            e.strings("contentSyncTriggers").forEach(db::execSQL)
        }
        schema.strings("setupQueries").forEach(db::execSQL)
        db.version = version
        return db
    }
}
