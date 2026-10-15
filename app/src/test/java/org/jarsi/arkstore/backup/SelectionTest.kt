package org.jarsi.arkstore.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionTest {

    private val rows = listOf("a" to true, "b" to true, "c" to false, "d" to false)
    private val all = setOf("a", "b", "c", "d")

    @Test
    fun theRuleChoosesUntilTheUserSaysOtherwise() {
        assertEquals(setOf("a", "b"), Selection.chosen(rows, all, Selection()))
        assertEquals(setOf("b", "c"), Selection.chosen(rows, all, Selection().toggled("a", false).toggled("c", true)))
        // What cannot be chosen is not, whatever was said.
        assertEquals(setOf("a"), Selection.chosen(rows, setOf("a", "c"), Selection().toggled("c", false)))
    }

    @Test
    fun allOrNoneOfTheGivenKeysLeavesTheRestAsItWasSaid() {
        // None, then all of what is on the screen: the apps off the screen stay as they were.
        val none = Selection().all(all, false)
        assertEquals(emptySet<String>(), Selection.chosen(rows, all, none))
        val some = none.all(listOf("c"), true)
        assertEquals(setOf("c"), Selection.chosen(rows, all, some))
        // All of the screen, then none of a part of it.
        val every = some.all(all, true)
        assertEquals(all, Selection.chosen(rows, all, every))
        assertEquals(setOf("a", "c"), Selection.chosen(rows, all, every.all(listOf("b", "d"), false)))
        // A word by hand on a key off the screen stands too.
        val dropped = Selection().toggled("a", false).all(listOf("c", "d"), true)
        assertEquals(setOf("b", "c", "d"), Selection.chosen(rows, all, dropped))
    }
}
