package ch.schreibwerkstatt.mobile.bundle

import android.content.Context
import android.util.Log
import ch.schreibwerkstatt.mobile.data.net.ApiJson
import ch.schreibwerkstatt.mobile.data.net.AuthInterceptor
import ch.schreibwerkstatt.mobile.data.net.dto.ApiErrorDto
import ch.schreibwerkstatt.mobile.data.net.dto.BundleManifestDto
import ch.schreibwerkstatt.mobile.data.prefs.TokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * Lädt das Focus-Editor-OTA-Bundle (`GET /content/editor-bundle.zip`) und
 * entpackt es ins App-internal-Storage. Nutzt `If-None-Match`/`ETag`: bei 304
 * wird nichts neu entpackt.
 *
 * Layout nach dem Entpacken (alles unter [bundleDir], an Origin-Root gemappt):
 *   js/editor/focus/standalone.js, css/…, icons.svg, bundle-manifest.json
 *   host.html, editor-host.css  ← aus den App-Assets hineinkopiert
 *     (Einstiegsseite der WebView + ihre app-eigenen Host-Styles)
 *
 * Der [WebViewAssetLoader] (siehe EditorScreen) serviert [bundleDir] unter
 * https://appassets.androidplatform.net/ — so greift Same-Origin und die
 * relativen Imports (`./js/…`) sowie `/icons.svg` des Bundles funktionieren.
 *
 * Bundle-Major: Der Download meldet per `X-Editor-Api` die Majors, die diese Schale
 * bedienen kann ([SUPPORTED_EDITOR_API]). Passt keiner, antwortet der Server
 * `409 EDITOR_API_UNSUPPORTED`; ein Bundle, dessen `bundle-manifest.json` einen
 * fremden `editorApi` trägt, wird ebenso abgewiesen. In beiden Fällen bleibt ein
 * gecachtes Bundle in Betrieb und [updateRequired] wird gesetzt (Hinweis
 * „App-Update nötig"); ohne Cache endet [ensureBundle] mit [EditorApiUnsupportedException].
 *
 * Cache zuerst: Liegt schon ein lauffähiges Bundle vor, liefert [ensureBundle] es sofort
 * und prüft den Server nur im Hintergrund. Ein neues Bundle wird dabei nur nach
 * [pendingDir] vorbereitet und erst beim nächsten [ensureBundle] eingesetzt — nie unter
 * einer laufenden WebView. **Why:** In einem Netz, das „verbunden" meldet, aber nichts
 * durchlässt, liefe die Prüfung in die OkHttp-Timeouts, und der Editor bliebe so lange
 * leer, obwohl alles zum Offline-Öffnen da ist. Nur ohne Cache wartet [ensureBundle]
 * auf den Download.
 */
class BundleManager internal constructor(
    private val context: Context,
    token: () -> String?,
    onUnauthorized: () -> Unit,
) {
    constructor(context: Context, tokenStore: TokenStore) :
        this(context, tokenStore::token, tokenStore::clear)

    private val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(token, onUnauthorized))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val bundleDir: File = File(context.filesDir, "editor-bundle")
    private val etagFile: File = File(context.filesDir, "editor-bundle.etag")
    /** Neues Bundle wird hier fertig entpackt und erst dann gegen [bundleDir] getauscht. */
    private val stagingDir: File = File(context.filesDir, "editor-bundle.staging")
    /** Download-Ziel der Hintergrund-Prüfung (eigenes Verzeichnis, läuft ohne [lock]). */
    private val downloadDir: File = File(context.filesDir, "editor-bundle.download")
    /** Fertig geprüftes neues Bundle, das beim nächsten [ensureBundle] eingesetzt wird. */
    private val pendingDir: File = File(context.filesDir, "editor-bundle.pending")
    private val pendingEtagFile: File = File(context.filesDir, "editor-bundle.pending.etag")
    private val retiredDir: File = File(context.filesDir, "editor-bundle.old")

    /**
     * Jede Editor-Instanz (auch jede Wisch-Navigation) ruft [ensureBundle] — ohne Lock
     * würden zwei Läufe gleichzeitig dasselbe Verzeichnis entpacken bzw. löschen.
     */
    private val lock = Mutex()

    /** App-weiter Scope der Hintergrund-Prüfung (BundleManager ist ein Singleton). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val backgroundSyncRunning = AtomicBoolean(false)

    /**
     * Der Server liefert nur noch Bundles eines Majors, den diese App-Version nicht
     * kann. Gesetzt vom letzten [ensureBundle]; ein gecachtes Bundle läuft weiter.
     */
    @Volatile var updateRequired: Boolean = false
        private set

    /** Liegt ein entpacktes, lauffähiges Bundle vor? */
    fun isReady(): Boolean = isReady(bundleDir)

    private fun isReady(dir: File): Boolean = File(dir, "host.html").exists() &&
        File(dir, "editor-host.css").exists() &&
        File(dir, "js/editor/focus/standalone.js").exists()

    /**
     * Liefert true, wenn ein lauffähiges Bundle bereitliegt. Mit Cache sofort (ein
     * vorbereitetes neues Bundle wird vorher eingesetzt, die Server-Prüfung läuft im
     * Hintergrund weiter); ohne Cache erst nach dem Download.
     */
    suspend fun ensureBundle(baseUrl: String): Result<Boolean> = lock.withLock {
        withContext(Dispatchers.IO) {
            promotePending()
            if (isReady()) {
                refreshHostPage()
                startBackgroundSync(baseUrl)
                Result.success(true)
            } else {
                syncBundle(baseUrl, stage = false)
            }
        }
    }

    /** Höchstens eine Hintergrund-Prüfung zugleich; sie hält [lock] nur für den Tausch nach [pendingDir]. */
    private fun startBackgroundSync(baseUrl: String) {
        if (!backgroundSyncRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                syncBundle(baseUrl, stage = true)
                    .onFailure { Log.i(TAG, "editor-bundle: Hintergrund-Prüfung fehlgeschlagen: ${it.message}") }
            } finally {
                backgroundSyncRunning.set(false)
            }
        }
    }

    /** Vorbereitetes Bundle aus [pendingDir] einsetzen (nur unter [lock] aufrufen). */
    private fun promotePending() {
        if (!pendingDir.exists()) return
        if (isReady(pendingDir)) {
            swapIn(pendingDir)
            pendingEtagFile.takeIf { it.exists() }?.let { etagFile.writeText(it.readText()) }
                ?: etagFile.delete()
        } else {
            pendingDir.deleteRecursively()
        }
        pendingEtagFile.delete()
    }

    /**
     * Prüft das Bundle gegen den Server. [stage] = false (kein Cache): ein neues Bundle
     * direkt einsetzen. [stage] = true (Hintergrund): nur nach [pendingDir] vorbereiten.
     */
    private suspend fun syncBundle(baseUrl: String, stage: Boolean): Result<Boolean> {
        val url = baseUrl.trimEnd('/') + "/content/editor-bundle.zip"
        val workDir = if (stage) downloadDir else stagingDir
        // Liegt schon ein neueres Bundle bereit, gilt dessen ETag als „aktueller Stand".
        val etagSource = if (stage && pendingEtagFile.exists() && isReady(pendingDir)) pendingEtagFile else etagFile
        val storedEtag = etagSource.takeIf { it.exists() }?.readText()?.trim().orEmpty()
        return try {
            val reqBuilder = Request.Builder().url(url)
                .header(EDITOR_API_HEADER, SUPPORTED_EDITOR_API.joinToString(","))
            if (storedEtag.isNotEmpty() && isReady()) {
                reqBuilder.header("If-None-Match", storedEtag)
            }
            http.newCall(reqBuilder.build()).execute().use { resp ->
                when {
                    resp.code == 304 -> {
                        // Server-Bundle unverändert → nicht neu entpacken. host.html
                        // ist aber app-versioniert (mit dem APK ausgeliefert), nicht
                        // bundle-versioniert: bei jedem Sync frisch aus den Assets
                        // spiegeln, sonst hinkt sie nach einem App-Update hinterher.
                        // (Mit Cache erledigt das schon ensureBundle.)
                        if (!stage) refreshHostPage()
                        updateRequired = false
                        Result.success(isReady())
                    }
                    resp.code == 409 && isEditorApiUnsupported(resp.body?.string()) -> {
                        // Server-Bundle braucht eine neuere Schale: gecachtes Bundle behalten.
                        Log.w(TAG, "editor-bundle: Server kennt keinen Major aus $SUPPORTED_EDITOR_API (409 EDITOR_API_UNSUPPORTED)")
                        editorApiUnsupported()
                    }
                    resp.isSuccessful -> {
                        val body = resp.body ?: return@use Result.failure(
                            IllegalStateException("editor-bundle: leerer Body")
                        )
                        // Erst vollständig in ein Staging-Verzeichnis entpacken und prüfen;
                        // das laufende Bundle bleibt bis zum Tausch unangetastet. Ein
                        // abgerissener Download oder eine Nicht-Zip-Antwort (Captive-Portal)
                        // zerstört so nie das funktionierende Offline-Bundle.
                        extractZip(body.byteStream(), workDir)
                        copyHostPage(workDir)
                        check(isReady(workDir)) { "editor-bundle: unvollständig" }
                        val major = manifestEditorApi(workDir)
                        if (major !in SUPPORTED_EDITOR_API) {
                            // Server ohne Header-Prüfung, aber Bundle eines fremden Majors.
                            workDir.deleteRecursively()
                            Log.w(TAG, "editor-bundle: Manifest meldet editorApi $major, die App kann $SUPPORTED_EDITOR_API")
                            return@use editorApiUnsupported()
                        }
                        val etag = resp.header("ETag")
                        if (stage) {
                            // Kurz unter dem Lock: ensureBundle setzt pendingDir nie halb getauscht ein.
                            lock.withLock {
                                pendingDir.deleteRecursively()
                                check(workDir.renameTo(pendingDir)) { "editor-bundle: Vorbereiten fehlgeschlagen" }
                                if (etag != null) pendingEtagFile.writeText(etag) else pendingEtagFile.delete()
                            }
                        } else {
                            swapIn(workDir)
                            if (etag != null) etagFile.writeText(etag) else etagFile.delete()
                        }
                        updateRequired = false
                        Result.success(isReady())
                    }
                    else -> {
                        // Offline/Fehler: vorhandenes Bundle weiterverwenden.
                        if (isReady()) { if (!stage) refreshHostPage(); Result.success(true) }
                        else Result.failure(IllegalStateException("editor-bundle HTTP ${resp.code}"))
                    }
                }
            }
        } catch (e: Exception) {
            workDir.deleteRecursively()
            if (stage) Result.failure(e)
            else if (isReady()) { refreshHostPage(); Result.success(true) } else Result.failure(e)
        }
    }

    /** Gecachtes Bundle weiterverwenden (falls vorhanden) und den Update-Hinweis setzen. */
    private fun editorApiUnsupported(): Result<Boolean> {
        updateRequired = true
        return if (isReady()) { refreshHostPage(); Result.success(true) }
        else Result.failure(EditorApiUnsupportedException())
    }

    private fun isEditorApiUnsupported(body: String?): Boolean =
        body?.let { runCatching { ApiJson.decodeFromString(ApiErrorDto.serializer(), it) }.getOrNull() }
            ?.code == "EDITOR_API_UNSUPPORTED"

    /**
     * `editorApi` aus `bundle-manifest.json`. Ein Manifest ohne das Feld (Server älter
     * als der Vertrag) gilt als Major 1, ebenso ein fehlendes/unlesbares — abgewiesen
     * wird nur ein ausdrücklich fremder Major.
     */
    private fun manifestEditorApi(dir: File): Int {
        val file = File(dir, "bundle-manifest.json")
        if (!file.exists()) return 1
        return runCatching { ApiJson.decodeFromString(BundleManifestDto.serializer(), file.readText()) }
            .getOrNull()?.editorApi ?: 1
    }

    /** Fertiges Bundle aus [from] per Rename an die Stelle von [bundleDir] setzen. */
    private fun swapIn(from: File) {
        retiredDir.deleteRecursively()
        if (bundleDir.exists() && !bundleDir.renameTo(retiredDir)) {
            bundleDir.deleteRecursively()
        }
        check(from.renameTo(bundleDir)) { "editor-bundle: Tausch fehlgeschlagen" }
        retiredDir.deleteRecursively()
    }

    private fun extractZip(input: java.io.InputStream, dir: File) {
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        val canonicalRoot = dir.canonicalPath
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val target = File(dir, entry.name)
                // Zip-Slip-Schutz.
                if (!target.canonicalPath.startsWith(canonicalRoot + File.separator) &&
                    target.canonicalPath != canonicalRoot
                ) {
                    throw SecurityException("Ungültiger Zip-Eintrag: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zis.copyTo(it) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    /** Host-Seite + ihre Styles aus den App-Assets ins Bundle-Root kopieren. */
    private fun copyHostPage(dir: File = bundleDir) {
        for (name in HOST_ASSETS) {
            context.assets.open("editor-host/$name").use { input ->
                File(dir, name).outputStream().use { input.copyTo(it) }
            }
        }
    }

    /**
     * host.html + editor-host.css best-effort gegen die App-Assets aktualisieren,
     * wenn bereits ein Bundle-Verzeichnis existiert (304-/Offline-Pfad). Fehler
     * sind nicht-fatal: die bestehenden Dateien bleiben dann liegen.
     */
    private fun refreshHostPage() {
        if (!bundleDir.exists()) return
        runCatching { copyHostPage() }
    }

    companion object {
        /**
         * Bundle-Majors (`editorApi`, contract/version.json im Mutterprojekt), die
         * host.html + EditorBridge bedienen können. Einzige Stelle; ein neuer Major
         * kommt erst dazu, wenn die Schale ihn kann.
         */
        val SUPPORTED_EDITOR_API: List<Int> = listOf(1)

        internal const val EDITOR_API_HEADER = "X-Editor-Api"

        private const val TAG = "BundleManager"

        /** App-versionierte Host-Assets, die neben das OTA-Bundle kopiert werden. */
        private val HOST_ASSETS = listOf("host.html", "editor-host.css")
    }
}

/** Kein lauffähiges Bundle: der Server liefert nur Majors, die diese App nicht kann. */
class EditorApiUnsupportedException : IllegalStateException("EDITOR_API_UNSUPPORTED")
