package com.sipsense.app.model

sealed class NotificationListItem {
    data class Header(val title: String) : NotificationListItem()
    data class Notification(
        val title: String,
        val message: String,
        val timeAgo: String,
        val iconResId: Int? = null,
        var isUnread: Boolean = true,
        val id: String = "",
        val timestamp: Long = 0L,
        val type: String = ""
    ) : NotificationListItem()
}
