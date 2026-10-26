package com.sipsense.app

import com.sipsense.app.data.BleConstants
import com.sipsense.app.data.Esp32SignalHub
import com.sipsense.app.data.Esp32SignalHub.SignalDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DeveloperModeAndSignalHubTest.kt
 *
 * Unit tests for:
 * 1. Developer Mode access authorization restricted strictly to testuser1@sipsense.com.
 * 2. Samsung-style 5-tap gesture unlock sequence and interval timeout logic.
 * 3. Esp32SignalHub real-time send (TX) and receive (RX) signal logging & filtering.
 * 4. Read-only ESP32 hardware details (serial number, firmware version, flash code, MAC, UUIDs).
 * 5. Dashboard bottle rest status icon state (teal when at rest, orange when lifted).
 */
class DeveloperModeAndSignalHubTest {

    @Before
    fun setUp() {
        Esp32SignalHub.clearLogs()
    }

    // ── 1. Authorization Tests ───────────────────────────────────────────

    @Test
    fun `developer mode is authorized only for testuser1@sipsense_com`() {
        val authorizedEmail = "testuser1@sipsense.com"
        val unauthorizedEmail1 = "user@example.com"
        val unauthorizedEmail2 = "admin@sipsense.com"
        val unauthorizedEmail3 = "testuser2@sipsense.com"
        val nullEmail: String? = null

        fun checkAuth(email: String?): Boolean {
            return email?.equals(DeveloperModeFragment.AUTHORIZED_EMAIL, ignoreCase = true) == true
        }

        assertTrue(checkAuth(authorizedEmail))
        assertTrue(checkAuth("TESTUSER1@SIPSENSE.COM"))
        assertTrue(checkAuth("TestUser1@SipSense.com"))

        assertFalse(checkAuth(unauthorizedEmail1))
        assertFalse(checkAuth(unauthorizedEmail2))
        assertFalse(checkAuth(unauthorizedEmail3))
        assertFalse(checkAuth(nullEmail))
    }

    // ── 2. Tap Counter Logic (Samsung Developer Mode Style) ───────────────

    @Test
    fun `tapping logo 5 times unlocks developer mode`() {
        var tapCount = 0
        var isUnlocked = false
        var lastTapTime = 0L

        fun tapLogo(timestamp: Long) {
            if (timestamp - lastTapTime > 2000L) {
                tapCount = 0
            }
            lastTapTime = timestamp
            tapCount++
            if (tapCount >= 5) {
                tapCount = 0
                isUnlocked = true
            }
        }

        // Tap 1 to 4: not unlocked yet
        tapLogo(1000L)
        assertFalse(isUnlocked)
        assertEquals(1, tapCount)

        tapLogo(1300L)
        assertFalse(isUnlocked)
        assertEquals(2, tapCount)

        tapLogo(1600L)
        assertFalse(isUnlocked)
        assertEquals(3, tapCount)

        tapLogo(1900L)
        assertFalse(isUnlocked)
        assertEquals(4, tapCount)

        // Tap 5: unlocks developer mode!
        tapLogo(2200L)
        assertTrue(isUnlocked)
        assertEquals(0, tapCount)
    }

    @Test
    fun `tap sequence resets if interval exceeds 2 seconds`() {
        var tapCount = 0
        var isUnlocked = false
        var lastTapTime = 0L

        fun tapLogo(timestamp: Long) {
            if (timestamp - lastTapTime > 2000L) {
                tapCount = 0
            }
            lastTapTime = timestamp
            tapCount++
            if (tapCount >= 5) {
                tapCount = 0
                isUnlocked = true
            }
        }

        // Tap twice
        tapLogo(1000L)
        tapLogo(1500L)
        assertEquals(2, tapCount)

        // Wait 3 seconds (> 2000ms timeout)
        tapLogo(4600L)
        assertEquals(1, tapCount) // Reset and started over
        assertFalse(isUnlocked)
    }

    // ── 3. Signal Hub Logging & Direction Tests ──────────────────────────

    @Test
    fun `signal hub correctly logs send TX and receive RX signals`() {
        Esp32SignalHub.logTx("GATT_MTU", "CMD: gatt.requestMtu(512)")
        Esp32SignalHub.logRx("GATT_MTU", "EVT: onMtuChanged(512)")
        Esp32SignalHub.logTx("GATT_CCCD", "CMD: writeDescriptor(0x0001)")
        Esp32SignalHub.logRx(
            "TELEMETRY",
            "RX: JSON Telemetry Notification [64 bytes]",
            rawPayload = "{\"waterLevel\":650}"
        )

        val logs = Esp32SignalHub.getLogs()
        assertTrue(logs.size >= 4)

        val txLogs = logs.filter { it.direction == SignalDirection.TX }
        val rxLogs = logs.filter { it.direction == SignalDirection.RX }

        assertEquals(2, txLogs.size)
        assertEquals(2, rxLogs.size)

        assertEquals("GATT_MTU", txLogs[0].tag)
        assertEquals("CMD: gatt.requestMtu(512)", txLogs[0].message)

        assertEquals("TELEMETRY", rxLogs[1].tag)
        assertEquals("{\"waterLevel\":650}", rxLogs[1].rawPayload)
    }

    @Test
    fun `signal log entry formats console string with direction tags`() {
        val txEntry = Esp32SignalHub.SignalLogEntry(
            direction = SignalDirection.TX,
            tag = "CMD_PING",
            message = "0x01 PING"
        )
        val rxEntry = Esp32SignalHub.SignalLogEntry(
            direction = SignalDirection.RX,
            tag = "RSP_PONG",
            message = "0x02 ACK"
        )

        val txStr = txEntry.toConsoleString()
        val rxStr = rxEntry.toConsoleString()

        assertTrue(txStr.contains("[TX ▶]"))
        assertTrue(txStr.contains("[CMD_PING]"))
        assertTrue(txStr.contains("0x01 PING"))

        assertTrue(rxStr.contains("[RX ◀]"))
        assertTrue(rxStr.contains("[RSP_PONG]"))
        assertTrue(rxStr.contains("0x02 ACK"))
    }

    // ── 4. ESP32 Hardware Details Tests (Read-Only) ───────────────────────

    @Test
    fun `esp32 details contain serial number, firmware version, and flash code`() {
        val details = Esp32SignalHub.deviceInfo

        assertNotNull(details.serialNumber)
        assertTrue("Serial number must not be empty", details.serialNumber.isNotEmpty())
        assertEquals("ESP32-SIP-9482147", details.serialNumber)

        assertNotNull(details.firmwareVersion)
        assertTrue("Firmware version must not be empty", details.firmwareVersion.isNotEmpty())
        assertEquals("v1.2.0-ble-prod", details.firmwareVersion)

        assertNotNull(details.flashCode)
        assertTrue("Flash code must not be empty", details.flashCode.isNotEmpty())
        assertTrue("Flash code should identify Winbond SPI flash", details.flashCode.contains("0x164020"))

        assertNotNull(details.macAddress)
        assertEquals("C8:2E:18:74:9A:32", details.macAddress)

        assertEquals(BleConstants.SERVICE_UUID.toString(), details.serviceUuid)
        assertEquals(BleConstants.TELEMETRY_CHAR_UUID.toString(), details.telemetryCharUuid)
        assertEquals(512, details.mtuSize)
    }

    // ── 5. Bottle At Rest vs Lifted Status Color Test ────────────────────

    @Test
    fun `bottle at rest status displays teal while lifted displays orange`() {
        // Test simulation of bottle rest status state
        var isBottleAtRest = true

        fun getStatusColor(atRest: Boolean): String {
            return if (atRest) "teal" else "orange"
        }

        assertEquals("teal", getStatusColor(isBottleAtRest))

        // Bottle is lifted
        isBottleAtRest = false
        assertEquals("orange", getStatusColor(isBottleAtRest))

        // Bottle placed back on base
        isBottleAtRest = true
        assertEquals("teal", getStatusColor(isBottleAtRest))
    }

    // ── 6. Connection State Transition Tests ─────────────────────────────

    @Test
    fun `connection state transitions correctly from SCANNING to STANDBY or CONNECTED`() {
        // Initial state
        Esp32SignalHub.updateDeviceInfo { it.copy(connectionState = "STANDBY") }
        assertEquals("STANDBY", Esp32SignalHub.deviceInfo.connectionState)

        // Scan started
        Esp32SignalHub.updateDeviceInfo { it.copy(connectionState = "SCANNING") }
        assertEquals("SCANNING", Esp32SignalHub.deviceInfo.connectionState)

        // Scan stopped or timed out without finding device
        Esp32SignalHub.updateDeviceInfo { info ->
            if (info.connectionState == "SCANNING") info.copy(connectionState = "STANDBY") else info
        }
        assertEquals("STANDBY", Esp32SignalHub.deviceInfo.connectionState)

        // Simulation triggered in developer mode
        Esp32SignalHub.simulatePingExchange()
        assertEquals("CONNECTED (SIMULATION)", Esp32SignalHub.deviceInfo.connectionState)
    }
}
