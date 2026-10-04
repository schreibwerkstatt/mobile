package ch.schreibwerkstatt.mobile.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Verschlüsselter Speicher für das Device-Token (`swd_…`) + Metadaten.
 * Backing: [EncryptedSharedPreferences] (AES256, Schlüssel im Android-Keystore).
 *
 * Das Token verlässt den Server genau einmal im Klartext (Create-Response) und
 * wird hier abgelegt. Danach: Header `Authorization: Bearer swd_…` für ALLE
 * Requests (siehe AuthInterceptor). 401 → [clear] → zurück zum Pairing.
 */
class TokenStore(context: Context) {

    // Lazy: EncryptedSharedPreferences greift auf den Android-Keystore (Tink) zu.
    // Erst beim ersten Token-Zugriff initialisieren — so bleibt die Konstruktion
    // krypto-frei (in Tests ohne Keystore konstruierbar, solange kein Token
    // gelesen/geschrieben wird).
    private val prefs: SharedPreferences by lazy {
        try {
            openPrefs(context)
        } catch (e: Exception) {
            // Datei da, Keystore-Schlüssel nicht (Restore/Gerätetransfer, Keystore-Reset):
            // Tink wirft dann bei jedem Start. Unlesbare Datei verwerfen und leer neu
            // anlegen — das Gerät muss neu gekoppelt werden, statt dauerhaft abzustürzen.
            context.deleteSharedPreferences(PREFS_NAME)
            openPrefs(context)
        }
    }

    private fun openPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val _isPaired by lazy { MutableStateFlow(prefs.getString(KEY_TOKEN, null) != null) }
    /** Reaktiv: true sobald ein Token vorliegt. Treibt die Start-Navigation. */
    val isPaired: StateFlow<Boolean> get() = _isPaired

    fun token(): String? = prefs.getString(KEY_TOKEN, null)

    fun deviceName(): String? = prefs.getString(KEY_DEVICE_NAME, null)

    fun save(plainToken: String, deviceName: String?, tokenId: Long?) {
        prefs.edit()
            .putString(KEY_TOKEN, plainToken)
            .putString(KEY_DEVICE_NAME, deviceName)
            .apply {
                if (tokenId != null) putLong(KEY_TOKEN_ID, tokenId) else remove(KEY_TOKEN_ID)
            }
            .apply()
        _isPaired.value = true
    }

    fun tokenId(): Long? =
        if (prefs.contains(KEY_TOKEN_ID)) prefs.getLong(KEY_TOKEN_ID, -1L).takeIf { it >= 0 } else null

    /** Lokales Abmelden — nur den Token verwerfen (kein Server-Revoke). */
    fun clear() {
        prefs.edit().clear().apply()
        _isPaired.value = false
    }

    private companion object {
        const val PREFS_NAME = "sw_secure_token"
        const val KEY_TOKEN = "device_token"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_TOKEN_ID = "token_id"
    }
}
