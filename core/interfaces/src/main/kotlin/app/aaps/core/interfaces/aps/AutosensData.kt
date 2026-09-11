package app.aaps.core.interfaces.aps

interface AutosensData {

    data class CarbsInPast(

        var time: Long,
        var carbs: Double,
        var min5minCarbImpact: Double = 0.0,
        var remaining: Double
    ) {
        // override fun toString(): String =
        //     String.format(Locale.ENGLISH, "CarbsInPast: time: %s carbs: %.02f min5minCI: %.02f remaining: %.2f", dateUtil.dateAndTimeString(time), carbs, min5minCarbImpact, remaining)
    }

    var time: Long
    var bg: Double
    var sens: Double
    var pastSensitivity: String
    var deviation: Double
    var validDeviation: Boolean
    var activeCarbsList: MutableList<CarbsInPast>
    var this5MinAbsorption: Double
    var carbsFromBolus: Double
    var cob: Double
    var bgi: Double
    var delta: Double
    var avgDelta: Double
    var slopeFromMaxDeviation: Double
    var slopeFromMinDeviation: Double
    var usedMinCarbsImpact: Double
    var failOverToMinAbsorptionRate: Boolean

    var avgDeviation: Double

    var absorbing: Boolean
    var mealCarbs: Double
    var mealStartCounter: Int
    var type: String
    var uam: Boolean
    var extraDeviation: MutableList<Double>

    var autosensResult: AutosensResult

    fun cloneCarbsList(): MutableList<CarbsInPast>

    /** A separate owner for every mutable row, nested carb entry and sensitivity result. */
    fun deepCopy(): AutosensData

    /**
     * Deduct this 5 min absorption from the active carbs list from oldest to newest.
     */
    fun deductAbsorbedCarbs()
    fun removeOldCarbs(toTime: Long, isAAPSOrWeighted: Boolean)
}

/** Copies values only; the target retains its own behavior/dependencies. */
fun AutosensData.copyStateTo(target: AutosensData): AutosensData = target.also {
    it.time = time
    it.bg = bg
    it.sens = sens
    it.pastSensitivity = pastSensitivity
    it.deviation = deviation
    it.validDeviation = validDeviation
    it.activeCarbsList = activeCarbsList.map { carb -> carb.copy() }.toMutableList()
    it.this5MinAbsorption = this5MinAbsorption
    it.carbsFromBolus = carbsFromBolus
    it.cob = cob
    it.bgi = bgi
    it.delta = delta
    it.avgDelta = avgDelta
    it.slopeFromMaxDeviation = slopeFromMaxDeviation
    it.slopeFromMinDeviation = slopeFromMinDeviation
    it.usedMinCarbsImpact = usedMinCarbsImpact
    it.failOverToMinAbsorptionRate = failOverToMinAbsorptionRate
    it.avgDeviation = avgDeviation
    it.absorbing = absorbing
    it.mealCarbs = mealCarbs
    it.mealStartCounter = mealStartCounter
    it.type = type
    it.uam = uam
    it.extraDeviation = extraDeviation.toMutableList()
    it.autosensResult = autosensResult.copy()
}
