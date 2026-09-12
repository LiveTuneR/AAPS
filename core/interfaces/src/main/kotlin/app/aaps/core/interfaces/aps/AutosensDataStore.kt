package app.aaps.core.interfaces.aps

import androidx.collection.LongSparseArray
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.utils.DateUtil

interface AutosensDataStore {
    val bucketReferenceTime: Long? get() = null
    val lastBucketPass: BucketPassEvidence? get() = null

    val dataLock: Any

    var bgReadings: List<GV>
    var autosensDataTable: LongSparseArray<AutosensData>
    var bucketedData: MutableList<InMemoryGlucoseValue>?
    var lastUsed5minCalculation: Boolean?

    /**
     * Return last valid (>39) InMemoryGlucoseValue from bucketed data or null if db is empty
     *
     * @return InMemoryGlucoseValue or null
     */
    fun lastBg(): InMemoryGlucoseValue?

    /**
     * Provide last bucketed InMemoryGlucoseValue or null if none exists within the last 9 minutes
     *
     * @return InMemoryGlucoseValue or null
     */
    fun actualBg(): InMemoryGlucoseValue?
    fun lastDataTime(dateUtil: DateUtil): String
    fun clone(): AutosensDataStore
    fun getBgReadingsDataTableCopy(): List<GV>
    fun getLastAutosensData(reason: String, aapsLogger: AAPSLogger, dateUtil: DateUtil): AutosensData?
    fun getAutosensDataAtTime(fromTime: Long): AutosensData?
    fun getBucketedDataTableCopy(): MutableList<InMemoryGlucoseValue>?
    fun createBucketedData(aapsLogger: AAPSLogger, dateUtil: DateUtil)
    fun slowAbsorptionPercentage(timeInMinutes: Int): Double
    fun newHistoryData(time: Long, aapsLogger: AAPSLogger, dateUtil: DateUtil)
    fun roundUpTime(time: Long): Long
    fun reset()
    /** Remove only keys strictly older than this calculation's historical window. */
    fun pruneOlderThan(cut: Long)
    /** Called only within successful generation-checked ADS publication. */
    fun markCalculationCompleted()
    /** Unknown/unpublished data must never provide positive equality proof. */
    fun classifyCompletedGlucose(gv: GV): app.aaps.core.data.diagnostics.GlucoseChange
}

/** Immutable diagnostic evidence, never reused as the next bucketing pass anchor. */
data class BucketPassEvidence(
    val startedAt: Long,
    val finishedAt: Long,
    val referenceTimeUsed: Long?,
    val rawBgTimestamp: Long?,
    val bucketBgTimestamp: Long?,
    val completed: Boolean
)
