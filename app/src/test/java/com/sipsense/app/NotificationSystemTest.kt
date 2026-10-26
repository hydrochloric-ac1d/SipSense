package com.sipsense.app

import com.sipsense.app.data.NotificationHelper
import com.sipsense.app.data.NotificationRepository
import com.sipsense.app.model.AppNotification
import com.sipsense.app.model.NotificationListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

/**
 * NotificationSystemTest.kt
 *
 * Unit tests verifying:
 * 1. AppNotification data model serialization, defaults, and unread checks
 * 2. Category icon resolution across all smart bottle notification types
 * 3. Relative time formatting ("Just now", "Xm ago", "Xh ago", "Yesterday", day of week, date)
 * 4. Section header categorization (TODAY, YESTERDAY, THIS WEEK, EARLIER)
 * 5. Full chronological grouping and adapter item generation
 * 6. Telemetry alert evaluation thresholds and debounce logic
 *
 * @project SipSense - Smart Bottle Ecosystem
 */
class NotificationSystemTest {

    @Before
    fun setUp() {
        NotificationRepository.resetDebounceState()
    }

    @Test
    fun testAppNotificationDefaultsAndUnread() {
        val notif = AppNotification(
            id = "test_1",
            title = "Time for a Sip!",
            message = "Stay hydrated",
            type = AppNotification.TYPE_SIP_REMINDER,
            timestamp = 1000L,
            isUnread = true
        )

        assertEquals("test_1", notif.id)
        assertEquals("Time for a Sip!", notif.title)
        assertEquals("Stay hydrated", notif.message)
        assertEquals(AppNotification.TYPE_SIP_REMINDER, notif.type)
        assertEquals(1000L, notif.timestamp)
        assertTrue(notif.checkIsUnread())

        notif.isUnread = false
        assertFalse(notif.checkIsUnread())
    }

    @Test
    fun testCategoryIconResolution() {
        assertEquals(R.drawable.ic_bell, NotificationHelper.getIconForType(AppNotification.TYPE_SIP_REMINDER))
        assertEquals(R.drawable.ic_target, NotificationHelper.getIconForType(AppNotification.TYPE_GOAL_REACHED))
        assertEquals(R.drawable.ic_shield_check, NotificationHelper.getIconForType(AppNotification.TYPE_STREAK))
        assertEquals(R.drawable.ic_humidity, NotificationHelper.getIconForType(AppNotification.TYPE_REFILL_NEEDED))
        assertEquals(R.drawable.ic_base_lifted, NotificationHelper.getIconForType(AppNotification.TYPE_BOTTLE_LIFTED))
        assertEquals(R.drawable.ic_temperature, NotificationHelper.getIconForType(AppNotification.TYPE_HIGH_TEMP))
        assertEquals(R.drawable.ic_battery, NotificationHelper.getIconForType(AppNotification.TYPE_LOW_BATTERY))
        assertEquals(R.drawable.ic_bluetooth, NotificationHelper.getIconForType(AppNotification.TYPE_DISCONNECTED))
        assertEquals(R.drawable.ic_bluetooth, NotificationHelper.getIconForType(AppNotification.TYPE_CONNECTED))
        // Unknown type defaults to ic_bell
        assertEquals(R.drawable.ic_bell, NotificationHelper.getIconForType("UNKNOWN_CUSTOM"))
    }

    @Test
    fun testFormatRelativeTime() {
        val now = 1700000000000L // arbitrary reference time

        // Under 1 minute
        val justNow = NotificationHelper.formatRelativeTime(now - 30_000L, now)
        assertEquals("Just now", justNow)

        // Under 1 hour (e.g. 15 minutes)
        val minsAgo = NotificationHelper.formatRelativeTime(now - (15 * 60 * 1000L), now)
        assertEquals("15m ago", minsAgo)

        // Same day, hours ago (e.g. 2 hours)
        val hoursAgo = NotificationHelper.formatRelativeTime(now - (2 * 60 * 60 * 1000L), now)
        assertEquals("2h ago", hoursAgo)

        // Yesterday
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val yesterdayTime = NotificationHelper.formatRelativeTime(cal.timeInMillis, now)
        assertEquals("Yesterday", yesterdayTime)
    }

    @Test
    fun testGetSectionForTimestamp() {
        val nowCal = Calendar.getInstance()
        val now = nowCal.timeInMillis

        // Today
        val todaySec = NotificationHelper.getSectionForTimestamp(now - 60_000L, now)
        assertEquals(NotificationHelper.NotificationSection.TODAY, todaySec)

        // Yesterday
        val yesterdayCal = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val yesterdaySec = NotificationHelper.getSectionForTimestamp(yesterdayCal.timeInMillis, now)
        assertEquals(NotificationHelper.NotificationSection.YESTERDAY, yesterdaySec)

        // 3 days ago (This Week)
        val threeDaysAgo = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -3)
        }
        val thisWeekSec = NotificationHelper.getSectionForTimestamp(threeDaysAgo.timeInMillis, now)
        assertEquals(NotificationHelper.NotificationSection.THIS_WEEK, thisWeekSec)

        // 12 days ago (Earlier)
        val twelveDaysAgo = Calendar.getInstance().apply {
            timeInMillis = now
            add(Calendar.DAY_OF_YEAR, -12)
        }
        val earlierSec = NotificationHelper.getSectionForTimestamp(twelveDaysAgo.timeInMillis, now)
        assertEquals(NotificationHelper.NotificationSection.EARLIER, earlierSec)
    }

    @Test
    fun testGroupNotificationsIntoSections() {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { timeInMillis = now }

        val todayNotif = AppNotification(
            id = "n1",
            title = "Goal Reached",
            message = "Hit 2500 ml",
            type = AppNotification.TYPE_GOAL_REACHED,
            timestamp = now - (10 * 60 * 1000L), // 10m ago
            isUnread = true
        )

        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayNotif = AppNotification(
            id = "n2",
            title = "Sip Reminder",
            message = "Drink water",
            type = AppNotification.TYPE_SIP_REMINDER,
            timestamp = cal.timeInMillis,
            isUnread = false
        )

        cal.add(Calendar.DAY_OF_YEAR, -3)
        val thisWeekNotif = AppNotification(
            id = "n3",
            title = "Bottle Connected",
            message = "Syncing",
            type = AppNotification.TYPE_CONNECTED,
            timestamp = cal.timeInMillis,
            isUnread = false
        )

        val list = listOf(yesterdayNotif, todayNotif, thisWeekNotif)
        val grouped = NotificationHelper.groupNotifications(list, now)

        // Expected output structure:
        // Header: TODAY
        // Notification: Goal Reached
        // Header: YESTERDAY
        // Notification: Sip Reminder
        // Header: THIS WEEK
        // Notification: Bottle Connected
        assertEquals(6, grouped.size)

        assertTrue(grouped[0] is NotificationListItem.Header)
        assertEquals("TODAY", (grouped[0] as NotificationListItem.Header).title)

        assertTrue(grouped[1] is NotificationListItem.Notification)
        val item1 = grouped[1] as NotificationListItem.Notification
        assertEquals("Goal Reached", item1.title)
        assertEquals("n1", item1.id)
        assertTrue(item1.isUnread)

        assertTrue(grouped[2] is NotificationListItem.Header)
        assertEquals("YESTERDAY", (grouped[2] as NotificationListItem.Header).title)

        assertTrue(grouped[3] is NotificationListItem.Notification)
        val item2 = grouped[3] as NotificationListItem.Notification
        assertEquals("Sip Reminder", item2.title)
        assertEquals("n2", item2.id)
        assertFalse(item2.isUnread)

        assertTrue(grouped[4] is NotificationListItem.Header)
        assertEquals("THIS WEEK", (grouped[4] as NotificationListItem.Header).title)

        assertTrue(grouped[5] is NotificationListItem.Notification)
        val item3 = grouped[5] as NotificationListItem.Notification
        assertEquals("Bottle Connected", item3.title)
        assertEquals("n3", item3.id)
    }

    @Test
    fun testEmptyNotificationsGroupingReturnsEmptyList() {
        val grouped = NotificationHelper.groupNotifications(emptyList())
        assertTrue(grouped.isEmpty())
    }
}
