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
            .addMigrations(MIGRATION_3_4).allowMainThreadQueries().build()
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
