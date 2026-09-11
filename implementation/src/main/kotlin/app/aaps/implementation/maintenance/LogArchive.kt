package app.aaps.implementation.maintenance

import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.file.Files
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*

/** Filename dates are local wall times; rotation indices are numbers, never strings. */
internal object LogFileOrder {
    private val rotated = Regex(".*?(\\d{4}-\\d{2}-\\d{2})[_T](\\d{2}-\\d{2}-\\d{2})_?\\.(\\d+)\\.zip")
    private val dated = Regex(".*?(\\d{4}-\\d{2}-\\d{2})(?:[_T](\\d{2}-\\d{2}-\\d{2}))?.*")
    fun rotation(file: File): Pair<String, BigInteger>? = rotated.matchEntire(file.name)?.let {
        "${it.groupValues[1]}_${it.groupValues[2]}" to it.groupValues[3].toBigInteger()
    }
    fun known(file: File) = file.name == "AndroidAPS.log" || dated.matches(file.name)
    private fun orderTime(file: File): Long = dated.matchEntire(file.name)?.let {
        runCatching {
            LocalDateTime.parse(it.groupValues[1] + "_" + it.groupValues[2].ifEmpty { "00-00-00" },
                DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    } ?: file.lastModified()
    fun newest(files: List<File>): List<File> = files.sortedWith(
        compareByDescending<File> { it.name == "AndroidAPS.log" }
            .thenByDescending { orderTime(it) }
            .thenByDescending { rotation(it)?.second ?: BigInteger.ZERO }
            .thenByDescending { it.name }
    )
}

/** Private, bounded copies are assembled and verified before any share/upload is allowed. */
internal object LogArchive {
    private const val MAX_BYTES = 256L * 1024 * 1024
    private val iso = Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}(?:Z|[+-]\\d{2}:\\d{2}))")
    private data class Captured(val file: File, val size: Long, val capturedAt: Long, val input: FileInputStream)

    fun create(files: List<File>, output: File, head: String, amount: Int, afterOpen: () -> Unit = {}) {
        val captured = mutableListOf<Captured>()
        val stage = Files.createTempDirectory(output.parentFile.toPath(), "aaps-log-snapshot-").toFile()
        try {
            require(files.map { it.name }.distinct().size == files.size) { "Duplicate log entry" }
            files.forEach { file ->
                val input = FileInputStream(file)
                try {
                    val size = input.channel.size()
                    if (size > MAX_BYTES - captured.sumOf { it.size }) throw IOException("Log snapshot exceeds byte limit")
                    captured += Captured(file, size, System.currentTimeMillis(), input)
                } catch (error: Exception) {
                    input.close()
                    throw error
                }
            }
            afterOpen()
            val manifestFiles = captured.map { entry ->
                val copy = File(stage, entry.file.name)
                copy.outputStream().use { copyExactly(entry.input, it, entry.size) }
                if (copy.extension == "zip") verifyZip(copy)
                val bounds = bounds(copy)
                buildJsonObject {
                    put("name", entry.file.name)
                    put("bytes", entry.size)
                    put("sha256", digest(copy))
                    put("capturedAtEpochMs", entry.capturedAt)
                    put("firstEpochMs", bounds.first?.let(::JsonPrimitive) ?: JsonNull)
                    put("lastEpochMs", bounds.second?.let(::JsonPrimitive) ?: JsonNull)
                    put("clockOrderReversed", bounds.first != null && bounds.second != null && bounds.first!! > bounds.second!!)
                    put("timestampBasis", if (bounds.first == null) "UNKNOWN_LEGACY_OR_NO_TIMESTAMP" else "EXPLICIT_ISO_OFFSET")
                    put("filenameOrderKnown", LogFileOrder.known(entry.file))
                }
            }
            val rotations = files.mapNotNull(LogFileOrder::rotation).groupBy({ it.first }, { it.second })
            val manifest = buildJsonObject {
                put("schema", 1)
                put("appHead", head)
                put("createdAtEpochMs", System.currentTimeMillis())
                put("timezoneAtExport", ZoneId.systemDefault().id)
                put("utcOffsetAtExport", OffsetDateTime.now().offset.toString())
                put("filenameTimezone", "UNKNOWN_LOCAL_WALL_TIME; export timezone is not historical timezone")
                put("selectionPolicy", "ACTIVE_FIRST_FILENAME_DATE_NUMERIC_ROTATION_UNKNOWN_MTIME_FALLBACK")
                put("requestedFiles", amount.coerceAtLeast(0))
                put("selectedFiles", files.size)
                put("byteLimit", MAX_BYTES)
                put("snapshotPolicy", "OPEN_HANDLES_FIXED_LENGTH_PER_FILE_NOT_GLOBAL_ATOMIC")
                put("coverage", "BOUNDED_SELECTION_NOT_COMPLETE_DAY; legacy clock-only epochs unknown")
                put("dataLossEvents", "UNKNOWN: logger drops cannot be inferred from retained files")
                put("files", JsonArray(manifestFiles))
                putJsonArray("rotationGaps") {
                    rotations.forEach { (date, indices) ->
                        indices.sorted().zipWithNext().filter { (a, b) -> b - a > BigInteger.ONE }.forEach { (a, b) ->
                            add(buildJsonObject { put("date", date); put("afterIndex", a.toString()); put("beforeIndex", b.toString()) })
                        }
                    }
                }
            }
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                captured.forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.file.name))
                    File(stage, entry.file.name).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            verifyZip(output)
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            captured.forEach { runCatching { it.input.close() } }
            stage.listFiles()?.forEach { it.delete() }
            stage.delete()
        }
    }

    internal fun copyExactly(input: InputStream, output: OutputStream, length: Long) {
        val buffer = ByteArray(64 * 1024)
        var remaining = length
        while (remaining > 0) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count <= 0) throw IOException("Log changed or was truncated during snapshot")
            output.write(buffer, 0, count)
            remaining -= count
        }
    }

    fun verifyZip(file: File) {
        ZipFile(file).use { check -> if (!check.entries().hasMoreElements()) throw IOException("Empty ZIP") }
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            val buffer = ByteArray(64 * 1024)
            var expanded = 0L
            while (zip.nextEntry != null) {
                var count = zip.read(buffer)
                while (count != -1) {
                    expanded += count
                    if (expanded > MAX_BYTES * 8) throw IOException("Expanded log limit exceeded")
                    count = zip.read(buffer)
                }
                zip.closeEntry()
            }
        }
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            var count = input.read(bytes)
            while (count != -1) { md.update(bytes, 0, count); count = input.read(bytes) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun bounds(file: File): Pair<Long?, Long?> {
        var first: Long? = null
        var last: Long? = null
        fun scan(input: InputStream) {
            // Only retain a prefix, even if an old payload occupies several megabytes on one line.
            val prefix = StringBuilder(40)
            val buffer = ByteArray(64 * 1024)
            fun line() {
                iso.find(prefix)?.groupValues?.get(1)?.let { value ->
                    runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()?.let {
                        if (first == null) first = it
                        last = it
                    }
                }
                prefix.setLength(0)
            }
            var count = input.read(buffer)
            while (count != -1) {
                for (i in 0 until count) if (buffer[i] == 10.toByte()) line() else if (prefix.length < 40) prefix.append(buffer[i].toInt().toChar())
                count = input.read(buffer)
            }
            line()
        }
        if (file.extension == "zip") ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (zip.nextEntry != null) { scan(zip); zip.closeEntry() }
        } else file.inputStream().use(::scan)
        return first to last
    }
}
