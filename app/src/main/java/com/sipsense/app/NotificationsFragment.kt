package com.sipsense.app

/**
 * NotificationsFragment.kt
 *
 * Placeholder fragment for the Notifications tab in the bottom navigation.
 * Displays hydration reminders and smart bottle alerts.
 *
 * This fragment will be expanded later with:
 * - Hydration reminder history
 * - Device alerts (low battery, disconnected, refill needed)
 * - Goal achievement notifications
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 1.0
 */

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sipsense.app.model.NotificationListItem

class NotificationsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_notifications, container, false)
        
        val rvNotifications = view.findViewById<RecyclerView>(R.id.rv_notifications)
        rvNotifications.layoutManager = LinearLayoutManager(context)
        
        val dummyData = listOf(
            NotificationListItem.Header("TODAY"),
            NotificationListItem.Notification(
                "Daily Hydration Goal Reached!",
                "Congratulations! You have reached your daily hydration goal. Keep it up!",
                "2m ago",
                R.drawable.ic_bell,
                true
            ),
            NotificationListItem.Notification(
                "Time for a Sip!",
                "It's been 2 hours since your last drink. Stay hydrated and take a sip now.",
                "1h ago",
                R.drawable.ic_bell,
                true
            ),
            NotificationListItem.Notification(
                "Streak Maintained",
                "Awesome! You've hit your hydration goal 3 days in a row.",
                "4h ago",
                R.drawable.ic_bell,
                false
            ),
            
            NotificationListItem.Header("YESTERDAY"),
            NotificationListItem.Notification(
                "Daily Hydration Goal Reached",
                "You reached yesterday's hydration goal successfully.",
                "Yesterday",
                R.drawable.ic_bell,
                false
            ),
            NotificationListItem.Notification(
                "Sip Reminder",
                "Don't forget to drink water this afternoon to keep your energy up.",
                "Yesterday",
                R.drawable.ic_bell,
                false
            ),
            NotificationListItem.Notification(
                "7-Day Streak Started!",
                "You just started a new streak. Keep hitting those daily goals.",
                "Yesterday",
                R.drawable.ic_bell,
                false
            ),
            
            NotificationListItem.Header("THIS WEEK"),
            NotificationListItem.Notification(
                "Weekly Streak: 5 Days!",
                "You are on a roll! 5 days of hitting your hydration target.",
                "Mon",
                R.drawable.ic_bell,
                false
            ),
            NotificationListItem.Notification(
                "Sip Reminder",
                "Remember to carry your SipSense bottle when you head out.",
                "Sun",
                R.drawable.ic_bell,
                false
            ),
            NotificationListItem.Notification(
                "Daily Hydration Goal Reached",
                "You completed your hydration goal for Sunday.",
                "Sun",
                R.drawable.ic_bell,
                false
            )
        )
        
        val adapter = NotificationsAdapter(dummyData)
        rvNotifications.adapter = adapter
        
        return view
    }
}
