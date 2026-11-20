package ch.schreibwerkstatt.mobile.bundle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verdrahtungs-Guard für die Rechtschreibprüfung im WebView
 * ([app/src/main/assets/editor-host/host.html]).
 *
 * Der Spellcheck-Controller kommt aus dem OTA-Bundle, wird aber von host.html
 * selbst gemountet — mit über die Bridge injiziertem `checkText`/`addWord`. Sein
 * Default würde relativ gegen den Asset-Origin (`appassets.androidplatform.net`)
 * fetchen: dort gibt es weder die Route noch den `Authorization`-Header, die
 * Prüfung bliebe still tot. Genau diese Fehlerklasse fängt dieser Test — kaputte
 * Verdrahtung fällt sonst erst am Gerät auf.
 */
class EditorHostSpellcheckWiringTest {

    @Test fun `host mounts the bundled spellcheck controller`() {
        val host = host()
        assertTrue(
            "host.html muss den Spellcheck-Controller des Bundles importieren",
            host.contains("./js/cards/editor-spellcheck/controller.js"),
        )
        assertTrue(
            "host.html muss createSpellcheckController aufrufen",
            host.contains("createSpellcheckController("),
        )
        assertTrue(
            "Ersetzungen laufen über den geteilten apply-replacement-Helfer des Bundles",
            host.contains("./js/editor/shared/apply-replacement.js")
                && host.contains("applySpellcheckReplacement("),
        )
    }

    @Test fun `check and dictionary calls go through the native bridge`() {
        val host = host()
        assertTrue(
            "checkText muss über die Bridge (SWHost.ltCheck) laufen",
            Regex("""checkText\s*:""").containsMatchIn(host) && host.contains("'ltCheck'"),
        )
        assertTrue(
            "addWord muss über die Bridge (SWHost.ltAddWord) laufen",
            Regex("""addWord\s*:""").containsMatchIn(host) && host.contains("'ltAddWord'"),
        )
        assertFalse(
            "kein direkter fetch auf /languagetool oder /dictionary — der Asset-Origin " +
                "kennt weder Route noch Auth-Header",
            Regex("""fetch\(\s*['"]/?(languagetool|dictionary)""").containsMatchIn(host),
        )
    }

    @Test fun `spellcheck is native-gated and localized from string resources`() {
        val host = host()
        assertTrue(
            "natives An/Aus über window.__sw.setSpellcheck",
            host.contains("setSpellcheck"),
        )
        assertTrue(
            "isEnabled muss am nativen Schalter hängen (kein hartes true)",
            host.contains("isEnabled: () => spell.on"),
        )
        assertTrue(
            "Badge-/Popover-Texte kommen aus den @string/-Ressourcen (SWHost.spellcheckStrings)",
            host.contains("SWHost.spellcheckStrings()"),
        )
    }

    @Test fun `spellcheck re-checks after replaceContent`() {
        val host = host()
        assertTrue(
            "refreshSpellcheck muss controller.refresh() per Feature-Check aufrufen",
            host.contains("typeof spell.ctl.refresh === 'function'"),
        )
        // replaceContent feuert kein `input`: jeder der drei Einspiel-Pfade
        // (merged-Antwort, Sync-Pull, «Server-Stand laden») muss selbst neu prüfen.
        val calls = Regex("""refreshSpellcheck\(\)""").findAll(host).count()
        assertTrue("refreshSpellcheck() in allen drei replaceContent-Pfaden (gefunden: $calls)", calls >= 3)
    }

    /** Liest host.html relativ zum Modul-Verzeichnis (CWD der Unit-Tests). */
    private fun host(): String {
        val rel = "src/main/assets/editor-host/host.html"
        val f = listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() }
            ?: error("Asset nicht gefunden: $rel (cwd=${File(".").absolutePath})")
        return f.readText()
    }
}
