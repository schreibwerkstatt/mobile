package ch.schreibwerkstatt.mobile.bundle

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Verdrahtungs-Guard für den Server-Merge im WebView
 * ([app/src/main/assets/editor-host/host.html]): Die EditorBridge spielt einen
 * gemergten Server-Stand über `window.__sw.applyServerPage` ein. host.html darf das
 * nur tun, wenn der Editor noch den gesendeten Stand zeigt — dazu muss
 * `bridge.savePage` ihn vor der Übergabe festhalten (`noteSent`).
 */
class EditorHostMergeWiringTest {

    @Test fun `host exposes applyServerPage and guards it with the sent text`() {
        val host = host()
        assertTrue("window.__sw.applyServerPage fehlt", Regex("""applyServerPage\s*\(json\)""").containsMatchIn(host))
        assertTrue("applyServerPage muss gegen lastSentText prüfen", host.contains("text !== lastSentText"))
        val save = host.substringAfter("savePage(page) {").substringBefore("return p;")
        assertTrue(
            "bridge.savePage muss den gesendeten Stand vor SWHost.savePage festhalten",
            save.indexOf("noteSent()") in 0 until save.indexOf("SWHost.savePage"),
        )
    }

    @Test fun `bridge calls applyServerPage only for merged saves`() {
        val bridge = source("src/main/java/ch/schreibwerkstatt/mobile/editor/EditorBridge.kt")
        val merged = bridge.substringAfter("if (res.merged) {").substringBefore("}")
        assertTrue(merged.contains("window.__sw.applyServerPage("))
    }

    private fun host(): String = source("src/main/assets/editor-host/host.html")

    /** Liest eine Datei relativ zum Modul-Verzeichnis (CWD der Unit-Tests). */
    private fun source(rel: String): String {
        val f = listOf(File(rel), File("app/$rel")).firstOrNull { it.exists() }
            ?: error("Datei nicht gefunden: $rel (cwd=${File(".").absolutePath})")
        return f.readText()
    }
}
