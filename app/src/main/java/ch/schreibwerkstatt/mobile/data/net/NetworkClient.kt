package ch.schreibwerkstatt.mobile.data.net

import ch.schreibwerkstatt.mobile.BuildConfig
import ch.schreibwerkstatt.mobile.data.prefs.TokenStore
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * Baut Retrofit-Clients gegen die (zur Laufzeit variable) Server-Basis-URL.
 * Cached pro Basis-URL — bei URL-Wechsel (Settings) wird neu gebaut.
 *
 * Pairing (Cookie-Session) läuft NICHT hierüber, sondern im WebView via
 * injiziertem fetch (siehe PairingScreen).
 */
open class NetworkClient(
    private val tokenStore: TokenStore,
    private val debug: Boolean,
) {
    private val json = ApiJson

    @Volatile private var cachedBaseUrl: String? = null
    @Volatile private var cachedRetrofit: Retrofit? = null

    private fun okHttp(): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor(tokenStore::token, tokenStore::clear))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)   // STT-Cold-Start grosszügig
            .writeTimeout(45, TimeUnit.SECONDS)
        if (debug) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
        }
        return builder.build()
    }

    @Synchronized
    private fun retrofitFor(baseUrl: String): Retrofit {
        val normalized = baseUrl.trimEnd('/') + "/"
        cachedRetrofit?.let { if (cachedBaseUrl == normalized) return it }
        val contentType = "application/json".toMediaType()
        val retrofit = Retrofit.Builder()
            .baseUrl(normalized)
            .client(okHttp())
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
        cachedBaseUrl = normalized
        cachedRetrofit = retrofit
        return retrofit
    }

    val jsonParser: Json get() = json

    // open: Tests überschreiben diese Factories, um die Retrofit-APIs durch Fakes
    // zu ersetzen (siehe SyncEngineTest / ContentRepositorySaveTest).
    open fun content(baseUrl: String): ContentApi = retrofitFor(baseUrl).create(ContentApi::class.java)
    open fun stt(baseUrl: String): SttApi = retrofitFor(baseUrl).create(SttApi::class.java)
    open fun config(baseUrl: String): ConfigApi = retrofitFor(baseUrl).create(ConfigApi::class.java)
    open fun research(baseUrl: String): ResearchApi = retrofitFor(baseUrl).create(ResearchApi::class.java)
    open fun languagetool(baseUrl: String): LanguageToolApi = retrofitFor(baseUrl).create(LanguageToolApi::class.java)

    /**
     * Prüft im Pairing ein manuell eingegebenes Device-Token gegen `GET …/config`
     * (liegt hinter dem Auth-Guard). Bewusst eigenständig — mit explizitem
     * `Authorization`-Header statt über [AuthInterceptor]/[TokenStore], weil das
     * Token erst nach erfolgreicher Prüfung abgelegt wird.
     */
    suspend fun verifyToken(baseUrl: String, token: String): VerifyResult = withContext(Dispatchers.IO) {
        val normalized = baseUrl.trimEnd('/') + "/"
        val request = Request.Builder()
            .url(normalized + "config")
            .header("Authorization", "Bearer $token")
            .header("X-Client-Version", BuildConfig.CLIENT_VERSION)
            .get()
            .build()
        try {
            okHttp().newCall(request).execute().use { resp ->
                when {
                    resp.isSuccessful -> VerifyResult.Ok
                    resp.code == 401 -> VerifyResult.Unauthorized
                    else -> VerifyResult.Failed("HTTP ${resp.code}")
                }
            }
        } catch (e: Exception) {
            VerifyResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}

/**
 * Die eine Json-Konfiguration der Server-API (Retrofit-Converter, Fehler-Bodys). Die
 * Contract-Tests dekodieren die Server-Fixtures mit genau dieser Instanz.
 */
internal val ApiJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

sealed interface VerifyResult {
    object Ok : VerifyResult
    object Unauthorized : VerifyResult
    data class Failed(val message: String) : VerifyResult
}
