package app.aaps.implementation.telemetry

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Called exclusively by the serialized writer. Active data is plain UTF-8 JSONL, never open gzip. */
internal class TherapyTelemetryStore(
    private val directory: File,
    private val buildSha: String,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val monotonic: () -> Long = System::nanoTime,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val diskLimit: Long = 256L * 1024 * 1024,
    private val segmentLimit: Long = 1024L * 1024
) : AutoCloseable {
    private val sessionId = UUID.randomUUID().toString()
    private var sequence = 0L
    private val control = File(directory,"control.json")
    private var state: JSONObject
    private val active = File(directory,"active.jsonl")
    private val integrityLedger = File(directory,"integrity.jsonl")
    private val segmentIndexFile = File(directory,"segments.json")
    private val segmentIndex = linkedMapOf<String, JSONObject>()
    private var activeHour: Long? = null
    private var segmentSequence = 0L
    private var lastRetentionHour: Long? = null
    private var closed = false
    private var highWater: Long
    private var previousWall: Long? = null
    private var previousMono: Long? = null
    private var previousZone: String? = null
    var retentionDays: Int
        private set
    val writerDrops: Long get() = state.optLong("writerDrops")
    val corruptedRecords: Long get() = state.optLong("corruptedRecords")
    val recoveredRecords: Long get() = state.optLong("recoveredRecords")
    val uncleanSessions: Long get() = state.optLong("uncleanSessions")
    val pressure: Boolean get() = state.optBoolean("storagePressure")

    init {
        check(directory.isDirectory || directory.mkdirs())
        loadSegmentIndex()
        state = if (control.exists()) try { JSONObject(control.readText()) } catch (_: Exception) {
            JSONObject().put("controlCorrupt",true).put("uncertainHistory",true)
        } else JSONObject()
        retentionDays = state.optInt("retentionDays",7).coerceIn(4,14)
        highWater = maxOf(state.optLong("highWater"),wallClock())
        if (state.optBoolean("open")) {
            increment("uncleanSessions")
            integrity("PROCESS_UNCLEAN_EXIT", wallClock(), wallClock(), 1, "previous_session_left_open")
        }
        recoverActiveTail()
        if (active.length() > 0) rotate()
        // Rotation always leaves active intact until the completed gzip is committed.
        directory.listFiles()?.filter { it.name.endsWith(".jsonl.gz.part") }?.forEach { check(it.delete()) }
        backfillSegmentIndex()
        state.put("open",true)
        persistState()
    }

    fun setRetention(days: Int) {
        require(days in 4..14)
        retentionDays = days
        state.put("retentionDays",days)
        persistState()
    }

    fun bytesOnDisk(): Long = directory.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0

    fun append(type: String, data: JSONObject, generation: Long? = null, correlationId: String? = null,
               observedUtc: Long = wallClock(), observedMonotonic: Long = monotonic(), observedZone: ZoneId = zone()): Boolean {
        check(!closed)
        val now = observedUtc
        val oldWall = previousWall
        val oldMono = previousMono
        val oldZone = previousZone
        previousWall = now
        previousMono = observedMonotonic
        previousZone = observedZone.id
        val skew = if (oldWall != null && oldMono != null) (now-oldWall)-(observedMonotonic-oldMono)/1_000_000L else 0L
        if (kotlin.math.abs(skew) > 120_000L || oldZone != null && oldZone != observedZone.id) {
            if (kotlin.math.abs(skew) > 120_000L) state.put("retentionClockUncertain",true)
            append("CLOCK_CHANGE",JSONObject().put("previousUtc",oldWall).put("currentUtc",now).put("skewMs",skew)
                .put("timezone",observedZone.id),observedUtc=now,observedMonotonic=observedMonotonic,observedZone=observedZone)
        }
        highWater = maxOf(highWater,now)
        val hour = Math.floorDiv(now,3_600_000L)
        if (activeHour != null && activeHour != hour || active.length() >= segmentLimit) rotate()
        activeHour = hour
        val offset = observedZone.rules.getOffset(Instant.ofEpochMilli(now)).totalSeconds
        val record = JSONObject().put("schemaVersion",1).put("timestampUtc",now).put("timestampIso",Instant.ofEpochMilli(now).toString())
            .put("utcOffsetSeconds",offset).put("timezone",observedZone.id).put("monotonicNanos",observedMonotonic)
            .put("buildSha",buildSha).put("sessionId",sessionId).put("sequence",sequence++)
            .put("eventId","$sessionId:${sequence-1}").put("type",type)
            .put("generation",generation ?: JSONObject.NULL).put("correlationId",correlationId ?: JSONObject.NULL)
            .put("data",TelemetrySanitizer.clean(data))
        val bytes = (record.toString() + "\n").toByteArray(Charsets.UTF_8)
        require(bytes.size <= 256 * 1024) { "Telemetry record limit" }
        if (lastRetentionHour != hour) { retain(); lastRetentionHour = hour }
        if (bytesOnDisk() + bytes.size > diskLimit) {
            dropped(now,"STORAGE_PRESSURE")
            return false
        }
        val originalLength = active.length()
        try { FileOutputStream(active,true).use { it.write(bytes); it.fd.sync() } }
        catch (error: Exception) {
            try { RandomAccessFile(active,"rw").use { it.setLength(originalLength); it.fd.sync() } }
            catch (_: Exception) { state.put("uncertainHistory",true) }
            throw error
        }
        if (pressure) { state.put("storagePressure",false); persistState() }
        return true
    }

    fun dropped(timestamp: Long = wallClock(), reason: String = "WRITER_QUEUE_FULL", count: Long = 1) {
        require(count > 0)
        state.put("writerDrops",writerDrops+count)
        state.put("firstDropUtc",minOf(state.optLong("firstDropUtc",timestamp),timestamp))
        state.put("lastDropUtc",maxOf(state.optLong("lastDropUtc",timestamp),timestamp))
        state.put("lastDropReason",reason).put("storagePressure",reason == "STORAGE_PRESSURE")
        integrity(if (reason == "STORAGE_PRESSURE") "STORAGE_PRESSURE" else "RECORD_LOSS", timestamp, timestamp, count, reason)
        // Kept outside segment budget, a bounded control record cannot be displaced by graph logs.
        persistState()
    }

    fun integrity(type: String, fromUtc: Long, toUtc: Long, count: Long = 1, details: String? = null) {
        val row = JSONObject().put("schemaVersion", 1).put("type", type).put("fromUtc", fromUtc)
            .put("toUtc", toUtc).put("count", count).put("details", details ?: JSONObject.NULL)
        FileOutputStream(integrityLedger, true).use { output ->
            output.write((row.toString() + "\n").toByteArray(Charsets.UTF_8)); output.flush(); output.fd.sync()
        }
    }

    private fun recoverActiveTail() {
        if (!active.exists() || active.length() == 0L) return
        RandomAccessFile(active,"rw").use { file ->
            var end = file.length()
            file.seek(end - 1)
            if (file.readByte().toInt() != 10) {
                while (end > 0) {
                    file.seek(--end)
                    if (file.readByte().toInt() == 10) { end++; break }
                }
                file.setLength(end)
                file.fd.sync()
                increment("corruptedRecords")
                state.put("uncertainHistory",true)
                integrity("CORRUPTION", wallClock(), wallClock(), 1, "truncated_active_tail")
            }
        }
        active.useLines { lines -> lines.forEach { line ->
            try { JSONObject(line); increment("recoveredRecords") }
            catch (_: Exception) { state.put("uncertainHistory",true) } // Still present: counted once when export reads it.
        } }
    }

    private fun rotate() {
        if (active.length() == 0L) { activeHour = null; return }
        var firstUtc = Long.MAX_VALUE
        var lastUtc = Long.MIN_VALUE
        var recordCount = 0L
        records(active) { record ->
            val timestamp = record.getLong("timestampUtc")
            firstUtc = minOf(firstUtc, timestamp); lastUtc = maxOf(lastUtc, timestamp); recordCount++
        }
        val destination = File(directory,"segment-${wallClock()}-$sessionId-${segmentSequence++}.jsonl.gz")
        val temporary = File(directory,destination.name + ".part")
        GZIPOutputStream(FileOutputStream(temporary)).use { compressed -> active.inputStream().use { it.copyTo(compressed) } }
        FileOutputStream(temporary,true).use { it.fd.sync() }
        // On a crash before deletion both copies may exist; export deduplicates immutable event IDs.
        Files.move(temporary.toPath(),destination.toPath(),StandardCopyOption.ATOMIC_MOVE)
        segmentIndex[destination.name] = JSONObject().put("firstUtc", firstUtc).put("lastUtc", lastUtc)
            .put("recordCount", recordCount).put("compressedSize", destination.length()).put("sha256", sha(destination))
        persistSegmentIndex()
        check(active.delete()) { "Unable to retire telemetry active file" }
        activeHour = null
    }

    private fun retain() {
        // A wall-clock jump must not age out real recent data. Fail conservatively to disk pressure.
        if (state.optBoolean("retentionClockUncertain")) return
        val cutoff = highWater - (retentionDays + 1L) * 86_400_000L
        directory.listFiles()?.filter { it.name.endsWith(".jsonl.gz") }?.forEach { file ->
            val newest = segmentIndex[file.name]?.optLong("lastUtc", Long.MAX_VALUE) ?: Long.MAX_VALUE
            if (newest < cutoff) {
                check(file.delete())
                segmentIndex.remove(file.name)
            }
        }
        persistSegmentIndex()
    }

    fun export(
        output: File,
        from: Long,
        to: Long,
        expectedInterval: Long? = null,
        supportFiles: List<File> = emptyList(),
        buildProvenance: JSONObject = JSONObject().put("sourceSha", buildSha),
    ): JSONObject {
        require(from <= to)
        require(expectedInterval == null || expectedInterval > 0)
        check(!closed)
        val temp = Files.createTempDirectory(requireNotNull(output.absoluteFile.parentFile).toPath(),"telemetry-export-").toFile()
        try {
            val names = listOf("therapy-events.jsonl","aps-decisions.jsonl","errors.jsonl","telemetry.jsonl")
            val writers = names.associateWith { File(temp,it).bufferedWriter() }
            val settings = JSONArray()
            val carryInSettings = mutableListOf<JSONObject>()
            val seen = HashSet<String>()
            val samples = sortedMapOf<Long,JSONObject>()
            val summary = TherapyMinuteSummary()
            val decisionIds = HashSet<String>()
            var duplicateDecisions = 0L
            var corrupt = 0L
            var count = 0L
            var periodFirst: Long? = null
            var periodLast: Long? = null
            val unreadable = JSONArray()
            try {
                val files = directory.listFiles()?.filter {
                    it.name == "active.jsonl" || it.name.endsWith(".jsonl.gz") && segmentIndex[it.name]?.let { metadata ->
                        metadata.optLong("lastUtc") >= from && metadata.optLong("firstUtc") <= to
                    } != false
                }.orEmpty()
                TelemetryExportOrder.forEachOrdered(files,temp, { file -> corrupt++; unreadable.put(file.name) }) { record ->
                        val timestamp = record.getLong("timestampUtc")
                        val type = record.getString("type")
                        if (timestamp < from) {
                            if (type == "SETTINGS_SNAPSHOT") carryInSettings.clear()
                            if (type == "SETTINGS_SNAPSHOT" || type == "SETTINGS_CHANGE") carryInSettings.add(record)
                            if (type == "SETTINGS_SNAPSHOT" || type == "PUMP_STATE" || type == "ACTIVITY") summary.accept(record)
                            return@forEachOrdered
                        }
                        if (timestamp > to || !seen.add(record.getString("eventId"))) return@forEachOrdered
                        periodFirst = minOf(periodFirst ?: timestamp,timestamp)
                        periodLast = maxOf(periodLast ?: timestamp,timestamp)
                        val data = record.getJSONObject("data")
                        summary.accept(record)
                        count++
                        writers.getValue("telemetry.jsonl").appendLine(record.toString())
                        when (type) {
                            "CGM" -> if (!data.optBoolean("metadataOnly") && !data.isNull("glucoseMgdl") && data.optBoolean("isValid",true)) {
                                val bgTime = data.optLong("rawBgTimestamp",timestamp)
                                if (bgTime in from..to) samples[bgTime] = record
                            }
                            "APS_INPUT", "APS_DECISION", "CONSTRAINT" -> {
                                writers.getValue("aps-decisions.jsonl").appendLine(record.toString())
                                if (type == "APS_DECISION") {
                                    val id = record.optString("correlationId")
                                    if (id.isNotEmpty() && id != "null" && !decisionIds.add(id)) duplicateDecisions++
                                }
                            }
                            "SETTINGS_SNAPSHOT", "SETTINGS_CHANGE" -> settings.put(record)
                            "ERROR", "TELEMETRY_STORAGE_PRESSURE" -> writers.getValue("errors.jsonl").appendLine(record.toString())
                            else -> writers.getValue("therapy-events.jsonl").appendLine(record.toString())
                        }
                }
                // Time-ranged integrity rows are added below; lifetime counters remain health-only.
            } finally { writers.values.forEach { it.close() } }
            File(temp,"settings-snapshots.json").writeText(JSONObject().put("schemaVersion",1)
                .put("contextBeforePeriod",JSONArray(carryInSettings)).put("records",settings).toString())
            summary.write(File(temp,"therapy-minute.csv"),from,to)
            val gaps = JSONArray()
            if (expectedInterval != null) {
                var previous: Long? = null
                for (timestamp in samples.keys.filter { it in from..to }) {
                    if (previous != null && timestamp - previous > expectedInterval + expectedInterval / 2) gaps.put(JSONObject().put("fromUtc",previous+expectedInterval).put("toUtc",timestamp-expectedInterval).put("reason","CGM_GAP"))
                    previous = timestamp
                }
                if (previous != null && to - previous > expectedInterval + expectedInterval / 2) gaps.put(JSONObject().put("fromUtc",previous+expectedInterval).put("toUtc",to).put("reason","CGM_GAP"))
            }
            val overlappingIntegrity = JSONArray()
            if (integrityLedger.exists()) integrityLedger.useLines { lines -> lines.forEach { line ->
                runCatching { JSONObject(line) }.getOrNull()?.takeIf { it.optLong("toUtc") >= from && it.optLong("fromUtc") <= to }?.let {
                    overlappingIntegrity.put(it); gaps.put(JSONObject(it.toString()).put("reason", it.optString("type")))
                    val exported = JSONObject().put("schemaVersion", 1)
                        .put("type", if (it.optString("type") == "STORAGE_PRESSURE") "TELEMETRY_STORAGE_PRESSURE" else "TELEMETRY_INTEGRITY")
                        .put("source", "TIME_RANGED_INTEGRITY_LEDGER").put("data", it)
                    File(temp,"errors.jsonl").appendText(exported.toString() + "\n")
                }
            } }
            if (corrupt > 0) gaps.put(JSONObject().put("reason","UNREADABLE_SEGMENT_IN_RANGE").put("count",corrupt))
            supportFiles.distinctBy { it.absolutePath }.filter { it.isFile }.forEachIndexed { index, source ->
                source.copyTo(File(temp, "support-${index}-${source.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}"), overwrite = true)
            }
            File(temp, "build-provenance.json").writeText(buildProvenance.toString(2))
            val hashes = JSONObject()
            temp.listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }.forEach { hashes.put(it.name,sha(it)) }
            val manifest = JSONObject().put("schemaVersion",1).put("telemetrySchemaVersion",1).put("buildSha",buildSha)
                .put("periodStartUtc",from).put("periodEndUtc",to).put("actualPeriodStartUtc",periodFirst ?: JSONObject.NULL).put("actualPeriodEndUtc",periodLast ?: JSONObject.NULL)
                .put("expectedCgmCount",expectedInterval?.let { if (samples.isEmpty()) 0 else ((samples.lastKey()-samples.firstKey())/it+1) } ?: JSONObject.NULL).put("actualCgmCount",samples.size)
                .put("recordCount",count).put("decisionCount",decisionIds.size).put("duplicateDecisions",duplicateDecisions)
                .put("writerDrops",writerDrops).put("corruptedRecords",corruptedRecords+corrupt).put("recoveredRecords",recoveredRecords)
                .put("uncleanSessions",uncleanSessions).put("missingIntervals",gaps).put("unreadableSegments",unreadable)
                .put("overlappingIntegrityEvents", overlappingIntegrity)
                .put("coverageVerified",expectedInterval != null && gaps.length()==0 && duplicateDecisions==0L)
                .put("retentionDays",retentionDays).put("diskLimitBytes",diskLimit).put("fileSha256",hashes)
                .put("retentionClockUncertain",state.optBoolean("retentionClockUncertain"))
            File(temp,"manifest.json").writeText(manifest.toString(2))
            ZipOutputStream(FileOutputStream(output)).use { zip ->
                temp.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
            return manifest
        } finally {
            temp.listFiles().orEmpty().forEach { check(it.delete()) }
            check(temp.delete())
        }
    }

    private fun records(file: File, onCorrupt: () -> Unit = {}, consume: (JSONObject) -> Unit) {
        val input = if (file.name.endsWith(".gz")) GZIPInputStream(FileInputStream(file)) else FileInputStream(file)
        input.bufferedReader(Charsets.UTF_8).useLines { lines -> lines.filter { it.isNotEmpty() }.forEach {
            val record = try { JSONObject(it).also { row -> row.getLong("timestampUtc") } } catch (_: Exception) { onCorrupt(); null }
            if (record != null) consume(record)
        } }
    }
    private fun loadSegmentIndex() {
        if (!segmentIndexFile.exists()) return
        runCatching { JSONObject(segmentIndexFile.readText()) }.getOrNull()?.optJSONArray("segments")?.let { array ->
            repeat(array.length()) { index -> array.optJSONObject(index)?.let { segmentIndex[it.getString("name")] = it } }
        }
    }
    private fun backfillSegmentIndex() {
        var changed = false
        directory.listFiles()?.filter { it.name.endsWith(".jsonl.gz") && it.name !in segmentIndex }?.forEach { file ->
            var first = Long.MAX_VALUE; var last = Long.MIN_VALUE; var count = 0L
            runCatching { records(file) { row -> val time=row.getLong("timestampUtc"); first=minOf(first,time); last=maxOf(last,time); count++ } }
                .onSuccess {
                    segmentIndex[file.name] = JSONObject().put("name", file.name).put("firstUtc", first).put("lastUtc", last)
                        .put("recordCount", count).put("compressedSize", file.length()).put("sha256", sha(file)); changed = true
                }
        }
        if (changed) persistSegmentIndex()
    }
    private fun persistSegmentIndex() {
        val rows = JSONArray()
        segmentIndex.forEach { (name, metadata) -> rows.put(JSONObject(metadata.toString()).put("name", name)) }
        val temporary = File(directory, "segments.part")
        FileOutputStream(temporary).use { it.write(JSONObject().put("schemaVersion",1).put("segments",rows).toString().toByteArray()); it.fd.sync() }
        Files.move(temporary.toPath(), segmentIndexFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    private fun persistState() {
        state.put("schemaVersion",1).put("highWater",highWater).put("retentionDays",retentionDays)
        val temporary = File(directory,"control.part")
        FileOutputStream(temporary).use { it.write(state.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(temporary.toPath(),control.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE)
    }
    private fun increment(key: String) { state.put(key,state.optLong(key)+1) }
    override fun close() {
        if (closed) return
        rotate(); state.put("open",false); persistState(); closed=true
    }
    companion object {
        private fun csv(value: Any?): String = if (value == null || value == JSONObject.NULL) "" else {
            val text = value.toString()
            // Formula-safe spreadsheet export, including profile or reason labels in future schema versions.
            val safe = if (text.firstOrNull() in listOf('=','+','@') || (text.startsWith('-') && text.toDoubleOrNull()==null)) "'$text" else text
            "\"${safe.replace("\"","\"\"")}\""
        }
        fun sha(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
            file.inputStream().use { input -> val buffer=ByteArray(8192); while (true) { val n=input.read(buffer); if (n<0) break; digest.update(buffer,0,n) } }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
