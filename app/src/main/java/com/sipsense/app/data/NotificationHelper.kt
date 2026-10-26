package com.sipsense.app.data

import com.sipsense.app.R
import com.sipsense.app.model.AppNotification
import com.sipsense.app.model.NotificationListItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * NotificationHelper.kt
 *
 * Utility functions for formatting relative timestamps, categorizing
 * notifications into chronological sections (TODAY, YESTERDAY, THIS WEEK, EARLIER),
 * resolving category icons, and assembling the final adapter list.
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 1.0
 */
object NotificationHelper {

    enum class NotificationSection(val headerTitle: String) {
        TODAY("TODAY"),
        YESTERDAY("YESTERDAY"),
        THIS_WEEK("THIS WEEK"),
        EARLIER("EARLIER")
    }

    /**
     * Resolves the appropriate drawable icon based on notification type.
     */
    fun getIconForType(type: String): Int {
        return when (type) {
            AppNotification.TYPE_SIP_REMINDER -> R.drawable.ic_bell
            AppNotification.TYPE_GOAL_REACHED -> R.drawable.ic_target
            AppNotification.TYPE_STREAK -> R.drawable.ic_shield_check
            AppNotification.TYPE_REFILL_NEEDED -> R.drawable.ic_humidity
            AppNotification.TYPE_BOTTLE_LIFTED -> R.drawable.ic_base_lifted
            AppNotification.TYPE_HIGH_TEMP -> R.drawable.ic_temperature
            AppNotification.TYPE_LOW_BATTERY -> R.drawable.ic_battery
            AppNotification.TYPE_DISCONNECTED -> R.drawable.ic_bluetooth
            AppNotification.TYPE_CONNECTED -> R.drawable.ic_bluetooth
            else -> R.drawable.ic_bell
        }
    }

    /**
     * Formats a millisecond timestamp into a human-friendly relative label:
     * - Under 1 min: "Just now"
     * - Under 60 mins: "Xm ago"
     * - Today: "Xh ago"
     * - Yesterday: "Yesterday"
     * - Within 7 days: Day of week ("Mon", "Tue", etc.)
     * - Older: "MMM d" (e.g., "Oct 24")
     */
    fun formatRelativeTime(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diffMs = (nowMs - timestampMs).coerceAtLeast(0L)
        val minutes = diffMs / (60 * 1000L)
        val hours = diffMs / (60 * 60 * 1000L)

        val itemCal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMs }

        val isToday = itemCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
                itemCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)

        val yesterdayCal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val isYesterday = itemCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
                itemCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)

        return when {
            minutes < 1L -> "Just now"
            minutes < 60L -> "${minutes}m ago"
            isToday -> "${hours}h ago"
            isYesterday -> "Yesterday"
            else -> {
                val daysAgo = diffMs / (24 * 60 * 60 * 1000L)
                if (daysAgo < 7L) {
                    SimpleDateFormat("EEE", Locale.US).format(itemCal.time)
                } else {
                    SimpleDateFormat("MMM d", Locale.US).format(itemCal.time)
                }
            }
        }
    }

    /**
     * Categorizes a notification into TODAY, YESTERDAY, THIS WEEK, or EARLIER.
     */
    fun getSectionForTimestamp(timestampMs: Long, nowMs: Long = System.currentTimeMillis()): NotificationSection {
        val itemCal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val nowCal = Calendar.getInstance().apply { timeInMillis = nowMs }

        if (itemCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR) &&
            itemCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)
        ) {
            return NotificationSection.TODAY
        }

        val yesterdayCal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_YEAR, -1)
        }
        if (itemCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
            itemCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
        ) {
            return NotificationSection.YESTERDAY
        }

        val diffMs = (nowMs - timestampMs).coerceAtLeast(0L)
        val daysAgo = diffMs / (24 * 60 * 60 * 1000L)
        return if (daysAgo <= 7L) {
            NotificationSection.THIS_WEEK
        } else {
            NotificationSection.EARLIER
        }
    }

    /**
     * Converts a flat list of AppNotifications from the database into
     * section-headered NotificationListItems ready for the RecyclerView.
     */
    fun groupNotifications(
        notifications: List<AppNotification>,
        nowMs: Long = System.currentTimeMillis()
    ): List<NotificationListItem> {
        if (notifications.isEmpty()) return emptyList()

        val sorted = notifications.sortedByDescending { it.timestamp }
        val grouped = sorted.groupBy { getSectionForTimestamp(it.timestamp, nowMs) }
        val result = mutableListOf<NotificationListItem>()

        val sectionOrder = listOf(
            NotificationSection.TODAY,
            NotificationSection.YESTERDAY,
            NotificationSection.THIS_WEEK,
            NotificationSection.EARLIER
        )

        for (section in sectionOrder) {
            val items = grouped[section]
            if (!items.isNullOrEmpty()) {
                result.add(NotificationListItem.Header(section.headerTitle))
                for (notif in items) {
                    result.add(
                        NotificationListItem.Notification(
                            id = notif.id,
                            title = notif.title,
                            message = notif.message,
                            timeAgo = formatRelativeTime(notif.timestamp, nowMs),
                            iconResId = getIconForType(notif.type),
                            isUnread = notif.checkIsUnread(),
                            timestamp = notif.timestamp,
                            type = notif.type
                        )
                    )
                }
            }
        }

        return result
    }
}
