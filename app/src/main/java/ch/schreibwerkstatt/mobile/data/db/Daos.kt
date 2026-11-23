package ch.schreibwerkstatt.mobile.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<BookEntity>>

    /** One-Shot-Liste (z.B. für den periodischen Background-Sync über alle Bücher). */
    @Query("SELECT * FROM books")
    suspend fun all(): List<BookEntity>

    @Upsert
    suspend fun upsertAll(books: List<BookEntity>)

    @Query("DELETE FROM books WHERE id NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<Long>)

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun byId(id: Long): BookEntity?
}

@Dao
interface PageDao {
    @Query("SELECT * FROM pages WHERE bookId = :bookId ORDER BY name COLLATE NOCASE")
    fun observeForBook(bookId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE id = :id")
    suspend fun byId(id: Long): PageEntity?

    @Query("SELECT * FROM pages WHERE id = :id")
    fun observeById(id: Long): Flow<PageEntity?>

    @Upsert
    suspend fun upsertAll(pages: List<PageEntity>)

    @Upsert
    suspend fun upsert(page: PageEntity)

    @Query("UPDATE pages SET html = :html, plain = :plain, dirty = :dirty WHERE id = :id")
    suspend fun updateHtml(id: Long, html: String, plain: String?, dirty: Boolean)

    /** Server-Stand übernehmen. [baseHtml] = Basis des nächsten Pushs, im Normalfall = [html]. */
    @Query("UPDATE pages SET html = :html, plain = :plain, updatedAt = :updatedAt, name = :name, baseHtml = :baseHtml, dirty = 0 WHERE id = :id")
    suspend fun applyServerVersion(id: Long, html: String?, plain: String?, updatedAt: String?, name: String?, baseHtml: String?)

    /**
     * Nur den bestätigten Server-Stand (`updatedAt`) nachführen, lokalen Inhalt und
     * `dirty` unangetastet lassen — wenn hinter dem gerade bestätigten Write schon ein
     * neuerer lokaler Edit wartet.
     */
    @Query("UPDATE pages SET updatedAt = :updatedAt, baseHtml = :baseHtml WHERE id = :id")
    suspend fun updateServerStamp(id: Long, updatedAt: String?, baseHtml: String?)

    /** Serverseitig gelöschte Seiten entfernen — lokal-dirty Seiten bleiben (Pending-Write hat Vorrang). */
    @Query("DELETE FROM pages WHERE id IN (:ids) AND dirty = 0")
    suspend fun deleteCleanByIds(ids: List<Long>)

    /** Nicht-dirty Seiten eines Buchs, die im Server-Baum fehlen (Abgleich bei gedeckeltem `deleted`). */
    @Query("DELETE FROM pages WHERE bookId = :bookId AND dirty = 0 AND id NOT IN (:keepIds)")
    suspend fun deleteCleanMissing(bookId: Long, keepIds: List<Long>)

    /**
     * Volltextsuche im Seiteninhalt eines Buchs via FTS4 (`pages_fts` MATCH).
     * Liefert pro Treffer einen markup-freien Snippet rund um die Fundstelle.
     * `:match` ist eine fertige FTS-MATCH-Query (siehe ContentRepository).
     */
    @Query(
        """
        SELECT p.id AS id, p.name AS name,
               snippet(pages_fts, '', '', '…', -1, 16) AS snippet
        FROM pages_fts
        JOIN pages p ON p.id = pages_fts.rowid
        WHERE p.bookId = :bookId AND pages_fts MATCH :match
        ORDER BY p.name COLLATE NOCASE
        """
    )
    suspend fun searchContent(bookId: Long, match: String): List<PageContentHit>
}

/** Treffer der Inhalts-Volltextsuche: Seite + markup-freier Snippet der Fundstelle. */
data class PageContentHit(
    val id: Long,
    val name: String?,
    val snippet: String?,
)

@Dao
interface SyncCursorDao {
    @Query("SELECT * FROM sync_cursors WHERE bookId = :bookId")
    suspend fun forBook(bookId: Long): SyncCursorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(cursor: SyncCursorEntity)
}

@Dao
interface PendingWriteDao {
    @Insert
    suspend fun insert(write: PendingWriteEntity): Long

    @Query("SELECT * FROM pending_writes WHERE status = :status ORDER BY createdAt ASC")
    suspend fun byStatus(status: String = PendingWriteEntity.STATUS_PENDING): List<PendingWriteEntity>

    @Query("SELECT * FROM pending_writes WHERE pageId = :pageId ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestForPage(pageId: Long): PendingWriteEntity?

    @Query("SELECT * FROM pending_writes WHERE localId = :localId")
    suspend fun byId(localId: Long): PendingWriteEntity?

    @Query("SELECT * FROM pending_writes WHERE pageId = :pageId AND status = :status ORDER BY createdAt DESC LIMIT 1")
    suspend fun pendingForPage(pageId: Long, status: String = PendingWriteEntity.STATUS_PENDING): PendingWriteEntity?

    /** Von einem bestätigten Save überholte conflict/locked/failed-Zeilen einer Seite aufräumen. */
    @Query("DELETE FROM pending_writes WHERE pageId = :pageId AND status != :status")
    suspend fun deleteSettledForPage(pageId: Long, status: String = PendingWriteEntity.STATUS_PENDING)

    /**
     * Basis eines (neueren) Pending-Writes auf den gerade bestätigten Server-Stand heben.
     * Die geerbten `ownSaveIds` liegen alle vor dieser neuen Basis → erledigt.
     */
    @Query("UPDATE pending_writes SET baseUpdatedAt = :baseUpdatedAt, baseHtml = :baseHtml, ownSaveIds = '[]' WHERE pageId = :pageId AND status = :status")
    suspend fun rebasePending(pageId: Long, baseUpdatedAt: String?, baseHtml: String?, status: String = PendingWriteEntity.STATUS_PENDING)

    /** Einen Write auf einen als eigen erkannten Server-Stand umbasieren (409-Sicherheitsnetz). */
    @Query("UPDATE pending_writes SET baseUpdatedAt = :baseUpdatedAt, baseHtml = :baseHtml, ownSaveIds = '[]' WHERE localId = :localId")
    suspend fun rebase(localId: Long, baseUpdatedAt: String?, baseHtml: String?)

    /** Ersten Sendeversuch vermerken — VOR dem Request, damit eine verlorene Antwort zählt. */
    @Query("UPDATE pending_writes SET sentAt = :sentAt WHERE localId = :localId AND sentAt IS NULL")
    suspend fun markSent(localId: Long, sentAt: Long)

    @Query("SELECT COUNT(*) FROM pending_writes")
    suspend fun count(): Int

    fun observePending(): Flow<List<PendingWriteEntity>> = observeByStatus(PendingWriteEntity.STATUS_PENDING)

    @Query("SELECT * FROM pending_writes WHERE status = :status ORDER BY createdAt ASC")
    fun observeByStatus(status: String): Flow<List<PendingWriteEntity>>

    @Query("UPDATE pending_writes SET status = :status, note = :note WHERE localId = :localId")
    suspend fun setStatus(localId: Long, status: String, note: String?)

    /**
     * Vom Server per 4xx abgewiesen: Status setzen und `sentAt` zurücknehmen — die
     * `saveId` kann nicht mehr der Server-Stand sein, ein Nachfolger erbt sie nicht.
     */
    @Query("UPDATE pending_writes SET status = :status, note = :note, sentAt = NULL WHERE localId = :localId")
    suspend fun setRejected(localId: Long, status: String, note: String?)

    @Query("DELETE FROM pending_writes WHERE localId = :localId")
    suspend fun delete(localId: Long)

    /** Beim Queuen einer neuen Version ältere Pending-Writes derselben Seite zusammenfassen. */
    @Query("DELETE FROM pending_writes WHERE pageId = :pageId AND status = :status")
    suspend fun deletePendingForPage(pageId: Long, status: String = PendingWriteEntity.STATUS_PENDING)

    /**
     * ALLE Queue-Einträge einer Seite entfernen (jeden Status). Bei Konfliktauflösung
     * verwenden, damit keine verwaisten conflict/locked/failed-Zeilen liegen bleiben,
     * die sonst [latestForPage] belegen und den gelösten Konflikt maskieren würden.
     */
    @Query("DELETE FROM pending_writes WHERE pageId = :pageId")
    suspend fun deleteAllForPage(pageId: Long)
}

@Dao
interface TreeCacheDao {
    @Query("SELECT * FROM tree_cache WHERE bookId = :bookId")
    suspend fun byBook(bookId: Long): TreeCacheEntity?

    @Upsert
    suspend fun upsert(entry: TreeCacheEntity)

    /** Gliederungen von Büchern entfernen, die der Server nicht mehr liefert. */
    @Query("DELETE FROM tree_cache WHERE bookId NOT IN (:keepIds)")
    suspend fun deleteMissing(keepIds: List<Long>)
}

@Dao
interface ResearchOutboxDao {
    @Insert
    suspend fun insert(row: ResearchOutboxEntity): Long

    @Query("SELECT * FROM research_outbox WHERE localId = :localId")
    suspend fun byId(localId: Long): ResearchOutboxEntity?

    @Query("SELECT * FROM research_outbox WHERE status = 'pending' ORDER BY createdAt ASC, localId ASC")
    suspend fun pending(): List<ResearchOutboxEntity>

    @Query("SELECT * FROM research_outbox WHERE bookId = :bookId ORDER BY createdAt DESC, localId DESC")
    fun observeForBook(bookId: Long): Flow<List<ResearchOutboxEntity>>

    @Query("SELECT COUNT(*) FROM research_outbox WHERE status = 'pending'")
    suspend fun pendingCount(): Int

    @Query("UPDATE research_outbox SET status = :status, note = :note WHERE localId = :localId")
    suspend fun setStatus(localId: Long, status: String, note: String?)

    @Query("UPDATE research_outbox SET targetId = :targetId, step = :step WHERE localId = :localId")
    suspend fun advance(localId: Long, targetId: Long?, step: Int)

    @Query("UPDATE research_outbox SET payload = :payload, status = 'pending', note = NULL WHERE localId = :localId")
    suspend fun retryWith(localId: Long, payload: String)

    @Query("DELETE FROM research_outbox WHERE localId = :localId")
    suspend fun delete(localId: Long)
}

@Dao
interface PendingIdeaDao {
    @Insert
    suspend fun insert(idea: PendingIdeaEntity): Long

    @Query("SELECT * FROM pending_ideas WHERE status = :status ORDER BY createdAt ASC, localId ASC")
    suspend fun byStatus(status: String = PendingIdeaEntity.STATUS_PENDING): List<PendingIdeaEntity>

    @Query("SELECT * FROM pending_ideas WHERE localId = :localId")
    suspend fun byId(localId: Long): PendingIdeaEntity?

    @Query("SELECT * FROM pending_ideas WHERE bookId = :bookId ORDER BY createdAt DESC, localId DESC")
    fun observeForBook(bookId: Long): Flow<List<PendingIdeaEntity>>

    @Query("SELECT COUNT(*) FROM pending_ideas")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_ideas WHERE status = :status")
    suspend fun count(status: String = PendingIdeaEntity.STATUS_PENDING): Int

    /** Abgewiesenes Anlegen nochmals als Buch-Idee versuchen (Anker fällt). */
    @Query("UPDATE pending_ideas SET pageId = NULL, chapterId = NULL WHERE localId = :localId")
    suspend fun dropAnchor(localId: Long)

    @Query("UPDATE pending_ideas SET status = :status, error = :error WHERE localId = :localId")
    suspend fun setStatus(localId: Long, status: String, error: String?)

    @Query("UPDATE pending_ideas SET content = :content WHERE localId = :localId")
    suspend fun setContent(localId: Long, content: String)

    @Query("DELETE FROM pending_ideas WHERE localId = :localId")
    suspend fun delete(localId: Long)
}

@Dao
interface PendingIdeaChangeDao {
    @Upsert
    suspend fun upsert(change: PendingIdeaChangeEntity): Long

    @Query("SELECT * FROM pending_idea_changes WHERE ideeId = :ideeId")
    suspend fun forIdee(ideeId: Long): PendingIdeaChangeEntity?

    @Query("SELECT * FROM pending_idea_changes WHERE status = 'pending' ORDER BY createdAt ASC, localId ASC")
    suspend fun pending(): List<PendingIdeaChangeEntity>

    @Query("SELECT * FROM pending_idea_changes WHERE bookId = :bookId")
    fun observeForBook(bookId: Long): Flow<List<PendingIdeaChangeEntity>>

    @Query("SELECT COUNT(*) FROM pending_idea_changes WHERE status = 'pending'")
    suspend fun pendingCount(): Int

    @Query("UPDATE pending_idea_changes SET status = :status, error = :error WHERE localId = :localId")
    suspend fun setStatus(localId: Long, status: String, error: String?)

    /**
     * Nur löschen/markieren, wenn die Zeile noch dem Gesendeten entspricht — wurde
     * währenddessen eine weitere Änderung hineingeführt, bleibt sie für den nächsten Lauf.
     */
    @Query("DELETE FROM pending_idea_changes WHERE localId = :localId AND patch = :patch AND remove = :remove")
    suspend fun deleteIfUnchanged(localId: Long, patch: String, remove: Boolean): Int

    @Query("UPDATE pending_idea_changes SET status = :status, error = :error WHERE localId = :localId AND patch = :patch AND remove = :remove")
    suspend fun setStatusIfUnchanged(localId: Long, patch: String, remove: Boolean, status: String, error: String?): Int

    @Query("DELETE FROM pending_idea_changes WHERE localId = :localId")
    suspend fun delete(localId: Long)
}

@Dao
interface IdeenCacheDao {
    @Query("SELECT * FROM ideen_cache WHERE bookId = :bookId")
    suspend fun byBook(bookId: Long): IdeenCacheEntity?

    @Query("SELECT * FROM ideen_cache WHERE bookId = :bookId")
    fun observe(bookId: Long): Flow<IdeenCacheEntity?>

    @Upsert
    suspend fun upsert(entry: IdeenCacheEntity)
}
