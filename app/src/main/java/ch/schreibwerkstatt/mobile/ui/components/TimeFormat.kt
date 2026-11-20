package ch.schreibwerkstatt.mobile.ui.components

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Zeitstempel vom Server lokalisiert formatieren (Datum mittel, Uhrzeit kurz, in der
 * Gerätezeitzone). Der Server liefert je nach Quelle ISO-Instants (`…Z`), ISO mit
 * Offset oder SQLite-UTC ohne Zeitzonen-Suffix (`YYYY-MM-DD HH:MM:SS`, via
 * `datetime('now')`) — alles wird robust geparst. Unlesbares kommt roh zurück,
 * leer/null als «—».
 */
fun formatServerTime(raw: String?): String {
    if (raw.isNullOrBlank()) return "—"
    val instant = runCatching { Instant.parse(raw) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
        ?: runCatching {
            LocalDateTime.parse(raw.trim().replace(' ', 'T'))
                .atZone(ZoneId.of("UTC"))
                .toInstant()
        }.getOrNull()
    return instant?.let { formatter().format(it) } ?: raw
}

// Pro Aufruf gebaut, damit ein Sprach-/Zeitzonenwechsel zur Laufzeit greift.
private fun formatter(): DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
