package ch.schreibwerkstatt.mobile.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import ch.schreibwerkstatt.mobile.R
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

sealed interface UpdateState {
    /** Kein Update-Vorgang sichtbar. */
    object Idle : UpdateState
    object Checking : UpdateState
    /** Manuelle Prüfung ergab: bereits aktuell (nur bei manuellem Check anzeigen). */
    object UpToDate : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val progress: Float) : UpdateState
    /** APK liegt bereit, aber „Unbekannte Apps installieren" ist (noch) nicht erlaubt. */
    data class NeedsInstallPermission(val file: File, val release: ReleaseInfo) : UpdateState
    data class Error(val message: String) : UpdateState
}

/**
 * Steuert den In-App-Update-Fluss: prüfen → herunterladen (mit Fortschritt) →
 * System-Installer öffnen. Singleton im [ServiceLocator]; Auto-Check beim Start
 * und manueller Check in den Einstellungen teilen sich denselben [state].
 *
 * Download/Installation laufen über GitHub-Release-Assets (kein Token, siehe
 * [UpdateChecker]).
 */
class UpdateManager(
    private val appContext: Context,
    private val scope: CoroutineScope,
    private val checker: UpdateChecker = UpdateChecker(),
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Verhindert wiederholten Auto-Check pro Prozess. */
    @Volatile private var launchChecked = false
    @Volatile private var job: Job? = null

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** Beim App-Start einmal still prüfen; nur bei verfügbarem Update sichtbar. */
    fun checkOnLaunch() {
        if (launchChecked) return
        launchChecked = true
        scope.launch {
            if (_state.value != UpdateState.Idle) return@launch
            when (val result = checker.check()) {
                is UpdateCheckResult.Available -> _state.value = UpdateState.Available(result.release)
                else -> Unit // UpToDate/Failed beim Auto-Check bewusst still
            }
        }
    }

    /** Manuelle Prüfung (Einstellungen): zeigt auch „aktuell"/Fehler an. */
    fun checkNow() {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Checking
        scope.launch {
            _state.value = when (val result = checker.check()) {
                is UpdateCheckResult.Available -> UpdateState.Available(result.release)
                is UpdateCheckResult.UpToDate -> UpdateState.UpToDate
                is UpdateCheckResult.Failed -> UpdateState.Error(result.message)
            }
        }
    }

    fun download(release: ReleaseInfo) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(0f)
        job = scope.launch {
            try {
                val file = downloadApk(release)
                // Vor dem Öffnen des Installers: die heruntergeladene APK muss mit
                // DEMSELBEN Zertifikat signiert sein wie die laufende Installation.
                // Schützt gegen eine untergeschobene/fremde APK, bevor überhaupt der
                // System-Installer aufgeht (der würde eine fremd signierte zwar auch
                // ablehnen, aber erst spät und verwirrend).
                if (!apkMatchesInstalledSignature(file)) {
                    file.delete()
                    _state.value = UpdateState.Error(appContext.getString(R.string.update_error_signature))
                    return@launch
                }
                installOrRequestPermission(file, release)
            } catch (e: CancellationException) {
                // „Später" während des Downloads (dismiss): kein Fehlerdialog, State
                // hat dismiss() schon auf Idle gesetzt.
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Error(e.message ?: "Download fehlgeschlagen")
            }
        }
    }

    private suspend fun downloadApk(release: ReleaseInfo): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        // Alte APKs aufräumen, damit der Cache nicht wächst.
        dir.listFiles()?.forEach { it.delete() }
        val target = File(dir, "schreibwerkstatt-${release.versionName}.apk")

        val request = Request.Builder().url(release.apkUrl).get().build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("Leere Antwort")
            val total = if (release.apkSizeBytes > 0) release.apkSizeBytes else body.contentLength()
            var downloaded = 0L
            body.byteStream().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        // Abbruch über dismiss() respektieren: sonst lädt die blockierende
                        // Schleife weiter und setzt den geschlossenen Dialog wieder auf.
                        ensureActive()
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            _state.value = UpdateState.Downloading((downloaded.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
            }
            // Abgebrochener/unvollständiger Stream: die erwartete Grösse (GitHub-Asset-
            // Grösse bzw. Content-Length) muss exakt getroffen sein, sonst ist die APK
            // korrupt — nicht an den Installer weiterreichen.
            ensureActive()
            if (total > 0 && downloaded != total) {
                target.delete()
                error(appContext.getString(R.string.update_error_incomplete))
            }
        }
        target
    }

    /**
     * True, wenn die APK unter [file] mit demselben Zertifikat signiert ist wie die
     * aktuell installierte App. Vergleicht die Menge der SHA-256-Fingerprints der
     * Signaturzertifikate. Fail-closed: fehlt eine Seite (Parsing-Fehler), false.
     */
    private fun apkMatchesInstalledSignature(file: File): Boolean {
        val installed = installedSignatureHashes()
        val downloaded = apkSignatureHashes(file.absolutePath)
        return installed.isNotEmpty() && installed == downloaded
    }

    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    private fun installedSignatureHashes(): Set<String> = runCatching {
        val pm = appContext.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            hashSignatures(info.signingInfo?.apkContentsSigners)
        } else {
            val info = pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNATURES)
            hashSignatures(info.signatures)
        }
    }.getOrDefault(emptySet())

    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    private fun apkSignatureHashes(path: String): Set<String> = runCatching {
        val pm = appContext.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
            hashSignatures(info?.signingInfo?.apkContentsSigners)
        } else {
            val info = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
            hashSignatures(info?.signatures)
        }
    }.getOrDefault(emptySet())

    private fun hashSignatures(sigs: Array<android.content.pm.Signature>?): Set<String> {
        if (sigs.isNullOrEmpty()) return emptySet()
        val sha = MessageDigest.getInstance("SHA-256")
        return sigs.map { sha.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }

    private fun installOrRequestPermission(file: File, release: ReleaseInfo) {
        if (appContext.packageManager.canRequestPackageInstalls()) {
            launchInstaller(file)
            _state.value = UpdateState.Idle
        } else {
            _state.value = UpdateState.NeedsInstallPermission(file, release)
        }
    }

    /** Nach erteilter Berechtigung erneut versuchen (aus dem Dialog). */
    fun retryInstall() {
        val current = _state.value
        if (current is UpdateState.NeedsInstallPermission) {
            installOrRequestPermission(current.file, current.release)
        }
    }

    private fun launchInstaller(file: File) {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        appContext.startActivity(intent)
    }

    /** Öffnet die System-Einstellung „Unbekannte Apps installieren" für diese App. */
    fun openInstallPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${appContext.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    fun dismiss() {
        if (_state.value is UpdateState.Downloading) {
            job?.cancel()
        }
        _state.value = UpdateState.Idle
    }
}
