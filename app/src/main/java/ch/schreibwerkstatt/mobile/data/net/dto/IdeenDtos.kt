package ch.schreibwerkstatt.mobile.data.net.dto

import kotlinx.serialization.Serializable

// ── Ideen (`routes/ideen.js`) ───────────────────────────────────────────────
// Eigene Datei statt Dtos.kt: das Ideen-Board ist ein abgeschlossener Bereich des
// Vertrags (eigene Route, eigene Fixtures), kein Buchinhalt.

/**
 * Eine Idee, wie `POST /ideen`, `PATCH /ideen/:id` und `GET /ideen/board` sie liefern
 * (`db/ideen.js#SELECT_ROW`). Ideen sind user-privat. Anker: höchstens EINER von
 * [page_id]/[chapter_id]; beide null = Buch-Idee (Einfall ohne Ort im Text).
 */
@Serializable
data class IdeeDto(
    val id: Long,
    val book_id: Long,
    val page_id: Long? = null,
    val page_name: String? = null,
    val chapter_id: Long? = null,
    val chapter_name: String? = null,
    /** Kapitel, in dessen Bahn die Idee steht (eigenes oder das des Abschnitts). */
    val lane_chapter_id: Long? = null,
    val lane_chapter_name: String? = null,
    val content: String = "",
    /** `offen` · `in_arbeit` · `erledigt` · `verworfen` (`lib/ideen-status.js`). */
    val status: String = STATUS_OFFEN,
    val status_at: String? = null,
    val sort_order: Int = 0,
    val created_at: String? = null,
    val updated_at: String? = null,
    /** Hinweis des Auto-Erledigt-Checks, null wenn keiner vorliegt. */
    val check_hint: IdeeCheckHintDto? = null,
    val links: List<IdeeLinkDto> = emptyList(),
) {
    val isOpen: Boolean get() = status == STATUS_OFFEN || status == STATUS_IN_ARBEIT

    companion object {
        const val STATUS_OFFEN = "offen"
        const val STATUS_IN_ARBEIT = "in_arbeit"
        const val STATUS_ERLEDIGT = "erledigt"
        const val STATUS_VERWORFEN = "verworfen"

        /** Server-Limit für [content] (`routes/ideen.js#MAX_LEN`). */
        const val MAX_CONTENT = 4000
    }
}

@Serializable
data class IdeeCheckHintDto(
    val beleg: String? = null,
    val page_id: Long? = null,
    val page_name: String? = null,
    val begruendung: String? = null,
    val at: String? = null,
)

/** Verknüpfung (Recherche-Fundstück / Plot-Beat / Motiv) — die App zeigt sie nur an. */
@Serializable
data class IdeeLinkDto(
    val link_id: Long? = null,
    val target_kind: String? = null,
    val target_id: Long? = null,
    val label: String? = null,
)

/** `GET /ideen/board?book_id=` — alle Ideen des Buchs, ungefiltert. */
@Serializable
data class IdeenBoardDto(
    /** Alle bekannten Stufen in Reihenfolge. */
    val statuses: List<String> = emptyList(),
    /** Im Buch aktive Stufen (`offen` und `erledigt` immer dabei). */
    val stages: List<String> = emptyList(),
    val ideen: List<IdeeDto> = emptyList(),
    val auto_check: Boolean = false,
)

/**
 * `POST /ideen`. Ohne [page_id]/[chapter_id] entsteht eine Buch-Idee. Geht mit
 * `Idempotency-Key`-Header raus (Offline-Queue, siehe IdeenRepository).
 * `book_id` und `content` haben bewusst keinen Default (ApiJson schreibt keine Defaults).
 */
@Serializable
data class CreateIdeeRequest(
    val book_id: Long,
    val content: String,
    val page_id: Long? = null,
    val chapter_id: Long? = null,
)

/** `DELETE /ideen/:id` → `{ ok: true }`. */
@Serializable
data class IdeeDeleteResponse(val ok: Boolean = false)
