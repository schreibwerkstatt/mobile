package ch.schreibwerkstatt.mobile.ui.history

import ch.schreibwerkstatt.mobile.ui.history.RevisionDiff.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RevisionDiffTest {

    @Test fun `blocks strip tags, entities and empty blocks`() {
        val html = "<h2 data-bid=\"a\">Titel &amp; Mehr</h2><p data-bid=\"b\"><em>Ein</em>  Satz.</p><p><br></p>"
        assertEquals(listOf("Titel & Mehr", "Ein Satz."), RevisionDiff.blocks(html))
    }

    @Test fun `identical text is unchanged even with different markup`() {
        val blocks = RevisionDiff.diff("<p>Ein Satz.</p>", "<p><strong>Ein</strong> Satz.</p>")
        assertTrue(RevisionDiff.unchanged(blocks))
    }

    @Test fun `edited paragraph shows word level changes`() {
        val blocks = RevisionDiff.diff(
            "<p>Erster.</p><p>Der Hund schläft.</p><p>Letzter.</p>",
            "<p>Erster.</p><p>Der Kater schläft tief.</p><p>Letzter.</p>",
        )
        assertEquals(3, blocks.size)
        val mid = blocks[1].segments
        assertTrue(mid.toString(), mid.contains(Segment.Removed("Hund")))
        assertTrue(mid.toString(), mid.contains(Segment.Added("Kater")))
        assertEquals("Der Kater schläft tief.", mid.filter { it !is Segment.Removed }.joinToString("") { it.text })
        assertEquals("Der Hund schläft.", mid.filter { it !is Segment.Added }.joinToString("") { it.text })
    }

    @Test fun `added and removed paragraphs are whole blocks`() {
        val blocks = RevisionDiff.diff("<p>A</p><p>B</p>", "<p>A</p><p>B</p><p>C</p>")
        assertEquals(listOf(Segment.Added("C")), blocks.last().segments)
        val removed = RevisionDiff.diff("<p>A</p><p>B</p>", "<p>B</p>")
        assertEquals(listOf(Segment.Removed("A")), removed.first().segments)
        assertFalse(RevisionDiff.unchanged(removed))
    }

    @Test fun `several edited paragraphs pair up in order`() {
        val blocks = RevisionDiff.diff("<p>eins alt</p><p>zwei alt</p>", "<p>eins neu</p><p>zwei neu</p>")
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].segments.contains(Segment.Same("eins ")))
        assertTrue(blocks[1].segments.contains(Segment.Same("zwei ")))
    }

    @Test fun `html output escapes text and marks changes`() {
        val html = RevisionDiff.toHtml(RevisionDiff.diff("<p>a &lt;b&gt;</p>", "<p>a &lt;c&gt;</p>"))
        assertTrue(html, html.contains("&lt;<del>b</del><ins>c</ins>&gt;"))
    }
}
