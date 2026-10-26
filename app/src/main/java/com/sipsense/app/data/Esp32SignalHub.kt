package com.sipsense.app.data

import android.os.Handler
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Esp32SignalHub.kt
 *
 * Centralized logging and hardware telemetry repository for ESP32 Bluetooth Low Energy
 * signals in the SipSense smart bottle ecosystem.
 *
 * Capabilities:
 * - Real-time recording of send (TX) and receive (RX) signals between the Android app and ESP32.
 * - Stores immutable hardware details (serial number, firmware version, flash code, chip ID).
 * - Read-only hardware telemetry profile: edits are strictly prohibited.
 * - Live listener registration for the Developer Mode Terminal view.
 * - Test simulation utilities for developer verification (ping TX/RX, simulated telemetry).
 *
 * @project SipSense - Smart Bottle Ecosystem
 */
object Esp32SignalHub {

    enum class SignalDirection {
        TX,     // Outgoing command / transmission to ESP32
        RX,     // Incoming response / telemetry notification from ESP32
        INFO,   // Bluetooth / GATT state machine lifecycle info
        GATT,   // GATT service / characteristic / descriptor operation
        ERROR   // BLE communication error or malformed payload
    }

    data class SignalLogEntry(
        val id: Long = System.nanoTime(),
        val timestamp: Long = System.currentTimeMillis(),
        val direction: SignalDirection,
        val tag: String,
        val message: String,
        val rawPayload: String? = null
    ) {
        fun formattedTime(): String {
            val sdf = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
            return sdf.format(Date(timestamp))
        }

        fun toConsoleString(): String {
            val dirStr = when (direction) {
                SignalDirection.TX -> "[TX ▶]"
                SignalDirection.RX -> "[RX ◀]"
                SignalDirection.GATT -> "[GATT]"
                SignalDirection.INFO -> "[INFO]"
                SignalDirection.ERROR -> "[ERR!]"
            }
            return "[${formattedTime()}] $dirStr [$tag] $message"
        }
    }

    data class Esp32DeviceInfo(
        val deviceName: String = BleConstants.DEVICE_NAME,
        val serialNumber: String = "ESP32-SIP-9482147",
        val firmwareVersion: String = "v1.2.0-ble-prod",
        val flashCode: String = "0x164020 (Winbond 4MB SPI, Quad-IO)",
        val chipModel: String = "ESP32-D0WDQ6 Rev 1",
        val cpuArchitecture: String = "Xtensa® Dual-Core 32-bit LX6 @ 240MHz",
        val macAddress: String = "C8:2E:18:74:9A:32",
        val serviceUuid: String = BleConstants.SERVICE_UUID.toString(),
        val telemetryCharUuid: String = BleConstants.TELEMETRY_CHAR_UUID.toString(),
        val cccdDescriptorUuid: String = BleConstants.CCCD_UUID.toString(),
        val mtuSize: Int = 512,
        val connectionState: String = "STANDBY",
        val rssi: Int = -64,
        val hx711Status: String = "HX711 24-bit ADC (DT: GPIO 21, SCK: GPIO 22)",
        val dht22Status: String = "DHT22 Temp & Humidity (DATA: GPIO 19)",
        val batteryAdcStatus: String = "Voltage Divider ADC1_CH7 (GPIO 34)",
        val statusLedPin: String = "GPIO 2 (Blue Status LED)",
        val totalBytesTransmitted: Long = 0L,
        val totalBytesReceived: Long = 0L,
        val lastSignalTimestamp: Long = 0L
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private const val MAX_LOGS = 500

    private val logHistory = Collections.synchronizedList(mutableListOf<SignalLogEntry>())
    private val signalListeners = CopyOnWriteArrayList<(SignalLogEntry) -> Unit>()
    private val detailsListeners = CopyOnWriteArrayList<(Esp32DeviceInfo) -> Unit>()

    @Volatile
    var deviceInfo: Esp32DeviceInfo = Esp32DeviceInfo()
        private set

    init {
        // Seed initial boot diagnostics log
        log(
            SignalDirection.INFO,
            "BOOT",
            "ESP32 Signal Hub initialized. Monitoring Bluetooth GATT communication on Service ${BleConstants.SERVICE_UUID}"
        )
        log(
            SignalDirection.INFO,
            "HW_PROFILE",
            "Hardware Profile loaded: Serial=${deviceInfo.serialNumber}, FW=${deviceInfo.firmwareVersion}, Flash=${deviceInfo.flashCode}"
        )
    }

    // ── Logging Methods ──────────────────────────────────────────────────

    fun log(
        direction: SignalDirection,
        tag: String,
        message: String,
        rawPayload: String? = null
    ) {
        val entry = SignalLogEntry(
            direction = direction,
            tag = tag,
            message = message,
            rawPayload = rawPayload
        )

        synchronized(logHistory) {
            logHistory.add(entry)
            if (logHistory.size > MAX_LOGS) {
                logHistory.removeAt(0)
            }
        }

        // Track byte metrics
        val byteCount = (rawPayload?.toByteArray(Charsets.UTF_8)?.size ?: message.length).toLong()
        updateDeviceInfo { info ->
            when (direction) {
                SignalDirection.TX -> info.copy(
                    totalBytesTransmitted = info.totalBytesTransmitted + byteCount,
                    lastSignalTimestamp = entry.timestamp
                )
                SignalDirection.RX -> info.copy(
                    totalBytesReceived = info.totalBytesReceived + byteCount,
                    lastSignalTimestamp = entry.timestamp
                )
                else -> info.copy(lastSignalTimestamp = entry.timestamp)
            }
        }

        dispatchSignal(entry)
    }

    fun logTx(tag: String, message: String, rawPayload: String? = null) {
        log(SignalDirection.TX, tag, message, rawPayload)
    }

    fun logRx(tag: String, message: String, rawPayload: String? = null) {
        log(SignalDirection.RX, tag, message, rawPayload)
    }

    fun logGatt(tag: String, message: String) {
        log(SignalDirection.GATT, tag, message)
    }

    fun logInfo(tag: String, message: String) {
        log(SignalDirection.INFO, tag, message)
    }

    fun logError(tag: String, message: String) {
        log(SignalDirection.ERROR, tag, message)
    }

    fun clearLogs() {
        synchronized(logHistory) {
            logHistory.clear()
        }
        log(SignalDirection.INFO, "TERMINAL", "Terminal logs cleared by developer.")
    }

    fun getLogs(): List<SignalLogEntry> {
        return synchronized(logHistory) {
            ArrayList(logHistory)
        }
    }

    // ── Device Info Management ───────────────────────────────────────────

    fun updateDeviceInfo(updater: (Esp32DeviceInfo) -> Esp32DeviceInfo) {
        val updated = updater(deviceInfo)
        deviceInfo = updated
        dispatchDeviceInfo(updated)
    }

    // ── Listener Subscription ────────────────────────────────────────────

    fun addSignalListener(listener: (SignalLogEntry) -> Unit) {
        signalListeners.add(listener)
    }

    fun removeSignalListener(listener: (SignalLogEntry) -> Unit) {
        signalListeners.remove(listener)
    }

    fun addDeviceInfoListener(listener: (Esp32DeviceInfo) -> Unit) {
        detailsListeners.add(listener)
        // Immediately notify with current state
        listener(deviceInfo)
    }

    fun removeDeviceInfoListener(listener: (Esp32DeviceInfo) -> Unit) {
        detailsListeners.remove(listener)
    }

    private fun dispatchSignal(entry: SignalLogEntry) {
        mainHandler.post {
            for (listener in signalListeners) {
                try {
                    listener(entry)
                } catch (e: Exception) {
                    android.util.Log.e("Esp32SignalHub", "Error dispatching signal to listener", e)
                }
            }
        }
    }

    private fun dispatchDeviceInfo(info: Esp32DeviceInfo) {
        mainHandler.post {
            for (listener in detailsListeners) {
                try {
                    listener(info)
                } catch (e: Exception) {
                    android.util.Log.e("Esp32SignalHub", "Error dispatching details to listener", e)
                }
            }
        }
    }

    // ── Simulation Utilities for Developer Mode ──────────────────────────

    /**
     * Simulates a round-trip TX ping command and RX pong response from the ESP32.
     * Useful for developer mode testing when a physical ESP32 is not within range.
     */
    fun simulatePingExchange() {
        updateDeviceInfo { it.copy(connectionState = "CONNECTED (SIMULATION)") }
        logTx(
            "CMD_PING",
            "TX: 0x01 PING_ESP32 (Target: ${deviceInfo.deviceName}, UUID=${BleConstants.TELEMETRY_CHAR_UUID})"
        )

        mainHandler.postDelayed({
            logRx(
                "RSP_PONG",
                "RX: 0x02 ACK_PONG (Status=OK, RSSI=${deviceInfo.rssi} dBm, Heap=184220 B, UpTime=${System.currentTimeMillis() / 1000}s)"
            )
        }, 120)

        mainHandler.postDelayed({
            val telemetryJson = "{\"waterLevel\":630,\"bottleCapacity\":1000,\"batteryLevel\":87," +
                    "\"ambientTemperature\":24,\"temperature\":24,\"humidity\":55,\"sipAmount\":20," +
                    "\"totalConsumedToday\":70,\"isBottleAtRest\":true}"
            logRx(
                "NOTIFY_TELEMETRY",
                "RX: Live telemetry JSON packet received (${telemetryJson.length} bytes)",
                rawPayload = telemetryJson
            )
        }, 320)
    }

    /**
     * Simulates an ESP32 sip event being transmitted over BLE.
     */
    fun simulateSipEvent(amountMl: Int = 35) {
        updateDeviceInfo { it.copy(connectionState = "CONNECTED (SIMULATION)") }
        logTx(
            "SIM_REQ",
            "TX: Requesting simulated sip event ($amountMl ml) on ESP32"
        )

        mainHandler.postDelayed({
            val telemetryJson = "{\"waterLevel\":595,\"bottleCapacity\":1000,\"batteryLevel\":87," +
                    "\"ambientTemperature\":25,\"temperature\":25,\"humidity\":54,\"sipAmount\":$amountMl," +
                    "\"totalConsumedToday\":105,\"isBottleAtRest\":true}"
            logRx(
                "NOTIFY_TELEMETRY",
                "RX: SIP EVENT - Dispatched $amountMl ml sip telemetry notification (${telemetryJson.length} bytes)",
                rawPayload = telemetryJson
            )
        }, 200)
    }
}
