package com.sipsense.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.sipsense.app.data.FirebaseDatabaseProvider
import com.sipsense.app.model.UserProfile
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DashboardFragment : Fragment() {

    // ── Hydration & Streak Views ──
    private lateinit var tvGreeting: TextView
    private lateinit var progressHydration: CircularProgressIndicator
    private lateinit var tvProgressCurrent: TextView
    private lateinit var tvProgressMax: TextView
    private lateinit var tvRecentSipTime: TextView
    private lateinit var tvRecentSipAmount: TextView

    // ── Device Telemetry Views (Battery, Humidity, Water In Liters) ──
    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceConnectionStatus: TextView
    private lateinit var vStatusDot: View
    private lateinit var tvDeviceWaterLiters: TextView
    private lateinit var tvDeviceWaterMl: TextView
    private lateinit var pbBottleWaterLevel: ProgressBar
    private lateinit var tvDeviceBattery: TextView
    private lateinit var tvDeviceBatteryStatus: TextView
    private lateinit var pbBottleBattery: ProgressBar
    private lateinit var ivBatteryIcon: ImageView
    private lateinit var tvDeviceHumidity: TextView
    private lateinit var tvDeviceHumidityStatus: TextView
    private lateinit var pbBottleHumidity: ProgressBar

    private val firebaseAuth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabaseProvider.reference()
    
    private var profileListener: ValueEventListener? = null
    private var telemetryListener: ValueEventListener? = null

    // ── Device & Hydration State ──
    private var isPolling = false
    private var lastSavedSipTimestampMs: Long = 0L
    private var currentHydrationTarget: Int = 2500
    private var currentDailyTotal: Int = 0
    private var isFirebaseRecentSipLoaded = false
    private var varFirebaseDailyTotalLoaded = false

    private var currentBottleWaterLevel: Int = 650
    private var currentBottleCapacity: Int = 1000
    private var currentBatteryLevel: Int = 88
    private var currentHumidity: Int = 55

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_dashboard, container, false)
        
        // Hydration views
        tvGreeting = view.findViewById(R.id.tv_greeting)
        progressHydration = view.findViewById(R.id.progress_hydration)
        tvProgressCurrent = view.findViewById(R.id.tv_progress_current)
        tvProgressMax = view.findViewById(R.id.tv_progress_max)
        tvRecentSipTime = view.findViewById(R.id.tv_recent_sip_time)
        tvRecentSipAmount = view.findViewById(R.id.tv_recent_sip_amount)

        // Device telemetry views
        tvDeviceName = view.findViewById(R.id.tv_device_name)
        tvDeviceConnectionStatus = view.findViewById(R.id.tv_device_connection_status)
        vStatusDot = view.findViewById(R.id.v_status_dot)
        tvDeviceWaterLiters = view.findViewById(R.id.tv_device_water_liters)
        tvDeviceWaterMl = view.findViewById(R.id.tv_device_water_ml)
        pbBottleWaterLevel = view.findViewById(R.id.pb_bottle_water_level)
        tvDeviceBattery = view.findViewById(R.id.tv_device_battery)
        tvDeviceBatteryStatus = view.findViewById(R.id.tv_device_battery_status)
        pbBottleBattery = view.findViewById(R.id.pb_bottle_battery)
        ivBatteryIcon = view.findViewById(R.id.iv_battery_icon)
        tvDeviceHumidity = view.findViewById(R.id.tv_device_humidity)
        tvDeviceHumidityStatus = view.findViewById(R.id.tv_device_humidity_status)
        pbBottleHumidity = view.findViewById(R.id.pb_bottle_humidity)

        setupGreeting()
        setupRecentSipTime()
        setupInitialDeviceStatus()
        loadUserProfile()
        loadRecentSipFromFirebase()
        loadDeviceTelemetryFromFirebase()

        return view
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        val uid = firebaseAuth.currentUser?.uid
        if (uid != null) {
            profileListener?.let { database?.child("users")?.child(uid)?.removeEventListener(it) }
            telemetryListener?.let { database?.child("users")?.child(uid)?.child("device_telemetry")?.removeEventListener(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        isPolling = true
        startPollingBackend()
    }

    override fun onPause() {
        super.onPause()
        isPolling = false
    }

    private fun setupInitialDeviceStatus() {
        updateDeviceStatusUI(
            waterLevel = currentBottleWaterLevel,
            bottleCapacity = currentBottleCapacity,
            batteryLevel = currentBatteryLevel,
            humidity = currentHumidity,
            isConnected = true
        )
    }

    private fun startPollingBackend() {
        Thread {
            while (isPolling) {
                try {
                    val url = java.net.URL("http://10.0.2.2:3000/api/hydration")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 2000
                    connection.readTimeout = 2000

                    if (connection.responseCode == 200) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonObject = JSONObject(response)
                        val dataObject = jsonObject.optJSONObject("data")
                        
                        if (dataObject != null) {
                            val waterLevel = dataObject.optInt("waterLevel", currentBottleWaterLevel)
                            val bottleCapacity = dataObject.optInt("bottleCapacity", currentBottleCapacity)
                            val battery = dataObject.optInt("batteryLevel", currentBatteryLevel)
                            val humidity = dataObject.optInt("humidity", currentHumidity)

                            currentBottleWaterLevel = waterLevel
                            currentBottleCapacity = bottleCapacity
                            currentBatteryLevel = battery
                            currentHumidity = humidity

                            if (!isFirebaseRecentSipLoaded || !varFirebaseDailyTotalLoaded) {
                                connection.disconnect()
                                Thread.sleep(500)
                                continue
                            }
                            
                            val totalConsumed = dataObject.optInt("totalConsumedToday", 0)
                            val sipAmount = dataObject.optInt("sipAmount", 0)
                            val lastSipTimestampStr = dataObject.optString("lastSipTimestamp", "")
                            
                            var currentSipTimestampMs = 0L
                            if (lastSipTimestampStr.isNotEmpty()) {
                                try {
                                    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                                    parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                    val date = parser.parse(lastSipTimestampStr)
                                    if (date != null) {
                                        currentSipTimestampMs = date.time
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }

                            // Detect a genuinely new sip and save to Firebase
                            val uid = firebaseAuth.currentUser?.uid
                            if (currentSipTimestampMs > lastSavedSipTimestampMs && sipAmount > 0) {
                                lastSavedSipTimestampMs = currentSipTimestampMs
                                currentDailyTotal += sipAmount
                                
                                if (uid != null && database != null) {
                                    // 1. Save the individual sip record
                                    val recordRef = database.child("users").child(uid).child("sip_records").push()
                                    val newRecord = com.sipsense.app.model.SipRecord(sipAmount, currentSipTimestampMs)
                                    recordRef.setValue(newRecord)
                                    
                                    // 2. Save the total daily sip record
                                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                    val dateKey = dateFormat.format(java.util.Date(currentSipTimestampMs))
                                    database.child("users").child(uid).child("daily_totals").child(dateKey).setValue(currentDailyTotal)
                                }
                            }

                            // Save device telemetry to Firebase for persistence
                            if (uid != null && database != null) {
                                val telemetryMap = mapOf(
                                    "waterLevel" to waterLevel,
                                    "bottleCapacity" to bottleCapacity,
                                    "batteryLevel" to battery,
                                    "humidity" to humidity,
                                    "lastUpdated" to System.currentTimeMillis()
                                )
                                database.child("users").child(uid).child("device_telemetry").setValue(telemetryMap)
                            }
                            
                            activity?.runOnUiThread {
                                if (isAdded) {
                                    updateProgressUI(currentDailyTotal)
                                    
                                    if (currentSipTimestampMs > 0) {
                                        updateRecentSipUI(sipAmount, currentSipTimestampMs)
                                    }

                                    updateDeviceStatusUI(
                                        waterLevel = waterLevel,
                                        bottleCapacity = bottleCapacity,
                                        batteryLevel = battery,
                                        humidity = humidity,
                                        isConnected = true
                                    )
                                }
                            }
                        }
                    } else {
                        activity?.runOnUiThread {
                            if (isAdded) {
                                updateDeviceConnectionBadge(isConnected = false)
                            }
                        }
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    activity?.runOnUiThread {
                        if (isAdded) {
                            updateDeviceConnectionBadge(isConnected = false)
                        }
                    }
                }
                Thread.sleep(3000)
            }
        }.start()
    }

    private fun setupGreeting(firstName: String = "User") {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        val greetingRes = when (hour) {
            in 0..11 -> R.string.greeting_morning
            in 12..16 -> R.string.greeting_afternoon
            else -> R.string.greeting_evening
        }

        tvGreeting.text = getString(greetingRes, firstName)
    }

    private fun setupRecentSipTime() {
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        val currentTime = sdf.format(Calendar.getInstance().time)
        tvRecentSipTime.text = currentTime
    }

    private fun loadUserProfile() {
        val user = firebaseAuth.currentUser ?: return
        val uid = user.uid

        // Set default name first
        val authName = user.displayName?.takeIf { it.isNotBlank() }
            ?: user.email?.substringBefore('@').orEmpty().ifBlank { "User" }
        val firstName = authName.substringBefore(" ").replaceFirstChar { it.uppercase() }
        setupGreeting(firstName)

        profileListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return

                val profile = snapshot.getValue(UserProfile::class.java) ?: return
                
                val name = profile.fullName.takeIf { it.isNotBlank() } ?: firstName
                val shortName = name.substringBefore(" ").replaceFirstChar { it.uppercase() }
                setupGreeting(shortName)

                val target = profile.hydrationTarget
                currentHydrationTarget = target
                progressHydration.max = target
                tvProgressMax.text = getString(R.string.progress_max_format_ml, target)
            }

            override fun onCancelled(error: DatabaseError) {
                if (!isAdded) return
                Toast.makeText(context, "Failed to load profile", Toast.LENGTH_SHORT).show()
            }
        }
        
        database?.child("users")?.child(uid)?.addValueEventListener(profileListener!!)
    }

    private fun loadRecentSipFromFirebase() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        
        // 1. Load Recent Sip
        database?.child("users")?.child(uid)?.child("sip_records")
            ?.orderByChild("timestamp")
            ?.limitToLast(1)
            ?.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded) return
                    for (child in snapshot.children) {
                        val record = child.getValue(com.sipsense.app.model.SipRecord::class.java)
                        if (record != null) {
                            lastSavedSipTimestampMs = record.timestamp
                            updateRecentSipUI(record.amount, record.timestamp)
                        }
                    }
                    isFirebaseRecentSipLoaded = true
                }
                override fun onCancelled(error: DatabaseError) {
                    isFirebaseRecentSipLoaded = true
                }
            })
            
        // 2. Load Daily Totals for Streak and Today's Progress
        database?.child("users")?.child(uid)?.child("daily_totals")
            ?.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded) return
                    val dailyTotals = mutableMapOf<String, Int>()
                    for (child in snapshot.children) {
                        val dateKey = child.key
                        val total = child.getValue(Int::class.java)
                        if (dateKey != null && total != null) {
                            dailyTotals[dateKey] = total
                        }
                    }
                    
                    val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val todayKey = dateFormat.format(java.util.Date())
                    
                    currentDailyTotal = dailyTotals[todayKey] ?: 0
                    activity?.runOnUiThread {
                        updateProgressUI(currentDailyTotal)
                        calculateAndDisplayStreak(dailyTotals)
                    }
                    varFirebaseDailyTotalLoaded = true
                }
                override fun onCancelled(error: DatabaseError) {
                    varFirebaseDailyTotalLoaded = true
                }
            })
    }

    private fun loadDeviceTelemetryFromFirebase() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        telemetryListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                val waterLevel = snapshot.child("waterLevel").getValue(Int::class.java) ?: currentBottleWaterLevel
                val bottleCapacity = snapshot.child("bottleCapacity").getValue(Int::class.java) ?: currentBottleCapacity
                val battery = snapshot.child("batteryLevel").getValue(Int::class.java) ?: currentBatteryLevel
                val humidity = snapshot.child("humidity").getValue(Int::class.java) ?: currentHumidity

                currentBottleWaterLevel = waterLevel
                currentBottleCapacity = bottleCapacity
                currentBatteryLevel = battery
                currentHumidity = humidity

                activity?.runOnUiThread {
                    if (isAdded) {
                        updateDeviceStatusUI(
                            waterLevel = waterLevel,
                            bottleCapacity = bottleCapacity,
                            batteryLevel = battery,
                            humidity = humidity,
                            isConnected = true
                        )
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // Keep default state
            }
        }
        database?.child("users")?.child(uid)?.child("device_telemetry")?.addValueEventListener(telemetryListener!!)
    }
    
    private fun calculateAndDisplayStreak(dailyTotals: Map<String, Int>) {
        var streak = 0
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val calendar = Calendar.getInstance()
        
        // Check today first
        val today = format.format(calendar.time)
        if ((dailyTotals[today] ?: 0) > 0) {
            streak++
        }
        
        // Check previous days backwards
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        while (true) {
            val dateString = format.format(calendar.time)
            if ((dailyTotals[dateString] ?: 0) > 0) {
                streak++
                calendar.add(Calendar.DAY_OF_YEAR, -1)
            } else {
                break
            }
        }
        
        val tvStreakDays = view?.findViewById<TextView>(R.id.tv_streak_days)
        val tvStreakIcon = view?.findViewById<TextView>(R.id.tv_streak_icon)
        
        tvStreakDays?.text = if (streak == 1) "1 Day" else "$streak Days"
        tvStreakIcon?.visibility = if (streak >= 3) View.VISIBLE else View.GONE
    }

    private fun updateProgressUI(totalConsumed: Int) {
        progressHydration.progress = totalConsumed
        
        if (totalConsumed >= currentHydrationTarget && currentHydrationTarget > 0) {
            progressHydration.setIndicatorColor(ContextCompat.getColor(requireContext(), R.color.strength_fair))
            val exceededBy = totalConsumed - currentHydrationTarget
            tvProgressCurrent.text = "+$exceededBy ml"
            tvProgressCurrent.setTextColor(ContextCompat.getColor(requireContext(), R.color.strength_fair))
            tvProgressMax.text = "Exceeded Daily Goal"
        } else {
            progressHydration.setIndicatorColor(ContextCompat.getColor(requireContext(), R.color.teal_primary))
            tvProgressCurrent.text = getString(R.string.progress_format_ml, totalConsumed)
            tvProgressCurrent.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            tvProgressMax.text = getString(R.string.progress_max_format_ml, currentHydrationTarget)
        }
    }

    private fun updateRecentSipUI(amount: Int, timestampMs: Long) {
        if (amount > 0) {
            tvRecentSipAmount.text = "$amount ml"
        }
        
        val sipCalendar = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val todayCalendar = Calendar.getInstance()
        
        val isToday = sipCalendar.get(Calendar.YEAR) == todayCalendar.get(Calendar.YEAR) &&
                      sipCalendar.get(Calendar.DAY_OF_YEAR) == todayCalendar.get(Calendar.DAY_OF_YEAR)
                      
        todayCalendar.add(Calendar.DAY_OF_YEAR, -1)
        val isYesterday = sipCalendar.get(Calendar.YEAR) == todayCalendar.get(Calendar.YEAR) &&
                          sipCalendar.get(Calendar.DAY_OF_YEAR) == todayCalendar.get(Calendar.DAY_OF_YEAR)

        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val timeString = timeFormat.format(sipCalendar.time)
        
        val displayString = when {
            isToday -> "Today, $timeString"
            isYesterday -> "Yesterday, $timeString"
            else -> {
                val dateFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                dateFormat.format(sipCalendar.time)
            }
        }
        
        tvRecentSipTime.text = displayString
    }

    /**
     * Updates the Device Status card on the Dashboard with:
     * 1. Water inside bottle in Liters (and ml capacity)
     * 2. Device battery percentage and status
     * 3. Ambient/bottle humidity percentage
     */
    private fun updateDeviceStatusUI(
        waterLevel: Int,
        bottleCapacity: Int,
        batteryLevel: Int,
        humidity: Int,
        isConnected: Boolean
    ) {
        if (!isAdded) return

        // ── 1. Water inside bottle formatted in Liters ──
        val safeCapacity = if (bottleCapacity > 0) bottleCapacity else 1000
        val safeWaterLevel = waterLevel.coerceAtLeast(0)
        val liters = safeWaterLevel / 1000.0
        tvDeviceWaterLiters.text = String.format(Locale.US, "%.2f L", liters)
        tvDeviceWaterMl.text = "$safeWaterLevel ml / $safeCapacity ml"
        val waterPercent = ((safeWaterLevel.toFloat() / safeCapacity) * 100).toInt().coerceIn(0, 100)
        pbBottleWaterLevel.progress = waterPercent

        // ── 2. Battery of the device ──
        val safeBattery = batteryLevel.coerceIn(0, 100)
        tvDeviceBattery.text = "$safeBattery%"
        pbBottleBattery.progress = safeBattery
        if (safeBattery <= 20) {
            tvDeviceBatteryStatus.text = getString(R.string.battery_status_low)
            tvDeviceBatteryStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.strength_weak))
            ivBatteryIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_weak)
            pbBottleBattery.progressTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_weak)
        } else {
            tvDeviceBatteryStatus.text = getString(R.string.battery_status_good)
            tvDeviceBatteryStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
            ivBatteryIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_strong)
            pbBottleBattery.progressTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_strong)
        }

        // ── 3. Humidity ──
        val safeHumidity = humidity.coerceIn(0, 100)
        tvDeviceHumidity.text = "$safeHumidity%"
        pbBottleHumidity.progress = safeHumidity
        tvDeviceHumidityStatus.text = when {
            safeHumidity < 30 -> "Dry"
            safeHumidity > 70 -> "High"
            else -> getString(R.string.humidity_status_normal)
        }

        // ── Connection Status ──
        updateDeviceConnectionBadge(isConnected)
    }

    private fun updateDeviceConnectionBadge(isConnected: Boolean) {
        if (!isAdded) return
        if (isConnected) {
            tvDeviceConnectionStatus.text = getString(R.string.device_connected)
            tvDeviceConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.teal_primary))
            vStatusDot.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_circle_green)
        } else {
            tvDeviceConnectionStatus.text = getString(R.string.device_offline)
            tvDeviceConnectionStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            vStatusDot.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_circle_inactive)
        }
    }
}