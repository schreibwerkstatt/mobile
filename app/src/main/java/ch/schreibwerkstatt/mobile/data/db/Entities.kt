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
    /**
     * HTML, auf dem der lokale Stand aufbaut — gehört zu [updatedAt] und geht beim
     * Queuen als `base_html` in den Pending-Write. Normalerweise der Server-Stand zu
     * [updatedAt]; nach einem Server-Merge bewusst das zuletzt Gesendete (siehe
     * ContentRepository.flushOne). null = unbekannt → kein Server-Merge, 409 wie ohne.
     */
    val baseHtml: String? = null,
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
    /**
     * HTML zu [baseUpdatedAt] (`base_html`): Mit veraltetem Stempel mergt der Server
     * damit selbst blockweise, statt mit 409 abzulehnen. Wird immer zusammen mit
     * [baseUpdatedAt] umbasiert. null = unbekannt → kein Server-Merge.
     */
    val baseHtml: String? = null,
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

/**
 * Offline-Cache der Buch-Gliederung (`GET content/books/{id}/tree`): die letzte
 * erfolgreiche Antwort als JSON ([ch.schreibwerkstatt.mobile.data.net.ApiJson]),
 * eine Zeile pro Buch. Nur Anzeige-Cache — Abschnittsinhalte liegen in `pages`.
 */
@Entity(tableName = "tree_cache")
data class TreeCacheEntity(
    @PrimaryKey val bookId: Long,
    val json: String,
    /** Abrufzeitpunkt (epoch ms). */
    val fetchedAt: Long,
)

/**
 * Outbox fürs Recherche-Erfassen: jedes neue Fundstück wird zuerst hier vorgemerkt und
 * dann seriell gesendet ([ch.schreibwerkstatt.mobile.data.repo.ResearchOutbox]) — offline
 * bleibt es liegen, bis Verbindung da ist. Ein Fundstück hat bis zu drei Schritte:
 * anlegen, Bild hochladen, Text nachladen (Scrape); [targetId] trägt nach dem Anlegen
 * die Server-Id für die Folgeschritte.
 */
@Entity(
    tableName = "research_outbox",
    indices = [Index("bookId")],
)
data class ResearchOutboxEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val bookId: Long,
    /** JSON von [ch.schreibwerkstatt.mobile.data.repo.ResearchCaptureOp]. */
    val payload: String,
    /** Server-Id des Fundstücks, sobald angelegt. */
    val targetId: Long? = null,
    /** Lokale Kopie eines geteilten Bilds (App-Speicher), wird nach dem Senden gelöscht. */
    val imagePath: String? = null,
    val imageMime: String? = null,
    /** 0 = anlegen, 1 = Bild hochladen, 2 = Text nachladen. */
    val step: Int = STEP_CREATE,
    val status: String = STATUS_PENDING,
    /** `error_code` bzw. bei [STATUS_DUPLICATE] der Titel des bestehenden Fundstücks. */
    val note: String? = null,
    val createdAt: Long,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        /** `409 DUPLICATE_URL` — wartet auf «Trotzdem speichern»/«Verwerfen». */
        const val STATUS_DUPLICATE = "duplicate"
        /** Vom Server abgewiesen (4xx) — wartet auf «Erneut»/«Verwerfen». */
        const val STATUS_FAILED = "failed"

        const val STEP_CREATE = 0
        const val STEP_IMAGE = 1
        const val STEP_SCRAPE = 2
    }
}

/**
 * Offline vorgemerkte neue Idee (`POST /ideen`). Ideen sind kein Buchinhalt — diese
 * Queue ist unabhängig von [PendingWriteEntity] und wird vom IdeenRepository
 * abgespielt. [idempotencyKey] geht bei JEDEM Versuch als `Idempotency-Key` mit: eine
 * verlorene Antwort führt beim Nachspielen nicht zu einer doppelten Idee.
 *
 * Ein Einfall fällt nie still weg: weist der Server das Anlegen ab, wird es einmal als
 * Buch-Idee (ohne Anker) wiederholt; scheitert auch das, bleibt die Zeile mit
 * [STATUS_STUCK] und Fehlercode liegen, bis der Nutzer sie erneut sendet, kopiert
 * oder verwirft.
 */
@Entity(
    tableName = "pending_ideas",
    indices = [Index("bookId")],
)
data class PendingIdeaEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val bookId: Long,
    val content: String,
    val pageId: Long? = null,
    val chapterId: Long? = null,
    val idempotencyKey: String = UUID.randomUUID().toString(),
    val createdAt: Long,
    val status: String = STATUS_PENDING,
    /** Fehlercode der Abweisung (`error_code` bzw. `HTTP nnn`), nur bei [STATUS_STUCK]. */
    val error: String? = null,
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_STUCK = "stuck"
    }
}

/**
 * Offline vorgemerkte Änderung an einer schon angelegten Idee: `PATCH /ideen/:id`
 * ([patch] = JSON-Objekt der geänderten Felder, inkl. explizitem `null` für „zurück
 * zur Buch-Idee") oder `DELETE`. Höchstens eine Zeile je Idee: eine weitere Änderung
 * wird in [patch] zusammengeführt, ein Löschen ersetzt sie. [placeName] ist der
 * Anzeigename eines neuen Orts, bis der Server antwortet.
 */
@Entity(
    tableName = "pending_idea_changes",
    indices = [Index("bookId"), Index(value = ["ideeId"], unique = true)],
)
data class PendingIdeaChangeEntity(
    @PrimaryKey(autoGenerate = true) val localId: Long = 0,
    val bookId: Long,
    val ideeId: Long,
    /** true = `DELETE` statt `PATCH`. */
    val remove: Boolean = false,
    val patch: String = "{}",
    val placeName: String? = null,
    val createdAt: Long,
    val status: String = PendingIdeaEntity.STATUS_PENDING,
    val error: String? = null,
)

/**
 * Offline-Cache des Ideen-Boards (`GET ideen/board`): die letzte Antwort als JSON
 * ([ch.schreibwerkstatt.mobile.data.net.ApiJson]), eine Zeile pro Buch. Bestätigte
 * Anlagen/Änderungen patchen ihn direkt, damit das Board ohne Neuladen stimmt.
 */
@Entity(tableName = "ideen_cache")
data class IdeenCacheEntity(
    @PrimaryKey val bookId: Long,
    val json: String,
    /** Abrufzeitpunkt (epoch ms). */
    val fetchedAt: Long,
)
