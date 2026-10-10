package com.sipsense.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.sipsense.app.model.NotificationListItem

class NotificationsAdapter(
    private val items: List<NotificationListItem>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_NOTIFICATION = 1
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is NotificationListItem.Header -> TYPE_HEADER
            is NotificationListItem.Notification -> TYPE_NOTIFICATION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_notification_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_notification, parent, false)
            NotificationViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is HeaderViewHolder && item is NotificationListItem.Header) {
            holder.bind(item)
        } else if (holder is NotificationViewHolder && item is NotificationListItem.Notification) {
            holder.bind(item)
            holder.itemView.setOnClickListener {
                if (item.isUnread) {
                    item.isUnread = false
                    notifyItemChanged(holder.adapterPosition)
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvHeader: TextView = view.findViewById(R.id.tv_header)

        fun bind(item: NotificationListItem.Header) {
            tvHeader.text = item.title
        }
    }

    class NotificationViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tv_title)
        private val tvMessage: TextView = view.findViewById(R.id.tv_message)
        private val tvTime: TextView = view.findViewById(R.id.tv_time)
        private val ivIcon: ImageView = view.findViewById(R.id.iv_icon)
        private val vAccent: View = view.findViewById(R.id.v_accent)
        private val flIcon: View = view.findViewById(R.id.fl_icon)

        fun bind(item: NotificationListItem.Notification) {
            tvTitle.text = item.title
            tvMessage.text = item.message
            tvTime.text = item.timeAgo
            
            if (item.iconResId != null) {
                ivIcon.setImageResource(item.iconResId)
            } else {
                ivIcon.setImageResource(R.drawable.ic_bell)
            }
            
            val context = itemView.context
            if (item.isUnread) {
                vAccent.visibility = View.VISIBLE
                tvTime.setTypeface(null, android.graphics.Typeface.BOLD)
                flIcon.setBackgroundResource(R.drawable.bg_circle_teal_primary)
                ivIcon.setColorFilter(android.graphics.Color.parseColor("#E1F4F3"))
            } else {
                vAccent.visibility = View.INVISIBLE
                tvTime.setTypeface(null, android.graphics.Typeface.NORMAL)
                flIcon.setBackgroundResource(R.drawable.bg_circle_light_teal)
                ivIcon.setColorFilter(androidx.core.content.ContextCompat.getColor(context, R.color.teal_primary))
            }
        }
    }
}
