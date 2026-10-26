package com.sipsense.app.data

import java.util.UUID

/**
 * BleConstants.kt
 *
 * Defines the standard GATT Service and Characteristic UUIDs for the
 * SipSense Smart Bottle BLE communication protocol.
 *
 * Must match the UUIDs advertised by the ESP32 firmware.
 *
 * @project SipSense - Smart Bottle Ecosystem
 */
object BleConstants {
    /** Expected advertising device name */
    const val DEVICE_NAME = "SipSense-Bottle"

    /** Primary custom GATT service for SipSense smart bottle */
    val SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")

    /** Characteristic used to transmit JSON telemetry packets via Notify / Read */
    val TELEMETRY_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

    /** Standard Client Characteristic Configuration Descriptor (CCCD) to enable notifications */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
