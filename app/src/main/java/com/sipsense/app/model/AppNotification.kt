package com.sipsense.app.model

import com.google.firebase.database.IgnoreExtraProperties
import com.google.firebase.database.PropertyName

/**
 * AppNotification.kt
 *
 * Data model for smart bottle and hydration notifications stored in
 * Firebase Realtime Database at /users/{uid}/notifications/{notificationId}.
 *
 * Categories:
 * 1. Hydration Reminders & Pacing:
 *    - SIP_REMINDER: Periodic reminder to drink water
 *    - GOAL_REACHED: Daily target achieved celebration
 *    - STREAK: Consecutive day target completion
 * 2. IoT Hardware & Sensor Alerts:
 *    - REFILL_NEEDED: Water reservoir running low (< 100 ml)
 *    - BOTTLE_LIFTED: Bottle left off the smart base load cell
 *    - HIGH_TEMP: Ambient temperature elevated (> 29°C)
 *    - LOW_BATTERY: ESP32 battery below 15%
 * 3. Connectivity & BLE Alerts:
 *    - DISCONNECTED: BLE lost / bottle left behind
 *    - CONNECTED: BLE paired and active
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 1.0
 */
@IgnoreExtraProperties
data class AppNotification(
    var id: String = "",
    var title: String = "",
    var message: String = "",
    var type: String = TYPE_SIP_REMINDER,
    var timestamp: Long = System.currentTimeMillis(),
    @get:PropertyName("unread")
    @set:PropertyName("unread")
    var isUnread: Boolean = true
) {
    // Zero-argument constructor required for Firebase Realtime Database deserialization
    constructor() : this(
        id = "",
        title = "",
        message = "",
        type = TYPE_SIP_REMINDER,
        timestamp = System.currentTimeMillis(),
        isUnread = true
    )

    fun checkIsUnread(): Boolean = isUnread

    companion object {
        const val TYPE_SIP_REMINDER = "SIP_REMINDER"
        const val TYPE_GOAL_REACHED = "GOAL_REACHED"
        const val TYPE_STREAK = "STREAK"
        const val TYPE_REFILL_NEEDED = "REFILL_NEEDED"
        const val TYPE_BOTTLE_LIFTED = "BOTTLE_LIFTED"
        const val TYPE_HIGH_TEMP = "HIGH_TEMP"
        const val TYPE_LOW_BATTERY = "LOW_BATTERY"
        const val TYPE_DISCONNECTED = "DISCONNECTED"
        const val TYPE_CONNECTED = "CONNECTED"
    }
}
