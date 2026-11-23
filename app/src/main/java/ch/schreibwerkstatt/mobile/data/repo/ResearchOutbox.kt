package ch.schreibwerkstatt.mobile.data.repo

import ch.schreibwerkstatt.mobile.data.db.AppDatabase
import ch.schreibwerkstatt.mobile.data.db.ResearchOutboxEntity
import ch.schreibwerkstatt.mobile.data.net.ApiJson
import ch.schreibwerkstatt.mobile.data.net.NetworkClient
import ch.schreibwerkstatt.mobile.data.net.parseApiError
import ch.schreibwerkstatt.mobile.data.net.dto.CreateResearchRequest
import ch.schreibwerkstatt.mobile.data.net.dto.ResearchScrapeRequest
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

/** Vorgemerktes Recherche-Erfassen: der Anlege-Request plus „Text nachladen?". */
@Serializable
data class ResearchCaptureOp(
    val request: CreateResearchRequest,
    /** Link ohne eigenen Text → nach dem Anlegen `POST research/{id}/scrape`. */
    val scrape: Boolean = false,
)

/**
 * Outbox fürs Recherche-Erfassen ([ResearchOutboxEntity]): jedes neue Fundstück wird
 * zuerst lokal vorgemerkt und dann seriell gesendet — sofort, bei wiederkehrender
 * Verbindung ([SyncCoordinator]) und im Hintergrund
 * ([ch.schreibwerkstatt.mobile.sync.CaptureFlushWorker]).
 *
 * Regeln:
 * - **Seriell, unter einem Mutex**; Folgeschritte (Bild, Text nachladen) laufen erst,
 *   wenn das Fundstück angelegt ist.
 * - **Netzfehler, 408/429/5xx** stoppen den Lauf, die Zeile bleibt (nächster Lauf).
 *   **401** stoppt ebenfalls (der AuthInterceptor hat das Token verworfen).
 * - **`409 DUPLICATE_URL`** → [ResearchOutboxEntity.STATUS_DUPLICATE]: die UI fragt
 *   «trotzdem speichern?» (`allow_duplicate`) oder «verwerfen».
 * - **Andere 4xx** → [ResearchOutboxEntity.STATUS_FAILED] mit `error_code`; der Lauf geht weiter.
 * - **Text nachladen ist Beiwerk:** jede Server-Antwort ausser 401/503 beendet die Zeile.
 *
 * `POST research` kennt (anders als `POST ideen`) keinen `Idempotency-Key`: geht die
 * Antwort verloren, legt die Wiederholung ein zweites Fundstück an — bei Links fängt
 * das der Server als `409 DUPLICATE_URL` ab, die UI fragt dann nach.
 */
class ResearchOutbox(
    private val db: AppDatabase,
    private val net: NetworkClient,
    private val settings: SettingsStore,
    private val scheduleBackground: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao get() = db.researchOutboxDao()
    private val mutex = Mutex()
    private val json = ApiJson

    enum class FlushStop { DONE, OFFLINE, UNAUTHORIZED, NO_SERVER }

    /** Zeile vormerken und die Hintergrund-Zustellung anmelden. Sendet nicht selbst. */
    suspend fun enqueue(row: ResearchOutboxEntity): Long {
        val id = dao.insert(row.copy(createdAt = if (row.createdAt > 0) row.createdAt else now()))
        scheduleBackground()
        return id
    }

    suspend fun hasPending(): Boolean = dao.pendingCount() > 0

    /** Alle wartenden Zeilen senden, bis die Warteschlange leer ist oder ein Stopp eintritt. */
    suspend fun flush(): FlushStop = mutex.withLock {
        val base = settings.serverBaseUrlOnce() ?: return@withLock FlushStop.NO_SERVER
        var stop: FlushStop? = null
        while (stop == null) {
            val row = dao.pending().firstOrNull() ?: break
            val step = try {
                send(base, row)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Step.Offline
            } catch (e: SerializationException) {
                // Unlesbare Antwort (z.B. Captive Portal) oder kaputte Zeile: später erneut,
                // eine kaputte Zeile aber nicht endlos.
                if (row.step == ResearchOutboxEntity.STEP_CREATE && !isDecodable(row)) {
                    dao.setStatus(row.localId, ResearchOutboxEntity.STATUS_FAILED, "BAD_PAYLOAD")
                    Step.Next
                } else {
                    Step.Offline
                }
            }
            stop = when (step) {
                Step.Next -> null
                Step.Offline -> FlushStop.OFFLINE
                Step.Unauthorized -> FlushStop.UNAUTHORIZED
            }
        }
        stop ?: FlushStop.DONE
    }

    /** Zeile samt lokaler Bildkopie verwerfen. */
    suspend fun discard(localId: Long) {
        val row = dao.byId(localId) ?: return
        dao.delete(localId)
        row.imagePath?.let { withContext(Dispatchers.IO) { File(it).delete() } }
    }

    private enum class Step { Next, Offline, Unauthorized }

    private fun isDecodable(row: ResearchOutboxEntity): Boolean =
        runCatching { json.decodeFromString(ResearchCaptureOp.serializer(), row.payload) }.isSuccess

    private suspend fun send(base: String, row: ResearchOutboxEntity): Step {
        val op = json.decodeFromString(ResearchCaptureOp.serializer(), row.payload)
        val api = net.research(base)
        return when (row.step) {
            ResearchOutboxEntity.STEP_CREATE -> {
                val resp = api.create(op.request)
                val item = resp.body()
                if (resp.isSuccessful && item != null) {
                    next(row, op, item.id, after = ResearchOutboxEntity.STEP_CREATE)
                } else {
                    val err = parseApiError(resp.errorBody())
                    if (resp.code() == 409 && err?.code == "DUPLICATE_URL") {
                        val title = (err.params?.get("title") as? JsonPrimitive)?.contentOrNull
                        dao.setStatus(row.localId, ResearchOutboxEntity.STATUS_DUPLICATE, title?.takeIf { it.isNotBlank() })
                        Step.Next
                    } else {
                        rejected(row, resp.code(), err?.code)
                    }
                }
            }
            ResearchOutboxEntity.STEP_IMAGE -> {
                val id = row.targetId ?: return finish(row)
                val file = row.imagePath?.let(::File)?.takeIf { it.exists() }
                    ?: return next(row, op, id, after = ResearchOutboxEntity.STEP_IMAGE)
                val body = file.asRequestBody((row.imageMime ?: "image/jpeg").toMediaTypeOrNull())
                val resp = api.uploadImage(id, body)
                if (resp.isSuccessful) {
                    next(row, op, id, after = ResearchOutboxEntity.STEP_IMAGE)
                } else {
                    val code = parseApiError(resp.errorBody())?.code
                        ?: if (resp.code() == 413) "IMAGE_TOO_LARGE" else null
                    rejected(row, resp.code(), code)
                }
            }
            else -> {
                val id = row.targetId ?: return finish(row)
                val resp = api.scrape(id, ResearchScrapeRequest())
                when (resp.code()) {
                    401 -> Step.Unauthorized
                    503 -> Step.Offline
                    else -> finish(row)
                }
            }
        }
    }

    private suspend fun next(row: ResearchOutboxEntity, op: ResearchCaptureOp, itemId: Long, after: Int): Step {
        val next = when {
            after < ResearchOutboxEntity.STEP_IMAGE && row.imagePath != null -> ResearchOutboxEntity.STEP_IMAGE
            after < ResearchOutboxEntity.STEP_SCRAPE && op.scrape -> ResearchOutboxEntity.STEP_SCRAPE
            else -> null
        } ?: return finish(row)
        dao.advance(row.localId, itemId, next)
        return Step.Next
    }

    private suspend fun rejected(row: ResearchOutboxEntity, status: Int, code: String?): Step = when {
        status == 401 -> Step.Unauthorized
        status == 408 || status == 429 || status >= 500 -> Step.Offline
        else -> {
            dao.setStatus(row.localId, ResearchOutboxEntity.STATUS_FAILED, code ?: "HTTP $status")
            Step.Next
        }
    }

    private suspend fun finish(row: ResearchOutboxEntity): Step {
        dao.delete(row.localId)
        row.imagePath?.let { withContext(Dispatchers.IO) { File(it).delete() } }
        return Step.Next
    }
}
