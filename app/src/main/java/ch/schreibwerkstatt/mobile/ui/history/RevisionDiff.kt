package ch.schreibwerkstatt.mobile.ui.history

/**
 * Text-Vergleich zweier Revisionen für die Verlaufs-Vorschau («Änderungen»).
 *
 * Zweistufig, damit auch lange Abschnitte billig bleiben: erst ein LCS über die
 * Blöcke (Absätze, Überschriften, Listenpunkte — ein paar hundert), dann ein
 * Wort-Diff nur zwischen einem gelöschten und dem direkt folgenden neuen Block
 * (der Normalfall «Absatz bearbeitet»). Formatierung geht dabei verloren; die
 * Ansicht zeigt reinen Text mit `<del>`/`<ins>`.
 */
object RevisionDiff {

    sealed interface Segment {
        val text: String
        data class Same(override val text: String) : Segment
        data class Removed(override val text: String) : Segment
        data class Added(override val text: String) : Segment
    }

    /** Ein Block der Ausgabe: unverändert, ganz neu, ganz gelöscht oder geändert (Wort-Diff). */
    data class Block(val segments: List<Segment>)

    private const val MAX_WORD_CELLS = 4_000_000

    private val BLOCK_END = Regex(
        "</(p|h[1-6]|li|blockquote|pre|div|figcaption|td|th)\\s*>|<br\\s*/?>|<hr[^>]*>",
        RegexOption.IGNORE_CASE,
    )
    private val TAG = Regex("<[^>]*>")
    private val WS = Regex("\\s+")
    /** Leerraum, Wörter (inkl. Binnen-Apostroph/-Bindestrich) und einzelne Satzzeichen. */
    private val TOKEN = Regex("\\s+|[\\p{L}\\p{N}]+(?:['’-][\\p{L}\\p{N}]+)*|[^\\s\\p{L}\\p{N}]")

    /** Sichtbarer Text der Blöcke eines HTML-Stands, leere Blöcke fallen weg. */
    fun blocks(html: String): List<String> =
        html.split(BLOCK_END)
            .map { unescape(it.replace(TAG, "")).replace(WS, " ").trim() }
            .filter { it.isNotEmpty() }

    fun diff(oldHtml: String, newHtml: String): List<Block> {
        val a = blocks(oldHtml)
        val b = blocks(newHtml)
        val ops = lcsOps(a, b)
        val out = mutableListOf<Block>()
        var i = 0
        while (i < ops.size) {
            val op = ops[i]
            if (op is Op.Keep) {
                out += Block(listOf(Segment.Same(op.value)))
                i++
                continue
            }
            // Änderungsstrecke: erst alle gelöschten, dann alle neuen Blöcke. Paarweise
            // (1. mit 1., 2. mit 2. …) als bearbeitete Absätze zeigen, den Rest ganz.
            val dels = mutableListOf<String>()
            val inss = mutableListOf<String>()
            while (i < ops.size && ops[i] is Op.Del) { dels += ops[i].value; i++ }
            while (i < ops.size && ops[i] is Op.Ins) { inss += ops[i].value; i++ }
            val paired = minOf(dels.size, inss.size)
            for (k in 0 until paired) out += Block(wordDiff(dels[k], inss[k]))
            for (k in paired until dels.size) out += Block(listOf(Segment.Removed(dels[k])))
            for (k in paired until inss.size) out += Block(listOf(Segment.Added(inss[k])))
        }
        return out
    }

    /** true, wenn beide Stände denselben sichtbaren Text haben. */
    fun unchanged(blocks: List<Block>): Boolean =
        blocks.all { b -> b.segments.all { it is Segment.Same } }

    /** HTML-Fragment für die Vorschau-WebView (CSS für `del`/`ins` liefert die Seite). */
    fun toHtml(blocks: List<Block>): String = buildString {
        for (block in blocks) {
            val whole = block.segments.singleOrNull()
            val cls = when (whole) {
                is Segment.Added -> " class=\"added\""
                is Segment.Removed -> " class=\"removed\""
                else -> ""
            }
            append("<p").append(cls).append('>')
            for (s in block.segments) {
                when (s) {
                    is Segment.Same -> append(escape(s.text))
                    is Segment.Removed -> append("<del>").append(escape(s.text)).append("</del>")
                    is Segment.Added -> append("<ins>").append(escape(s.text)).append("</ins>")
                }
            }
            append("</p>")
        }
    }

    private fun wordDiff(old: String, new: String): List<Segment> {
        val a = TOKEN.findAll(old).map { it.value }.toList()
        val b = TOKEN.findAll(new).map { it.value }.toList()
        if (a.size.toLong() * b.size > MAX_WORD_CELLS) {
            return listOf(Segment.Removed(old), Segment.Added(new))
        }
        val merged = mutableListOf<Segment>()
        for (op in lcsOps(a, b)) {
            val seg = when (op) {
                is Op.Keep -> Segment.Same(op.value)
                is Op.Del -> Segment.Removed(op.value)
                is Op.Ins -> Segment.Added(op.value)
            }
            val last = merged.lastOrNull()
            if (last != null && last::class == seg::class) {
                merged[merged.lastIndex] = when (seg) {
                    is Segment.Same -> Segment.Same(last.text + seg.text)
                    is Segment.Removed -> Segment.Removed(last.text + seg.text)
                    is Segment.Added -> Segment.Added(last.text + seg.text)
                }
            } else merged += seg
        }
        return merged
    }

    private sealed interface Op {
        val value: String
        data class Keep(override val value: String) : Op
        data class Del(override val value: String) : Op
        data class Ins(override val value: String) : Op
    }

    /**
     * Klassisches LCS mit gemeinsamem Präfix/Suffix vorab abgeschnitten. Gelöschtes
     * steht innerhalb einer Änderungsstrecke vor Eingefügtem.
     */
    private fun lcsOps(a: List<String>, b: List<String>): List<Op> {
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }

        val n = endA - start
        val m = endB - start
        val ops = ArrayList<Op>(a.size + b.size)
        for (k in 0 until start) ops += Op.Keep(a[k])
        if (n.toLong() * m > MAX_WORD_CELLS) {
            // Zu gross für die Tabelle: Mittelteil als ganz ersetzt zeigen.
            for (k in start until endA) ops += Op.Del(a[k])
            for (k in start until endB) ops += Op.Ins(b[k])
            for (k in endA until a.size) ops += Op.Keep(a[k])
            return ops
        }
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[start + i] == b[start + j]) dp[i + 1][j + 1] + 1
            else maxOf(dp[i + 1][j], dp[i][j + 1])
        }

        val dels = mutableListOf<Op>()
        val inss = mutableListOf<Op>()
        fun flush() { ops += dels; ops += inss; dels.clear(); inss.clear() }
        var i = 0
        var j = 0
        while (i < n || j < m) {
            when {
                i < n && j < m && a[start + i] == b[start + j] -> {
                    flush(); ops += Op.Keep(a[start + i]); i++; j++
                }
                j < m && (i == n || dp[i][j + 1] >= dp[i + 1][j]) -> { inss += Op.Ins(b[start + j]); j++ }
                else -> { dels += Op.Del(a[start + i]); i++ }
            }
        }
        flush()
        for (k in endA until a.size) ops += Op.Keep(a[k])
        return ops
    }

    private fun unescape(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&#x27;", "'")
        .replace("&amp;", "&")

    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
