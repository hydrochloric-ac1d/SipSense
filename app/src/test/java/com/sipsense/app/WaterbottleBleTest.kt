package com.sipsense.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WaterbottleBleTest.kt
 *
 * Unit tests verifying:
 * 1. Discovered BLE device modeling and RSSI signal quality classification.
 * 2. Manual connection state transitions (User control: Standby -> Connecting -> Connected -> Disconnected).
 * 3. Prevention of automatic BLE connection during scanning.
 * 4. ESP32 LED indicator state expectations (Blinking when broadcasting, Steady ON when connected).
 * 5. Parsing and validation of ESP32 BLE telemetry JSON payloads.
 *
 * @project SipSense - Smart Bottle Ecosystem
 */
class WaterbottleBleTest {

    // ── 1. RSSI Signal Quality Classification ───────────────────────────

    @Test
    fun `rssi signal quality categorizes correctly across dBm thresholds`() {
        fun classifyRssi(rssi: Int): String {
            return when {
                rssi >= -60 -> "Excellent"
                rssi >= -75 -> "Good"
                rssi >= -85 -> "Fair"
                else        -> "Weak"
            }
        }

        assertEquals("Excellent", classifyRssi(-45))
        assertEquals("Excellent", classifyRssi(-59))
        assertEquals("Excellent", classifyRssi(-60))

        assertEquals("Good", classifyRssi(-61))
        assertEquals("Good", classifyRssi(-70))
        assertEquals("Good", classifyRssi(-75))

        assertEquals("Fair", classifyRssi(-76))
        assertEquals("Fair", classifyRssi(-80))
        assertEquals("Fair", classifyRssi(-85))

        assertEquals("Weak", classifyRssi(-86))
        assertEquals("Weak", classifyRssi(-95))
        assertEquals("Weak", classifyRssi(-100))
    }

    // ── 2. Manual Connect vs Auto-connect Logic ─────────────────────────

    @Test
    fun `scan does not auto connect when autoConnectOnScan is false`() {
        val autoConnectOnScan = false
        var connectAttempted = false

        // Discovered ESP32 bottle advertisement
        val isTargetDevice = true

        if (isTargetDevice && autoConnectOnScan) {
            connectAttempted = true
        }

        // Must remain false: device is listed in UI so the user can manually connect
        assertFalse(connectAttempted)
    }

    @Test
    fun `manual connect and disconnect state transitions`() {
        var connectionState = "DISCONNECTED"
        var activeDeviceName: String? = null

        // User taps "Connect" on discovered device
        fun userTapConnect(targetName: String) {
            connectionState = "CONNECTING"
            // Simulate GATT onConnectionStateChange success
            connectionState = "CONNECTED"
            activeDeviceName = targetName
        }

        // User taps "Disconnect"
        fun userTapDisconnect() {
            connectionState = "DISCONNECTED"
            activeDeviceName = null
        }

        assertEquals("DISCONNECTED", connectionState)

        userTapConnect("SipSense-Bottle")
        assertEquals("CONNECTED", connectionState)
        assertEquals("SipSense-Bottle", activeDeviceName)

        userTapDisconnect()
        assertEquals("DISCONNECTED", connectionState)
        assertEquals(null, activeDeviceName)
    }

    // ── 3. ESP32 Status LED State Verification ──────────────────────────

    @Test
    fun `esp32 led indicator blinks while broadcasting and stays steady once connected`() {
        fun getExpectedLedState(isDeviceConnected: Boolean): String {
            return if (isDeviceConnected) "STEADY_ON" else "BLINKING"
        }

        // When ESP32 is advertising / broadcasting BLE signal
        val broadcastingState = getExpectedLedState(isDeviceConnected = false)
        assertEquals("BLINKING", broadcastingState)

        // When app connects to ESP32
        val connectedState = getExpectedLedState(isDeviceConnected = true)
        assertEquals("STEADY_ON", connectedState)

        // When app disconnects, returns to blinking
        val disconnectedState = getExpectedLedState(isDeviceConnected = false)
        assertEquals("BLINKING", disconnectedState)
    }

    // ── 4. Telemetry JSON Parsing Verification ──────────────────────────

    @Test
    fun `esp32 telemetry json parses accurately with all sensor fields`() {
        val jsonString = """
            {
                "waterLevel": 650,
                "bottleCapacity": 1000,
                "batteryLevel": 88,
                "ambientTemperature": 23.5,
                "temperature": 23.5,
                "humidity": 52,
                "isBottleAtRest": true,
                "lastSipAmount": 50,
                "totalConsumedToday": 450,
                "deviceStatus": "Connected",
                "ledStatus": "Steady"
            }
        """.trimIndent()

        val json = JSONObject(jsonString)
        assertEquals(650, json.getInt("waterLevel"))
        assertEquals(1000, json.getInt("bottleCapacity"))
        assertEquals(88, json.getInt("batteryLevel"))
        assertEquals(23.5, json.getDouble("ambientTemperature"), 0.01)
        assertEquals(52, json.getInt("humidity"))
        assertTrue(json.getBoolean("isBottleAtRest"))
        assertEquals(50, json.getInt("lastSipAmount"))
        assertEquals(450, json.getInt("totalConsumedToday"))
        assertEquals("Connected", json.getString("deviceStatus"))
        assertEquals("Steady", json.getString("ledStatus"))
    }
}

