package org.jarsi.arkstore.ui

import org.jarsi.arkstore.ui.ReleaseNotes.Kind
import org.jarsi.arkstore.ui.ReleaseNotes.Span
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {

    @Test
    fun headingsBulletsAndParagraphsAreToldApart() {
        val lines = ReleaseNotes.parse(
            """
            ## What's new

            - **Wake-up keeps going:** locking the phone no longer stops it.
            * Hold to rewind.
            1. First
            2) Second

            ## Installing
            Download `app.apk` below and open it
            on the phone.
            """.trimIndent()
        )
        assertEquals(
            listOf(Kind.HEADING, Kind.BULLET, Kind.BULLET, Kind.BULLET, Kind.BULLET, Kind.HEADING, Kind.TEXT),
            lines.map { it.kind }
        )
        assertEquals("What's new", lines[0].text)
        assertEquals(
            listOf(Span("Wake-up keeps going:", bold = true), Span(" locking the phone no longer stops it.")),
            lines[1].spans
        )
        assertEquals("Download app.apk below and open it on the phone.", lines[6].text)
        assertEquals(Span("app.apk", code = true), lines[6].spans[1])
    }

    @Test
    fun blankLinesEndAParagraphAndAreNotKept() {
        val lines = ReleaseNotes.parse("\n\nOne\n\n\nTwo\n\n")
        assertEquals(listOf("One", "Two"), lines.map { it.text })
    }

    @Test
    fun linksImagesAndTagsLeaveTheirText() {
        val spans = ReleaseNotes.spans("See [the notes](https://example.com) <https://example.com/x> ![shot](a.png)<br>__done__")
        assertEquals("See the notes https://example.com/x done", spans.joinToString("") { it.text })
        assertEquals(Span("done", bold = true), spans.last())
    }

    @Test
    fun unmatchedMarksStayAsWritten() {
        assertEquals(listOf(Span("a ** b `c")), ReleaseNotes.spans("a ** b `c"))
        assertEquals("2 * 3 * 4", ReleaseNotes.parse("2 * 3 * 4")[0].text)
    }
}
