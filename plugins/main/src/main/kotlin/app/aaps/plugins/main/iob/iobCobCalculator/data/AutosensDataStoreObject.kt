package app.aaps.plugins.main.iob.iobCobCalculator.data

import androidx.collection.LongSparseArray
import androidx.collection.size
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.aps.AutosensData
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.objects.extensions.fromGv
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

class AutosensDataStoreObject(private val nowProvider: () -> Long = System::currentTimeMillis) : AutosensDataStore {

    override val dataLock = Any()
    private var fiveMinuteMode: Boolean? = null
    override var lastUsed5minCalculation: Boolean?
        get() = synchronized(dataLock) { fiveMinuteMode }
        set(value) = synchronized(dataLock) { fiveMinuteMode = value }

    companion object {

        const val IRREGULAR_DATA_SEC = 30L

        // Autosens/COB data (table or stored fallback) older than this is treated as stale and not handed out.
        val MAX_AUTOSENS_AGE_MS = T.mins(11).msecs()
    }

    // Align buckets within one building pass; never retain the anchor across a live reload.
    private var passReferenceTime: Long = -1
    var referenceTime: Long
        get() = synchronized(dataLock) { passReferenceTime }
        set(value) = synchronized(dataLock) { passReferenceTime = value }
    override val bucketReferenceTime: Long? get() = referenceTime.takeIf { it >= 0 }
    private var passEvidence: app.aaps.core.interfaces.aps.BucketPassEvidence? = null
    override var lastBucketPass: app.aaps.core.interfaces.aps.BucketPassEvidence?
        get() = synchronized(dataLock) { passEvidence }
        private set(value) = synchronized(dataLock) { passEvidence = value }
    private var completedReadings: Map<Long, GV> = emptyMap()

    override fun markCalculationCompleted() = synchronized(dataLock) {
        completedReadings = readings.filter { it.id > 0 }.associate { it.id to it.copy(ids = it.ids.copy()) }
    }

    override fun classifyCompletedGlucose(gv: GV) = synchronized(dataLock) {
        app.aaps.core.data.diagnostics.GlucoseChangeClassifier.classify(completedReadings[gv.id], gv)
    }

    private var readings: List<GV> = emptyList()
    private var table = LongSparseArray<AutosensData>()
    private var buckets: MutableList<InMemoryGlucoseValue>? = null

    override var bgReadings: List<GV>
        get() = getBgReadingsDataTableCopy()
        set(value) = synchronized(dataLock) { readings = value.map { it.copy(ids = it.ids.copy()) } }
    override var autosensDataTable: LongSparseArray<AutosensData>
        get() = synchronized(dataLock) { copyTable(table) }
        set(value) = synchronized(dataLock) { table = copyTable(value) }
    override var bucketedData: MutableList<InMemoryGlucoseValue>?
        get() = getBucketedDataTableCopy()
        set(value) = synchronized(dataLock) { buckets = value?.map { it.copy() }?.toMutableList() }

    override fun putAutosensData(time: Long, data: AutosensData) = synchronized(dataLock) { table.put(time, data.deepCopy()) }

    private fun copyTable(source: LongSparseArray<AutosensData>) = LongSparseArray<AutosensData>(source.size()).apply {
        for (index in 0 until source.size()) put(source.keyAt(index), source.valueAt(index).deepCopy())
    }

    override fun clone(): AutosensDataStore =
        AutosensDataStoreObject(nowProvider).also {
            synchronized(dataLock) {
                it.lastBucketPass = this.lastBucketPass
                it.fiveMinuteMode = fiveMinuteMode
                it.storedFallback = storedFallback?.deepCopy()
                it.readings = this.readings.map { row -> row.copy(ids = row.ids.copy()) }
                it.table = LongSparseArray<AutosensData>(this.table.size).apply {
                    val source = this@AutosensDataStoreObject.table
                    for (index in 0 until source.size()) put(source.keyAt(index), source.valueAt(index).deepCopy())
                }
                it.buckets = this.buckets?.map { row -> row.copy() }?.toMutableList()
            }
        }

    override fun getBucketedDataTableCopy(): MutableList<InMemoryGlucoseValue>? = synchronized(dataLock) { buckets?.map { it.copy() }?.toMutableList() }
    override fun getBgReadingsDataTableCopy(): List<GV> = synchronized(dataLock) { readings.map { it.copy(ids = it.ids.copy()) } }

    override fun reset() {
        synchronized(dataLock) {
            completedReadings = emptyMap()
            table = LongSparseArray()
            storedFallback = null
        }
    }

    override fun pruneOlderThan(cut: Long) = synchronized(dataLock) {
        if (storedFallback?.time?.let { it < cut } == true) storedFallback = null
        val table = table
        var count = 0
        while (count < table.size() && table.keyAt(count) < cut) count++
        // SparseArray compacts after removal; descending indices keep consecutive keys safe.
        for (index in count - 1 downTo 0) table.removeAt(index)
    }

    override fun newHistoryData(time: Long, aapsLogger: AAPSLogger, dateUtil: DateUtil) {
        synchronized(dataLock) {
            completedReadings = emptyMap()
            if (storedFallback?.time?.let { it > time } == true) storedFallback = null
            for (index in table.size() - 1 downTo 0) {
                if (table.keyAt(index) > time) {
                    aapsLogger.debug(LTag.AUTOSENS) { "Removing from table: ${dateUtil.dateAndTimeAndSecondsString(table.keyAt(index))}" }
                    table.removeAt(index)
                } else {
                    break
                }
            }
        }
    }

    // roundup to whole minute
    override fun roundUpTime(time: Long): Long {
        return if (time % 60000 == 0L) time else (time / 60000 + 1) * 60000
    }

    /**
     * Return last valid (>39) InMemoryGlucoseValue from bucketed data or null if db is empty
     *
     * @return InMemoryGlucoseValue or null
     */
    override fun lastBg(): InMemoryGlucoseValue? =
        synchronized(dataLock) {
            buckets?.let { buckets ->
                if (buckets.isNotEmpty()) buckets[0].copy()
                else null
            }
        }

    /**
     * Provide last bucketed InMemoryGlucoseValue or null if none exists within the last 9 minutes
     *
     * @return InMemoryGlucoseValue or null
     */
    override fun actualBg(): InMemoryGlucoseValue? {
        val lastBg = lastBg() ?: return null
        return if (lastBg.timestamp > nowProvider() - T.mins(9).msecs()) lastBg else null
    }

    override fun lastDataTime(dateUtil: DateUtil): String =
        synchronized(dataLock) {
            if (table.size() > 0) dateUtil.dateAndTimeAndSecondsString(table.valueAt(table.size() - 1).time)
            else "table empty"
        }

    fun findPreviousTimeFromBucketedData(time: Long): Long? = synchronized(dataLock) {
        val bData = buckets ?: return null
        for (index in bData.indices) {
            if (bData[index].timestamp <= time) return bData[index].timestamp
        }
        return null
    }

    override fun getAutosensDataAtTime(fromTime: Long): AutosensData? {
        synchronized(dataLock) {
            val now = System.currentTimeMillis()
            if (fromTime > now) return null
            val previous = findPreviousTimeFromBucketedData(fromTime) ?: return null
            return table[roundUpTime(previous)]?.deepCopy()
        }
    }

    // during recalculation table is cleared and not available
    // for providing COB, which is an serious issue in BolusWizard
    // So let save last value after every calculation and use it
    // if table is not available
    private var storedFallback: AutosensData? = null
    var storedLastAutosensResult: AutosensData?
        get() = synchronized(dataLock) { storedFallback?.deepCopy() }
        set(value) = synchronized(dataLock) { storedFallback = value?.deepCopy() }

    // The stored fallback is only trustworthy while it is recent; hand out null once it ages past the window.
    // Uses the injected clock (not System.currentTimeMillis) so it stays consistent with the table freshness
    // check below and is actually testable.
    private fun freshStoredResult(dateUtil: DateUtil): AutosensData? =
        storedLastAutosensResult?.takeIf { it.time >= dateUtil.now() - MAX_AUTOSENS_AGE_MS }

    override fun getLastAutosensData(reason: String, aapsLogger: AAPSLogger, dateUtil: DateUtil): AutosensData? {
        synchronized(dataLock) {
            if (table.size() < 1) {
                aapsLogger.debug(LTag.AUTOSENS, "AUTOSENSDATA null: table empty ($reason)")
                return freshStoredResult(dateUtil)
            }
            val data: AutosensData = try {
                table.valueAt(table.size() - 1)
            } catch (_: Exception) {
                // data can be processed on the background
                // in this rare case better return null and do not block UI
                // APS plugin should use getLastAutosensDataSynchronized where the blocking is not an issue
                aapsLogger.error("AUTOSENSDATA null: Exception caught ($reason)")
                return freshStoredResult(dateUtil)
            }
            return if (data.time < dateUtil.now() - MAX_AUTOSENS_AGE_MS) {
                aapsLogger.debug(LTag.AUTOSENS) { "AUTOSENSDATA null: data is old ($reason) size()=${table.size()} lastData=${dateUtil.dateAndTimeAndSecondsString(data.time)}" }
                freshStoredResult(dateUtil)
            } else {
                aapsLogger.debug(LTag.AUTOSENS) { "AUTOSENSDATA ($reason) $data" }
                storedLastAutosensResult = data
                data.deepCopy()
            }
        }
    }

    private fun adjustToReferenceTime(someTime: Long): Long {
        if (referenceTime == -1L) {
            referenceTime = someTime
            return someTime
        }
        var diff = abs(someTime - referenceTime)
        diff %= T.mins(5).msecs()
        return if (diff > T.mins(2).plus(T.secs(30)).msecs()) someTime + abs(diff - T.mins(5).msecs()) // Adjust to the future
        else someTime - diff // adjust to the past
    }

    fun isAbout5minData(aapsLogger: AAPSLogger): Boolean {
        synchronized(dataLock) {
            if (readings.size < 3) return true

            var totalDiff: Long = 0
            for (i in 1 until readings.size) {
                val bgTime = readings[i].timestamp
                val lastBgTime = readings[i - 1].timestamp
                var diff = lastBgTime - bgTime
                diff %= T.mins(5).msecs()
                if (diff > T.mins(2).plus(T.secs(30)).msecs()) diff -= T.mins(5).msecs()
                totalDiff += diff
                diff = abs(diff)
                if (diff > T.secs(IRREGULAR_DATA_SEC).msecs()) {
                    aapsLogger.debug(LTag.AUTOSENS, "Interval detection: values: ${readings.size} diff: ${diff / 1000}[s] is5minData: false")
                    return false
                }
            }
            val averageDiff = totalDiff / readings.size / 1000
            val is5minData = averageDiff < 1
            aapsLogger.debug(LTag.AUTOSENS, "Interval detection: values: ${readings.size} averageDiff: $averageDiff[s] is5minData: $is5minData")
            return is5minData
        }
    }

    override fun createBucketedData(aapsLogger: AAPSLogger, dateUtil: DateUtil) = synchronized(dataLock) {
        var startedAt: Long? = null
        var completed = false
        try {
            startedAt = dateUtil.now()
            val fiveMinData = isAbout5minData(aapsLogger)
            if (lastUsed5minCalculation != null && lastUsed5minCalculation != fiveMinData) {
                // changing mode => clear cache
                aapsLogger.debug("Invalidating cached data because of changed mode.")
                reset()
            }
            lastUsed5minCalculation = fiveMinData
            if (fiveMinData) createBucketedData5min(aapsLogger, dateUtil) else createBucketedDataRecalculated(aapsLogger, dateUtil)
            completed = true
        } finally {
            // Superseded generations may never publish their clone: reset the live store too.
            val used = referenceTime.takeIf { it >= 0 }
            referenceTime = -1L
            lastBucketPass = startedAt?.let { app.aaps.core.interfaces.aps.BucketPassEvidence(
                it, dateUtil.now(), used, readings.firstOrNull()?.timestamp,
                buckets?.firstOrNull()?.timestamp, completed
            ) }
        }
    }

    fun findNewer(time: Long): GV? = synchronized(dataLock) {
        if (readings.isEmpty()) return null
        var lastFound = readings[0]
        if (lastFound.timestamp < time) return null
        for (i in 1 until readings.size) {
            if (readings[i].timestamp == time) return readings[i].copy(ids = readings[i].ids.copy())
            if (readings[i].timestamp > time) continue
            lastFound = readings[i - 1]
            if (readings[i].timestamp < time) break
        }
        return lastFound.copy(ids = lastFound.ids.copy())
    }

    fun findOlder(time: Long): GV? = synchronized(dataLock) {
        if (readings.isEmpty()) return null
        var lastFound = readings[readings.size - 1]
        if (lastFound.timestamp > time) return null
        for (i in readings.size - 2 downTo 0) {
            if (readings[i].timestamp == time) return readings[i].copy(ids = readings[i].ids.copy())
            if (readings[i].timestamp < time) continue
            lastFound = readings[i + 1]
            if (readings[i].timestamp > time) break
        }
        return lastFound.copy(ids = lastFound.ids.copy())
    }

    private fun createBucketedDataRecalculated(aapsLogger: AAPSLogger, dateUtil: DateUtil) {
        if (readings.size < 3) {
            buckets = null
            return
        }
        val lastBg = readings[0]
        val newBucketedData = ArrayList<InMemoryGlucoseValue>()
        var currentTime = readings[0].timestamp
        val adjustedTime = adjustToReferenceTime(currentTime)
        // after adjusting time may be newer. In this case use T-5min
        currentTime = if (adjustedTime > currentTime) adjustedTime - T.mins(5).msecs() else adjustedTime
        aapsLogger.debug("Adjusted time " + dateUtil.dateAndTimeAndSecondsString(currentTime))
        while (true) {
            // test if current value is older than current time
            val newer = findNewer(currentTime)
            val older = findOlder(currentTime)
            if (newer == null || older == null) break
            if (older.timestamp == newer.timestamp) { // direct hit
                newBucketedData.add(InMemoryGlucoseValue.fromGv(newer))
            } else {
                val bgDelta = newer.value - older.value
                val timeDiffToNew = newer.timestamp - currentTime
                val timeDiffToOlder = currentTime - older.timestamp
                val filledGap = min(timeDiffToOlder, timeDiffToNew) > T.secs(IRREGULAR_DATA_SEC).msecs()
                val currentBg = newer.value - timeDiffToNew.toDouble() / (newer.timestamp - older.timestamp) * bgDelta
                val newBgReading = InMemoryGlucoseValue(currentTime, currentBg.roundToLong().toDouble(), filledGap = filledGap, sourceSensor = lastBg.sourceSensor)
                newBucketedData.add(newBgReading)
            }
            currentTime -= T.mins(5).msecs()
        }
        buckets = newBucketedData
    }

    private fun createBucketedData5min(aapsLogger: AAPSLogger, dateUtil: DateUtil) {
        if (readings.size < 3) {
            buckets = null
            return
        }
        val lastBg = readings[0]
        val bData: MutableList<InMemoryGlucoseValue> = ArrayList()
        bData.add(InMemoryGlucoseValue.fromGv(readings[0]))
        aapsLogger.debug(LTag.AUTOSENS) { "Adding. bgTime: ${dateUtil.toISOString(readings[0].timestamp)} lastBgTime: none-first-value ${readings[0]}" }
        var j = 0
        for (i in 1 until readings.size) {
            val bgTime = readings[i].timestamp
            var lastBgTime = readings[i - 1].timestamp
            var elapsedMinutes = (bgTime - lastBgTime) / (60 * 1000)
            when {
                abs(elapsedMinutes) > 8 -> {
                    // interpolate missing data points
                    var lastBgValue = readings[i - 1].value
                    elapsedMinutes = abs(elapsedMinutes)
                    var nextBgTime: Long
                    while (elapsedMinutes > 5) {
                        nextBgTime = lastBgTime - 5 * 60 * 1000
                        j++
                        val gapDelta = readings[i].value - lastBgValue
                        val nextBg = lastBgValue + 5.0 / elapsedMinutes * gapDelta
                        val newBgReading = InMemoryGlucoseValue(nextBgTime, nextBg.roundToLong().toDouble(), filledGap = true, sourceSensor = lastBg.sourceSensor)
                        bData.add(newBgReading)
                        aapsLogger.debug(LTag.AUTOSENS) { "Adding. bgTime: ${dateUtil.toISOString(bgTime)} lastBgTime: ${dateUtil.toISOString(lastBgTime)} $newBgReading" }
                        elapsedMinutes -= 5
                        lastBgValue = nextBg
                        lastBgTime = nextBgTime
                    }
                    j++
                    val newBgReading = InMemoryGlucoseValue(bgTime, readings[i].value, sourceSensor = lastBg.sourceSensor)
                    bData.add(newBgReading)
                    aapsLogger.debug(LTag.AUTOSENS) { "Adding. bgTime: ${dateUtil.toISOString(bgTime)} lastBgTime: ${dateUtil.toISOString(lastBgTime)} $newBgReading" }
                }

                abs(elapsedMinutes) > 2 -> {
                    j++
                    val newBgReading = InMemoryGlucoseValue(bgTime, readings[i].value, sourceSensor = lastBg.sourceSensor)
                    bData.add(newBgReading)
                    aapsLogger.debug(LTag.AUTOSENS) { "Adding. bgTime: ${dateUtil.toISOString(bgTime)} lastBgTime: ${dateUtil.toISOString(lastBgTime)} $newBgReading" }
                }

                else                    -> {
                    bData[j].value = (bData[j].value + readings[i].value) / 2
                }
            }
        }

        // Normalize bucketed data
        val oldest = bData[bData.size - 1]
        oldest.timestamp = adjustToReferenceTime(oldest.timestamp)
        aapsLogger.debug("Adjusted time " + dateUtil.dateAndTimeAndSecondsString(oldest.timestamp))
        for (i in bData.size - 2 downTo 0) {
            val current = bData[i]
            val previous = bData[i + 1]
            val mSecDiff = current.timestamp - previous.timestamp
            val adjusted = (mSecDiff - T.mins(5).msecs()) / 1000
            aapsLogger.debug(LTag.AUTOSENS) {
                "Adjusting bucketed data time. Current: ${dateUtil.dateAndTimeAndSecondsString(current.timestamp)} to: ${
                    dateUtil.dateAndTimeAndSecondsString(previous.timestamp + T.mins(5).msecs())
                } by $adjusted sec"
            }
            if (abs(adjusted) > 90) {
                // too big adjustment, fallback to non 5 min data
                aapsLogger.debug(LTag.AUTOSENS, "Fallback to non 5 min data")
                createBucketedDataRecalculated(aapsLogger, dateUtil)
                return
            }
            current.timestamp = previous.timestamp + T.mins(5).msecs()
        }
        aapsLogger.debug(LTag.AUTOSENS, "Bucketed data created. Size: " + bData.size)
        buckets = bData
    }

    override fun slowAbsorptionPercentage(timeInMinutes: Int): Double {
        var sum = 0.0
        var count = 0
        val valuesToProcess = timeInMinutes / 5
        synchronized(dataLock) {
            var i = table.size() - 1
            while (i >= 0 && count < valuesToProcess) {
                if (table.valueAt(i).failOverToMinAbsorptionRate) sum++
                count++
                i--
            }
        }
        return if (count != 0) sum / count else 0.0
    }
}
