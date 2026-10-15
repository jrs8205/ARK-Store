package org.jarsi.arkstore.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ZipBackupTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val signer = "a".repeat(64)

    private fun entry(pkg: String, label: String, vararg files: Pair<String, Int>) = BackupEntry(
        packageName = pkg,
        label = label,
        versionCode = 3,
        versionName = "1.0",
        installer = null,
        signers = listOf(signer),
        updatedAt = 1,
        minSdk = 26,
        files = files.map { BackupFile(it.first, it.second.toLong()) }
    )

    private val a = entry("org.a.app", "A", "base.apk" to 300_000, "split_config.arm64_v8a.apk" to 1_000)
    private val b = entry("org.b.app", "B", "base.apk" to 2_000)
    private val c = entry("org.c.app", "C", "base.apk" to 3_000)

    /** The bytes of a file of an entry: made of the package and the file, so each is told apart. */
    private fun content(entry: BackupEntry, file: BackupFile): ByteArray {
        val seed = (entry.packageName + file.name).hashCode()
        return ByteArray(file.size.toInt()) { ((it * 31 + seed) and 0xff).toByte() }
    }

    private fun zipOf(vararg entries: BackupEntry, settings: String = "{\"app\":\"org.jarsi.arkstore\"}"): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking {
            ZipBackup.write(out, entries.toList(), settings, "2026-10-11T07:00:00Z", { e, i ->
                ByteArrayInputStream(content(e, e.files[i]))
            })
        }
        return out.toByteArray()
    }

    private fun names(zip: ByteArray): List<String> {
        val names = ArrayList<String>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) names += (input.nextEntry ?: break).name
        }
        return names
    }

    private fun rawZip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private class Counting(input: InputStream) : FilterInputStream(input) {
        var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) count++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }
    }

    private class Extracted(val entry: BackupEntry, val files: List<File>?, val data: Map<String, File>)

    private fun extract(zip: ByteArray, vararg wanted: BackupEntry): List<Extracted> {
        val got = ArrayList<Extracted>()
        runBlocking {
            ZipBackup.extract(
                ByteArrayInputStream(zip),
                wanted.toList(),
                dirFor = { File(temp.root, "restore-" + it.packageName) },
                onApp = { e, files, data -> got += Extracted(e, files, data) }
            )
        }
        return got
    }

    /** The bytes of a data archive of an entry, as the test writes them; no real archive. */
    private fun dataContent(entry: BackupEntry, data: BackupData): ByteArray =
        ("data of " + entry.packageName + "/" + data.name).toByteArray()

    @Test
    fun fileNameCarriesTheDate() {
        assertEquals("ark-store-backup-2026-10-11.zip", ZipBackup.fileName(LocalDate.of(2026, 10, 11)))
    }

    @Test
    fun namesAreOursOrNothing() {
        assertEquals(ZipBackup.Name.Index, ZipBackup.parseName("index.json"))
        assertEquals(ZipBackup.Name.Settings, ZipBackup.parseName("ark-store-settings.json"))
        assertEquals(ZipBackup.Name.Entry("org.a.app"), ZipBackup.parseName("org.a.app/app.json"))
        assertEquals(ZipBackup.Name.File("org.a.app", "base.apk"), ZipBackup.parseName("org.a.app/base.apk"))
        // A folder zipped by hand has the folder's own name in front.
        assertEquals(ZipBackup.Name.File("org.a.app", "base.apk"), ZipBackup.parseName("ARK backup/org.a.app/base.apk"))
        assertEquals(ZipBackup.Name.Settings, ZipBackup.parseName("ARK backup/ark-store-settings.json"))
        for (bad in listOf(
            "../evil.apk", "org.a.app/../evil.apk", "/org.a.app/base.apk", "org.a.app\\base.apk",
            "a/b/org.a.app/base.apk", "org.a.app/base.txt", "noDots/base.apk", "org.a.app/", "org.a.app/sub/base.apk",
            "..", "", "org.a.app/.hidden.apk", "../org.a.app/base.apk"
        )) {
            assertNull(bad, ZipBackup.parseName(bad))
        }
    }

    @Test
    fun theAppsAreToldFirstAndTheirFilesFollowAppByApp() {
        val zip = zipOf(a, b)
        assertEquals(
            listOf(
                "index.json", "ark-store-settings.json", "org.a.app/app.json", "org.b.app/app.json",
                "org.a.app/base.apk", "org.a.app/split_config.arm64_v8a.apk", "org.b.app/base.apk"
            ),
            names(zip)
        )
    }

    @Test
    fun theHeadIsReadWithoutTheFiles() = runBlocking {
        val zip = zipOf(a, b)
        val input = Counting(ByteArrayInputStream(zip))
        val head = ZipBackup.readHead(input)
        assertEquals(listOf(a, b), head.entries)
        assertEquals("{\"app\":\"org.jarsi.arkstore\"}", head.settings)
        // The files of the apps, most of the zip, are not read for the list.
        assertTrue("read ${input.count} of ${zip.size}", input.count < zip.size / 2)
    }

    @Test
    fun aFileOfAnotherSizeThanItsEntryFailsTheZip() {
        val out = ByteArrayOutputStream()
        try {
            runBlocking {
                ZipBackup.write(out, listOf(b), "{}", "x", { _, _ -> ByteArrayInputStream(ByteArray(1_999)) })
            }
            fail("a short file was taken")
        } catch (_: IOException) {
        }
    }

    @Test
    fun theHeadOfAZipInAnotherOrderIsFoundAllTheSame() = runBlocking {
        val zip = rawZip(
            "Backup/" to ByteArray(0),
            "Backup/org.b.app/base.apk" to ByteArray(2_000),
            "Backup/org.b.app/app.json" to b.toJson().toString().toByteArray(),
            "Backup/ark-store-settings.json" to "{}".toByteArray()
        )
        val head = ZipBackup.readHead(ByteArrayInputStream(zip))
        assertEquals(listOf(b), head.entries)
        assertEquals("{}", head.settings)
    }

    @Test
    fun aFolderZippedByHandIsReadToItsEnd() = runBlocking {
        // Folder by folder, as a file manager packs it: each app's entry is followed by its files.
        val zip = rawZip(
            "Backup/org.a.app/app.json" to a.toJson().toString().toByteArray(),
            "Backup/org.a.app/base.apk" to ByteArray(10),
            "Backup/org.b.app/app.json" to b.toJson().toString().toByteArray(),
            "Backup/org.b.app/base.apk" to ByteArray(10),
            "Backup/ark-store-settings.json" to "{}".toByteArray()
        )
        val head = ZipBackup.readHead(ByteArrayInputStream(zip))
        assertEquals(listOf(a, b), head.entries)
        assertEquals("{}", head.settings)
    }

    @Test
    fun anIndexFirstIsNoReasonToStopBeforeEveryAppItNamesIsFound() = runBlocking {
        val index = AppBackup.indexToJson(listOf(a, b), "x").toString().toByteArray()
        val zip = rawZip(
            "index.json" to index,
            "ark-store-settings.json" to "{}".toByteArray(),
            "org.a.app/app.json" to a.toJson().toString().toByteArray(),
            "org.a.app/base.apk" to ByteArray(10),
            "org.b.app/app.json" to b.toJson().toString().toByteArray()
        )
        assertEquals(listOf(a, b), ZipBackup.readHead(ByteArrayInputStream(zip)).entries)
    }

    @Test
    fun anEntryOfAnotherPackageThanItsFolderIsNotTaken() = runBlocking {
        val zip = rawZip("org.c.app/app.json" to b.toJson().toString().toByteArray())
        assertEquals(emptyList<BackupEntry>(), ZipBackup.readHead(ByteArrayInputStream(zip)).entries)
        assertEquals(emptyList<BackupEntry>(), ZipBackup.readHead(ByteArrayInputStream("not a zip".toByteArray())).entries)
    }

    @Test
    fun theAppsWantedAreExtractedWholeAndInOrder() {
        val got = extract(zipOf(a, b, c), a, c)
        assertEquals(listOf("org.a.app", "org.c.app"), got.map { it.entry.packageName })
        for (x in got) {
            val files = x.files!!
            assertEquals(x.entry.files.map { it.name }, files.map { it.name })
            x.entry.files.forEachIndexed { i, f -> assertArrayEquals(content(x.entry, f), files[i].readBytes()) }
        }
        // An app not wanted is not written anywhere.
        assertFalse(File(temp.root, "restore-org.b.app").exists())
    }

    @Test
    fun anAppMissingFromTheZipOrMissingAFileComesBackEmpty() {
        val zip = rawZip(
            "org.a.app/app.json" to a.toJson().toString().toByteArray(),
            "org.a.app/base.apk" to content(a, a.files[0])
        )
        val got = extract(zip, a, b)
        assertEquals(listOf("org.a.app" to null, "org.b.app" to null), got.map { it.entry.packageName to it.files })
        // What was taken out for an app that cannot be installed is not left behind.
        assertFalse(File(temp.root, "restore-org.a.app").exists())
    }

    @Test
    fun aFileLargerThanItsEntryIsNotTaken() {
        val zip = rawZip("org.b.app/base.apk" to ByteArray(2_001))
        val got = extract(zip, b)
        assertNull(got.single().files)
        assertFalse(File(temp.root, "restore-org.b.app").exists())
    }

    @Test
    fun aFileOfAnAppAlreadyDoneIsLeftAlone() {
        val zip = rawZip(
            "org.a.app/base.apk" to content(a, a.files[0]),
            "org.b.app/base.apk" to content(b, b.files[0]),
            "org.a.app/split_config.arm64_v8a.apk" to content(a, a.files[1])
        )
        val got = extract(zip, a, b)
        // A's files did not come together, so A is not installed from a part of them.
        assertEquals(listOf("org.a.app" to false, "org.b.app" to true), got.map { it.entry.packageName to (it.files != null) })
    }

    @Test
    fun theDataArchivesFollowTheFilesOfTheirAppAndComeBackByPart() {
        val withData = a.copy(data = listOf(BackupData("data.tar.gz.aes", -1), BackupData("data_de.tar.gz", -1)))
        val out = ByteArrayOutputStream()
        runBlocking {
            ZipBackup.write(
                out, listOf(withData, b), "{}", "2026-10-13T07:00:00Z",
                open = { e, i -> ByteArrayInputStream(content(e, e.files[i])) },
                writeData = { e, d, stream ->
                    stream.write(dataContent(e, d))
                    // Closing what the writer was given must not close the zip.
                    stream.close()
                }
            )
        }
        val zip = out.toByteArray()
        assertEquals(
            listOf(
                "index.json", "ark-store-settings.json", "org.a.app/app.json", "org.b.app/app.json",
                "org.a.app/base.apk", "org.a.app/split_config.arm64_v8a.apk", "org.a.app/data.tar.gz.aes",
                "org.a.app/data_de.tar.gz", "org.b.app/base.apk"
            ),
            names(zip)
        )
        val head = runBlocking { ZipBackup.readHead(ByteArrayInputStream(zip)) }
        assertEquals(listOf(withData, b), head.entries)
        val got = extract(zip, withData, b)
        assertEquals(listOf("org.a.app", "org.b.app"), got.map { it.entry.packageName })
        val first = got[0]
        assertEquals(withData.files.map { it.name }, first.files!!.map { it.name })
        assertEquals(setOf("data", "data_de"), first.data.keys)
        assertArrayEquals(dataContent(withData, withData.data[0]), first.data.getValue("data").readBytes())
        assertArrayEquals(dataContent(withData, withData.data[1]), first.data.getValue("data_de").readBytes())
        assertEquals(emptyMap<String, File>(), got[1].data)
    }

    @Test
    fun aDataArchiveMissingOrOfAnotherSizeIsLeftOutWhileTheFilesStand() {
        val withData = b.copy(data = listOf(BackupData("data.tar.gz", 5), BackupData("data_de.tar.gz", 5)))
        val zip = rawZip(
            "org.b.app/base.apk" to content(b, b.files[0]),
            "org.b.app/data.tar.gz" to ByteArray(6),
            // Not named by the entry at all.
            "org.b.app/data_de.tar.gz.aes" to ByteArray(5)
        )
        val got = extract(zip, withData).single()
        assertTrue(got.files != null)
        assertEquals(emptyMap<String, File>(), got.data)
        assertFalse(File(temp.root, "restore-org.b.app/data.tar.gz").exists())
        val right = rawZip(
            "org.b.app/base.apk" to content(b, b.files[0]),
            "org.b.app/data.tar.gz" to ByteArray(5)
        )
        assertEquals(setOf("data"), extract(right, withData).single().data.keys)
    }

    @Test
    fun dataNamesAreTheirOwnKind() {
        assertEquals(ZipBackup.Name.Data("org.a.app", "data.tar.gz.aes"), ZipBackup.parseName("org.a.app/data.tar.gz.aes"))
        assertEquals(ZipBackup.Name.Data("org.a.app", "data_de.tar.gz"), ZipBackup.parseName("x/org.a.app/data_de.tar.gz"))
        assertNull(ZipBackup.parseName("org.a.app/data.tar"))
        assertNull(ZipBackup.parseName("org.a.app/other.tar.gz"))
    }

    @Test
    fun namesThatWouldLeadOutOfTheFolderAreNeverWritten() {
        val zip = rawZip(
            "../evil.apk" to ByteArray(10),
            "org.b.app/../../evil.apk" to ByteArray(10),
            "org.b.app/base.apk" to content(b, b.files[0])
        )
        val got = extract(zip, b)
        assertTrue(got.single().files != null)
        assertFalse(File(temp.root.parentFile, "evil.apk").exists())
        assertFalse(File(temp.root, "evil.apk").exists())
    }
}
