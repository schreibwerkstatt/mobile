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
        TreeCacheEntity::class,
        ResearchOutboxEntity::class,
        PendingIdeaEntity::class,
        PendingIdeaChangeEntity::class,
        IdeenCacheEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun pageDao(): PageDao
    abstract fun syncCursorDao(): SyncCursorDao
    abstract fun pendingWriteDao(): PendingWriteDao
    abstract fun treeCacheDao(): TreeCacheDao
    abstract fun researchOutboxDao(): ResearchOutboxDao
    abstract fun pendingIdeaDao(): PendingIdeaDao
    abstract fun pendingIdeaChangeDao(): PendingIdeaChangeDao
    abstract fun ideenCacheDao(): IdeenCacheDao

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
                    .addMigrations(*ALL_MIGRATIONS)
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

/**
 * 4 → 5: Basis-HTML für den Server-Merge (`base_html`) an Seiten und Pending-Queue.
 * Bestehende Zeilen bleiben null — ihre Pushes laufen bei veraltetem Stempel wie
 * bisher in den 409; ab dem nächsten Pull/Laden/bestätigten Push ist die Basis bekannt.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE pages ADD COLUMN baseHtml TEXT")
        db.execSQL("ALTER TABLE pending_writes ADD COLUMN baseHtml TEXT")
    }
}

/** 5 → 6: Offline-Cache der Buch-Gliederung (`tree_cache`), startet leer. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tree_cache` (`bookId` INTEGER NOT NULL, `json` TEXT NOT NULL, " +
                "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`bookId`))"
        )
    }
}

/**
 * 6 → 7: Offline-Queues für Recherche-Erfassen (`research_outbox`), neue Ideen
 * (`pending_ideas`) und Ideen-Änderungen (`pending_idea_changes`), dazu der
 * Ideen-Board-Cache (`ideen_cache`). Alle starten leer.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `research_outbox` (`localId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`bookId` INTEGER NOT NULL, `payload` TEXT NOT NULL, `targetId` INTEGER, `imagePath` TEXT, " +
                "`imageMime` TEXT, `step` INTEGER NOT NULL, `status` TEXT NOT NULL, `note` TEXT, " +
                "`createdAt` INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_research_outbox_bookId` ON `research_outbox` (`bookId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_ideas` (" +
                "`localId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, " +
                "`content` TEXT NOT NULL, `pageId` INTEGER, `chapterId` INTEGER, " +
                "`idempotencyKey` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                "`status` TEXT NOT NULL, `error` TEXT)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_ideas_bookId` ON `pending_ideas` (`bookId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_idea_changes` (" +
                "`localId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `bookId` INTEGER NOT NULL, " +
                "`ideeId` INTEGER NOT NULL, `remove` INTEGER NOT NULL, `patch` TEXT NOT NULL, " +
                "`placeName` TEXT, `createdAt` INTEGER NOT NULL, `status` TEXT NOT NULL, `error` TEXT)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pending_idea_changes_bookId` ON `pending_idea_changes` (`bookId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_idea_changes_ideeId` ON `pending_idea_changes` (`ideeId`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `ideen_cache` (`bookId` INTEGER NOT NULL, `json` TEXT NOT NULL, " +
                "`fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`bookId`))"
        )
    }
}

/** Alle Migrationen in Reihenfolge — App und MigrationTest nutzen dieselbe Liste. */
val ALL_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
