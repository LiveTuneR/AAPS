package app.aaps.ui.compose.overview.enhanced

import app.aaps.core.interfaces.aps.AlgorithmDecisionSnapshot
import org.junit.Assert.*
import org.junit.Test

class OverviewFactorTest {
    private val decision=AlgorithmDecisionSnapshot("SMB",true,1,1,40.0,35.0,10.0,true,dynIsfAdjustmentFactor=0.9)

    @Test fun standardSmbDisplaysActualRatioAdjustedIsfAndDosingBranchTakesPriority() {
        val standard = decision.copy(dynamicIsf = false, currentDynamicIsfMgdl = null)
        assertEquals(50.0, overviewCalculatedIsfMgdl(standard, 0.8)!!, 0.0)
        assertEquals(36.4, overviewCalculatedIsfMgdl(standard, 1.1)!!, 0.0)
        assertEquals(28.0, overviewCalculatedIsfMgdl(decision.copy(insulinReqIsfMgdl = 28.0), 1.0)!!, 0.0)
        assertEquals(35.0, overviewCalculatedIsfMgdl(decision, null)!!, 0.0)
    }

    @Test fun unavailableOrInvalidCalculationIsNotFabricatedFromProfile() {
        assertNull(overviewCalculatedIsfMgdl(null, 1.0))
        val standard = decision.copy(dynamicIsf = false, currentDynamicIsfMgdl = null)
        for (ratio in listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertNull(overviewCalculatedIsfMgdl(standard, ratio))
        assertNull(overviewCalculatedIsfMgdl(standard.copy(algorithm = "AUTO_ISF"), 1.0))
        assertNull(overviewCalculatedIsfMgdl(standard.copy(profileIsfMgdl = Double.NaN), 1.0))
    }

    @Test fun adjustmentIsPercentageNotDuplicateIsf() {
        assertEquals("90%",overviewAdjustmentFactor(decision))
        assertEquals("90%",overviewAdjustmentFactor(decision.copy(currentDynamicIsfMgdl=100.0)))
    }
    @Test fun missingEvidenceNeverFabricatesAFactorAndAutoIsfUsesItsOwnTypedFactor() {
        assertNull(overviewAdjustmentFactor(null))
        assertNull(overviewAdjustmentFactor(decision.copy(dynamicIsf=false,algorithm="AUTO_ISF")))
        assertEquals("110%",overviewAdjustmentFactor(decision.copy(dynamicIsf=false,algorithm="AUTO_ISF",autoIsfFactor=1.1)))
        assertNull(overviewAdjustmentFactor(decision.copy(dynIsfAdjustmentFactor=null)))
        assertNull(overviewAdjustmentFactor(decision.copy(dynIsfAdjustmentFactor=Double.NaN)))
    }
    @Test fun eligibilityStatesComeOnlyFromTypedBranchEvidence() {
        assertEquals(OverviewSmbState.UNKNOWN,overviewSmbState(null))
        assertEquals(OverviewSmbState.UNKNOWN,overviewSmbState(decision))
        assertEquals(OverviewSmbState.ON,overviewSmbState(decision.copy(conditionEligible=true,intervalWaiting=false)))
        assertEquals(OverviewSmbState.WAIT,overviewSmbState(decision.copy(conditionEligible=true,intervalWaiting=true)))
        assertEquals(OverviewSmbState.BLOCKED,overviewSmbState(decision.copy(conditionEligible=false)))
        assertEquals(OverviewSmbState.BLOCKED,overviewSmbState(decision.copy(conditionEligible=true,intervalWaiting=true,blockReason=AlgorithmDecisionSnapshot.Reason.PREDICTED_LOW)))
        assertEquals(OverviewSmbState.OFF,overviewSmbState(decision.copy(smbConfigured=false)))
    }
}
