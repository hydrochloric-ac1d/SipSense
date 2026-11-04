package com.sipsense.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for HX711 Load Cell bottle-at-rest state, fixed 500ml bottle capacity,
 * and automatic Daily Goal drinking water calculation when water volume is deducted.
 */
class BottleBaseLoadCellCalculationTest {

    /**
     * Helper class mirroring the DashboardFragment & MainActivity HX711 bottle-at-rest
     * state machine and 500ml water volume deduction logic.
     */
    class BottleRestStateMachine(
        initialWaterLevel: Int = MainActivity.DEFAULT_BOTTLE_CAPACITY_ML,
        val bottleCapacity: Int = MainActivity.DEFAULT_BOTTLE_CAPACITY_ML,
        initialAtRest: Boolean = true
    ) {
        var isBottleAtRest: Boolean = initialAtRest
            private set

        var displayedWaterLevel: Int = initialWaterLevel.coerceIn(0, bottleCapacity)
            private set

        var latestSensorWaterLevel: Int = initialWaterLevel.coerceIn(0, bottleCapacity)
            private set

        var totalConsumedToday: Int = 0
            private set

        var lastRecordedSip: Int = 0
            private set

        /**
         * Receives new sensor telemetry from ESP32 / Firebase.
         * Every time the water volume decreases while at rest, the deducted ml is
         * automatically counted as drinking water towards the Daily Goal.
         */
        fun onSensorTelemetryReceived(sensorWaterLevel: Int, sensorReportsAtRest: Boolean? = null) {
            val clampedSensor = sensorWaterLevel.coerceIn(0, bottleCapacity)
            latestSensorWaterLevel = clampedSensor
            if (sensorReportsAtRest != null) {
                isBottleAtRest = sensorReportsAtRest
            }
            if (isBottleAtRest) {
                if (clampedSensor < displayedWaterLevel) {
                    val deductedMl = displayedWaterLevel - clampedSensor
                    lastRecordedSip = deductedMl
                    totalConsumedToday += deductedMl
                } else {
                    lastRecordedSip = 0
                }
                displayedWaterLevel = clampedSensor
            }
        }

        /**
         * User or hardware toggles bottle rest state (lift off base vs place on base).
         */
        fun toggleBottleRestState() {
            isBottleAtRest = !isBottleAtRest
            if (isBottleAtRest) {
                if (latestSensorWaterLevel != displayedWaterLevel) {
                    val sipConsumed = (displayedWaterLevel - latestSensorWaterLevel).coerceAtLeast(0)
                    displayedWaterLevel = latestSensorWaterLevel
                    if (sipConsumed > 0) {
                        lastRecordedSip = sipConsumed
                        totalConsumedToday += sipConsumed
                    }
                } else {
                    lastRecordedSip = 0
                }
            }
        }
    }

    @Test
    fun `default bottle capacity and full water level is fixed at 500ml`() {
        val stateMachine = BottleRestStateMachine()
        assertEquals(500, MainActivity.DEFAULT_BOTTLE_CAPACITY_ML)
        assertEquals(500, stateMachine.bottleCapacity)
        assertEquals(500, stateMachine.displayedWaterLevel)
    }

    @Test
    fun `every water volume deduction while at rest counts towards Daily Goal`() {
        val stateMachine = BottleRestStateMachine(initialWaterLevel = 500, initialAtRest = true)

        // First drink: 500 ml -> 420 ml (80 ml deducted)
        stateMachine.onSensorTelemetryReceived(420)
        assertEquals(420, stateMachine.displayedWaterLevel)
        assertEquals(80, stateMachine.lastRecordedSip)
        assertEquals(80, stateMachine.totalConsumedToday)

        // Second drink: 420 ml -> 300 ml (120 ml deducted)
        stateMachine.onSensorTelemetryReceived(300)
        assertEquals(300, stateMachine.displayedWaterLevel)
        assertEquals(120, stateMachine.lastRecordedSip)
        assertEquals(200, stateMachine.totalConsumedToday)
    }

    @Test
    fun `when bottle is lifted off base, water level display is held steady and does not change`() {
        val stateMachine = BottleRestStateMachine(initialWaterLevel = 500, initialAtRest = true)

        // Lift bottle off base
        stateMachine.toggleBottleRestState()
        assertFalse(stateMachine.isBottleAtRest)
        assertEquals(500, stateMachine.displayedWaterLevel)

        // Even if load cell reads 0ml while bottle is in the air, displayed level stays 500ml
        stateMachine.onSensorTelemetryReceived(0)
        assertEquals("Displayed water level must not change while lifted", 500, stateMachine.displayedWaterLevel)
        assertEquals(0, stateMachine.totalConsumedToday)
    }

    @Test
    fun `toggling lift and place back on base without sensor change does not fabricate sips`() {
        val stateMachine = BottleRestStateMachine(initialWaterLevel = 500, initialAtRest = true)

        // Lift bottle
        stateMachine.toggleBottleRestState()
        assertFalse(stateMachine.isBottleAtRest)
        assertEquals(500, stateMachine.displayedWaterLevel)

        // Place bottle back on base without any sensor change
        stateMachine.toggleBottleRestState()
        assertTrue(stateMachine.isBottleAtRest)
        assertEquals(500, stateMachine.displayedWaterLevel)
        assertEquals(0, stateMachine.lastRecordedSip)
        assertEquals(0, stateMachine.totalConsumedToday)
    }

    @Test
    fun `when bottle returns to rest after drinking, deducted volume is calculated accurately`() {
        val stateMachine = BottleRestStateMachine(initialWaterLevel = 500, initialAtRest = true)

        // 1. Lift bottle to drink
        stateMachine.toggleBottleRestState()
        assertFalse(stateMachine.isBottleAtRest)

        // 2. User drinks 150ml; sensor measures 350ml just as bottle is placed back on base
        stateMachine.onSensorTelemetryReceived(350)
        // Still lifted until rest state is confirmed
        assertEquals(500, stateMachine.displayedWaterLevel)

        // 3. Bottle is now at rest on the base
        stateMachine.toggleBottleRestState()
        assertTrue(stateMachine.isBottleAtRest)
        assertEquals(350, stateMachine.displayedWaterLevel)
        assertEquals(150, stateMachine.lastRecordedSip)
        assertEquals(150, stateMachine.totalConsumedToday)
    }
}
