package org.jarsi.arkstore.ui

/**
 * A description as developers publish it for F-Droid, in `full_description.txt`: plain
 * text whose paragraphs are set apart by blank lines, with bullets, or the few tags of
 * HTML that F-Droid allows. It is turned into the lines the details show the way release
 * notes are (see [ReleaseNotes]), after its tags are read for what they mean to the lines.
 */
internal object Descriptions {

    /** The most characters that are read: a description is longer than release notes. */
    const val LIMIT = 20_000

    private val lineBreak = Regex("""<br\s*/?>|</p>|</li>|</ul>|</ol>""", RegexOption.IGNORE_CASE)
    private val listItem = Regex("""<li>""", RegexOption.IGNORE_CASE)
    private val paragraph = Regex("""<p>""", RegexOption.IGNORE_CASE)
    private val bold = Regex("""</?(?:b|strong)>""", RegexOption.IGNORE_CASE)
    private val bullet = Regex("""^[ \t]*[•·][ \t]*""", RegexOption.MULTILINE)
    private val entity = Regex("""&(#\d{1,7}|#[xX][0-9a-fA-F]{1,6}|amp|lt|gt|quot|apos|nbsp);""")
    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00a0")

    fun lines(text: String): List<ReleaseNotes.Line> {
        // Lines of a paragraph run together, as F-Droid reads them, so a break the text
        // asks for is a blank line, which ends the paragraph.
        val marked = text
            .replace(lineBreak, "\n\n")
            .replace(listItem, "\n- ")
            .replace(paragraph, "\n\n")
            .replace(bold, "**")
            .replace(bullet, "- ")
        // Entities are read once the tags have been: "&lt;b&gt;" is text, not a tag.
        return ReleaseNotes.parse(marked, LIMIT).map { line ->
            line.copy(spans = line.spans.map { it.copy(text = unescaped(it.text)) })
        }
    }

    private fun unescaped(text: String): String = text.replace(entity) { match ->
        val name = match.groupValues[1]
        val code = when {
            name.startsWith("#x") || name.startsWith("#X") -> name.substring(2).toIntOrNull(16)
            name.startsWith("#") -> name.substring(1).toIntOrNull()
            else -> null
        }
        when {
            code != null -> if (code in 1..0x10FFFF) String(Character.toChars(code)) else match.value
            else -> named[name] ?: match.value
        }
    }
}
