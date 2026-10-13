package org.jarsi.arkstore.install

import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PmOutputTest {

    @Test
    fun theSessionIdIsReadFromTheBrackets() {
        assertEquals(1234, PmOutput.sessionId("Success: created install session [1234]\n"))
        assertEquals(7, PmOutput.sessionId("Success: created install session [7]"))
        assertNull(PmOutput.sessionId("Failure [INSTALL_FAILED_INVALID_APK]"))
        assertNull(PmOutput.sessionId("Error: no session"))
        assertNull(PmOutput.sessionId(""))
        assertNull(PmOutput.sessionId("Success: created install session [99999999999999]"))
    }

    @Test
    fun successAndFailureAreToldApart() {
        assertEquals(PmResult(true, null), PmOutput.result("Success\n"))
        assertEquals(PmResult(true, null), PmOutput.result("Success: streamed 1234 bytes\n"))
        assertEquals(
            PmResult(false, "INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package org.example signatures do not match newer version; ignoring!"),
            PmOutput.result("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package org.example signatures do not match newer version; ignoring!]\n")
        )
        assertEquals(PmResult(false, "INSTALL_FAILED_INVALID_APK"), PmOutput.result("Failure [INSTALL_FAILED_INVALID_APK]"))
        // Something was said before the verdict: a warning of the shell, say.
        assertEquals(PmResult(true, null), PmOutput.result("WARNING: linker: something\nSuccess\n"))
        // Nothing of pm's: the command never ran, or was cut short.
        assertNull(PmOutput.result(""))
        assertNull(PmOutput.result("Permission denied"))
        assertNull(PmOutput.result("/system/bin/sh: pm: inaccessible or not found"))
    }

    @Test
    fun aFailureIsMappedToTheStatusTheSystemWouldGive() {
        assertEquals(
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PmOutput.status("INSTALL_FAILED_UPDATE_INCOMPATIBLE: Existing package signatures do not match")
        )
        assertEquals(PackageInstaller.STATUS_FAILURE_CONFLICT, PmOutput.status("INSTALL_FAILED_SHARED_USER_INCOMPATIBLE"))
        assertEquals(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PmOutput.status("INSTALL_FAILED_OLDER_SDK: Failed parse"))
        assertEquals(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE, PmOutput.status("INSTALL_FAILED_NO_MATCHING_ABIS"))
        assertEquals(PackageInstaller.STATUS_FAILURE_INVALID, PmOutput.status("INSTALL_PARSE_FAILED_NOT_APK: bad"))
        assertEquals(PackageInstaller.STATUS_FAILURE_INVALID, PmOutput.status("INSTALL_FAILED_INVALID_APK"))
        assertEquals(PackageInstaller.STATUS_FAILURE_STORAGE, PmOutput.status("INSTALL_FAILED_INSUFFICIENT_STORAGE"))
        assertEquals(PackageInstaller.STATUS_FAILURE, PmOutput.status("INSTALL_FAILED_VERSION_DOWNGRADE"))
        assertEquals(PackageInstaller.STATUS_FAILURE, PmOutput.status(null))
    }

    @Test
    fun theCommandsAreMadeOfCheckedNamesAndNumbersOnly() {
        assertEquals(
            "pm install-create -r --user current -i org.jarsi.arkstore --pkg org.example.app -S 12345",
            RootCommands.createSession("org.jarsi.arkstore", "org.example.app", 12345)
        )
        assertThrows(IllegalArgumentException::class.java) { RootCommands.createSession("org.jarsi.arkstore", "org.example app", 1) }
        assertEquals("pm install-write -S 100 42 base.apk -", RootCommands.writeSplit(42, "base.apk", 100))
        assertEquals(
            "pm install-write -S 7 42 split_config.arm64_v8a.apk -",
            RootCommands.writeSplit(42, "split_config.arm64_v8a.apk", 7)
        )
        assertEquals("pm install-commit 42", RootCommands.commit(42))
        assertEquals("pm install-abandon 42", RootCommands.abandon(42))
        assertEquals("pm uninstall --user current org.example.app", RootCommands.uninstall("org.example.app"))
        assertThrows(IllegalArgumentException::class.java) { RootCommands.createSession("org.example; rm -rf /", "org.example.app", 1) }
        assertThrows(IllegalArgumentException::class.java) { RootCommands.uninstall("org.example app") }
        assertThrows(IllegalArgumentException::class.java) { RootCommands.writeSplit(42, "base.apk; id", 1) }
        assertThrows(IllegalArgumentException::class.java) { RootCommands.writeSplit(42, "../x.apk", 1) }
        assertThrows(IllegalArgumentException::class.java) { RootCommands.writeSplit(42, "base.apk", -1) }
        assertThrows(IllegalArgumentException::class.java) { RootCommands.createSession("org.jarsi.arkstore", "org.example.app", 0) }
    }
}
