package com.sipsense.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.ValueEventListener
import com.sipsense.app.data.NotificationHelper
import com.sipsense.app.data.NotificationRepository

/**
 * NotificationsFragment.kt
 *
 * Displays hydration reminders, IoT smart bottle sensor alerts,
 * and connectivity notifications stored in Firebase Realtime Database.
 *
 * Features:
 * - Dynamic retrieval and real-time syncing from Firebase (/users/{uid}/notifications)
 * - Automatic seeding if notifications node is currently empty
 * - Chronological grouping (TODAY, YESTERDAY, THIS WEEK, EARLIER)
 * - Unread count badge and "Mark all read" quick action
 * - Tapping an individual notification marks it as read in Firebase
 * - Loading indicator and friendly empty state view
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 1.1
 */
class NotificationsFragment : Fragment() {

    private lateinit var rvNotifications: RecyclerView
    private lateinit var pbNotificationsLoading: ProgressBar
    private lateinit var layoutEmptyNotifications: View
    private lateinit var tvUnreadBadge: TextView
    private lateinit var tvMarkAllRead: TextView

    private lateinit var adapter: NotificationsAdapter
    private var notificationListener: ValueEventListener? = null
    private val firebaseAuth = FirebaseAuth.getInstance()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_notifications, container, false)

        rvNotifications = view.findViewById(R.id.rv_notifications)
        pbNotificationsLoading = view.findViewById(R.id.pb_notifications_loading)
        layoutEmptyNotifications = view.findViewById(R.id.layout_empty_notifications)
        tvUnreadBadge = view.findViewById(R.id.tv_unread_badge)
        tvMarkAllRead = view.findViewById(R.id.tv_mark_all_read)

        rvNotifications.layoutManager = LinearLayoutManager(context)

        // Set up adapter with tap-to-mark-read interaction
        adapter = NotificationsAdapter(emptyList()) { clickedNotification ->
            val uid = firebaseAuth.currentUser?.uid
            if (uid != null && clickedNotification.id.isNotBlank()) {
                NotificationRepository.markAsRead(uid, clickedNotification.id)
            }
        }
        rvNotifications.adapter = adapter

        // Set up "Mark all read" button click
        tvMarkAllRead.setOnClickListener {
            val uid = firebaseAuth.currentUser?.uid
            if (uid != null) {
                NotificationRepository.markAllAsRead(uid)
                context?.let { ctx ->
                    Toast.makeText(ctx, R.string.notifications_marked_all_read_toast, Toast.LENGTH_SHORT).show()
                }
            }
        }

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadNotifications()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val uid = firebaseAuth.currentUser?.uid
        val listener = notificationListener
        if (uid != null && listener != null) {
            NotificationRepository.removeListener(uid, listener)
            notificationListener = null
        }
    }

    private fun loadNotifications() {
        val uid = firebaseAuth.currentUser?.uid ?: run {
            layoutEmptyNotifications.visibility = View.VISIBLE
            rvNotifications.visibility = View.GONE
            pbNotificationsLoading.visibility = View.GONE
            return
        }

        pbNotificationsLoading.visibility = View.VISIBLE
        layoutEmptyNotifications.visibility = View.GONE
        rvNotifications.visibility = View.GONE

        notificationListener = NotificationRepository.listenToNotifications(
            userId = uid,
            onUpdate = { notifications ->
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    pbNotificationsLoading.visibility = View.GONE

                    val unreadCount = notifications.count { it.checkIsUnread() }
                    if (unreadCount > 0) {
                        tvUnreadBadge.visibility = View.VISIBLE
                        tvUnreadBadge.text = getString(R.string.notifications_unread_badge, unreadCount)
                        tvMarkAllRead.visibility = View.VISIBLE
                    } else {
                        tvUnreadBadge.visibility = View.GONE
                        tvMarkAllRead.visibility = View.GONE
                    }

                    if (notifications.isEmpty()) {
                        layoutEmptyNotifications.visibility = View.VISIBLE
                        rvNotifications.visibility = View.GONE
                    } else {
                        layoutEmptyNotifications.visibility = View.GONE
                        rvNotifications.visibility = View.VISIBLE
                        val groupedItems = NotificationHelper.groupNotifications(notifications)
                        adapter.updateItems(groupedItems)
                    }
                }
            },
            onError = { _ ->
                activity?.runOnUiThread {
                    if (!isAdded) return@runOnUiThread
                    pbNotificationsLoading.visibility = View.GONE
                }
            }
        )
    }
}
