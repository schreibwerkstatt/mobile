package ch.schreibwerkstatt.mobile.ui.research

import android.content.Intent
import android.net.Uri
import android.text.SpannableString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Teilen-Empfang und Link-Prüfung des Recherche-Erfassens. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SharedResearchTest {

    private fun send(text: CharSequence?, subject: String? = null, type: String = "text/plain") =
        Intent(Intent.ACTION_SEND).apply {
            this.type = type
            text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
        }

    @Test fun `explicit http url wins over a bare domain earlier in the text`() {
        val s = parseShareIntent(send("Artikel auf spiegel.de: https://www.spiegel.de/kultur/a-1.html"))!!
        assertEquals("https://www.spiegel.de/kultur/a-1.html", s.url)
        assertEquals("Artikel auf spiegel.de:", s.title)
    }

    @Test fun `trailing punctuation is not part of the url`() {
        assertEquals("https://example.org/x", findSharedUrl("Siehe (https://example.org/x)."))
    }

    @Test fun `formatted text shared as CharSequence is read`() {
        val s = parseShareIntent(send(SpannableString("Titel\nhttps://example.org/a")))!!
        assertEquals("https://example.org/a", s.url)
        assertEquals("Titel", s.title)
    }

    @Test fun `subject becomes the title and repeated text is not a note`() {
        val s = parseShareIntent(send("Seitentitel https://example.org/a", subject = "Seitentitel"))!!
        assertEquals("Seitentitel", s.title)
        assertEquals("", s.note)
    }

    @Test fun `shared image is picked up from the stream extra`() {
        val uri = Uri.parse("content://media/external/images/1")
        val s = parseShareIntent(send(null, type = "image/jpeg").putExtra(Intent.EXTRA_STREAM, uri))!!
        assertEquals(uri.toString(), s.imageUri)
        assertEquals("image/jpeg", s.imageMime)
        assertEquals("", s.url)
    }

    @Test fun `every share gets its own id and survives the instance state`() {
        val a = parseShareIntent(send("https://example.org/a"))!!
        val b = parseShareIntent(send("https://example.org/a"))!!
        assertNotEquals(a.id, b.id)
        assertEquals(a, SharedResearch.fromArray(a.toArray()))
        assertNull(SharedResearch.fromArray(arrayOf("x")))
    }

    @Test fun `non send intents are ignored`() {
        assertNull(parseShareIntent(Intent(Intent.ACTION_VIEW)))
        assertNull(parseShareIntent(send(null)))
    }

    @Test fun `urls are normalized and only http(s) is accepted`() {
        assertEquals("https://example.org/x", normalizeResearchUrl(" example.org/x "))
        assertEquals("https://localhost:3000/a", normalizeResearchUrl("localhost:3000/a"))
        assertEquals("http://example.org", normalizeResearchUrl("http://example.org"))
        assertEquals("mailto:a@b.ch", normalizeResearchUrl("mailto:a@b.ch"))

        assertTrue(isValidResearchUrl(""))
        assertTrue(isValidResearchUrl("https://example.org/x"))
        assertFalse(isValidResearchUrl("mailto:a@b.ch"))
        assertFalse(isValidResearchUrl("ftp://example.org/file"))
        assertFalse(isValidResearchUrl("https://example.org/" + "a".repeat(RESEARCH_URL_MAX)))
    }
}
