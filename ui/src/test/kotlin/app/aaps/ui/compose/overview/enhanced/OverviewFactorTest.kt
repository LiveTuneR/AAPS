package app.aaps.ui.compose.overview.enhanced

import app.aaps.core.interfaces.aps.AlgorithmDecisionSnapshot
import org.junit.Assert.*
import org.junit.Test

class OverviewFactorTest {
    private val decision=AlgorithmDecisionSnapshot("SMB",true,1,1,40.0,35.0,10.0,true,dynIsfAdjustmentFactor=0.9)

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
        assertEquals(OverviewSmbState.OFF,overviewSmbState(decision.copy(conditionEligible=false)))
    }
}
