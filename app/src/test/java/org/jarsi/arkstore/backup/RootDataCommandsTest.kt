package org.jarsi.arkstore.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootDataCommandsTest {

    private val pkg = "org.example.app"

    @Test
    fun theFoldersOfTheDataAreTheUsers() {
        assertEquals("/data/user/0", RootDataCommands.userDir(AppBackup.DATA_USER, 0))
        assertEquals("/data/user_de/10", RootDataCommands.userDir(AppBackup.DATA_USER_DE, 10))
        assertThrows(IllegalArgumentException::class.java) { RootDataCommands.userDir("cache", 0) }
        assertThrows(IllegalArgumentException::class.java) { RootDataCommands.userDir(AppBackup.DATA_USER, -1) }
    }

    @Test
    fun tarIsTakenFromWhatTheDeviceSaysOfIt() {
        assertEquals("/system/bin/tar", RootDataCommands.tarFrom("/system/bin/tar\n"))
        assertEquals("tar", RootDataCommands.tarFrom("tar"))
        assertEquals("/data/adb/magisk/busybox tar", RootDataCommands.tarFrom("/data/adb/magisk/busybox tar\n"))
        assertNull(RootDataCommands.tarFrom(""))
        assertNull(RootDataCommands.tarFrom("\n  \n"))
        assertNull(RootDataCommands.tarFrom("sh: tar: not found"))
        assertNull(RootDataCommands.tarFrom("/system/bin/tar; rm -rf /"))
        assertNull(RootDataCommands.tarFrom("/data/adb/magisk/busybox cat"))
        assertTrue(RootDataCommands.findTar().startsWith("command -v tar || "))
    }

    @Test
    fun sizesAreAskedForEveryPartAndAddedUpByPackage() {
        assertEquals(
            "du -s -k /data/user/0/org.a /data/user/0/org.b /data/user_de/0/org.a /data/user_de/0/org.b 2>/dev/null",
            RootDataCommands.sizes(0, listOf("org.a", "org.b"))
        )
        val output = "1024\t/data/user/0/org.a\n8\t/data/user/0/org.b\n16\t/data/user_de/0/org.a\nnonsense\n12\t/data/user/0/not a package\n"
        assertEquals(mapOf("org.a" to (1024L + 16) * 1024, "org.b" to 8L * 1024), RootDataCommands.sizesFrom(output, 0))
        assertEquals(emptyMap<String, Long>(), RootDataCommands.sizesFrom(output, 10))
        assertThrows(IllegalArgumentException::class.java) { RootDataCommands.sizes(0, listOf("org.a; id")) }
        assertThrows(IllegalArgumentException::class.java) { RootDataCommands.sizes(0, emptyList()) }
    }

    @Test
    fun thePartsWorthTakingAreThoseWithMoreThanCachesInThem() {
        val command = RootDataCommands.parts(0, listOf("org.a"))
        assertTrue(command.contains("[ -d /data/user/0/org.a ]"))
        assertTrue(command.contains("find /data/user/0/org.a -mindepth 1 -maxdepth 1 ! -name cache ! -name code_cache ! -name lib"))
        assertTrue(command.contains("echo \"data org.a\""))
        assertTrue(command.contains("echo \"data_de org.a\""))
        assertTrue(command.endsWith("; true"))
        assertEquals(
            mapOf("org.a" to listOf("data", "data_de"), "org.b" to listOf("data_de")),
            RootDataCommands.partsFrom("data_de org.a\ndata org.a\ndata_de org.b\nfind: oops\ncache org.c\ndata not.a package\n")
        )
        assertThrows(IllegalArgumentException::class.java) { RootDataCommands.parts(0, listOf("../x")) }
    }

    @Test
    fun theArchiveLeavesTheCachesAndTheLibrariesOut() {
        assertEquals(
            "/system/bin/tar -c -f - -C /data/user/0 --exclude=$pkg/cache --exclude=$pkg/code_cache --exclude=$pkg/lib $pkg",
            RootDataCommands.archive("/system/bin/tar", AppBackup.DATA_USER, 0, pkg)
        )
        assertEquals(
            "/data/adb/magisk/busybox tar -c -f - -C /data/user_de/0 --exclude=$pkg/cache --exclude=$pkg/code_cache --exclude=$pkg/lib $pkg",
            RootDataCommands.archive("/data/adb/magisk/busybox tar", AppBackup.DATA_USER_DE, 0, pkg)
        )
        assertThrows(IllegalArgumentException::class.java) { RootDataCommands.archive("tar", AppBackup.DATA_USER, 0, "org.example app") }
    }

    @Test
    fun theRestoreUnpacksBesideTheAppsAndChecksBeforeMovingIntoPlace() {
        val staging = RootDataCommands.staging(AppBackup.DATA_USER, 0, 7)
        assertEquals("/data/user/0/.arkstore-restore-7", staging)
        assertEquals(
            "rm -rf $staging && mkdir $staging && chmod 700 $staging && tar -x -p -f - -C $staging",
            RootDataCommands.extract("tar", staging)
        )
        assertEquals("find $staging -mindepth 1 -maxdepth 1", RootDataCommands.listStaging(staging))
        assertTrue(RootDataCommands.stagingHoldsOnly("$staging/$pkg\n", staging, pkg))
        assertFalse(RootDataCommands.stagingHoldsOnly("", staging, pkg))
        assertFalse(RootDataCommands.stagingHoldsOnly("$staging/$pkg\n$staging/other\n", staging, pkg))
        assertFalse(RootDataCommands.stagingHoldsOnly("$staging/other\n", staging, pkg))
        val swap = RootDataCommands.swap(AppBackup.DATA_USER, 0, pkg, staging, 10234, 7)
        val target = "/data/user/0/$pkg"
        val old = "/data/user/0/.arkstore-old-7"
        assertEquals(
            listOf(
                "rm -rf $old",
                "{ [ ! -e $target ] || mv $target $old; }",
                "{ mv $staging/$pkg $target || { mv $old $target; false; }; }",
                "{ [ ! -L $old/lib ] || [ -e $target/lib ] || mv $old/lib $target/lib; }",
                "chown -R -h 10234:10234 $target",
                "restorecon -R -F -D $target",
                "rm -rf $old $staging"
            ).joinToString(" && "),
            swap
        )
        assertThrows(IllegalArgumentException::class.java) { RootDataCommands.swap(AppBackup.DATA_USER, 0, pkg, staging, -1, 7) }
        assertEquals("am force-stop $pkg", RootDataCommands.forceStop(pkg))
        assertEquals("rm -rf $staging", RootDataCommands.remove(staging))
    }
}
