package com.sipsense.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SipRecordTest.kt
 *
 * Verifies no-arg defaults and property retention for SipRecord
 * and HydrationSip with ambientTemperature.
 */
class SipRecordTest {

    @Test
    fun `SipRecord constructs with defaults for Firebase deserialization`() {
        val record = SipRecord()
        assertEquals(0, record.amount)
        assertEquals(0L, record.timestamp)
        assertEquals(0, record.ambientTemperature)
    }

    @Test
    fun `SipRecord preserves supplied ambient temperature`() {
        val record = SipRecord(amount = 25, timestamp = 1791918000000L, ambientTemperature = 24)
        assertEquals(25, record.amount)
        assertEquals(1791918000000L, record.timestamp)
        assertEquals(24, record.ambientTemperature)
    }

    @Test
    fun `HydrationSip preserves ambient temperature`() {
        val sip = HydrationSip(amountMl = 30, timestampMs = 1791918000000L, ambientTemperature = 26)
        assertEquals(30, sip.amountMl)
        assertEquals(1791918000000L, sip.timestampMs)
        assertEquals(26, sip.ambientTemperature)
    }
}
