package ch.schreibwerkstatt.mobile.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import ch.schreibwerkstatt.mobile.data.db.AppDatabase
import ch.schreibwerkstatt.mobile.data.db.ResearchOutboxEntity
import ch.schreibwerkstatt.mobile.data.net.ApiJson
import ch.schreibwerkstatt.mobile.data.net.NetworkClient
import ch.schreibwerkstatt.mobile.data.net.withApiErrors
import ch.schreibwerkstatt.mobile.data.net.dto.CreateResearchRequest
import ch.schreibwerkstatt.mobile.data.net.dto.ResearchItemDto
import ch.schreibwerkstatt.mobile.data.net.dto.ResearchUrlDto
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Eingaben des Erfassen-Formulars. [url] ist bereits normalisiert und geprüft (oder leer). */
data class ResearchDraft(
    val bookId: Long,
    val url: String,
    val title: String,
    val note: String,
    val imageUri: Uri? = null,
    val imageMime: String? = null,
)

/** Ausgang eines Erfassens nach dem ersten Sendeversuch. */
sealed interface CaptureOutcome {
    /** Angelegt (Bild/Text nachladen kann noch im Hintergrund laufen). */
    data object Saved : CaptureOutcome
    /** Offline vorgemerkt — wird bei Verbindung gesendet. */
    data object Queued : CaptureOutcome
    /** `409 DUPLICATE_URL`: Rückfrage «trotzdem speichern?» über [localId]. */
    data class Duplicate(val localId: Long, val existingTitle: String?) : CaptureOutcome
    /** Vom Server abgewiesen ([code] = `error_code`); die Zeile ist verworfen, das Formular bleibt. */
    data class Rejected(val code: String) : CaptureOutcome
}

/**
 * Zugriff auf das buchweite Recherche-/Wissensboard (`routes/research.js`).
 *
 * Bewusst **eigenständig** neben [ContentRepository]: Recherche-Items sind keine
 * Buchinhalte (Abschnitte/Kapitel) und laufen serverseitig nicht über die Content-
 * Store-Facade — die „ContentRepository = einziger Content-Eintrittspunkt"-Regel
 * betrifft sie nicht. Lesen ist online (kein Cache); **Erfassen läuft über die
 * [ResearchOutbox]**: offline vorgemerkt, Bild und „Text nachladen" als Folgeschritte.
 */
class ResearchRepository(
    private val net: NetworkClient,
    private val settings: SettingsStore,
    private val db: AppDatabase,
    private val outbox: ResearchOutbox,
    private val appContext: Context,
) {
    private suspend fun baseUrl(): String =
        settings.serverBaseUrlOnce() ?: error("Keine Server-URL konfiguriert")

    /** Recherche-Items eines Buchs (angeheftete zuerst, dann nach `updated_at`), höchstens [ch.schreibwerkstatt.mobile.data.net.ResearchApi.LIST_LIMIT]. */
    suspend fun list(bookId: Long): Result<List<ResearchItemDto>> = runCatching {
        withApiErrors { net.research(baseUrl()).list(bookId) }
    }

    /** Vorgemerkte Erfassungen dieses Buchs (wartend, Dublette, abgewiesen). */
    fun observeQueued(bookId: Long): Flow<List<QueuedResearch>> =
        db.researchOutboxDao().observeForBook(bookId).map { rows ->
            rows.mapNotNull { row ->
                val op = runCatching { ApiJson.decodeFromString(ResearchCaptureOp.serializer(), row.payload) }
                    .getOrNull() ?: return@mapNotNull null
                QueuedResearch(
                    localId = row.localId,
                    title = op.request.title,
                    url = op.request.urls.firstOrNull()?.url,
                    note = op.request.body,
                    hasImage = row.imagePath != null,
                    status = row.status,
                    serverNote = row.note,
                    created = row.step > ResearchOutboxEntity.STEP_CREATE,
                )
            }
        }

    /**
     * Erfassen: lokal vormerken (Bild als Kopie im App-Speicher — die Leseberechtigung
     * eines geteilten `content://` hält nicht bis zum Nachsenden), dann sofort senden.
     */
    suspend fun capture(draft: ResearchDraft): CaptureOutcome {
        val url = draft.url.trim().takeIf { it.isNotEmpty() }
        val note = draft.note.trim().takeIf { it.isNotEmpty() }
        val request = CreateResearchRequest(
            book_id = draft.bookId,
            // Ohne Link ist es eine Notiz; ein Bild macht der Server beim Upload zur Bild-Art.
            kind = if (url != null) "link" else "note",
            title = draft.title.trim().takeIf { it.isNotEmpty() },
            body = note,
            urls = url?.let { listOf(ResearchUrlDto(url = it)) } ?: emptyList(),
        )
        val image = draft.imageUri?.let { copyImage(it, draft.imageMime) }
        val localId = outbox.enqueue(
            ResearchOutboxEntity(
                bookId = draft.bookId,
                payload = ApiJson.encodeToString(
                    ResearchCaptureOp.serializer(),
                    ResearchCaptureOp(request, scrape = url != null && note == null),
                ),
                imagePath = image?.first,
                imageMime = image?.second,
                createdAt = 0,
            )
        )
        outbox.flush()
        return outcomeOf(localId)
    }

    /** Rückfrage zur Dublette: [keep] = trotzdem speichern (`allow_duplicate`), sonst verwerfen. */
    suspend fun resolveDuplicate(localId: Long, keep: Boolean): CaptureOutcome? {
        if (!keep) {
            outbox.discard(localId)
            return null
        }
        val row = db.researchOutboxDao().byId(localId) ?: return CaptureOutcome.Saved
        val op = ApiJson.decodeFromString(ResearchCaptureOp.serializer(), row.payload)
        val forced = op.copy(request = op.request.copy(allow_duplicate = true))
        db.researchOutboxDao().retryWith(localId, ApiJson.encodeToString(ResearchCaptureOp.serializer(), forced))
        outbox.flush()
        return outcomeOf(localId)
    }

    /** Vorgemerkte Erfassung verwerfen (samt Bildkopie). */
    suspend fun discard(localId: Long) = outbox.discard(localId)

    /** Abgewiesene Zeile erneut versuchen (z.B. nach vorübergehendem Rechteproblem). */
    suspend fun retry(localId: Long) {
        val row = db.researchOutboxDao().byId(localId) ?: return
        db.researchOutboxDao().retryWith(localId, row.payload)
        outbox.flush()
    }

    private suspend fun outcomeOf(localId: Long): CaptureOutcome {
        val row = db.researchOutboxDao().byId(localId) ?: return CaptureOutcome.Saved
        return when {
            row.status == ResearchOutboxEntity.STATUS_DUPLICATE -> CaptureOutcome.Duplicate(localId, row.note)
            row.status == ResearchOutboxEntity.STATUS_FAILED && row.step == ResearchOutboxEntity.STEP_CREATE -> {
                // Nicht angelegt: Zeile weg, das Formular zeigt den Fehler und bleibt offen.
                outbox.discard(localId)
                CaptureOutcome.Rejected(row.note ?: "")
            }
            // Angelegt, nur der Bild-Schritt scheiterte: die Liste zeigt die Zeile mit Hinweis.
            row.status == ResearchOutboxEntity.STATUS_FAILED -> CaptureOutcome.Saved
            row.step > ResearchOutboxEntity.STEP_CREATE -> CaptureOutcome.Saved
            else -> CaptureOutcome.Queued
        }
    }

    /** Bild in den App-Speicher kopieren; über [MAX_IMAGE_BYTES] verkleinert als JPEG. */
    private suspend fun copyImage(uri: Uri, mime: String?): Pair<String, String>? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(appContext.filesDir, MEDIA_DIR).apply { mkdirs() }
            val file = File(dir, UUID.randomUUID().toString())
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { input.copyTo(it) }
            } ?: return@runCatching null
            var type = mime?.takeIf { it.startsWith("image/") }
                ?: appContext.contentResolver.getType(uri)?.takeIf { it.startsWith("image/") }
                ?: "image/jpeg"
            if (file.length() > MAX_IMAGE_BYTES && downscale(file)) type = "image/jpeg"
            file.absolutePath to type
        }.getOrNull()
    }

    /** Auf höchstens [MAX_IMAGE_EDGE] px Kantenlänge verkleinern (Server-Limit 12 MB). */
    private fun downscale(file: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight).takeIf { it > 0 } ?: return false
        var sample = 1
        while (longest / (sample * 2) >= MAX_IMAGE_EDGE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return false
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bitmap.recycle()
        return true
    }

    private companion object {
        const val MEDIA_DIR = "outbox-media"
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        const val MAX_IMAGE_EDGE = 3000
    }
}

/** Anzeige einer vorgemerkten Erfassung in der Recherche-Liste. */
data class QueuedResearch(
    val localId: Long,
    val title: String?,
    val url: String?,
    val note: String?,
    val hasImage: Boolean,
    /** [ResearchOutboxEntity.STATUS_PENDING]/[ResearchOutboxEntity.STATUS_DUPLICATE]/[ResearchOutboxEntity.STATUS_FAILED]. */
    val status: String,
    /** `error_code` bzw. Titel des bestehenden Fundstücks (Dublette). */
    val serverNote: String?,
    /** Schon angelegt, es fehlen nur Bild/Text-Nachladen. */
    val created: Boolean,
)
