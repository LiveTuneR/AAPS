package app.aaps.pump.apex

import app.aaps.pump.apex.bolus.ApexHistoryCandidate
import app.aaps.pump.apex.bolus.classifyHistory
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ApexHistoryAnomalyTest {
    @Test fun `non monotonic latest history is reported`() {
        val anomalies = classifyHistory(listOf(row(0, 300), row(1, 100), row(2, 200)))
        assertThat(anomalies.monotonicityViolations).isEqualTo(1)
    }

    @Test fun `stale slot between current rows is reported`() {
        val day = 86_400_000L
        val anomalies = classifyHistory(listOf(row(0, 40 * day), row(1, day), row(2, 39 * day)))
        assertThat(anomalies.staleSlots).isEqualTo(1)
    }

    @Test fun `duplicate history object is reported`() {
        val duplicate = row(1, 100)
        assertThat(classifyHistory(listOf(duplicate, duplicate)).duplicates).isEqualTo(1)
    }

    @Test fun `cursor jump is reported`() {
        assertThat(classifyHistory(listOf(row(0, 300), row(5, 200))).cursorJumps).isEqualTo(1)
    }

    @Test fun `newest and oldest timestamps are retained`() {
        val anomalies = classifyHistory(listOf(row(0, 300), row(1, 100), row(2, 200)))
        assertThat(anomalies.newestTimestamp).isEqualTo(300)
        assertThat(anomalies.oldestTimestamp).isEqualTo(100)
    }

    private fun row(index: Int, timestamp: Long) = ApexHistoryCandidate(index, timestamp, 16, 16, "date", 16, "LatestBoluses", index)
}
