package ch.schreibwerkstatt.mobile.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.VisibleForTesting
import ch.schreibwerkstatt.mobile.data.net.NetworkClient
import ch.schreibwerkstatt.mobile.data.net.dto.ApiErrorDto
import ch.schreibwerkstatt.mobile.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Diktat-Aufnahme (MediaRecorder → AAC/MP4) + Transkription über den STT-Proxy.
 *
 * Aufnahme als `audio/mp4` (AAC) — in der Server-Whitelist und ohne Multipart
 * direkt als Roh-Bytes POSTbar. Segment-Grösse < 5 MB (Server-Limit).
 *
 * Stille-Erkennung (einfaches VAD): [currentAmplitude] liefert die Spitzen-
 * amplitude seit dem letzten Aufruf (MediaRecorder.getMaxAmplitude). Das
 * [EditorViewModel] pollt sie und beendet das Segment automatisch nach einer
 * Sprechpause bzw. an einer Maximaldauer (Schutz vor dem 5-MB-Limit).
 */
class DictationController(
    private val context: Context,
    private val net: NetworkClient,
    private val settings: SettingsStore,
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recorder != null

    private val mime = "audio/mp4"

    /**
     * Spitzen-Amplitude (0..32767) seit dem letzten Aufruf. 0, wenn keine
     * Aufnahme läuft. Grundlage der Stille-Erkennung im VM.
     */
    fun currentAmplitude(): Int =
        recorder?.let { runCatching { it.maxAmplitude }.getOrDefault(0) } ?: 0

    fun startRecording(): Result<Unit> = runCatching {
        check(recorder == null) { "Aufnahme läuft bereits" }
        val file = File(context.cacheDir, "dictation_${System.currentTimeMillis()}.m4a")
        @Suppress("DEPRECATION")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        try {
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16_000)      // Whisper-freundlich
                setAudioEncodingBitRate(64_000)   // ~8 KB/s → bei 30 s deutlich < 5 MB
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {
            // Mikrofon belegt (z.B. Telefonat) o.ä.: Recorder + angelegte Datei freigeben,
            // sonst bleibt die native Instanz bis zum GC hängen.
            runCatching { rec.release() }
            runCatching { file.delete() }
            throw e
        }
        recorder = rec
        outputFile = file
    }

    /** Aufnahme stoppen und das Segment transkribieren. Liefert den erkannten Text. */
    suspend fun stopAndTranscribe(bookId: Long, pageId: Long): Result<String> {
        val file = stopRecorder() ?: return Result.failure(IllegalStateException("Keine Aufnahme"))
        if (file.length() == 0L) {
            // stop() direkt nach start() wirft und hinterlässt keine gültige Datei — nichts
            // senden (der Server meldete sonst irreführend „Audioformat nicht unterstützt").
            runCatching { file.delete() }
            return Result.success("")
        }
        return transcribe(file, bookId, pageId).also { runCatching { file.delete() } }
    }

    /** Aufnahme verwerfen (z.B. abgebrochen). */
    fun cancel() {
        stopRecorder()?.let { runCatching { it.delete() } }
    }

    private fun stopRecorder(): File? {
        val rec = recorder ?: return null
        recorder = null
        val stopped = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        val file = outputFile.also { outputFile = null }
        if (!stopped) {
            // Kein gültiges Segment (zu kurz/Fehler) → Datei verwerfen statt senden.
            file?.let { runCatching { it.writeBytes(ByteArray(0)) } }
        }
        return file
    }

    private suspend fun transcribe(file: File, bookId: Long, pageId: Long): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val baseUrl = settings.serverBaseUrlOnce() ?: error("Keine Server-URL")
                val bytes = file.readBytes()
                require(!segmentTooLarge(bytes.size)) { "stt_audio_too_large" }
                val body = bytes.toRequestBody(mime.toMediaType())
                val resp = net.stt(baseUrl).transcribe(bookId, pageId, body)
                if (resp.isSuccessful) {
                    resp.body()?.text.orEmpty()
                } else {
                    val err = resp.errorBody()?.string()?.takeIf { it.isNotBlank() }?.let {
                        runCatching { net.jsonParser.decodeFromString(ApiErrorDto.serializer(), it) }.getOrNull()
                    }
                    error(sttErrorMessage(resp.code(), err?.code))
                }
            }
        }

    private fun sttErrorMessage(httpCode: Int, code: String?): String =
        Companion.sttErrorMessage(httpCode, code)

    companion object {
        /** Server-Limit pro Audiosegment (siehe routes/stt.js). */
        const val MAX_SEGMENT_BYTES: Int = 5 * 1024 * 1024

        /**
         * true, wenn ein Segment das Server-Limit überschreitet und gar nicht erst
         * gesendet werden darf. Genau am Limit ist noch erlaubt.
         */
        @VisibleForTesting
        fun segmentTooLarge(sizeBytes: Int): Boolean = sizeBytes > MAX_SEGMENT_BYTES

        @VisibleForTesting
        fun sttErrorMessage(httpCode: Int, code: String?): String = when (code ?: httpCode.toString()) {
            "stt_disabled", "404" -> "Diktat ist auf dem Server deaktiviert."
            "stt_audio_too_large", "413" -> "Audiosegment zu gross (max 5 MB)."
            "stt_unsupported_audio", "415" -> "Audioformat nicht unterstützt."
            "stt_timeout", "408" -> "Transkription hat zu lange gedauert."
            "stt_upstream", "502" -> "STT-Dienst nicht erreichbar."
            else -> "Transkription fehlgeschlagen (HTTP $httpCode)."
        }
    }
}
