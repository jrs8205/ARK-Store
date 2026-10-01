package org.jarsi.arkstore.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkPickerTest {
    private val arm64Phone = listOf("arm64-v8a", "armeabi-v7a")

    @Test
    fun prefersTheDeviceArchitectureOverFileOrder() {
        val files = listOf("app-x86_64.apk", "app-arm64-v8a.apk", "app-universal.apk")
        assertEquals(1, ApkPicker.pick(files, arm64Phone))
        assertEquals(0, ApkPicker.pick(files, listOf("x86_64", "x86")))
    }

    @Test
    fun fallsBackToSecondaryArchitectureAndUniversal() {
        assertEquals(1, ApkPicker.pick(listOf("app-x86.apk", "app-armeabi-v7a.apk"), arm64Phone))
        assertEquals(1, ApkPicker.pick(listOf("app-x86.apk", "app-universal.apk"), arm64Phone))
        assertEquals(0, ApkPicker.pick(listOf("ARK-launcher-0.8.2.apk"), arm64Phone))
    }

    @Test
    fun rejectsFilesBuiltOnlyForOtherArchitectures() {
        assertNull(ApkPicker.pick(listOf("app-x86_64.apk", "app-x86.apk"), arm64Phone))
        assertNull(ApkPicker.pick(emptyList(), arm64Phone))
    }

    @Test
    fun debugBuildsAreALastResort() {
        assertEquals(1, ApkPicker.pick(listOf("app-debug.apk", "app-release.apk"), arm64Phone))
        assertEquals(0, ApkPicker.pick(listOf("app-debug.apk"), arm64Phone))
        assertEquals(
            1,
            ApkPicker.pick(listOf("app-arm64-v8a-debug.apk", "app-universal-release.apk"), arm64Phone)
        )
    }

    @Test
    fun doesNotConfuseSimilarArchitectureNames() {
        assertEquals(setOf("x86_64"), ApkPicker.architectures("app-x86_64-release.apk"))
        assertEquals(setOf("arm64-v8a"), ApkPicker.architectures("App_ARM64.apk"))
        assertEquals(emptySet<String>(), ApkPicker.architectures("app-universal-arm64.apk"))
    }
}
