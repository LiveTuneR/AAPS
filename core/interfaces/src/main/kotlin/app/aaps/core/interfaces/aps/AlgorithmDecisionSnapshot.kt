package app.aaps.core.interfaces.aps

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Read-only evidence of branches actually visited. Null means the branch was not reached. */
@Serializable
data class AlgorithmDecisionSnapshot(
    val algorithm: String,
    val dynamicIsf: Boolean,
    val calculatedAt: Long,
    val inputTimestamp: Long,
    val profileIsfMgdl: Double,
    val currentDynamicIsfMgdl: Double?,
    val carbRatio: Double,
    val smbConfigured: Boolean,
    val conditionEligible: Boolean? = null,
    val conditionReason: Reason? = null,
    val blockReason: Reason? = null,
    val futureIsfMgdl: Double? = null,
    val futureIsfBasis: IsfBasis? = null,
    val insulinReqIsfMgdl: Double? = null,
    val minPredBgMgdl: Double? = null,
    val minGuardBgMgdl: Double? = null,
    val eventualBgMgdl: Double? = null,
    val iobU: Double? = null,
    val cobG: Double? = null,
    val maxBolusU: Double? = null,
    val iobLimited: Boolean = false,
    val bolusLimited: Boolean = false,
    val intervalWaiting: Boolean? = null,
    val intervalSeconds: Double? = null,
    val lastBolusAgeSeconds: Double? = null,
    val requestedSmbU: Double? = null,
    val requestedTbrUph: Double? = null,
    val requestedTbrMinutes: Int? = null,
    val tddU: Double? = null,
    val insulinDivisor: Int? = null,
    val dynIsfAdjustmentFactor: Double? = null
) {
    @Serializable
    enum class Reason { INPUT_CONSTRAINT, HIGH_TT_BLOCK, ALWAYS, COB, RECENT_CARBS, TT, NO_CONDITION, PREDICTED_LOW, EXCESSIVE_DELTA, IOB, INVALID_INPUT }
    @Serializable
    enum class IsfBasis { BLENDED_CURRENT_MIN_PREDICTED, CURRENT_BG, MIN_PREDICTED_BG }

    // Non-finite input remains explicit diagnostic evidence, not an exception on the dosing path.
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { allowSpecialFloatingPointValues = true; encodeDefaults = true }
    }
}
