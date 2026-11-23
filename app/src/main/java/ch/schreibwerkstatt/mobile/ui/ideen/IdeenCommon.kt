package ch.schreibwerkstatt.mobile.ui.ideen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.schreibwerkstatt.mobile.R
import ch.schreibwerkstatt.mobile.data.net.dto.ChapterNodeDto
import ch.schreibwerkstatt.mobile.data.net.dto.IdeeDto
import ch.schreibwerkstatt.mobile.data.net.dto.TreeDto
import ch.schreibwerkstatt.mobile.locator
import kotlinx.coroutines.flow.first

/** Ort einer Idee: ganzes Buch, ein Kapitel oder ein Abschnitt. */
sealed interface IdeePlace {
    data object Book : IdeePlace
    data class Chapter(val id: Long, val name: String) : IdeePlace
    data class Page(val id: Long, val name: String) : IdeePlace

    val pageId: Long? get() = (this as? Page)?.id
    val chapterId: Long? get() = (this as? Chapter)?.id

    companion object {
        fun of(idee: IdeeDto): IdeePlace = when {
            idee.page_id != null -> Page(idee.page_id, idee.page_name.orEmpty())
            idee.chapter_id != null -> Chapter(idee.chapter_id, idee.chapter_name.orEmpty())
            else -> Book
        }
    }
}

/** Reihenfolge der Stufen in Liste und Auswahl (`lib/ideen-status.js`). */
val IDEE_STATUS_ORDER = listOf(
    IdeeDto.STATUS_OFFEN,
    IdeeDto.STATUS_IN_ARBEIT,
    IdeeDto.STATUS_ERLEDIGT,
    IdeeDto.STATUS_VERWORFEN,
)

@Composable
fun statusLabel(status: String): String = when (status) {
    IdeeDto.STATUS_OFFEN -> stringResource(R.string.ideen_status_offen)
    IdeeDto.STATUS_IN_ARBEIT -> stringResource(R.string.ideen_status_in_arbeit)
    IdeeDto.STATUS_ERLEDIGT -> stringResource(R.string.ideen_status_erledigt)
    IdeeDto.STATUS_VERWORFEN -> stringResource(R.string.ideen_status_verworfen)
    else -> status
}

@Composable
fun placeLabel(place: IdeePlace): String = when (place) {
    IdeePlace.Book -> stringResource(R.string.ideen_anchor_book)
    is IdeePlace.Chapter -> stringResource(R.string.ideen_anchor_chapter, place.name)
    is IdeePlace.Page -> stringResource(R.string.ideen_anchor_page, place.name)
}

fun placeIcon(place: IdeePlace): ImageVector = when (place) {
    IdeePlace.Book -> Icons.Filled.Book
    is IdeePlace.Chapter -> Icons.Filled.Folder
    is IdeePlace.Page -> Icons.AutoMirrored.Filled.Article
}

/** Zeile der Ort-Auswahl (mit Einrückung nach Gliederungstiefe). */
data class PlaceRow(val place: IdeePlace, val depth: Int)

/**
 * Gliederung als Ort-Liste: zuerst „Ganzes Buch", dann Kapitel und Abschnitte in
 * Baum-Reihenfolge. Spiegelt `flattenTree` (TreeViewModel) inkl. beider Server-Formen
 * (verschachtelt über `subchapters` oder flach über `parent_chapter_id`), behält aber
 * die Kapitel-IDs.
 */
fun placeRows(tree: TreeDto): List<PlaceRow> {
    val rows = mutableListOf(PlaceRow(IdeePlace.Book, 0))
    tree.topPages.forEach { rows += PlaceRow(IdeePlace.Page(it.id, it.name), 0) }
    val ids = tree.chapters.map { it.id }.toSet()
    val childrenOf = tree.chapters
        .filter { it.parent_chapter_id != null && it.parent_chapter_id in ids }
        .groupBy { it.parent_chapter_id!! }
    fun walk(c: ChapterNodeDto, depth: Int) {
        rows += PlaceRow(IdeePlace.Chapter(c.id, c.name), depth)
        c.pages.forEach { rows += PlaceRow(IdeePlace.Page(it.id, it.name), depth + 1) }
        c.subchapters.forEach { walk(it, depth + 1) }
        childrenOf[c.id]?.forEach { walk(it, depth + 1) }
    }
    tree.chapters
        .filter { it.parent_chapter_id == null || it.parent_chapter_id !in ids }
        .forEach { walk(it, 0) }
    return rows
}

/**
 * Bottom-Sheet „Ort wählen". Lädt die Gliederung frisch vom Server; offline fällt es
 * auf die gecachten Abschnitte zurück (ohne Kapitel — deren Namen kennt der Cache nicht).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlacePickerSheet(
    bookId: Long,
    current: IdeePlace,
    onPick: (IdeePlace) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val repo = context.locator.repository
    var rows by remember(bookId) { mutableStateOf<List<PlaceRow>?>(null) }
    var offline by remember(bookId) { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }

    LaunchedEffect(bookId) {
        repo.tree(bookId)
            .onSuccess { rows = placeRows(it) }
            .onFailure {
                offline = true
                val cached = repo.observePages(bookId).first()
                rows = listOf(PlaceRow(IdeePlace.Book, 0)) +
                    cached.map { PlaceRow(IdeePlace.Page(it.id, it.name.orEmpty()), 0) }
            }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp),
        ) {
            Text(
                stringResource(R.string.ideen_pick_place),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            OutlinedTextField(
                value = filter,
                onValueChange = { filter = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.ideen_pick_filter)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (offline) {
                Text(
                    stringResource(R.string.ideen_pick_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }
            val all = rows
            if (all == null) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.ideen_pick_loading), Modifier.padding(start = 12.dp))
                    }
                }
            } else {
                val q = filter.trim()
                val shown = if (q.isEmpty()) all else all.filter { row ->
                    when (val p = row.place) {
                        IdeePlace.Book -> false
                        is IdeePlace.Chapter -> p.name.contains(q, ignoreCase = true)
                        is IdeePlace.Page -> p.name.contains(q, ignoreCase = true)
                    }
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    items(shown, key = { it.place.toString() }) { row ->
                        PlaceRowItem(
                            row = row,
                            selected = row.place == current ||
                                (row.place.pageId != null && row.place.pageId == current.pageId) ||
                                (row.place.chapterId != null && row.place.chapterId == current.chapterId),
                            indent = if (q.isEmpty()) row.depth else 0,
                            onClick = { onPick(row.place) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaceRowItem(row: PlaceRow, selected: Boolean, indent: Int, onClick: () -> Unit) {
    val label = when (val p = row.place) {
        IdeePlace.Book -> stringResource(R.string.ideen_anchor_book)
        is IdeePlace.Chapter -> p.name
        is IdeePlace.Page -> p.name
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = (24 + indent * 16).dp, end = 24.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            placeIcon(row.place),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            label,
            style = if (row.place is IdeePlace.Chapter) MaterialTheme.typography.titleSmall
            else MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
