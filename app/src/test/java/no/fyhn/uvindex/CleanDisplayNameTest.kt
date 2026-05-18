package no.fyhn.uvindex

import org.junit.Assert.assertEquals
import org.junit.Test

class CleanDisplayNameTest {

    @Test fun `keeps non-numeric components`() {
        assertEquals("Oslo, Norway", cleanDisplayName("Oslo, Norway"))
    }

    @Test fun `strips 4-digit postcode`() {
        // Norwegian postcodes are 4 digits.
        assertEquals("Oslo, Norway", cleanDisplayName("Oslo, 0010, Norway"))
    }

    @Test fun `strips 5-digit postcode`() {
        assertEquals("Berlin, Germany", cleanDisplayName("Berlin, 10115, Germany"))
    }

    @Test fun `keeps short numeric components`() {
        // The regex requires 3-6 digits, so street numbers (typically 1-2)
        // and other short numerics stay.
        assertEquals(
            "Karl Johans gate, 12, Oslo",
            cleanDisplayName("Karl Johans gate, 12, Oslo"),
        )
    }

    @Test fun `collapses extra whitespace around commas`() {
        assertEquals("A, B", cleanDisplayName("A ,  B"))
    }
}
