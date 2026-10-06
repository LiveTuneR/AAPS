package app.aaps.core.data.diagnostics

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PresentationBuildGateTest {
    @Test fun `clock age corrections history config and restart invalidate display equality`() {
        val gate = PresentationBuildGate()
        val base = listOf(1L, 110.0, 1L, 2L, 10L)
        assertTrue(gate.needsBuild(base)); gate.commit(base)
        assertFalse(gate.needsBuild(base.toList()))
        base.indices.forEach { index ->
            assertTrue(gate.needsBuild(base.toMutableList().apply { this[index] = 99L }))
        }
        assertTrue(gate.needsBuild(null)); gate.reset(); assertTrue(gate.needsBuild(base))
    }
}
