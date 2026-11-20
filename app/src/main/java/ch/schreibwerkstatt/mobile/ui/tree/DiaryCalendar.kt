package ch.schreibwerkstatt.mobile.ui.tree

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import ch.schreibwerkstatt.mobile.R
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Monats-Kalender für Tagebuch-Bücher. Tage mit vorhandenem Eintrag sind
 * hervorgehoben; Tippen öffnet den Eintrag, Tippen auf einen leeren Tag legt
 * ihn an. Der Button legt (oder öffnet) den Eintrag für heute an.
 *
 * [loading]/[error] spiegeln das Laden des Buchbaums, aus dem [entries] stammt:
 * Solange er fehlt, ist ein leerer Monat KEINE Aussage über vorhandene Einträge —
 * darum Ladebalken bzw. Fehlerstreifen mit [onRetry] statt stummer Leere.
 */
@Composable
fun DiaryCalendar(
    month: YearMonth,
    entries: Map<String, Long>,
    creating: Boolean,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onDayClick: (dateIso: String) -> Unit,
    onTodayClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Locale aus der aktuellen Konfiguration (nicht eingefroren beim Prozessstart).
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) {
        ConfigurationCompat.getLocales(configuration)[0] ?: Locale.getDefault()
    }
    val firstDayOfWeek = remember(locale) { WeekFields.of(locale).firstDayOfWeek }
    val monthFormatter = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    val dayDescFormatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale)
    }
    val today = rememberToday()
    val todayHasEntry = entries.containsKey(today.toString())

    // Aussen nur 8dp Rand, damit die Tageszellen auch bei 360dp Breite ≥ 48dp
    // Tippfläche haben (344dp / 7). Kopf, Hinweise und Button rücken um weitere 8dp ein.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Kopf: Monat + Navigation
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevMonth) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.calendar_prev_month),
                )
            }
            Text(
                text = monthLabel(month, monthFormatter, locale),
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = onNextMonth) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.calendar_next_month),
                )
            }
        }

        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        } else if (error != null) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(start = 12.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.tree_load_error, error),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
        }

        // Wochentags-Header ab dem ersten Wochentag der Locale (de: Mo, en-US: So)
        Row(Modifier.fillMaxWidth()) {
            for (i in 0..6) {
                val dow = firstDayOfWeek.plus(i.toLong())
                Text(
                    text = dow.getDisplayName(TextStyle.SHORT, locale),
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 6 Wochen × 7 Tage. Keine Abstände zwischen den Zellen: die ganze Zelle ist
        // Tippfläche, die sichtbare Kachel sitzt mit Innenrand darin (siehe DayCell).
        val first = month.atDay(1)
        val offset = (first.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
        val gridStart = first.minusDays(offset.toLong())
        Column {
            for (week in 0 until 6) {
                Row(Modifier.fillMaxWidth()) {
                    for (dow in 0 until 7) {
                        val date = gridStart.plusDays((week * 7 + dow).toLong())
                        DayCell(
                            date = date,
                            inMonth = date.month == month.month,
                            hasEntry = entries.containsKey(date.toString()),
                            isToday = date == today,
                            // Während ein Eintrag angelegt wird, keine weiteren Tipps.
                            enabled = !creating,
                            dayDescFormatter = dayDescFormatter,
                            onClick = { onDayClick(date.toString()) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        Button(
            onClick = onTodayClick,
            // Immer aktiv: öffnet den vorhandenen heutigen Eintrag oder legt ihn an.
            enabled = !creating,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp, end = 8.dp),
        ) {
            if (creating) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 8.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    if (todayHasEntry) Icons.Filled.Edit else Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            Text(stringResource(R.string.calendar_today_entry))
        }
    }
}

/**
 * Heutiges Datum, das über Mitternacht und Rückkehr in die App aktuell bleibt.
 * Der Timer allein genügt nicht: `delay` zählt im Gerätetiefschlaf nicht mit, und
 * eine Zeitzonen-Änderung verschiebt Mitternacht — darum zusätzlich bei ON_RESUME.
 */
@Composable
private fun rememberToday(): LocalDate {
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { today = LocalDate.now() }
    LaunchedEffect(today) {
        val now = LocalDateTime.now()
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay()
        delay(Duration.between(now, nextMidnight).toMillis() + 1_000)
        today = LocalDate.now()
    }
    return today
}

@Composable
private fun DayCell(
    date: LocalDate,
    inMonth: Boolean,
    hasEntry: Boolean,
    isToday: Boolean,
    enabled: Boolean,
    dayDescFormatter: DateTimeFormatter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (hasEntry) scheme.primaryContainer else scheme.surfaceVariant.copy(alpha = 0.4f)
    val fg = when {
        hasEntry -> scheme.onPrimaryContainer
        inMonth -> scheme.onSurface
        else -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
    }
    // Screenreader liest sonst nur die nackte Tageszahl. Vollständiges Datum +
    // Zustand (heute / Eintrag) als ein zusammengefasstes Label ansagen.
    val description = buildList {
        add(date.format(dayDescFormatter))
        if (isToday) add(stringResource(R.string.a11y_calendar_today))
        add(stringResource(if (hasEntry) R.string.a11y_calendar_has_entry else R.string.a11y_calendar_no_entry))
    }.joinToString(", ")
    val clickLabel = stringResource(
        if (hasEntry) R.string.a11y_action_open else R.string.a11y_calendar_create_entry
    )
    // Äussere Box = Tippfläche (volle Zellbreite, ≥ 48dp), innere Kachel mit 2dp Rand.
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clickable(enabled = enabled, onClickLabel = clickLabel, onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .then(
                if (isToday) Modifier.border(2.dp, scheme.primary, RoundedCornerShape(8.dp))
                else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = date.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = fg,
                fontWeight = if (hasEntry || isToday) FontWeight.Bold else FontWeight.Normal,
                // Zahl nicht separat vorlesen – das Box-Label deckt sie ab.
                modifier = Modifier.clearAndSetSemantics {},
            )
            if (hasEntry) {
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                )
            }
        }
    }
}

/** Monatstitel im Standalone-Format der Locale (`LLLL yyyy`), erster Buchstabe gross. */
private fun monthLabel(month: YearMonth, formatter: DateTimeFormatter, locale: Locale): String =
    month.format(formatter)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
