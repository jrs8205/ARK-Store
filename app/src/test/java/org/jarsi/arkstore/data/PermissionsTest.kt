package org.jarsi.arkstore.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PermissionsTest {

    private fun asked(name: String, maxSdk: Int? = null) = Permissions.Asked(name, maxSdk)

    @Test
    fun aListWritesThePlatformsOwnNamesShortAndTheRestWhole() {
        assertEquals(
            listOf(".INTERNET", ".health.READ_STEPS", "com.example.permission.OWN", "android.permission", "android.permissions.X"),
            Permissions.listed(
                listOf(
                    asked("android.permission.INTERNET"),
                    asked("android.permission.health.READ_STEPS"),
                    asked("com.example.permission.OWN"),
                    asked("android.permission"),
                    asked("android.permissions.X")
                )
            )
        )
    }

    @Test
    fun theHighestAndroidAskedOnIsWrittenAfterTheName() {
        assertEquals(
            listOf(".WRITE_EXTERNAL_STORAGE@28", ".INTERNET"),
            Permissions.listed(listOf(asked("android.permission.WRITE_EXTERNAL_STORAGE", 28), asked("android.permission.INTERNET")))
        )
        // Android takes 0 for no limit, and a limit no device comes to is none either.
        assertEquals(
            listOf(".A", ".B", ".C"),
            Permissions.listed(listOf(asked("android.permission.A", 0), asked("android.permission.B", 1 shl 20), asked("android.permission.C", -3)))
        )
    }

    @Test
    fun aNameAskedForTwiceIsListedOnceWithItsWidestReach() {
        assertEquals(
            listOf(".A", ".B", ".C@32"),
            Permissions.listed(
                listOf(
                    asked("android.permission.A", 28), asked("android.permission.B"), asked("android.permission.A"),
                    asked("android.permission.B", 30), asked("android.permission.C", 28), asked("android.permission.C", 32)
                )
            )
        )
    }

    @Test
    fun namesNotWrittenTheWayNamesAreAreLeftOut() {
        val long = "a." + "b".repeat(Permissions.MAX_NAME)
        assertEquals(
            listOf("ok.NAME_1"),
            Permissions.listed(
                listOf(
                    asked(""), asked("no spaces.please"), asked("android.permission.CAMERA\n"), asked("quote\"d"),
                    asked("at@sign"), asked(".CAMERA"), asked(long), asked("ok.NAME_1")
                )
            )
        )
    }

    @Test
    fun onlySoManyAreKept() {
        val names = List(Permissions.MAX + 20) { "org.example.P$it" }
        assertEquals(names.take(Permissions.MAX), Permissions.listed(names.map { asked(it) }))
    }

    @Test
    fun anEntryOfAListIsReadBackAsWhatItStandsFor() {
        assertEquals(asked("android.permission.CAMERA"), Permissions.asked(".CAMERA"))
        assertEquals(asked("android.permission.WRITE_EXTERNAL_STORAGE", 28), Permissions.asked(".WRITE_EXTERNAL_STORAGE@28"))
        assertEquals(asked("com.example.permission.OWN"), Permissions.asked("com.example.permission.OWN"))
        assertEquals(asked("android.permission.health.READ_STEPS"), Permissions.asked(".health.READ_STEPS"))
        for (odd in listOf("", ".", "@28", ".A@", ".A@x", ".A@0", ".A@99999", ".A@28@29", "two words", ".A B", "a\"b")) {
            assertNull(odd, Permissions.asked(odd))
        }
    }

    @Test
    fun whatAFileOfAListTellsIsTakenAsWritten() {
        fun file(permissions: Any?) = JSONObject().put("permissions", permissions)
        assertEquals(
            listOf(".INTERNET", ".CAMERA@30"),
            Permissions.of(file(JSONArray(listOf(".INTERNET", ".CAMERA@30"))))
        )
        assertEquals(emptyList<String>(), Permissions.of(file(JSONArray())))
        // A list written before the permissions were read tells none, which is not the same as none asked for.
        assertNull(Permissions.of(JSONObject()))
        assertNull(Permissions.of(file(JSONObject.NULL)))
        assertNull(Permissions.of(file("INTERNET")))
        // What is not written the way entries are is left out, and so is what comes after the most kept.
        assertEquals(
            listOf(".INTERNET"),
            Permissions.of(file(JSONArray(listOf(7, ".INTERNET", "two words", JSONObject.NULL, ".A@x"))))
        )
        val many = List(Permissions.MAX + 5) { "org.example.P$it" }
        assertEquals(many.take(Permissions.MAX), Permissions.of(file(JSONArray(many))))
    }

    @Test
    fun aDeviceIsToldWhatItWouldBeAskedFor() {
        val listed = listOf(".INTERNET", ".WRITE_EXTERNAL_STORAGE@28", ".BLUETOOTH@30", "org.example.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION", "com.other.permission.X")
        assertEquals(
            listOf("android.permission.INTERNET", "android.permission.BLUETOOTH", "com.other.permission.X"),
            Permissions.onDevice(listed, 30, "org.example.app")
        )
        assertEquals(
            listOf("android.permission.INTERNET", "android.permission.WRITE_EXTERNAL_STORAGE", "android.permission.BLUETOOTH", "com.other.permission.X"),
            Permissions.onDevice(listed, 28, "org.example.app")
        )
        assertEquals(
            listOf("android.permission.INTERNET", "com.other.permission.X"),
            Permissions.onDevice(listed, 35, "org.example.app")
        )
        // Without the app's package nothing is taken for its own.
        assertEquals(3, Permissions.onDevice(listed, 35, null).size)
        // A package that only begins the same is another's.
        assertEquals(
            listOf("org.example.application.permission.X"),
            Permissions.onDevice(listOf("org.example.application.permission.X"), 35, "org.example.app")
        )
    }

    @Test
    fun anUpdateIsToldWhatItAsksForBeyondTheInstalledVersion() {
        val offered = listOf("android.permission.INTERNET", "android.permission.CAMERA", "android.permission.RECORD_AUDIO")
        assertEquals(
            listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
            Permissions.added(offered, setOf("android.permission.INTERNET", "android.permission.VIBRATE"))
        )
        assertEquals(emptyList<String>(), Permissions.added(offered, offered))
    }
}
