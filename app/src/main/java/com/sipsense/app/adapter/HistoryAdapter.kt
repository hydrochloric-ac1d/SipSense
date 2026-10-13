package com.sipsense.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.sipsense.app.R
import com.sipsense.app.model.HydrationDay
import com.sipsense.app.model.HydrationSip
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * HistoryAdapter.kt
 *
 * RecyclerView adapter that renders one card per day of hydration history.
 * Each card can be tapped to expand and reveal the individual sips logged
 * on that day (rendered by [SipsAdapter]).
 *
 * @project SipSense - Smart Bottle Ecosystem
 */
class HistoryAdapter(
    private val days: MutableList<HydrationDay>
) : RecyclerView.Adapter<HistoryAdapter.DayViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DayViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_day, parent, false)
        return DayViewHolder(view)
    }

    override fun onBindViewHolder(holder: DayViewHolder, position: Int) {
        holder.bind(days[position])
    }

    override fun getItemCount(): Int = days.size

    inner class DayViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvDate: TextView = view.findViewById(R.id.tv_day_date)
        private val tvTotal: TextView = view.findViewById(R.id.tv_day_total)
        private val tvTarget: TextView = view.findViewById(R.id.tv_day_target)
        private val tvBadge: TextView = view.findViewById(R.id.tv_day_badge)
        private val ivChevron: ImageView = view.findViewById(R.id.iv_day_chevron)
        private val progressBar: ProgressBar = view.findViewById(R.id.pb_day_progress)
        private val rvSips: RecyclerView = view.findViewById(R.id.rv_day_sips)
        private val divider: View = view.findViewById(R.id.v_day_divider)

        fun bind(day: HydrationDay) {
            // ── Date header (e.g., "Today", "Yesterday", "Mon, Oct 12") ──
            tvDate.text = formatDateLabel(day.dateKey)

            // ── Totals ──
            tvTotal.text = "${day.totalMl} ml"
            tvTarget.text = "/ ${day.targetMl} ml"

            // ── Progress bar (0..100) ──
            progressBar.progress = (day.progressFraction * 100).toInt()

            // ── Goal badge ──
            val ctx = itemView.context
            if (day.goalMet) {
                tvBadge.visibility = View.VISIBLE
                tvBadge.text = "GOAL MET"
                tvBadge.setTextColor(ContextCompat.getColor(ctx, R.color.strength_strong))
                progressBar.progressTintList =
                    ContextCompat.getColorStateList(ctx, R.color.strength_strong)
            } else {
                tvBadge.visibility = View.GONE
                progressBar.progressTintList =
                    ContextCompat.getColorStateList(ctx, R.color.teal_primary)
            }

            // ── Expanded vs collapsed state ──
            if (day.isExpanded) {
                ivChevron.rotation = 180f
                rvSips.visibility = View.VISIBLE
                divider.visibility = View.VISIBLE
            } else {
                ivChevron.rotation = 0f
                rvSips.visibility = View.GONE
                divider.visibility = View.GONE
            }

            // ── Sips list inside the expanded day ──
            val sipsAdapter = SipsAdapter(day.sips)
            rvSips.layoutManager = LinearLayoutManager(itemView.context)
            rvSips.adapter = sipsAdapter
            rvSips.isNestedScrollingEnabled = false

            // ── Tap the card to expand/collapse ──
            itemView.setOnClickListener {
                val pos = adapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
                day.isExpanded = !day.isExpanded
                notifyItemChanged(pos)
            }
        }

        private fun formatDateLabel(dateKey: String): String {
            return try {
                val parser = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val parsed = parser.parse(dateKey) ?: return dateKey

                val today = Calendar.getInstance()
                val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
                val target = Calendar.getInstance().apply { time = parsed }

                val sameDay = { a: Calendar, b: Calendar ->
                    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
                            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
                }

                when {
                    sameDay(today, target) -> "Today"
                    sameDay(yesterday, target) -> "Yesterday"
                    else -> SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault())
                        .format(parsed)
                }
            } catch (e: Exception) {
                dateKey
            }
        }
    }
}

/**
 * SipsAdapter.kt (nested helper)
 *
 * Renders the individual sip rows inside an expanded day card.
 */
class SipsAdapter(
    private val sips: List<HydrationSip>
) : RecyclerView.Adapter<SipsAdapter.SipViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SipViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history_sip, parent, false)
        return SipViewHolder(view)
    }

    override fun onBindViewHolder(holder: SipViewHolder, position: Int) {
        holder.bind(sips[position])
    }

    override fun getItemCount(): Int = sips.size

    class SipViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTime: TextView = view.findViewById(R.id.tv_sip_time)
        private val tvAmount: TextView = view.findViewById(R.id.tv_sip_amount)

        fun bind(sip: HydrationSip) {
            tvTime.text = SimpleDateFormat("h:mm a", Locale.getDefault())
                .format(Date(sip.timestampMs))
            tvAmount.text = "${sip.amountMl} ml"
        }
    }
}