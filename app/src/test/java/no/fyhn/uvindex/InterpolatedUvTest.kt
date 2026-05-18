package no.fyhn.uvindex

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class InterpolatedUvTest {

    private val hours = (0..23).map {
        HourUv(LocalDateTime.of(2026, 5, 18, it, 0), it.toDouble())
    }

    @Test fun `returns the exact value at an integer hour`() {
        assertEquals(3.0, interpolatedUv(hours, 3.0), 1e-9)
        assertEquals(12.0, interpolatedUv(hours, 12.0), 1e-9)
    }

    @Test fun `linearly interpolates between two hours`() {
        // 3.5 sits exactly between hour 3 (uv 3.0) and hour 4 (uv 4.0).
        assertEquals(3.5, interpolatedUv(hours, 3.5), 1e-9)
    }

    @Test fun `clamps fracHour above range to the last value`() {
        assertEquals(23.0, interpolatedUv(hours, 25.0), 1e-9)
    }

    @Test fun `clamps fracHour below zero to the first value`() {
        assertEquals(0.0, interpolatedUv(hours, -1.0), 1e-9)
    }

    @Test fun `returns zero on empty list`() {
        assertEquals(0.0, interpolatedUv(emptyList(), 5.0), 1e-9)
    }
}
