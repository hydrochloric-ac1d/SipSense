package com.sipsense.app.data

import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.sipsense.app.model.AppNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * NotificationRepository.kt
 *
 * Manages persisting, updating, querying, and auto-seeding notifications
 * in Firebase Realtime Database at path: /users/{userId}/notifications.
 *
 * Also provides telemetry-driven alert evaluations (water level low,
 * low battery, high ambient temperature, goal reached, BLE state)
 * with robust cooldowns/debouncing to prevent spamming notifications.
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 1.0
 */
object NotificationRepository {

    private const val TAG = "NotificationRepository"

    // Cooldown trackers to prevent notification floods
    private var lastGoalNotifiedDateKey: String? = null
    private var lastLowBatteryNotificationMs: Long = 0L
    private var lastRefillNotificationMs: Long = 0L
    private var lastHighTempNotificationMs: Long = 0L
    private var lastBleDisconnectAlertMs: Long = 0L
    private var lastBleConnectedAlertMs: Long = 0L

    /**
     * Resets debouncing timestamps (primarily for unit tests).
     */
    fun resetDebounceState() {
        lastGoalNotifiedDateKey = null
        lastLowBatteryNotificationMs = 0L
        lastRefillNotificationMs = 0L
        lastHighTempNotificationMs = 0L
        lastBleDisconnectAlertMs = 0L
        lastBleConnectedAlertMs = 0L
    }

    /**
     * Attaches a real-time ValueEventListener to /users/{userId}/notifications.
     * If the node is empty, automatically seeds initial smart bottle notifications.
     */
    fun listenToNotifications(
        userId: String,
        onUpdate: (List<AppNotification>) -> Unit,
        onError: ((Exception) -> Unit)? = null
    ): ValueEventListener? {
        if (userId.isBlank()) return null
        val database = FirebaseDatabaseProvider.reference() ?: run {
            Log.w(TAG, "Database unavailable, falling back to empty notifications")
            onUpdate(emptyList())
            return null
        }

        val notificationsRef = database.child("users").child(userId).child("notifications")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists() || snapshot.childrenCount == 0L) {
                    // Seed initial notifications into the database so the user has data stored in Firebase
                    Log.d(TAG, "Notifications empty for user $userId. Seeding database...")
                    seedInitialNotifications(userId)
                    return
                }

                val list = mutableListOf<AppNotification>()
                for (child in snapshot.children) {
                    val id = child.child("id").getValue(String::class.java) ?: child.key ?: ""
                    val title = child.child("title").getValue(String::class.java) ?: ""
                    val message = child.child("message").getValue(String::class.java) ?: ""
                    val type = child.child("type").getValue(String::class.java) ?: AppNotification.TYPE_SIP_REMINDER
                    val timestamp = child.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()
                    val isUnread = child.child("unread").getValue(Boolean::class.java)
                        ?: child.child("isUnread").getValue(Boolean::class.java)
                        ?: true

                    list.add(
                        AppNotification(
                            id = id,
                            title = title,
                            message = message,
                            type = type,
                            timestamp = timestamp,
                            isUnread = isUnread
                        )
                    )
                }

                // Sort newest first
                list.sortByDescending { it.timestamp }
                onUpdate(list)
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Notification listener cancelled: ${error.message}")
                onError?.invoke(error.toException())
            }
        }

        notificationsRef.addValueEventListener(listener)
        return listener
    }

    /**
     * Seeds initial smart bottle notifications into Firebase for the specified user.
     */
    fun seedInitialNotifications(userId: String) {
        if (userId.isBlank()) return
        val database = FirebaseDatabaseProvider.reference() ?: return
        val notifRef = database.child("users").child(userId).child("notifications")
        val now = System.currentTimeMillis()

        val seedList = listOf(
            // TODAY
            AppNotification(
                title = "Daily Hydration Goal Reached!",
                message = "Congratulations! You have reached your daily hydration goal. Keep it up!",
                type = AppNotification.TYPE_GOAL_REACHED,
                timestamp = now - (2 * 60 * 1000L), // 2m ago
                isUnread = true
            ),
            AppNotification(
                title = "Time for a Sip!",
                message = "It's been 2 hours since your last drink. Stay hydrated and take a sip now.",
                type = AppNotification.TYPE_SIP_REMINDER,
                timestamp = now - (60 * 60 * 1000L), // 1h ago
                isUnread = true
            ),
            AppNotification(
                title = "Streak Maintained",
                message = "Awesome! You've hit your hydration goal 3 days in a row.",
                type = AppNotification.TYPE_STREAK,
                timestamp = now - (4 * 60 * 60 * 1000L), // 4h ago
                isUnread = false
            ),
            // YESTERDAY
            AppNotification(
                title = "Water Refill Needed",
                message = "Your SipSense bottle is running low (below 100 ml). Please refill to continue tracking.",
                type = AppNotification.TYPE_REFILL_NEEDED,
                timestamp = now - (25 * 60 * 60 * 1000L), // ~1 day ago
                isUnread = false
            ),
            AppNotification(
                title = "Warm Environment Alert",
                message = "Ambient temperature is 30°C. Drink extra water to stay hydrated in the heat!",
                type = AppNotification.TYPE_HIGH_TEMP,
                timestamp = now - (29 * 60 * 60 * 1000L),
                isUnread = false
            ),
            // THIS WEEK
            AppNotification(
                title = "Bottle Connected",
                message = "SipSense Smart Base paired over Bluetooth. Telemetry synchronization is active.",
                type = AppNotification.TYPE_CONNECTED,
                timestamp = now - (3 * 24 * 60 * 60 * 1000L),
                isUnread = false
            ),
            AppNotification(
                title = "Welcome to SipSense!",
                message = "Your smart bottle ecosystem is ready. Place your bottle on the base to start automated sip tracking.",
                type = AppNotification.TYPE_SIP_REMINDER,
                timestamp = now - (5 * 24 * 60 * 60 * 1000L),
                isUnread = false
            )
        )

        for (item in seedList) {
            val newChildRef = notifRef.push()
            item.id = newChildRef.key ?: ""
            newChildRef.setValue(item)
        }
    }

    /**
     * Marks an individual notification as read in Firebase Realtime Database.
     */
    fun markAsRead(userId: String, notificationId: String) {
        if (userId.isBlank() || notificationId.isBlank()) return
        val database = FirebaseDatabaseProvider.reference() ?: return
        val notifRef = database.child("users").child(userId).child("notifications").child(notificationId)
        val updates = mapOf(
            "unread" to false,
            "isUnread" to false
        )
        notifRef.updateChildren(updates).addOnFailureListener { e ->
            Log.e(TAG, "Failed to mark notification $notificationId as read", e)
        }
    }

    /**
     * Marks all notifications belonging to the user as read in Firebase Realtime Database.
     */
    fun markAllAsRead(userId: String) {
        if (userId.isBlank()) return
        val database = FirebaseDatabaseProvider.reference() ?: return
        val notifRef = database.child("users").child(userId).child("notifications")

        notifRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                val bulkUpdates = mutableMapOf<String, Any>()
                for (child in snapshot.children) {
                    val key = child.key ?: continue
                    bulkUpdates["$key/unread"] = false
                    bulkUpdates["$key/isUnread"] = false
                }
                if (bulkUpdates.isNotEmpty()) {
                    notifRef.updateChildren(bulkUpdates).addOnFailureListener { e ->
                        Log.e(TAG, "Failed to mark all notifications as read", e)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Mark all read cancelled: ${error.message}")
            }
        })
    }

    /**
     * Pushes a new notification to Firebase under /users/{userId}/notifications.
     */
    fun postNotification(
        userId: String,
        notification: AppNotification,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        if (userId.isBlank()) {
            onComplete?.invoke(false)
            return
        }
        val database = FirebaseDatabaseProvider.reference() ?: run {
            onComplete?.invoke(false)
            return
        }
        val notifRef = database.child("users").child(userId).child("notifications").push()
        notification.id = notifRef.key ?: System.currentTimeMillis().toString()
        notifRef.setValue(notification)
            .addOnSuccessListener { onComplete?.invoke(true) }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to post notification", e)
                onComplete?.invoke(false)
            }
    }

    /**
     * Removes the ValueEventListener from the user's notifications node.
     */
    fun removeListener(userId: String, listener: ValueEventListener) {
        if (userId.isBlank()) return
        val database = FirebaseDatabaseProvider.reference() ?: return
        database.child("users").child(userId).child("notifications").removeEventListener(listener)
    }

    /**
     * Evaluates incoming telemetry values from BLE or HTTP polling against threshold triggers.
     * Automatically posts new alerts to Firebase when criteria are satisfied (debounced).
     */
    fun evaluateTelemetryForAlerts(
        userId: String,
        waterLevel: Int,
        bottleCapacity: Int,
        batteryLevel: Int,
        ambientTemp: Int,
        dailyTotal: Int,
        dailyTarget: Int,
        isAtRest: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (userId.isBlank()) return

        // 1. Daily Hydration Goal Reached Alert
        if (dailyTotal >= dailyTarget && dailyTarget > 0) {
            val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
            if (lastGoalNotifiedDateKey != todayKey) {
                lastGoalNotifiedDateKey = todayKey
                postNotification(
                    userId,
                    AppNotification(
                        title = "Daily Hydration Goal Reached!",
                        message = "Congratulations! You have reached your daily hydration goal of $dailyTarget ml. Outstanding job!",
                        type = AppNotification.TYPE_GOAL_REACHED,
                        timestamp = nowMs,
                        isUnread = true
                    )
                )
            }
        }

        // 2. Low Battery Alert (at or below 15%, 4h cooldown)
        if (batteryLevel in 1..15) {
            if (nowMs - lastLowBatteryNotificationMs > 4 * 60 * 60 * 1000L) {
                lastLowBatteryNotificationMs = nowMs
                postNotification(
                    userId,
                    AppNotification(
                        title = "Low Battery Warning",
                        message = "Your SipSense smart base battery is at $batteryLevel%. Recharge soon to avoid missing sip tracking.",
                        type = AppNotification.TYPE_LOW_BATTERY,
                        timestamp = nowMs,
                        isUnread = true
                    )
                )
            }
        }

        // 3. Water Refill Needed Alert (at or below 100 ml when resting, 2h cooldown)
        if (isAtRest && waterLevel in 1..100) {
            if (nowMs - lastRefillNotificationMs > 2 * 60 * 60 * 1000L) {
                lastRefillNotificationMs = nowMs
                postNotification(
                    userId,
                    AppNotification(
                        title = "Water Refill Needed",
                        message = "Water inside your bottle is low ($waterLevel ml). Refill your bottle to maintain your hydration pace.",
                        type = AppNotification.TYPE_REFILL_NEEDED,
                        timestamp = nowMs,
                        isUnread = true
                    )
                )
            }
        }

        // 4. High Ambient Temperature Alert (>= 30°C, 3h cooldown)
        if (ambientTemp >= 30) {
            if (nowMs - lastHighTempNotificationMs > 3 * 60 * 60 * 1000L) {
                lastHighTempNotificationMs = nowMs
                postNotification(
                    userId,
                    AppNotification(
                        title = "Warm Environment Alert",
                        message = "Ambient temperature is high ($ambientTemp°C). Remember to drink extra water to stay hydrated in the heat!",
                        type = AppNotification.TYPE_HIGH_TEMP,
                        timestamp = nowMs,
                        isUnread = true
                    )
                )
            }
        }
    }

    /**
     * Evaluates BLE connection / disconnection transitions to notify the user.
     */
    fun evaluateConnectionAlert(
        userId: String,
        isConnected: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (userId.isBlank()) return
        if (!isConnected) {
            // Alert on disconnection if 15 minutes have passed since last alert
            if (nowMs - lastBleDisconnectAlertMs > 15 * 60 * 1000L) {
                lastBleDisconnectAlertMs = nowMs
                postNotification(
                    userId,
                    AppNotification(
                        title = "Bottle Disconnected",
                        message = "Bluetooth connection to SipSense Smart Base was lost. Did you leave your bottle behind?",
                        type = AppNotification.TYPE_DISCONNECTED,
                        timestamp = nowMs,
                        isUnread = true
                    )
                )
            }
        } else {
            // Alert on reconnection if 30 minutes have passed
            if (nowMs - lastBleConnectedAlertMs > 30 * 60 * 1000L) {
                lastBleConnectedAlertMs = nowMs
                postNotification(
                    userId,
                    AppNotification(
                        title = "Bottle Connected",
                        message = "SipSense Smart Base reconnected over Bluetooth. Telemetry synchronization is active.",
                        type = AppNotification.TYPE_CONNECTED,
                        timestamp = nowMs,
                        isUnread = true
                    )
                )
            }
        }
    }
}
