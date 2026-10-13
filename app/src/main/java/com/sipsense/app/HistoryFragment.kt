package com.sipsense.app

/**
 * HistoryFragment.kt
 *
 * Displays the user's hydration history as a list of day cards.
 * Each day card shows the total consumed vs. target and can be
 * tapped to expand and reveal the individual sips logged that day.
 *
 * Data source:
 *  /users/{uid}/daily_totals/{yyyy-MM-dd} = Int (total ml for the day)
 *  /users/{uid}/sip_records/{pushId}      = { amount: Int, timestamp: Long }
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 2.0
 */

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.sipsense.app.adapter.HistoryAdapter
import com.sipsense.app.data.FirebaseDatabaseProvider
import com.sipsense.app.model.HydrationDay
import com.sipsense.app.model.HydrationSip
import com.sipsense.app.model.SipRecord
import com.sipsense.app.model.UserProfile
import java.text.SimpleDateFormat
import java.util.Locale

class HistoryFragment : Fragment() {

    // ── View references ──────────────────────────────────────────────
    private lateinit var rvHistory: RecyclerView
    private lateinit var layoutEmptyState: LinearLayout
    private lateinit var tvSummaryDays: TextView
    private lateinit var tvSummaryTotal: TextView

    // ── Firebase ─────────────────────────────────────────────────────
    private val firebaseAuth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabaseProvider.reference()

    // ── State ────────────────────────────────────────────────────────
    private var targetMl: Int = 2500
    private val dailyTotals = mutableMapOf<String, Int>()
    private val sipsByDay = mutableMapOf<String, MutableList<HydrationSip>>()
    private val daysList = mutableListOf<HydrationDay>()
    private lateinit var adapter: HistoryAdapter

    private var dailyTotalsListener: ValueEventListener? = null
    private var sipRecordsListener: ValueEventListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_history, container, false)

        // ── Bind views ──
        rvHistory = view.findViewById(R.id.rv_history)
        layoutEmptyState = view.findViewById(R.id.layout_empty_state)
        tvSummaryDays = view.findViewById(R.id.tv_summary_days)
        tvSummaryTotal = view.findViewById(R.id.tv_summary_total)

        // ── Set up RecyclerView ──
        adapter = HistoryAdapter(daysList)
        rvHistory.layoutManager = LinearLayoutManager(requireContext())
        rvHistory.adapter = adapter

        // ── Load data from Firebase ──
        loadUserTarget()
        loadDailyTotals()
        loadSipRecords()

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val uid = firebaseAuth.currentUser?.uid
        if (uid != null) {
            dailyTotalsListener?.let { database?.child("users")?.child(uid)?.child("daily_totals")?.removeEventListener(it) }
            sipRecordsListener?.let { database?.child("users")?.child(uid)?.child("sip_records")?.removeEventListener(it) }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // FIREBASE LOADERS
    // ═══════════════════════════════════════════════════════════════════

    /** Loads the user's hydration target from /users/{uid}/hydrationTarget. */
    private fun loadUserTarget() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        database?.child("users")?.child(uid)
            ?.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val profile = snapshot.getValue(UserProfile::class.java)
                    if (profile != null && profile.hydrationTarget > 0) {
                        targetMl = profile.hydrationTarget
                        activity?.runOnUiThread {
                            if (isAdded) {
                                rebuildDayList()
                            }
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    /** Loads /users/{uid}/daily_totals — one entry per date key. */
    private fun loadDailyTotals() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        dailyTotalsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                dailyTotals.clear()
                for (child in snapshot.children) {
                    val key = child.key ?: continue
                    val value = child.getValue(Int::class.java) ?: 0
                    dailyTotals[key] = value
                }
                activity?.runOnUiThread {
                    if (isAdded) {
                        rebuildDayList()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        database?.child("users")?.child(uid)?.child("daily_totals")
            ?.addValueEventListener(dailyTotalsListener!!)
    }

    /** Loads /users/{uid}/sip_records — every individual sip ever logged. */
    private fun loadSipRecords() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        sipRecordsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                sipsByDay.clear()
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                for (child in snapshot.children) {
                    val record = child.getValue(SipRecord::class.java) ?: continue
                    val dateKey = dateFormat.format(java.util.Date(record.timestamp))
                    sipsByDay.getOrPut(dateKey) { mutableListOf() }
                        .add(HydrationSip(record.amount, record.timestamp))
                }
                // Sort each day's sips newest first
                sipsByDay.values.forEach { list ->
                    list.sortByDescending { it.timestampMs }
                }
                activity?.runOnUiThread {
                    if (isAdded) {
                        rebuildDayList()
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }
        database?.child("users")?.child(uid)?.child("sip_records")
            ?.addValueEventListener(sipRecordsListener!!)
    }

    // ═══════════════════════════════════════════════════════════════════
    // DATA ASSEMBLY
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Combines dailyTotals + sipsByDay into a sorted list of HydrationDay
     * objects and pushes it to the adapter.
     */
    private fun rebuildDayList() {
        val allKeys = (dailyTotals.keys + sipsByDay.keys).toSortedSet(reverseOrder())

        val newList = allKeys.map { key ->
            val total = dailyTotals[key] ?: sipsByDay[key]?.sumOf { it.amountMl } ?: 0
            val sips = sipsByDay[key] ?: emptyList()
            HydrationDay(
                dateKey = key,
                totalMl = total,
                targetMl = targetMl,
                sips = sips
            )
        }

        // Preserve expanded state across reloads
        val expandedKeys = daysList.filter { it.isExpanded }.map { it.dateKey }.toSet()
        newList.forEach { it.isExpanded = it.dateKey in expandedKeys }

        daysList.clear()
        daysList.addAll(newList)
        adapter.notifyDataSetChanged()

        // Toggle empty state vs list
        if (daysList.isEmpty()) {
            layoutEmptyState.visibility = View.VISIBLE
            rvHistory.visibility = View.GONE
        } else {
            layoutEmptyState.visibility = View.GONE
            rvHistory.visibility = View.VISIBLE
        }

        // Update summary row
        val totalConsumed = dailyTotals.values.sum()
        tvSummaryDays.text = daysList.size.toString()
        tvSummaryTotal.text = "%,d ml".format(totalConsumed)
    }

    companion object {
        /** Comparator that sorts date strings ("yyyy-MM-dd") in reverse. */
        private fun <T> Iterable<T>.toSortedSet(reverse: Boolean): Set<T> {
            val sorted = toMutableList()
            @Suppress("UNCHECKED_CAST")
            (sorted as MutableList<Comparable<Any>>).sort()
            if (reverse) sorted.reverse()
            return sorted.toSet()
        }
    }
}