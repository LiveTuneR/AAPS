package app.aaps.plugins.sensitivity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutosensRangeTest {
    @Test fun `range matches full scan including exact boundaries`() {
        for (size in listOf(0, 3, 288, 4605, 100000)) {
            val keys = LongArray(size) { it * 300000L }
            for (from in listOf(-1L, 0L, 300001L, (size - 290L) * 300000, Long.MAX_VALUE)) {
                var reads = 0
                val first = autosensLowerBound(size, from) { reads++; keys[it] }
                val expected = keys.indexOfFirst { it >= from }.let { if (it < 0) size else it }
                assertEquals(expected, first)
                assertTrue(reads <= 18)
                assertEquals(keys.filter { it >= from && it <= 900000 }, keys.drop(first).takeWhile { it <= 900000 })
            }
        }
    }
}
