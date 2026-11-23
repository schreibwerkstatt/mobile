package ch.schreibwerkstatt.mobile.ui.research

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Patterns
import java.util.UUID

/**
 * Aus einem `ACTION_SEND`-Intent extrahierte Recherche-Vorbelegung. Rein
 * datentragend; die Felder speisen die editierbaren Eingabefelder des
 * Capture-Screens (der Nutzer kann alles noch anpassen).
 *
 * [id] unterscheidet zwei Shares voneinander: die Navigation öffnet pro Share ein
 * eigenes Formular, auch wenn schon eines offen ist, und ein Konfigurationswechsel
 * öffnet denselben Share nicht ein zweites Mal.
 */
data class SharedResearch(
    val url: String,
    val title: String,
    val note: String,
    /** Geteiltes Bild (`content://`), null bei reinem Text. */
    val imageUri: String? = null,
    val imageMime: String? = null,
    val id: String = UUID.randomUUID().toString(),
) {
    /** Für den Instance-State (null-Felder als ""). */
    fun toArray(): Array<String> = arrayOf(id, url, title, note, imageUri.orEmpty(), imageMime.orEmpty())

    companion object {
        fun fromArray(a: Array<String>?): SharedResearch? {
            if (a == null || a.size < 6) return null
            return SharedResearch(
                id = a[0], url = a[1], title = a[2], note = a[3],
                imageUri = a[4].ifEmpty { null }, imageMime = a[5].ifEmpty { null },
            )
        }
    }
}

/** Explizite Web-Adresse im Fliesstext; schliessende Satzzeichen gehören nicht dazu. */
private val HTTP_URL = Regex("""https?://[^\s<>"«»]+""", RegexOption.IGNORE_CASE)
private const val TRAILING_PUNCTUATION = ".,;:!?)]}'’”»"

/** Erste Web-Adresse im Text: zuerst mit `http(s)://`, sonst eine schemalose (`example.org/x`). */
internal fun findSharedUrl(text: String): String {
    HTTP_URL.find(text)?.let { return it.value.trimEnd { c -> c in TRAILING_PUNCTUATION } }
    val m = Patterns.WEB_URL.matcher(text)
    return if (m.find()) text.substring(m.start(), m.end()) else ""
}

/**
 * Zerlegt einen geteilten `text/plain`- oder Bild-Intent (`image/…`) in URL / Titel / Notiz / Bild.
 *
 * Heuristik (Android liefert je nach Quell-App Unterschiedliches):
 * - Erste `http(s)://`-URL im `EXTRA_TEXT` → [SharedResearch.url] (sonst eine schemalose
 *   Adresse) — so gewinnt bei „Artikel auf spiegel.de: https://…" der echte Link.
 * - `EXTRA_SUBJECT`/`EXTRA_TITLE` (falls vorhanden) → Titel; sonst der Text ohne
 *   die URL (viele Browser teilen „Seitentitel\nURL").
 * - Bleibt nach Titel-Extraktion noch Text übrig, wandert er in die Notiz.
 * - Ein Bild kommt als `EXTRA_STREAM`.
 *
 * `EXTRA_TEXT` wird als CharSequence gelesen: manche Apps teilen formatierten Text
 * (Spanned), den `getStringExtra` als null liefert.
 *
 * Liefert null, wenn der Intent kein verwertbarer Share ist.
 */
fun parseShareIntent(intent: Intent?): SharedResearch? {
    if (intent?.action != Intent.ACTION_SEND) return null
    val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.trim().orEmpty()
    val subject = (intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
        ?: intent.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString())
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val image = intent.type?.takeIf { it.startsWith("image/") }?.let { streamUri(intent) }
    if (text.isEmpty() && subject == null && image == null) return null

    val url = findSharedUrl(text)
    val leftover = if (url.isNotEmpty()) text.replace(url, "").trim() else text

    val title: String
    val note: String
    when {
        subject != null -> {
            title = subject
            // Leftover nur als Notiz übernehmen, wenn er nicht bloss den Titel wiederholt.
            note = leftover.takeIf { it.isNotEmpty() && !it.equals(subject, ignoreCase = true) }.orEmpty()
        }
        leftover.isNotEmpty() -> {
            // Erste Zeile als Titel, Rest als Notiz.
            val lines = leftover.lines().map { it.trim() }.filter { it.isNotEmpty() }
            title = lines.firstOrNull().orEmpty()
            note = lines.drop(1).joinToString("\n")
        }
        else -> {
            title = ""
            note = ""
        }
    }
    return SharedResearch(
        url = url,
        title = title,
        note = note,
        imageUri = image?.toString(),
        imageMime = image?.let { intent.type },
    )
}

private fun streamUri(intent: Intent): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
    }

private val SCHEME = Regex("""^[A-Za-z][A-Za-z0-9+.-]*:""")

/**
 * Link für Speichern/Öffnen normalisieren: Ohne Schema (`example.org/x`) behandelt
 * Android die Eingabe nicht als Web-Adresse — dann `https://` voranstellen. Adressen
 * mit Schema (auch `mailto:`/`tel:`) bleiben unverändert, leer bleibt leer.
 */
fun normalizeResearchUrl(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty()) return t
    if (SCHEME.containsMatchIn(t) && !t.matches(Regex("""^[^/]+:\d+(/.*)?$"""))) return t
    return "https://$t"
}

/** Server-Obergrenze einer URL (`URL_MAX`); längere weist er ab statt zu kürzen. */
const val RESEARCH_URL_MAX = 2000

/**
 * Taugt die (normalisierte) Adresse fürs Recherche-Board? Der Server nimmt nur
 * `http(s)` bis [RESEARCH_URL_MAX] Zeichen an (`400 INVALID_URL`) — das Formular prüft
 * vorher und nennt den Grund am Feld. Leer ist gültig (kein Link).
 */
fun isValidResearchUrl(normalized: String): Boolean {
    if (normalized.isEmpty()) return true
    if (normalized.length > RESEARCH_URL_MAX) return false
    val uri = runCatching { Uri.parse(normalized) }.getOrNull() ?: return false
    return (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) && !uri.host.isNullOrBlank()
}
