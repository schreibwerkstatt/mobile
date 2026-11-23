package ch.schreibwerkstatt.mobile.ideen

import android.content.ComponentName
import android.content.Intent
import ch.schreibwerkstatt.mobile.data.net.dto.ChapterNodeDto
import ch.schreibwerkstatt.mobile.data.net.dto.TreeDto
import ch.schreibwerkstatt.mobile.data.net.dto.TreePageDto
import ch.schreibwerkstatt.mobile.ui.ideen.IdeePlace
import ch.schreibwerkstatt.mobile.ui.ideen.placeRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Systemweite Einstiege ins Ideen-Erfassen und die Ort-Liste. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class IdeaLaunchTest {

    private fun share(alias: Boolean, text: String?, subject: String? = null) =
        Intent(Intent.ACTION_SEND).apply {
            component = ComponentName(
                "ch.schreibwerkstatt.mobile",
                if (alias) "ch.schreibwerkstatt.mobile.ShareAsIdea" else "ch.schreibwerkstatt.mobile.MainActivity",
            )
            type = "text/plain"
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }

    @Test fun `shortcut and tile action opens an empty capture`() {
        assertEquals(IdeaLaunch(""), parseIdeaLaunch(Intent(ACTION_NEW_IDEA)))
    }

    @Test fun `share via idea alias prefills subject and text`() {
        val l = parseIdeaLaunch(share(alias = true, text = "https://x.ch/a", subject = "Artikel"))
        assertEquals(IdeaLaunch("Artikel\nhttps://x.ch/a"), l)
        assertTrue(isIdeaShare(share(alias = true, text = "t")))
    }

    @Test fun `plain share stays a research share`() {
        assertNull(parseIdeaLaunch(share(alias = false, text = "https://x.ch")))
        assertFalse(isIdeaShare(share(alias = false, text = "https://x.ch")))
        assertNull(parseIdeaLaunch(Intent(Intent.ACTION_MAIN)))
    }

    @Test fun `place rows start with the book and keep chapter ids in tree order`() {
        val tree = TreeDto(
            topPages = listOf(TreePageDto(1, "Vorwort")),
            chapters = listOf(
                ChapterNodeDto(10, "Teil 1", pages = listOf(TreePageDto(2, "Anfang", 10)),
                    subchapters = listOf(ChapterNodeDto(11, "Kap 1.1", parent_chapter_id = 10))),
                // Flache Form: Unterkapitel nur über parent_chapter_id.
                ChapterNodeDto(20, "Teil 2"),
                ChapterNodeDto(21, "Kap 2.1", parent_chapter_id = 20),
            ),
        )
        val rows = placeRows(tree)
        assertEquals(
            listOf(
                IdeePlace.Book to 0,
                IdeePlace.Page(1, "Vorwort") to 0,
                IdeePlace.Chapter(10, "Teil 1") to 0,
                IdeePlace.Page(2, "Anfang") to 1,
                IdeePlace.Chapter(11, "Kap 1.1") to 1,
                IdeePlace.Chapter(20, "Teil 2") to 0,
                IdeePlace.Chapter(21, "Kap 2.1") to 1,
            ),
            rows.map { it.place to it.depth },
        )
    }
}
