package org.jarsi.arkstore.install

import android.content.pm.PackageInstaller
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RootInstallerTest {

    private val commands = ArrayList<String>()

    @After
    fun after() {
        Root.transport = null
    }

    /** Answers every command with [answer], taking whatever is written to its input. */
    private fun answering(answer: (String) -> Root.Outcome) {
        Root.transport = { command, stdin, _, _ ->
            commands += command
            stdin?.invoke(ByteArrayOutputStream())
            answer(command)
        }
    }

    private fun apk(): File = File.createTempFile("base", ".apk").apply {
        writeBytes(byteArrayOf(1, 2, 3))
        deleteOnExit()
    }

    @Test
    fun aSessionOfTenDigitsIsWrittenToAndCommitted() = runBlocking {
        answering { command ->
            Root.Outcome.Ran(0, if (command.startsWith("pm install-create")) "Success: created install session [1500000000]\n" else "Success\n", "")
        }
        var session: Int? = null
        val result = RootInstaller.install("org.jarsi.arkstore", "org.example.app", listOf("base.apk" to apk())) { session = it }
        assertEquals(1500000000, session)
        assertEquals(RootInstaller.Result.Done(PackageInstaller.STATUS_SUCCESS, null), result)
        assertEquals(
            listOf(
                "pm install-create -r --user 0 -i org.jarsi.arkstore --pkg org.example.app -S 3",
                "pm install-write -S 3 1500000000 base.apk -",
                "pm install-commit 1500000000"
            ),
            commands
        )
    }

    @Test
    fun aSuccessWithoutASessionIsNoInstall() = runBlocking {
        // Whatever pm says, nothing can be written to a session that is not known.
        answering { Root.Outcome.Ran(0, "Success\n", "") }
        var session: Int? = null
        val result = RootInstaller.install("org.jarsi.arkstore", "org.example.app", listOf("base.apk" to apk())) { session = it }
        assertNull(session)
        assertEquals(1, commands.size)
        assertTrue(commands.single().startsWith("pm install-create"))
        assertEquals(PackageInstaller.STATUS_FAILURE, (result as RootInstaller.Result.Done).status)
    }

    @Test
    fun aSessionRefusedIsToldAsTheSystemWould() = runBlocking {
        answering { Root.Outcome.Ran(1, "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE: not enough]\n", "") }
        val result = RootInstaller.install("org.jarsi.arkstore", "org.example.app", listOf("base.apk" to apk())) { }
        assertEquals(RootInstaller.Result.Done(PackageInstaller.STATUS_FAILURE_STORAGE, "INSTALL_FAILED_INSUFFICIENT_STORAGE: not enough"), result)
        assertEquals(1, commands.size)
    }

    @Test
    fun aWriteThatFailsAbandonsTheSession() = runBlocking {
        answering { command ->
            when {
                command.startsWith("pm install-create") -> Root.Outcome.Ran(0, "Success: created install session [42]\n", "")
                command.startsWith("pm install-write") -> Root.Outcome.Ran(1, "Failure [INSTALL_FAILED_INVALID_APK]\n", "")
                else -> Root.Outcome.Ran(0, "Success\n", "")
            }
        }
        val result = RootInstaller.install("org.jarsi.arkstore", "org.example.app", listOf("base.apk" to apk())) { }
        assertEquals(RootInstaller.Result.Done(PackageInstaller.STATUS_FAILURE_INVALID, "INSTALL_FAILED_INVALID_APK"), result)
        assertEquals("pm install-abandon 42", commands.last())
    }

    @Test
    fun theRemovalNamesTheStoresUser() = runBlocking {
        answering { Root.Outcome.Ran(0, "Success\n", "") }
        assertEquals(RootInstaller.Result.Done(PackageInstaller.STATUS_SUCCESS, null), RootInstaller.uninstall("org.example.app"))
        assertEquals(listOf("pm uninstall --user 0 org.example.app"), commands)
    }
}
