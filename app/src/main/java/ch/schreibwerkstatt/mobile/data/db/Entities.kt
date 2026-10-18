package ch.schreibwerkstatt.mobile.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val role: String? = null,
    val ownerEmail: String? = null,
    val buchtyp: String? = null,
)

@Entity(
    tableName = "pages",
    indices = [Index("bookId"), Index("chapterId")],
)
data class PageEntity(
    @PrimaryKey val id: Long,
    val bookId: Long,
    val chapterId: Long? = null,
    val name: String? = null,
    val html: String? = null,
    /** Aus [html] gestrippter Klartext für die Volltextsuche (FTS-Index + Snippets). */
    val plain: String? = null,
    /** Server-Stand (ISO-8601 Z). Für Konflikt-Vergleich. */
    val updatedAt: String? = null,
    /** true = lokale, noch nicht bestätigte Änderung (Pending-Write existiert). */
    val dirty: Boolean = false,
)

/**
 * FTS4-Spiegel von [PageEntity] über den Klartext ([PageEntity.plain]) für die
 * lokale Volltextsuche im Seiteninhalt. External-Content-Tabelle: Room hält sie
 * per generierter Trigger automatisch mit `pages` synchron — daher nie direkt
 * beschreiben, nur `pages` mutieren. `rowid` == `pages.id`.
 */
@Fts4(contentEntity = PageEntity::class)
@Entity(tableName = "pages_fts")
data class PageFtsEntity(
    val plain: String? = null,
)

/** Delta-Sync-Cursor pro Buch. Erstaufruf ohne Cursor = Baseline. */
@Entity(tableName = "sync_cursors")
data class SyncCursorEntity(
    @PrimaryKey val bookId: Long,
    val since: String? = null,
    val sinceId: Long? = null,
    val lastSyncedAt: String? = null,
)

/**
 * Offline-Schreib-Queue. Bei Verbindung seriell geflusht (PUT /content/pages/:id);
 * 409/423 setzen [status] entsprechend.
 */
@Entity(
    tableName = "pending_writes",
    indices = [Index("pageId")],
)
data class PendingWriteEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val pageId: Long,
    val bookId: Long,
    val html: String,
    val deviceId: String,
    val createdAt: Long,
    /**
     * Server-`updated_at`-Snapshot, auf dem dieser Edit basierte (aus der Page-Row
     * beim Queuen). Geht als `expected_updated_at` an den Server, damit ein
     * verzögerter Offline-Flush einen zwischenzeitlichen Fremd-Save als 409 erkennt
     * statt blind zu überschreiben. null = keine bekannte Basis → kein Guard.
     */
    val baseUpdatedAt: String? = null,
    val status: String = STATUS_PENDING,
    /** Letzte Fehlermeldung / Konfliktinfo (z.B. server_editor_name). */
    val note: String? = null,
    /**
     * Save-Kennung (UUID v4) dieses Writes, geht als `save_id` an den Server. Bleibt
     * über Retries gleich: Ging eine Antwort verloren, erkennt der Server die
     * Wiederholung (`save_match: "replay"`) statt sie als Konflikt abzulehnen.
     */
    @ColumnInfo(defaultValue = "''")
    val saveId: String = UUID.randomUUID().toString(),
    /**
     * Kennungen früherer, unbeantworteter Writes DIESER Queue für diesen Abschnitt seit
     * [baseUpdatedAt], älteste zuerst (`own_save_ids`). Nie Kennungen anderer Geräte
     * oder Abschnitte — sonst überschriebe ein Flush still einen fremden Stand.
     */
    @ColumnInfo(defaultValue = "'[]'")
    val ownSaveIds: List<String> = emptyList(),
    /**
     * Zeitpunkt des ersten Sendeversuchs, gesetzt BEVOR der Request rausgeht. null =
     * nie gesendet oder vom Server per 4xx abgewiesen — dann kann [saveId] nicht der
     * Server-Stand sein und wird beim Ersetzen nicht in [ownSaveIds] übernommen.
     */
    val sentAt: Long? = null,
) {
    /** Kennungen, die ein diesen Write ersetzender Nachfolger als `own_save_ids` erbt. */
    fun saveIdsForSuccessor(): List<String> =
        (if (sentAt != null) ownSaveIds + saveId else ownSaveIds).takeLast(MAX_OWN_SAVE_IDS)

    companion object {
        /** Server-Obergrenze für `own_save_ids`. */
        const val MAX_OWN_SAVE_IDS = 16

        const val STATUS_PENDING = "pending"
        const val STATUS_CONFLICT = "conflict"   // 409
        const val STATUS_LOCKED = "locked"       // 423
        const val STATUS_FAILED = "failed"
    }
}
