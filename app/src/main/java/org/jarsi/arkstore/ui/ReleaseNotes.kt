package org.jarsi.arkstore.ui

/**
 * Release notes as developers write them on GitHub, in Markdown, read into lines the
 * details can show: headings, bullets, fenced code and plain text, with bold and code
 * within them. Only what release notes commonly use is understood; anything else stays
 * as written.
 */
internal object ReleaseNotes {

    enum class Kind { TEXT, HEADING, BULLET }

    data class Span(val text: String, val bold: Boolean = false, val code: Boolean = false)

    data class Line(val kind: Kind, val spans: List<Span>) {
        val text: String get() = spans.joinToString("") { it.text }
    }

    private val heading = Regex("""^#{1,6}\s+(.*?)(?:\s+#+)?\s*$""")
    private val bullet = Regex("""^\s*(?:[-*+]|\d+[.)])\s+(.*)$""")
    private val fence = Regex("""^\s*(?:```|~~~)""")
    private val image = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val autoLink = Regex("""<(https?://[^>\s]+)>""")
    // A tag by its shape, with attributes that have values, so that "a<b and c>d" is text.
    private val tag = Regex(
        """</?(?:img|br|hr|p|div|details|summary|b|i|u|s|em|strong|code|kbd|sub|sup|ul|ol|li|a|h[1-6])""" +
            """(?:\s+[\w:-]+=(?:"[^"]*"|'[^']*'|[^\s>]+))*\s*/?>""",
        RegexOption.IGNORE_CASE
    )
    private val mark = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1|`([^`]+)`""")

    fun parse(markdown: String): List<Line> {
        val lines = ArrayList<Line>()
        var blank = false
        var fenced = false
        for (raw in markdown.replace("\r\n", "\n").split('\n')) {
            val line = raw.trimEnd()
            if (fence.containsMatchIn(line)) {
                // Fenced code keeps its lines as they are, one line each, in monospace.
                fenced = !fenced
                blank = true
                continue
            }
            if (line.isBlank()) {
                blank = blank || lines.isNotEmpty()
                continue
            }
            if (fenced) {
                lines += Line(Kind.TEXT, listOf(Span(line, code = true)))
                continue
            }
            val heading = heading.matchEntire(line)
            val bullet = bullet.matchEntire(line)
            val spans = spans((heading ?: bullet)?.groupValues?.get(1) ?: line.trimStart())
            // A line with nothing left to show, an image alone, is not a line.
            if (spans.isEmpty()) continue
            val last = lines.lastOrNull()
            // Lines of one paragraph run together, and an indented line continues its
            // bullet; a blank line ends both.
            val continues = !blank && last != null &&
                (last.kind == Kind.TEXT || last.kind == Kind.BULLET && line.first().isWhitespace())
            when {
                heading != null -> lines += Line(Kind.HEADING, spans)
                bullet != null -> lines += Line(Kind.BULLET, spans)
                continues -> lines[lines.lastIndex] = Line(last!!.kind, last.spans + Span(" ") + spans)
                else -> lines += Line(Kind.TEXT, spans)
            }
            blank = false
        }
        return lines.map { Line(it.kind, merge(it.spans)) }
    }

    /** The text of one line with its bold and code parts told apart. */
    fun spans(text: String): List<Span> {
        val plain = text
            .replace(image, "")
            .replace(tag, "")
            .replace(link) { it.groupValues[1] }
            .replace(autoLink) { it.groupValues[1] }
        return merge(marked(plain, bold = false))
    }

    /** [text] split at its marks; what is bold may still hold code. */
    private fun marked(text: String, bold: Boolean): List<Span> {
        val spans = ArrayList<Span>()
        var at = 0
        for (match in mark.findAll(text)) {
            if (match.range.first > at) spans += Span(text.substring(at, match.range.first), bold = bold)
            val code = match.groupValues[3]
            if (code.isNotEmpty()) spans += Span(code, bold = bold, code = true)
            else spans += marked(match.groupValues[2], bold = true)
            at = match.range.last + 1
        }
        if (at < text.length) spans += Span(text.substring(at), bold = bold)
        return spans
    }

    private fun merge(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        for (span in spans) {
            if (span.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.bold == span.bold && last.code == span.code) {
                out[out.lastIndex] = last.copy(text = last.text + span.text)
            } else {
                out += span
            }
        }
        return out
    }
}
