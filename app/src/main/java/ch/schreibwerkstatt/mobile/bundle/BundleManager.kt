package ch.schreibwerkstatt.mobile.bundle

import android.content.Context
import ch.schreibwerkstatt.mobile.data.net.AuthInterceptor
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
                        Result.success(isReady())
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
                        swapInStaging()
                        resp.header("ETag")?.let { etagFile.writeText(it) }
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

    private companion object {
        /** App-versionierte Host-Assets, die neben das OTA-Bundle kopiert werden. */
        val HOST_ASSETS = listOf("host.html", "editor-host.css")
    }
}
