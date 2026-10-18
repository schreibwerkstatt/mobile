package ch.schreibwerkstatt.mobile.data.repo

import ch.schreibwerkstatt.mobile.data.db.PageEntity

/** Ergebnis eines Speicherversuchs (PUT /content/pages/:id). */
sealed interface SaveResult {
    /**
     * Server-Bestätigung; lokaler Stand = Server-Stand. [merged] = der Server hat gegen
     * einen fremden Stand gemergt und der Cache hat den gemergten Stand übernommen
     * ([page] enthält fremde Änderungen, die ein offener Editor noch nicht zeigt).
     */
    data class Saved(val page: PageEntity, val merged: Boolean = false) : SaveResult

    /** Kein Netz/Fehler: lokal gespeichert, Pending-Write liegt in der Queue. */
    data object Queued : SaveResult

    /** 409 — Server hat eine neuere Version. */
    data class Conflict(
        val serverUpdatedAt: String?,
        val serverEditorName: String?,
    ) : SaveResult

    /** 423 — Seite durch Lektorat-Lock fremd gesperrt. */
    data class Locked(
        val lockedByEmail: String?,
        val expiresAt: String?,
    ) : SaveResult
}

/** Beide Fassungen eines Seiten-Konflikts als Klartext für die Vergleichsansicht. */
data class ConflictPreview(
    /** Lokale, noch nicht durchgesetzte Änderung. */
    val local: String,
    /** Aktueller Server-Stand. */
    val server: String,
)
