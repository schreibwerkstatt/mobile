package ch.schreibwerkstatt.mobile.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Floskel-Filter fürs Diktat (Whisper-Geisterphrasen aus stillen Segmenten), gesteuert
 * über `uncertain` aus `POST /stt/transcribe` (Vertrag api 5).
 */
class DictationHallucinationTest {

    private fun hallu(text: String, uncertain: Boolean?) =
        DictationController.isLikelyHallucination(text, uncertain)

    @Test fun `ghost phrase is dropped when uncertain or confidence unknown`() {
        assertTrue(hallu("Vielen Dank.", true))
        assertTrue(hallu("Vielen Dank fürs Zuschauen!", null))
        assertTrue(hallu("  Tschüss …  ", null))
        assertTrue(hallu("Untertitel im Auftrag des ZDF, 2021", true))
        assertTrue(hallu("Untertitelung des NDR", null))
        assertTrue(hallu("SWR 2020", null))
        assertTrue(hallu("Copyright WDR 2021", null))
        assertTrue(hallu("Untertitel von Amara.org", null))
    }

    @Test fun `confidently spoken text is never filtered`() {
        // «Tschüss.» als echte Dialogzeile: das Modell hat sicher Sprache gehört.
        assertFalse(hallu("Tschüss.", false))
        assertFalse(hallu("Vielen Dank.", false))
    }

    @Test fun `prose that merely mentions the markers survives`() {
        assertFalse(hallu("Sie arbeitete beim SRF.", null))
        assertFalse(hallu("Die Untertitel liefen bis 2003 mit.", true))
        assertFalse(hallu("Vielen Dank für den Kaffee, sagte sie.", true))
        assertFalse(hallu("", true))
    }
}
