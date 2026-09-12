package app.aaps.implementation.maintenance

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LogArchiveTest {
    @TempDir lateinit var directory: File
    private fun rotated(index: Int, date: String = "2026-09-11") = File(directory, "AndroidAPS._${date}_00-00-00_.$index.zip")
    private fun archive(file: File, text: String) = file.also {
        ZipOutputStream(it.outputStream()).use { out -> out.putNextEntry(ZipEntry("AndroidAPS.log")); out.write(text.toByteArray()); out.closeEntry() }
    }

    @Test fun `numeric indices cross digit widths where old selection loses latest records`() {
        val files = listOf(1, 9, 10, 90, 91, 99, 100, 101, 1000).map(::rotated)
        assertEquals(rotated(99).name, files.sortedByDescending { it.name }.first().name)
        assertEquals(listOf(1000, 101, 100, 99, 91, 90, 10, 9, 1).map { rotated(it).name }, LogFileOrder.newest(files).map { it.name })
        val active = File(directory, "AndroidAPS.log")
        assertEquals(active, LogFileOrder.newest(files + active).first())
        assertEquals(rotated(1, "2026-09-12"), LogFileOrder.newest(files + rotated(1, "2026-09-12")).first())
    }

    @Test fun `filename order is unaffected by mtimes and unknown names have deterministic total order`() {
        val older = rotated(99).apply { writeText(""); setLastModified(9999999999999) }
        val newer = rotated(100).apply { writeText(""); setLastModified(1) }
        assertEquals(newer, LogFileOrder.newest(listOf(older, newer)).first())
        val unknown = File(directory, "AndroidAPS.unknown.zip").apply { writeText(""); setLastModified(1) }
        val input = listOf(unknown, older, newer)
        repeat(30) { assertEquals(LogFileOrder.newest(input), LogFileOrder.newest(input.shuffled())) }
    }

    @Test fun `snapshot stops at opened length despite active append and includes manifest hashes and epochs`() {
        val text = "2026-09-11T11:00:00.000+03:00 event\n2026-09-11T08:01:00.000Z next\n"
        val active = File(directory, "AndroidAPS.log").apply { writeText(text) }
        val output = File(directory, "export.zip")
        LogArchive.create(listOf(active), output, "test-head", 1) { active.appendText("LATE\n") }
        ZipFile(output).use { zip ->
            assertEquals(text, zip.getInputStream(zip.getEntry(active.name)).reader().readText())
            val manifest = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()).jsonObject
            val item = manifest.getValue("files").jsonArray.single().jsonObject
            assertEquals(text.toByteArray().size.toLong(), item.getValue("bytes").jsonPrimitive.long)
            assertEquals(64, item.getValue("sha256").jsonPrimitive.content.length)
            assertEquals(60_000, item.getValue("lastEpochMs").jsonPrimitive.long - item.getValue("firstEpochMs").jsonPrimitive.long)
        }
    }

    @Test fun `rotation after handles open retains original bytes and reports selected index gaps`() {
        val active = File(directory, "AndroidAPS.log").apply { writeText("08:00:00.000 old clock-only log\n") }
        val files = listOf(active, archive(rotated(99), "old\n"), archive(rotated(101), "new\n"))
        val output = File(directory, "export.zip")
        LogArchive.create(files, output, "head", 3) {
            Files.move(active.toPath(), File(directory, "moved.log").toPath())
            active.writeText("new active file")
        }
        ZipFile(output).use { zip ->
            assertTrue(zip.getInputStream(zip.getEntry("AndroidAPS.log")).reader().readText().contains("old clock-only"))
            val manifest = Json.parseToJsonElement(zip.getInputStream(zip.getEntry("manifest.json")).reader().readText()).jsonObject
            assertEquals(1, manifest.getValue("rotationGaps").jsonArray.size)
            assertEquals(JsonNull, manifest.getValue("files").jsonArray.first().jsonObject["firstEpochMs"])
        }
    }

    @Test fun `truncation missing file and corrupt rotated zip fail without deliverable archive`() {
        val output = File(directory, "export.zip")
        val file = File(directory, "AndroidAPS.log").apply { writeText("abcdefgh") }
        assertThrows(IOException::class.java) { LogArchive.create(listOf(file), output, "head", 1) { file.writeText("") } }
        assertFalse(output.exists())
        assertThrows(IOException::class.java) { LogArchive.create(listOf(File(directory, "missing.log")), output, "head", 1) }
        val corrupt = rotated(100).apply { writeText("not a zip") }
        assertThrows(IOException::class.java) { LogArchive.create(listOf(corrupt), output, "head", 1) }
        assertFalse(output.exists())
        assertTrue(directory.listFiles()!!.none { it.name.startsWith("aaps-log-snapshot-") })
    }

    @Test fun `bounded copy propagates read and write failures`() {
        assertThrows(IOException::class.java) { LogArchive.copyExactly(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), 2) }
        val output = object : java.io.OutputStream() { override fun write(b: Int) { throw IOException("disk full") } }
        assertThrows(IOException::class.java) { LogArchive.copyExactly(ByteArrayInputStream(byteArrayOf(1)), output, 1) }
    }

    @Test fun `zero selected files produces verified manifest only archive`() {
        val output = File(directory, "export.zip")
        LogArchive.create(emptyList(), output, "head", 0)
        ZipFile(output).use { assertEquals(listOf("manifest.json"), it.entries().asSequence().map { entry -> entry.name }.toList()) }
    }
}
