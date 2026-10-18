package ch.schreibwerkstatt.mobile.bundle

import android.content.Context
import android.util.Log
import ch.schreibwerkstatt.mobile.data.net.ApiJson
import ch.schreibwerkstatt.mobile.data.net.AuthInterceptor
import ch.schreibwerkstatt.mobile.data.net.dto.ApiErrorDto
import ch.schreibwerkstatt.mobile.data.net.dto.BundleManifestDto
import ch.schreibwerkstatt.mobile.data.prefs.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
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
 */
class BundleManager(
    private val context: Context,
    tokenStore: TokenStore,
) {
    private val http: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor(tokenStore::token, tokenStore::clear))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val bundleDir: File = File(context.filesDir, "editor-bundle")
    private val etagFile: File = File(context.filesDir, "editor-bundle.etag")
    /** Neues Bundle wird hier fertig entpackt und erst dann gegen [bundleDir] getauscht. */
    private val stagingDir: File = File(context.filesDir, "editor-bundle.staging")
    private val retiredDir: File = File(context.filesDir, "editor-bundle.old")

    /**
     * Jede Editor-Instanz (auch jede Wisch-Navigation) ruft [ensureBundle] — ohne Lock
     * würden zwei Läufe gleichzeitig dasselbe Verzeichnis entpacken bzw. löschen.
     */
    private val lock = Mutex()

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
     * Synchronisiert das Bundle gegen den Server. Liefert true, wenn danach ein
     * lauffähiges Bundle bereitliegt (auch bei 304/Offline mit vorhandenem Cache).
     */
    suspend fun ensureBundle(baseUrl: String): Result<Boolean> = lock.withLock {
        withContext(Dispatchers.IO) { syncBundle(baseUrl) }
    }

    private fun syncBundle(baseUrl: String): Result<Boolean> {
        val url = baseUrl.trimEnd('/') + "/content/editor-bundle.zip"
        val storedEtag = etagFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
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
                        refreshHostPage()
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
                        extractZip(body.byteStream(), stagingDir)
                        copyHostPage(stagingDir)
                        check(isReady(stagingDir)) { "editor-bundle: unvollständig" }
                        val major = manifestEditorApi(stagingDir)
                        if (major !in SUPPORTED_EDITOR_API) {
                            // Server ohne Header-Prüfung, aber Bundle eines fremden Majors.
                            stagingDir.deleteRecursively()
                            Log.w(TAG, "editor-bundle: Manifest meldet editorApi $major, die App kann $SUPPORTED_EDITOR_API")
                            return@use editorApiUnsupported()
                        }
                        swapInStaging()
                        resp.header("ETag")?.let { etagFile.writeText(it) }
                        updateRequired = false
                        Result.success(isReady())
                    }
                    else -> {
                        // Offline/Fehler: vorhandenes Bundle weiterverwenden.
                        if (isReady()) { refreshHostPage(); Result.success(true) }
                        else Result.failure(IllegalStateException("editor-bundle HTTP ${resp.code}"))
                    }
                }
            }
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            if (isReady()) { refreshHostPage(); Result.success(true) } else Result.failure(e)
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

    /** Fertiges Staging-Bundle per Rename an die Stelle von [bundleDir] setzen. */
    private fun swapInStaging() {
        retiredDir.deleteRecursively()
        if (bundleDir.exists() && !bundleDir.renameTo(retiredDir)) {
            bundleDir.deleteRecursively()
        }
        check(stagingDir.renameTo(bundleDir)) { "editor-bundle: Tausch fehlgeschlagen" }
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
