package org.jarsi.arkstore.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoriesTest {
    @Test
    fun explicitTopicWinsOverRecognisedOnes() {
        assertEquals("tools", Categories.of(listOf("android", "launcher", "arkstore-tools")))
        assertEquals("games", Categories.of(listOf("ArkStore-Games")))
    }

    @Test
    fun recognisesCommonTopics() {
        assertEquals("personalization", Categories.of(listOf("android", "kotlin", "launcher")))
        assertEquals("communication", Categories.of(listOf("android", "dialer", "sms")))
        assertEquals("system", Categories.of(listOf("battery", "widget")))
    }

    @Test
    fun everythingElseIsOther() {
        assertEquals(Categories.OTHER, Categories.of(emptyList()))
        assertEquals(Categories.OTHER, Categories.of(listOf("android", "kotlin")))
        assertEquals(Categories.OTHER, Categories.of(listOf("arkstore-nonsense")))
    }
}
