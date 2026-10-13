package com.sipsense.app.model

/**
 * HydrationDay.kt
 *
 * Represents one calendar day of hydration activity, used by the
 * History screen to display a grouped, expandable day card.
 *
 * @project SipSense - Smart Bottle Ecosystem
 */
data class HydrationDay(
    /** Date key in "yyyy-MM-dd" format (matches the Firebase daily_totals keys). */
    val dateKey: String,

    /** Total millilitres consumed on this day. */
    val totalMl: Int,

    /** The user's hydration target on this day (typically from their profile). */
    val targetMl: Int,

    /** All individual sip records logged on this day, sorted newest first. */
    val sips: List<HydrationSip>
) {
    /** True if the user met or exceeded their daily target. */
    val goalMet: Boolean get() = totalMl >= targetMl

    /** Progress as a fraction between 0f and 1f (clamped). */
    val progressFraction: Float
        get() = if (targetMl <= 0) 0f else (totalMl.toFloat() / targetMl).coerceIn(0f, 1f)

    /** Tracks whether this day is currently expanded in the RecyclerView. */
    var isExpanded: Boolean = false
}

/**
 * A single sip entry within a day.
 *
 * @project SipSense - Smart Bottle Ecosystem
 */
data class HydrationSip(
    /** Volume of this sip in millilitres. */
    val amountMl: Int,

    /** Timestamp of the sip (milliseconds since epoch). */
    val timestampMs: Long,

    /** Ambient temperature in °C recorded at the time of the sip. */
    val ambientTemperature: Int = 0
)