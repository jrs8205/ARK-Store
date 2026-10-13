package org.jarsi.arkstore.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootDataCommandsTest {

    private val pkg = "org.example.app"
    private val token = "1760371234567-7"

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
        // The listing says when it has run to its end: one that was never run says nothing.
        assertTrue(command.endsWith("; echo ${RootDataCommands.PARTS_END}"))
        assertEquals(
            mapOf("org.a" to listOf("data", "data_de"), "org.b" to listOf("data_de")),
            RootDataCommands.partsFrom("data_de org.a\ndata org.a\ndata_de org.b\nfind: oops\ncache org.c\ndata not.a package\n${RootDataCommands.PARTS_END}\n")
        )
        assertEquals(emptyMap<String, List<String>>(), RootDataCommands.partsFrom("${RootDataCommands.PARTS_END}\n"))
        assertNull(RootDataCommands.partsFrom("data org.a\n"))
        assertNull(RootDataCommands.partsFrom(""))
        assertNull(RootDataCommands.partsFrom("Permission denied\n"))
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
        val staging = RootDataCommands.staging(AppBackup.DATA_USER, 0, token)
        assertEquals("/data/user/0/.arkstore-restore-$token", staging)
        assertEquals("/data/user/0/.arkstore-old-$token", RootDataCommands.old(AppBackup.DATA_USER, 0, token))
        assertThrows(IllegalArgumentException::class.java) { RootDataCommands.staging(AppBackup.DATA_USER, 0, "7") }
        assertThrows(IllegalArgumentException::class.java) { RootDataCommands.staging(AppBackup.DATA_USER, 0, "1-2; id") }
        assertEquals(
            "rm -rf $staging && mkdir $staging && chmod 700 $staging && tar -x -p -f - -C $staging",
            RootDataCommands.extract("tar", staging)
        )
        assertEquals("find $staging -mindepth 1 -maxdepth 1", RootDataCommands.listStaging(staging))
        assertEquals(
            "find $staging -type l -exec sh -c 'readlink -f \"\$1\" || echo ?' - {} \\;",
            RootDataCommands.listLinks(staging)
        )
        assertFalse(RootDataCommands.linksStayIn("$staging/$pkg/a\n?\n", staging, pkg))
        assertTrue(RootDataCommands.linksStayIn("", staging, pkg))
        assertTrue(RootDataCommands.linksStayIn("$staging/$pkg/files/a\n$staging/$pkg\n", staging, pkg))
        assertFalse(RootDataCommands.linksStayIn("$staging/$pkg/files/a\n/data/user/0\n", staging, pkg))
        assertFalse(RootDataCommands.linksStayIn("$staging/${pkg}2/x\n", staging, pkg))
        assertFalse(RootDataCommands.linksStayIn("$staging\n", staging, pkg))
        assertTrue(RootDataCommands.stagingHoldsOnly("$staging/$pkg\n", staging, pkg))
        assertFalse(RootDataCommands.stagingHoldsOnly("", staging, pkg))
        assertFalse(RootDataCommands.stagingHoldsOnly("$staging/$pkg\n$staging/other\n", staging, pkg))
        assertFalse(RootDataCommands.stagingHoldsOnly("$staging/other\n", staging, pkg))
        assertEquals("am force-stop --user 0 $pkg", RootDataCommands.forceStop(pkg, 0))
        assertEquals("am force-stop --user 10 $pkg", RootDataCommands.forceStop(pkg, 10))
        assertThrows(IllegalArgumentException::class.java) { RootDataCommands.forceStop(pkg, -1) }
        assertEquals("rm -rf $staging", RootDataCommands.remove(staging))
    }

    @Test
    fun theSwapKeepsTheOldDataUntilTheNewIsInPlaceAndLabelled() {
        val staging = RootDataCommands.staging(AppBackup.DATA_USER, 0, token)
        val target = "/data/user/0/$pkg"
        val old = "/data/user/0/.arkstore-old-$token"
        val putBack = "{ [ ! -e $old ] || [ ! -L $target/lib ] || [ -e $old/lib ] || mv $target/lib $old/lib; } && " +
            "rm -rf $target && { [ ! -e $old ] || mv $old $target; }"
        assertEquals(
            listOf(
                "chown -R -h 10234:10234 $staging/$pkg",
                "rm -rf $old",
                "{ [ ! -e $target ] || mv $target $old; }",
                "{ mv $staging/$pkg $target || { [ ! -e $old ] || mv $old $target; false; }; }",
                "{ [ ! -L $old/lib ] || [ -e $target/lib ] || mv $old/lib $target/lib; }",
                "{ restorecon -R -F -D $target || { $putBack; false; }; }"
            ).joinToString(" && "),
            RootDataCommands.swap(AppBackup.DATA_USER, 0, pkg, 10234, token)
        )
        // Nothing of the swap removes the old data: that is for the finish, once every part is in place.
        assertFalse(RootDataCommands.swap(AppBackup.DATA_USER, 0, pkg, 10234, token).contains("rm -rf $old $staging"))
        assertEquals(putBack, RootDataCommands.unswap(AppBackup.DATA_USER, 0, pkg, token))
        assertEquals("rm -rf $old $staging", RootDataCommands.finish(AppBackup.DATA_USER, 0, token))
        assertThrows(IllegalArgumentException::class.java) { RootDataCommands.swap(AppBackup.DATA_USER, 0, pkg, -1, token) }
        assertThrows(IllegalArgumentException::class.java) { RootDataCommands.swap(AppBackup.DATA_USER, 0, "org.example app", 1, token) }
        assertThrows(IllegalArgumentException::class.java) { RootDataCommands.finish(AppBackup.DATA_USER, 0, "x") }
    }
}
