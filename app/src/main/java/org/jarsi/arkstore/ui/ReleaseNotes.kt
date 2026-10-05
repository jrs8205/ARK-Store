package org.jarsi.arkstore.ui

/**
 * Release notes as developers write them on GitHub, in Markdown, read into lines the
 * details can show: headings, bullets and plain text, with bold and code within them.
 * Only what release notes commonly use is understood; anything else stays as written.
 */
internal object ReleaseNotes {

    enum class Kind { TEXT, HEADING, BULLET }

    data class Span(val text: String, val bold: Boolean = false, val code: Boolean = false)

    data class Line(val kind: Kind, val spans: List<Span>) {
        val text: String get() = spans.joinToString("") { it.text }
    }

    private val heading = Regex("""^#{1,6}\s+(.*?)\s*#*\s*$""")
    private val bullet = Regex("""^\s*(?:[-*+]|\d+[.)])\s+(.*)$""")
    private val image = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val autoLink = Regex("""<(https?://[^>\s]+)>""")
    private val tag = Regex("""</?(?:img|br|p|details|summary|b|i|em|strong|ul|ol|li|a|h[1-6])\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val mark = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1|`([^`]+)`""")

    fun parse(markdown: String): List<Line> {
        val lines = ArrayList<Line>()
        var blank = false
        for (raw in markdown.replace("\r\n", "\n").split('\n')) {
            val line = raw.trimEnd()
            if (line.isBlank()) {
                blank = lines.isNotEmpty()
                continue
            }
            heading.matchEntire(line)?.let { match ->
                lines += Line(Kind.HEADING, spans(match.groupValues[1]))
                blank = false
                return@let
            } ?: bullet.matchEntire(line)?.let { match ->
                lines += Line(Kind.BULLET, spans(match.groupValues[1]))
                blank = false
                return@let
            } ?: run {
                // Lines of one paragraph run together; a blank line ends it.
                val last = lines.lastOrNull()
                if (!blank && last != null && last.kind == Kind.TEXT) {
                    lines[lines.lastIndex] = Line(Kind.TEXT, last.spans + spans(" " + line.trimStart()))
                } else {
                    lines += Line(Kind.TEXT, spans(line.trimStart()))
                }
                blank = false
            }
        }
        return lines
    }

    /** The text of one line with its bold and code parts told apart. */
    fun spans(text: String): List<Span> {
        val plain = text
            .replace(image, "")
            .replace(tag, "")
            .replace(link) { it.groupValues[1] }
            .replace(autoLink) { it.groupValues[1] }
        val spans = ArrayList<Span>()
        var at = 0
        for (match in mark.findAll(plain)) {
            if (match.range.first > at) spans += Span(plain.substring(at, match.range.first))
            val code = match.groupValues[3]
            spans += if (code.isNotEmpty()) Span(code, code = true) else Span(match.groupValues[2], bold = true)
            at = match.range.last + 1
        }
        if (at < plain.length) spans += Span(plain.substring(at))
        return merge(spans)
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
