package ch.schreibwerkstatt.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.schreibwerkstatt.mobile.R
import kotlinx.coroutines.delay

/**
 * Schmaler Statusstreifen, der den Offline-Zustand bzw. noch nicht
 * synchronisierte Pending-Writes sichtbar macht. Blendet sich aus, wenn online
 * und nichts in der Queue hängt.
 *
 * Speist sich aus [ch.schreibwerkstatt.mobile.data.repo.SyncCoordinator]
 * (`online` / `pendingCount`).
 *
 * Online erscheint der Pending-Hinweis erst, wenn die Queue länger als
 * [PENDING_SHOW_DELAY_MS] nicht leer wird: Jeder Autosave liegt kurz als
 * Pending-Write in der Queue, bis der Flush durch ist. Ohne Verzögerung
 * blendete der Streifen bei jedem Save kurz ein — im Editor eine echte
 * Grössenänderung der WebView (Text springt). Offline erscheint er sofort,
 * ausgeblendet wird ohne Verzögerung, sobald die Queue leer ist.
 */
@Composable
fun SyncStatusBar(
    online: Boolean,
    pendingCount: Int,
    modifier: Modifier = Modifier,
) {
    val hasPending = pendingCount > 0
    // „Pending hält an": online erst nach der Wartezeit; offline sofort, damit der
    // Streifen beim Wechsel offline → online mit noch offener Queue stehen bleibt.
    val pendingPersists by produceState(initialValue = false, online, hasPending) {
        when {
            !hasPending -> value = false
            !online -> value = true
            !value -> {
                delay(PENDING_SHOW_DELAY_MS)
                value = true
            }
        }
    }
    val visible = !online || (hasPending && pendingPersists)
    AnimatedVisibility(visible = visible) {
        val offline = !online
        val container = if (offline) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
        val content = if (offline) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
        val text = when {
            offline && pendingCount > 0 ->
                pluralStringResource(R.plurals.sync_pending_offline, pendingCount, pendingCount)
            offline ->
                stringResource(R.string.sync_offline)
            else ->
                pluralStringResource(R.plurals.sync_pending_online, pendingCount, pendingCount)
        }
        Surface(
            color = container,
            contentColor = content,
            // Als Live-Region zusammengefasst: TalkBack liest beim Ein-/Umblenden
            // den Statustext vor. Das Icon bleibt dekorativ (contentDescription=null),
            // da der Text den Zustand bereits vollständig benennt.
            modifier = modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = if (offline) Icons.Filled.CloudOff else Icons.Filled.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Wartezeit, bis ein online anstehender Pending-Write als Streifen sichtbar wird. */
private const val PENDING_SHOW_DELAY_MS = 2_000L
