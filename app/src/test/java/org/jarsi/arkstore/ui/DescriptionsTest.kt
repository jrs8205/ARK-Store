package org.jarsi.arkstore.ui

import org.jarsi.arkstore.ui.ReleaseNotes.Kind
import org.jarsi.arkstore.ui.ReleaseNotes.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class DescriptionsTest {

    @Test
    fun aLineBreakTagBreaksTheLine() {
        val lines = Descriptions.lines("First<br>Second<br/>Third")
        assertEquals(listOf("First", "Second", "Third"), lines.map { it.text })
        assertEquals(listOf(Kind.TEXT, Kind.TEXT, Kind.TEXT), lines.map { it.kind })
    }

    @Test
    fun paragraphsAndListItemsOfHtmlAreToldApart() {
        val lines = Descriptions.lines("<p>Para one</p><p>Para two</p>\n<ul>\n<li>One</li>\n<li>Two</li>\n</ul>")
        assertEquals(listOf(Kind.TEXT, Kind.TEXT, Kind.BULLET, Kind.BULLET), lines.map { it.kind })
        assertEquals(listOf("Para one", "Para two", "One", "Two"), lines.map { it.text })
    }

    @Test
    fun bulletCharactersBeginBullets() {
        val lines = Descriptions.lines("Intro\n\n• One\n• Two\n")
        assertEquals(listOf(Kind.TEXT, Kind.BULLET, Kind.BULLET), lines.map { it.kind })
        assertEquals(listOf("Intro", "One", "Two"), lines.map { it.text })
    }

    @Test
    fun boldStaysBoldAndOtherTagsGo() {
        val line = Descriptions.lines("A <b>bold</b> word and <tt>mono</tt>, <big>big</big> and <strike>gone</strike>").single()
        assertEquals("A bold word and mono, big and gone", line.text)
        assertEquals(Span("bold", bold = true), line.spans[1])
    }

    @Test
    fun linesOfAParagraphRunTogether() {
        val lines = Descriptions.lines("Line one\nline two\n\nLine three")
        assertEquals(listOf("Line one line two", "Line three"), lines.map { it.text })
    }

    @Test
    fun entitiesAreReadAfterTheTagsSoThatAnEscapedTagStaysText() {
        val line = Descriptions.lines("Backup &amp; restore, &lt;b&gt;not bold&lt;/b&gt;, &quot;x&quot; &#39;y&#39; a&nbsp;b &#8211; &#x2014; &unknown;").single()
        assertEquals("Backup & restore, <b>not bold</b>, \"x\" 'y' a b – — &unknown;", line.text)
    }
}
