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

    fun lines(text: String): List<ReleaseNotes.Line> {
        // Lines of a paragraph run together, as F-Droid reads them, so a break the text
        // asks for is a blank line, which ends the paragraph.
        val marked = text
            .replace(lineBreak, "\n\n")
            .replace(listItem, "\n- ")
            .replace(paragraph, "\n\n")
            .replace(bold, "**")
            .replace(bullet, "- ")
        return ReleaseNotes.parse(marked, LIMIT)
    }
}
