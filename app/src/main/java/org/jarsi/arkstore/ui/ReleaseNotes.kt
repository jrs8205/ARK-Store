package org.jarsi.arkstore.ui

/**
 * Release notes as developers write them on GitHub, in Markdown, read into lines the
 * details can show: headings, bullets, fenced code and plain text, with bold and code
 * within them. Only what release notes commonly use is understood; anything else stays
 * as written. The notes are read on the main thread, so they are cut at [LIMIT], the
 * length the index keeps, and read in time linear in that.
 */
internal object ReleaseNotes {

    /** The most characters that are read; the index keeps the same. */
    const val LIMIT = 4000

    enum class Kind { TEXT, HEADING, BULLET }

    data class Span(val text: String, val bold: Boolean = false, val code: Boolean = false)

    data class Line(val kind: Kind, val spans: List<Span>) {
        val text: String get() = spans.joinToString("") { it.text }
    }

    private val bullet = Regex("""^\s*(?:[-*+]|\d+[.)])\s+(.*)$""")
    private val fence = Regex("""^\s{0,3}(`{3,}|~{3,})(.*)$""")
    private val image = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val link = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val autoLink = Regex("""<(https?://[^>\s]+)>""")
    // A tag by its shape, with attributes that have values, so that "a<b and c>d" is text.
    // An unquoted value never starts with a quote, so that each attribute reads one way
    // only and a tag left open fails in time linear in its length.
    private val tag = Regex(
        """</?(?:img|br|hr|p|div|details|summary|b|i|u|s|em|strong|code|kbd|sub|sup|ul|ol|li|a|h[1-6])""" +
            """(?:\s+[\w:-]+=(?:"[^"]*"|'[^']*'|[^\s>"']+))*\s*/?>""",
        RegexOption.IGNORE_CASE
    )
    private val code = Regex("""`([^`]+)`""")
    private val bold = Regex("""(\*\*|__)(?=\S)(.+?)(?<=\S)\1""")

    /** Stands in for a code span while the text around it is cleaned and marked. */
    private const val HELD = ''

    fun parse(markdown: String): List<Line> {
        val lines = ArrayList<Line>()
        var blank = false
        var fenced: MatchResult? = null
        for (raw in markdown.take(LIMIT).replace("\r\n", "\n").split('\n')) {
            val line = raw.trimEnd()
            val fence = fence.matchEntire(line)
            if (fenced != null) {
                // Fenced code keeps its lines as they are, one line each, in monospace,
                // until a fence of the same kind at least as long, with nothing after it.
                if (fence != null && fence.groupValues[2].isBlank() && closes(fence, fenced)) {
                    fenced = null
                    blank = true
                } else if (line.isNotBlank()) {
                    lines += Line(Kind.TEXT, listOf(Span(line, code = true)))
                }
                continue
            }
            if (fence != null) {
                fenced = fence
                blank = true
                continue
            }
            if (line.isBlank()) {
                blank = blank || lines.isNotEmpty()
                continue
            }
            val heading = headingOf(line)
            val bullet = bullet.matchEntire(line)
            val spans = spans(heading ?: bullet?.groupValues?.get(1) ?: line.trimStart())
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

    private fun closes(fence: MatchResult, opened: MatchResult): Boolean {
        val marks = fence.groupValues[1]
        val opening = opened.groupValues[1]
        return marks[0] == opening[0] && marks.length >= opening.length
    }

    /**
     * The text of a heading line, one to six hashes and a space, without a closing run of
     * hashes set off by a space; null for a line that is not a heading. Read by hand,
     * so that a long run of spaces costs no more than its length.
     */
    private fun headingOf(line: String): String? {
        var hashes = 0
        while (hashes < line.length && line[hashes] == '#') hashes++
        if (hashes !in 1..6 || hashes == line.length || !line[hashes].isWhitespace()) return null
        val text = line.substring(hashes).trim()
        val body = text.trimEnd('#')
        return if (body.length < text.length && (body.isEmpty() || body.last().isWhitespace())) body.trimEnd() else text
    }

    /**
     * The text of one line with its bold and code parts told apart. Code is taken out
     * first and kept as written, while the text around it loses its images, tags and
     * link addresses and is read for its bold marks; then the code goes back in place.
     */
    fun spans(text: String): List<Span> {
        val codes = ArrayList<String>()
        val held = text.replace(code) { codes += it.groupValues[1]; HELD.toString() }
        val plain = held
            .replace(image, "")
            .replace(tag, "")
            .replace(link) { it.groupValues[1] }
            .replace(autoLink) { it.groupValues[1] }
        val spans = ArrayList<Span>()
        var next = 0
        for (span in marked(plain, bold = false)) {
            var at = 0
            while (at < span.text.length) {
                val held = span.text.indexOf(HELD, at).takeIf { it >= 0 && next < codes.size } ?: span.text.length
                if (held > at) spans += span.copy(text = span.text.substring(at, held))
                if (held < span.text.length) spans += Span(codes[next++], bold = span.bold, code = true)
                at = held + 1
            }
        }
        return merge(spans)
    }

    /** [text] split at its bold marks; what is bold may hold bold again. */
    private fun marked(text: String, bold: Boolean): List<Span> {
        val spans = ArrayList<Span>()
        var at = 0
        for (match in this.bold.findAll(text)) {
            if (match.range.first > at) spans += Span(text.substring(at, match.range.first), bold = bold)
            spans += marked(match.groupValues[2], bold = true)
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
