package ch.schreibwerkstatt.mobile.data.net.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Bücher / Baum ───────────────────────────────────────────────────────────

@Serializable
data class BookDto(
    val id: Long,
    val name: String,
    val role: String? = null,
    val owner_email: String? = null,
    val buchtyp: String? = null,
)

@Serializable
data class TreeDto(
    val chapters: List<ChapterNodeDto> = emptyList(),
    val topPages: List<TreePageDto> = emptyList(),
)

@Serializable
data class ChapterNodeDto(
    val id: Long,
    val name: String,
    val position: Int? = null,
    val parent_chapter_id: Long? = null,
    /** Verschachtelte Unterkapitel (Server-Schlüssel `subchapters`, gleiche Shape). */
    val subchapters: List<ChapterNodeDto> = emptyList(),
    /** Manche Tree-Builder hängen Seiten direkt unter den Kapitelknoten. */
    val pages: List<TreePageDto> = emptyList(),
)

@Serializable
data class TreePageDto(
    val id: Long,
    val name: String,
    val chapter_id: Long? = null,
)

// ── Seiten ──────────────────────────────────────────────────────────────────

@Serializable
data class PageDto(
    val id: Long,
    val name: String? = null,
    val chapter_id: Long? = null,
    val html: String? = null,
    val updated_at: String? = null,
)

/**
 * Neue Seite anlegen (`POST content/pages`). Mindestens eines von `book_id`/
 * `chapter_id` muss gesetzt sein; `name` ist Pflicht. Für Tagebuch-Einträge ist
 * `name` der ISO-Tag `YYYY-MM-DD`. Antwort = [PageDto] (Extra-Felder ignoriert).
 */
@Serializable
data class CreatePageRequest(
    val book_id: Long? = null,
    val chapter_id: Long? = null,
    val name: String,
    val html: String? = null,
)

/**
 * Neues Kapitel anlegen (`POST content/chapters`). `book_id` + `name` Pflicht;
 * `parent_chapter_id` für verschachtelte (Monats-)Kapitel, `position` für die
 * Sortierung (Jahr = Jahrzahl, Monat = 1–12). Antwort = [ChapterNodeDto].
 */
@Serializable
data class CreateChapterRequest(
    val book_id: Long,
    val name: String,
    val position: Int? = null,
    val parent_chapter_id: Long? = null,
)

@Serializable
data class SavePageRequest(
    val html: String,
    val device_id: String,
    val source: String = "main",
    /**
     * Optimistic-Concurrency-Guard: zuletzt bekannter Server-`updated_at`, auf dem
     * dieser Edit basiert. Stimmt der DB-Stand nicht überein → Server liefert 409
     * PAGE_CONFLICT statt last-write-wins. null = kein Guard (z.B. unbekannte Basis).
     */
    val expected_updated_at: String? = null,
)

/** 409 PAGE_CONFLICT-Body. */
@Serializable
data class PageConflictDto(
    val error_code: String? = null,
    val server_updated_at: String? = null,
    val server_editor_email: String? = null,
    val server_editor_name: String? = null,
)

/** 423 PAGE_LOCKED-Body. */
@Serializable
data class PageLockedDto(
    val error_code: String? = null,
    val locked_by_email: String? = null,
    val expires_at: String? = null,
)

// ── Seiten-Versionen (Revisions) ─────────────────────────────────────────────

/**
 * Eine gespeicherte Seitenversion. In der Listen-Antik kommt sie ohne
 * `body_html` (nur Metadaten); im Detail-Abruf ist `body_html` gesetzt.
 */
@Serializable
data class RevisionDto(
    val id: Long,
    val page_id: Long? = null,
    val book_id: Long? = null,
    val chars: Int? = null,
    val words: Int? = null,
    val tok: Int? = null,
    /** Herkunft der Version (`focus`/`main`/`chat-apply`/`macapp`/…). */
    val source: String? = null,
    val user_email: String? = null,
    /** Browser-/OS-Kennung bzw. nativer Client-Label. */
    val client: String? = null,
    val created_at: String? = null,
    val summary: String? = null,
    /** Voller HTML-Stand – nur in der Detail-Antwort gesetzt. */
    val body_html: String? = null,
)

@Serializable
data class RevisionListResponse(
    val revisions: List<RevisionDto> = emptyList(),
)

@Serializable
data class RevisionDetailResponse(
    val revision: RevisionDto? = null,
)

/** Antwort auf das Zurücksetzen (`…/restore`). */
@Serializable
data class RestoreResponse(
    val ok: Boolean = false,
    val restored_from: Long? = null,
)

// ── Delta-Sync ──────────────────────────────────────────────────────────────

@Serializable
data class SyncResponse(
    val now: String,
    val pages: List<SyncPageDto> = emptyList(),
    val has_more: Boolean = false,
    val cursor: SyncCursorDto? = null,
    /** Seit `since` gelöschte Seiten (leer beim Voll-Pull ohne `since`). */
    val deleted: List<SyncDeletedDto> = emptyList(),
    /** `deleted` ist gedeckelt → vollständiger Abgleich nur über `…/tree`. */
    val deleted_has_more: Boolean = false,
)

@Serializable
data class SyncDeletedDto(
    val page_id: Long,
    val page_name: String? = null,
    val deleted_at: String? = null,
)

@Serializable
data class SyncPageDto(
    val page_id: Long,
    val page_name: String? = null,
    val chapter_id: Long? = null,
    val updated_at: String? = null,
    val html: String? = null,
)

@Serializable
data class SyncCursorDto(
    val since: String? = null,
    val since_id: Long? = null,
)

// ── Recherche (buchweites Wissensboard, `routes/research.js`) ─────────────────

/** Eine URL eines Recherche-Items. `url_id` nur in Server-Antworten gesetzt. */
@Serializable
data class ResearchUrlDto(
    val url_id: Long? = null,
    val url: String,
    val label: String = "",
)

/**
 * Ein Recherche-Schnipsel. `pinned`/`archived` kommen als 0/1-Integer aus SQLite
 * (nicht als JSON-Boolean) → bewusst `Int`. Bild-/Dokument-Blobs werden hier nur
 * als `has_image`/`has_doc`-Flags reflektiert; die App lädt/erzeugt sie (noch) nicht.
 */
@Serializable
data class ResearchItemDto(
    val id: Long,
    val book_id: Long? = null,
    val kind: String = "note",
    val title: String? = null,
    val body: String? = null,
    val source: String? = null,
    val pinned: Int = 0,
    val archived: Int = 0,
    val created_at: String? = null,
    val updated_at: String? = null,
    val tags: List<String> = emptyList(),
    val urls: List<ResearchUrlDto> = emptyList(),
    val has_image: Boolean = false,
    val has_doc: Boolean = false,
    val doc_name: String? = null,
)

/**
 * Neues Recherche-Item anlegen (`POST research`). `book_id` Pflicht; mindestens
 * eines von `title`/`body`/http(s)-`urls` muss gesetzt sein (sonst 400 `EMPTY`).
 * Für einen geteilten Link: `kind="link"`, URL in [urls], geteilter Titel/Text in
 * [title]/[body]. Antwort = [ResearchItemDto].
 */
@Serializable
data class CreateResearchRequest(
    val book_id: Long,
    val kind: String = "link",
    val title: String? = null,
    val body: String? = null,
    val source: String? = null,
    val urls: List<ResearchUrlDto> = emptyList(),
    val tags: List<String> = emptyList(),
)

// ── /config (nur STT-/LanguageTool-relevanter Teil) ─────────────────────────

@Serializable
data class ConfigDto(
    val stt: SttConfigDto? = null,
    val languagetool: LanguageToolConfigDto? = null,
)

/**
 * LanguageTool-Verfügbarkeit (`routes/proxies.js`). Der Server exponiert bewusst
 * nur das Existenz-Flag + die Tipp-Pause — die LT-URL verlässt ihn nie.
 */
@Serializable
data class LanguageToolConfigDto(
    val enabled: Boolean = false,
    val debounceMs: Long = 1500,
)

@Serializable
data class SttConfigDto(
    val enabled: Boolean = false,
    val provider: String? = null,
    val vad: SttVadDto? = null,
)

@Serializable
data class SttVadDto(
    val silenceMs: Long = 800,
    val threshold: Double = 0.015,
    val maxSegmentS: Int = 30,
)

// ── STT ─────────────────────────────────────────────────────────────────────

@Serializable
data class TranscribeResponse(
    val text: String = "",
)

/** Generischer Fehler-Body ({ error_code } oder { error }). */
@Serializable
data class ApiErrorDto(
    val error_code: String? = null,
    val error: String? = null,
    val detail: String? = null,
) {
    val code: String? get() = error_code ?: error
}
