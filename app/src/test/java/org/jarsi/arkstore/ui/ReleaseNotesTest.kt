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
    fun aLineLeftEmptyByItsImageIsNotShown() {
        val lines = ReleaseNotes.parse("![shot](a.png)\n- ![b](b.png)\nOne\n![c](c.png)\nTwo")
        assertEquals(listOf("One Two"), lines.map { it.text })
    }

    @Test
    fun fencedCodeKeepsItsLinesInMonospace() {
        val lines = ReleaseNotes.parse("Install:\n```sh\nadb install app.apk\n\n  adb shell pm grant x\n```\nDone")
        assertEquals(
            listOf("Install:", "adb install app.apk", "  adb shell pm grant x", "Done"),
            lines.map { it.text }
        )
        assertEquals(listOf(Span("adb install app.apk", code = true)), lines[1].spans)
        assertEquals(Kind.TEXT, lines[1].kind)
        // A fence left open runs to the end.
        assertEquals(listOf("a", "b"), ReleaseNotes.parse("~~~\na\nb").map { it.text })
    }

    @Test
    fun anIndentedLineContinuesItsBullet() {
        val lines = ReleaseNotes.parse("- Wake-up keeps going even when\n  the phone is locked.\nNext")
        assertEquals(listOf(Kind.BULLET, Kind.TEXT), lines.map { it.kind })
        assertEquals("Wake-up keeps going even when the phone is locked.", lines[0].text)
        assertEquals("Next", lines[1].text)
    }

    @Test
    fun aHeadingKeepsAHashOfItsOwn() {
        assertEquals("Support for C#", ReleaseNotes.parse("## Support for C#")[0].text)
        assertEquals("Closed", ReleaseNotes.parse("## Closed ##")[0].text)
    }

    @Test
    fun boldAroundCodeIsBoldCode() {
        assertEquals(
            listOf(Span("Run ", bold = true), Span("adb install", bold = true, code = true)),
            ReleaseNotes.spans("**Run `adb install`**")
        )
    }

    @Test
    fun anAngleBracketInTextIsNotATag() {
        assertEquals("works if a<b and c>d", ReleaseNotes.spans("works if a<b and c>d").joinToString("") { it.text })
        assertEquals("xy", ReleaseNotes.spans("x<br/>y<b><img src=\"a.png\" width=100>").joinToString("") { it.text })
    }

    @Test
    fun codeKeepsTagsAndLinksAsWritten() {
        assertEquals(
            listOf(Span("Use "), Span("<br>", code = true), Span(" to insert a line break.")),
            ReleaseNotes.spans("Use `<br>` to insert a line break.")
        )
        assertEquals(Span("[label](url)", code = true), ReleaseNotes.spans("Use `[label](url)` syntax.")[1])
        // While a link's text is still read for its marks.
        assertEquals(listOf(Span("x", bold = true)), ReleaseNotes.spans("[**x**](https://example.com)"))
        // A code span taken away with its image does not shift the ones after it.
        val line = ReleaseNotes.parse("![`logo`](icon.png) Run `app --safe` then `exit`").single()
        assertEquals("Run app --safe then exit", line.text)
        assertEquals(listOf(Span("app --safe", code = true), Span("exit", code = true)), line.spans.filter { it.code })
        // Private-use characters, which stand in for the code spans meanwhile, are not text.
        assertEquals(listOf(Span("ab")), ReleaseNotes.spans("ab"))
    }

    @Test
    fun aFenceClosesOnlyWithItsOwnKind() {
        val lines = ReleaseNotes.parse("~~~sh\necho hi\n```\necho bye\n~~~\nDone")
        assertEquals(listOf("echo hi", "```", "echo bye", "Done"), lines.map { it.text })
        assertEquals(listOf(true, true, true, false), lines.map { it.spans.single().code })
        // A longer fence holds a shorter one as content.
        assertEquals(listOf("```", "x", "```"), ReleaseNotes.parse("````\n```\nx\n```\n````").map { it.text })
    }

    @Test
    fun notesAreCutAtTheLengthTheIndexKeeps() {
        val text = ReleaseNotes.parse("a".repeat(5000)).single().text
        assertEquals(4000, text.length)
    }

    @Test
    fun hostileInputIsReadInGoodTime() {
        val inputs = listOf(
            "<img" + "  a=\"a\" ".repeat(24) + "!",
            "# x" + " ".repeat(3990) + "z",
            "**a ".repeat(1000),
            "<a " + "b='c' ".repeat(600) + "d",
            "[x](" + "y".repeat(3990)
        )
        val started = System.nanoTime()
        inputs.forEach { ReleaseNotes.parse(it) }
        val millis = (System.nanoTime() - started) / 1_000_000
        assert(millis < 1000) { "took $millis ms" }
    }

    @Test
    fun unmatchedMarksStayAsWritten() {
        assertEquals(listOf(Span("a ** b `c")), ReleaseNotes.spans("a ** b `c"))
        assertEquals("2 * 3 * 4", ReleaseNotes.parse("2 * 3 * 4")[0].text)
    }
}
