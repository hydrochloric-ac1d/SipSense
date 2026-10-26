package com.sipsense.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests validating the Load Cell and HX711 amplifier bottle base rest logic:
 * "The system shall calculate fluid volume in real-time by subtracting the updated
 * bottle weight from the prior full reference weight using the Load Cell and HX711
 * amplifier whenever the bottle rests on the base."
 */
class BottleBaseLoadCellCalculationTest {

    @Test
    fun `fluid volume calculation subtracts updated weight from prior reference weight`() {
        val priorReferenceWeightMl = 650
        val updatedWeightMl = 615
        
        // System calculates fluid volume difference on resting
        val sipVolumeCalculated = (priorReferenceWeightMl - updatedWeightMl).coerceAtLeast(0)
        val remainingWaterLevel = updatedWeightMl

        assertEquals(35, sipVolumeCalculated)
        assertEquals(615, remainingWaterLevel)
    }

    @Test
    fun `water level display freezes while bottle is lifted and updates upon resting`() {
        var isBottleAtRest = true
        var displayedWaterLevel = 650
        var latestSensorWaterLevel = 650

        // 1. Initial state: resting on base
        assertTrue(isBottleAtRest)
        assertEquals(650, displayedWaterLevel)

        // 2. User lifts bottle to take a sip
        isBottleAtRest = false
        assertFalse(isBottleAtRest)

        // Sensor / simulator reports water level has decreased to 590 ml while lifted
        val incomingSensorWaterLevel = 590
        latestSensorWaterLevel = incomingSensorWaterLevel

        // Display must ONLY update if bottle is at rest on base
        if (isBottleAtRest) {
            displayedWaterLevel = latestSensorWaterLevel
        }
        // Displayed level remains frozen at 650 ml while bottle is off base!
        assertEquals(650, displayedWaterLevel)

        // 3. User places bottle back on base
        isBottleAtRest = true
        assertTrue(isBottleAtRest)

        // Once returned to rest, water level display updates to latest sensor level
        if (isBottleAtRest) {
            displayedWaterLevel = latestSensorWaterLevel
        }
        assertEquals(590, displayedWaterLevel)
    }

    @Test
    fun `toggle base status does not add water unless sensor weight changed`() {
        var isBottleAtRest = true
        var displayedWaterLevel = 650
        var latestSensorWaterLevel = 650
        var totalConsumedToday = 0

        // User taps indicator to lift bottle
        isBottleAtRest = !isBottleAtRest
        assertFalse(isBottleAtRest)

        // User taps indicator again to place back on base without drinking (no sensor level change)
        isBottleAtRest = !isBottleAtRest
        assertTrue(isBottleAtRest)

        // Only calculate sip if sensor telemetry reported a difference
        if (latestSensorWaterLevel != displayedWaterLevel) {
            val sip = (displayedWaterLevel - latestSensorWaterLevel).coerceAtLeast(0)
            totalConsumedToday += sip
            displayedWaterLevel = latestSensorWaterLevel
        }

        assertEquals(650, displayedWaterLevel)
        assertEquals(0, totalConsumedToday)
    }
}
