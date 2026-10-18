package ch.schreibwerkstatt.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID

@Database(
    entities = [
        BookEntity::class,
        PageEntity::class,
        PageFtsEntity::class,
        SyncCursorEntity::class,
        PendingWriteEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun pageDao(): PageDao
    abstract fun syncCursorDao(): SyncCursorDao
    abstract fun pendingWriteDao(): PendingWriteDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "schreibwerkstatt.db",
                )
                    // KEIN destruktiver Fallback für Upgrades: Ein Schema-Bump OHNE
                    // passende Migration soll laut hier lieber laut scheitern als still
                    // die Offline-Schreib-Queue (pending_writes) und den Cache zu löschen
                    // (Offline-first-Regel). Für jeden künftigen versionsanstieg ein
                    // Migration-Objekt via .addMigrations(...) ergänzen (Schemas liegen
                    // exportiert unter app/schemas/ → mit MigrationTestHelper testbar).
                    // Nur ein Downgrade (Dev/Rollback) darf destruktiv neu aufsetzen.
                    .addMigrations(MIGRATION_3_4)
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { instance = it }
            }
    }
}

/**
 * 3 → 4: Save-Kennungen an der Pending-Queue (`saveId`, `ownSaveIds`, `sentAt`).
 * Bestehende Zeilen bekommen je eine frische UUID und eine leere Liste; `sentAt`
 * bleibt null — ihre früheren Sendeversuche trugen keine Kennung.
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pending_writes ADD COLUMN saveId TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE pending_writes ADD COLUMN ownSaveIds TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE pending_writes ADD COLUMN sentAt INTEGER")
        val ids = mutableListOf<Long>()
        db.query("SELECT localId FROM pending_writes").use { c ->
            while (c.moveToNext()) ids += c.getLong(0)
        }
        for (id in ids) {
            db.execSQL(
                "UPDATE pending_writes SET saveId = ? WHERE localId = ?",
                arrayOf<Any>(UUID.randomUUID().toString(), id),
            )
        }
    }
}
