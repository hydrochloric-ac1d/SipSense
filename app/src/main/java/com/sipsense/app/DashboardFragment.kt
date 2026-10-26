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
import androidx.activity.result.contract.ActivityResultContracts
import com.sipsense.app.data.SipSenseBleManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class DashboardFragment : Fragment() {

    // ── Hydration & Streak Views ──
    private lateinit var tvGreeting: TextView
    private lateinit var progressHydration: CircularProgressIndicator
    private lateinit var tvProgressCurrent: TextView
    private lateinit var tvProgressMax: TextView
    private lateinit var tvRecentSipTime: TextView
    private lateinit var tvRecentSipAmount: TextView

    // ── Device Telemetry Views (Battery, Ambient Room Temperature, Water In Liters) ──
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
    private lateinit var tvDeviceAmbientTemp: TextView
    private lateinit var tvDeviceTempStatus: TextView
    private lateinit var pbBottleTemp: ProgressBar

    // ── Bottle Base Rest Views (HX711 Load Cell) ──
    private lateinit var layoutBaseRestStatus: View
    private lateinit var layoutBaseIconContainer: View
    private lateinit var ivBaseRestIcon: ImageView
    private lateinit var tvBaseRestTitle: TextView
    private lateinit var tvBaseRestSubtitle: TextView
    private lateinit var layoutBaseRestBadge: View
    private lateinit var vBaseRestDot: View
    private lateinit var tvBaseRestBadge: TextView

    private val firebaseAuth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabaseProvider.reference()
    
    private var profileListener: ValueEventListener? = null
    private var telemetryListener: ValueEventListener? = null
    private var dailyTotalsListener: ValueEventListener? = null

    // ── Device & Hydration State ──
    private var isPolling = false
    private var lastSavedSipTimestampMs: Long = 0L
    private var currentHydrationTarget: Int = 2500
    private var currentDailyTotal: Int = 0
    private var isFirebaseRecentSipLoaded = false
    private var varFirebaseDailyTotalLoaded = false

    private var isBottleAtRest: Boolean = true
    private var currentBottleWaterLevel: Int = 650
    private var latestSensorWaterLevel: Int = 650
    private var currentBottleCapacity: Int = 1000
    private var currentBatteryLevel: Int = 88
    private var currentAmbientTemp: Int = 24

    // ── BLE Connection Manager ──
    private var bleManager: SipSenseBleManager? = null

    private val blePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            bleManager?.startScan()
        } else {
            android.util.Log.w("SipSenseDashboard", "BLE permissions denied by user.")
        }
    }

    private fun initBleManager() {
        if (bleManager != null) return
        val appContext = context?.applicationContext ?: return
        bleManager = SipSenseBleManager(
            context = appContext,
            onConnectionStateChanged = { isConnected, deviceName ->
                if (isAdded) {
                    updateDeviceConnectionBadge(isConnected)
                    (activity as? MainActivity)?.updateWifiStatus(isConnected)
                    if (isConnected && deviceName != null) {
                        tvDeviceName.text = deviceName
                    }
                }
            },
            onTelemetryReceived = { telemetryJson ->
                processTelemetryData(telemetryJson)
            },
            onLogMessage = { msg ->
                android.util.Log.d("SipSenseDashboard", "BLE: $msg")
            }
        )
    }

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
        tvDeviceAmbientTemp = view.findViewById(R.id.tv_device_ambient_temp)
        tvDeviceTempStatus = view.findViewById(R.id.tv_device_temp_status)
        pbBottleTemp = view.findViewById(R.id.pb_bottle_temp)

        // Bottle base rest indicator views (interactive simulation)
        layoutBaseRestStatus = view.findViewById(R.id.layout_base_rest_status)
        layoutBaseIconContainer = view.findViewById(R.id.layout_base_icon_container)
        ivBaseRestIcon = view.findViewById(R.id.iv_base_rest_icon)
        tvBaseRestTitle = view.findViewById(R.id.tv_base_rest_title)
        tvBaseRestSubtitle = view.findViewById(R.id.tv_base_rest_subtitle)
        layoutBaseRestBadge = view.findViewById(R.id.layout_base_rest_badge)
        vBaseRestDot = view.findViewById(R.id.v_base_rest_dot)
        tvBaseRestBadge = view.findViewById(R.id.tv_base_rest_badge)

        layoutBaseRestStatus.setOnClickListener {
            toggleBottleRestState()
        }

        setupGreeting()
        setupRecentSipTime()
        setupInitialDeviceStatus()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadUserProfile()
        loadRecentSipFromFirebase()
        loadDeviceTelemetryFromFirebase()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        bleManager?.disconnect()
        bleManager = null
        val uid = firebaseAuth.currentUser?.uid
        if (uid != null) {
            profileListener?.let { database?.child("users")?.child(uid)?.removeEventListener(it) }
            telemetryListener?.let { database?.child("users")?.child(uid)?.child("device_telemetry")?.removeEventListener(it) }
            dailyTotalsListener?.let { database?.child("users")?.child(uid)?.child("daily_totals")?.removeEventListener(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        isPolling = true
        initBleManager()
        val currentContext = context
        if (currentContext != null && SipSenseBleManager.hasPermissions(currentContext)) {
            bleManager?.startScan()
        } else {
            blePermissionsLauncher.launch(SipSenseBleManager.getRequiredPermissions())
        }
        startPollingBackend()
    }

    override fun onPause() {
        super.onPause()
        isPolling = false
        bleManager?.stopScan()
    }

    private fun setupInitialDeviceStatus() {
        updateBaseRestIndicatorUI(isBottleAtRest)
        updateDeviceStatusUI(
            waterLevel = currentBottleWaterLevel,
            bottleCapacity = currentBottleCapacity,
            batteryLevel = currentBatteryLevel,
            ambientTemp = currentAmbientTemp,
            isConnected = false
        )
    }

    /**
     * Toggles the bottle's resting state on the base platform for simulation purposes.
     * Specification:
     * "The system shall calculate fluid volume in real-time by subtracting the updated
     * bottle weight from the prior full reference weight using the Load Cell and HX711
     * amplifier whenever the bottle rests on the base."
     */
    private fun toggleBottleRestState() {
        isBottleAtRest = !isBottleAtRest
        updateBaseRestIndicatorUI(isBottleAtRest)

        if (isBottleAtRest) {
            // Bottle has returned to rest on the base:
            // Calculate fluid volume and update the water level display!
            val now = System.currentTimeMillis()
            if (latestSensorWaterLevel != currentBottleWaterLevel) {
                // Telemetry changed while bottle was lifted
                val sipConsumed = (currentBottleWaterLevel - latestSensorWaterLevel).coerceAtLeast(0)
                currentBottleWaterLevel = latestSensorWaterLevel
                if (sipConsumed > 0) {
                    recordSipForUser(sipConsumed, now, currentAmbientTemp)
                }
            } else {
                // Manual simulation mode (e.g. backend inactive or offline):
                // Simulate taking a 35 ml sip and updating volume once placed on base!
                val simulatedSip = 35
                val newLevel = (currentBottleWaterLevel - simulatedSip).coerceAtLeast(0)
                val actualSip = currentBottleWaterLevel - newLevel
                currentBottleWaterLevel = newLevel
                latestSensorWaterLevel = newLevel
                if (actualSip > 0) {
                    recordSipForUser(actualSip, now, currentAmbientTemp)
                }
            }

            // Immediately refresh the water level display now that the bottle is resting
            updateDeviceStatusUI(
                waterLevel = currentBottleWaterLevel,
                bottleCapacity = currentBottleCapacity,
                batteryLevel = currentBatteryLevel,
                ambientTemp = currentAmbientTemp,
                isConnected = true
            )

            val msg = getString(R.string.msg_bottle_placed_on_base, currentBottleWaterLevel)
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        } else {
            // Bottle lifted from base: measurement paused, displayed water level held steady
            updateDeviceStatusUI(
                waterLevel = currentBottleWaterLevel,
                bottleCapacity = currentBottleCapacity,
                batteryLevel = currentBatteryLevel,
                ambientTemp = currentAmbientTemp,
                isConnected = true
            )
            Toast.makeText(requireContext(), getString(R.string.msg_bottle_lifted), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Updates the visual indicator for bottle resting on base vs lifted.
     */
    private fun updateBaseRestIndicatorUI(isAtRest: Boolean) {
        if (!isAdded) return
        if (isAtRest) {
            layoutBaseRestStatus.setBackgroundResource(R.drawable.bg_base_rest_card_at_rest)
            layoutBaseIconContainer.setBackgroundResource(R.drawable.bg_circle_light_teal)
            ivBaseRestIcon.setImageResource(R.drawable.ic_base_rest)
            ivBaseRestIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.teal_primary)
            tvBaseRestTitle.text = getString(R.string.bottle_status_at_rest)
            tvBaseRestSubtitle.text = getString(R.string.bottle_rest_desc_active)
            layoutBaseRestBadge.setBackgroundResource(R.drawable.bg_pill_at_rest)
            vBaseRestDot.setBackgroundResource(R.drawable.bg_circle_green)
            tvBaseRestBadge.text = getString(R.string.badge_at_rest)
            tvBaseRestBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.teal_primary))
        } else {
            layoutBaseRestStatus.setBackgroundResource(R.drawable.bg_base_rest_card_lifted)
            layoutBaseIconContainer.setBackgroundResource(R.drawable.bg_circle_light_amber)
            ivBaseRestIcon.setImageResource(R.drawable.ic_base_lifted)
            ivBaseRestIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_fair)
            tvBaseRestTitle.text = getString(R.string.bottle_status_lifted)
            tvBaseRestSubtitle.text = getString(R.string.bottle_rest_desc_paused)
            layoutBaseRestBadge.setBackgroundResource(R.drawable.bg_pill_lifted)
            vBaseRestDot.setBackgroundResource(R.drawable.bg_circle_amber)
            tvBaseRestBadge.text = getString(R.string.badge_lifted)
            tvBaseRestBadge.setTextColor(ContextCompat.getColor(requireContext(), R.color.strength_fair))
        }
    }

    /**
     * Records a sip and updates daily totals under the current user's Firebase path.
     */
    private fun recordSipForUser(sipAmount: Int, timestampMs: Long, ambientTemp: Int) {
        if (sipAmount <= 0) return
        val uid = firebaseAuth.currentUser?.uid
        lastSavedSipTimestampMs = timestampMs
        currentDailyTotal += sipAmount

        if (uid != null && database != null) {
            // 1. Save the individual sip record under /users/{uid}/sip_records
            val recordRef = database.child("users").child(uid).child("sip_records").push()
            val newRecord = com.sipsense.app.model.SipRecord(
                amount = sipAmount,
                timestamp = timestampMs,
                ambientTemperature = ambientTemp
            )
            recordRef.setValue(newRecord)
                .addOnSuccessListener {
                    android.util.Log.d("SipSenseDashboard", "Saved sip ($sipAmount ml) for user $uid")
                }
                .addOnFailureListener { e ->
                    android.util.Log.e("SipSenseDashboard", "Failed to save sip for user $uid", e)
                }

            // 2. Save the total daily sip record under /users/{uid}/daily_totals/{dateKey}
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val dateKey = dateFormat.format(java.util.Date(timestampMs))
            database.child("users").child(uid).child("daily_totals").child(dateKey).setValue(currentDailyTotal)
                .addOnSuccessListener {
                    android.util.Log.d("SipSenseDashboard", "Updated daily total ($currentDailyTotal ml) for user $uid on $dateKey")
                }
                .addOnFailureListener { e ->
                    android.util.Log.e("SipSenseDashboard", "Failed to update daily total for user $uid", e)
                }
        }

        activity?.runOnUiThread {
            if (isAdded) {
                updateProgressUI(currentDailyTotal)
                updateRecentSipUI(sipAmount, timestampMs)
            }
        }
    }

    /**
     * Processes incoming telemetry data (from BLE notifications or HTTP fallback)
     * and updates the UI, Firebase database, and sip tracking logs.
     */
    private fun processTelemetryData(dataObject: JSONObject) {
        val waterLevel = dataObject.optInt("waterLevel", latestSensorWaterLevel)
        val bottleCapacity = dataObject.optInt("bottleCapacity", currentBottleCapacity)
        val battery = dataObject.optInt("batteryLevel", currentBatteryLevel)
        val ambientTemp = dataObject.optInt(
            "ambientTemperature",
            dataObject.optInt("temperature", currentAmbientTemp)
        )

        latestSensorWaterLevel = waterLevel
        currentBottleCapacity = bottleCapacity
        currentBatteryLevel = battery
        currentAmbientTemp = ambientTemp

        // Check if device reports bottle base rest state (HX711 / switch)
        if (dataObject.has("isBottleAtRest")) {
            isBottleAtRest = dataObject.optBoolean("isBottleAtRest", isBottleAtRest)
            activity?.runOnUiThread {
                if (isAdded) {
                    updateBaseRestIndicatorUI(isBottleAtRest)
                }
            }
        }

        // Water level should ONLY update display if the bottle is at rest on the base
        if (isBottleAtRest) {
            currentBottleWaterLevel = waterLevel
        }

        val uid = firebaseAuth.currentUser?.uid

        // 1. Immediately update and persist device telemetry for the active user ID
        if (uid != null && database != null) {
            val telemetryMap = mapOf(
                "waterLevel" to currentBottleWaterLevel,
                "rawSensorWaterLevel" to latestSensorWaterLevel,
                "bottleCapacity" to bottleCapacity,
                "batteryLevel" to battery,
                "ambientTemperature" to ambientTemp,
                "temperature" to ambientTemp,
                "humidity" to dataObject.optInt("humidity", 55),
                "isBottleAtRest" to isBottleAtRest,
                "lastUpdated" to System.currentTimeMillis()
            )
            database.child("users").child(uid).child("device_telemetry").setValue(telemetryMap)
                .addOnFailureListener { e ->
                    android.util.Log.e("SipSenseDashboard", "Failed to persist telemetry for user $uid", e)
                }
        }

        activity?.runOnUiThread {
            if (isAdded) {
                updateDeviceStatusUI(
                    waterLevel = currentBottleWaterLevel,
                    bottleCapacity = bottleCapacity,
                    batteryLevel = battery,
                    ambientTemp = ambientTemp,
                    isConnected = true
                )
                (activity as? MainActivity)?.updateWifiStatus(true)
            }
        }

        // 2. Process sip detection and daily hydration totals per user ID ONLY when resting on base
        if (varFirebaseDailyTotalLoaded && isBottleAtRest) {
            val sipAmount = dataObject.optInt("sipAmount", 0)
            val rawTimestamp = if (dataObject.isNull("lastSipTimestamp")) "" else dataObject.optString("lastSipTimestamp", "")

            var currentSipTimestampMs = 0L
            if (rawTimestamp.isNotBlank() && rawTimestamp != "null") {
                currentSipTimestampMs = parseIsoTimestamp(rawTimestamp)
            } else {
                currentSipTimestampMs = System.currentTimeMillis()
            }

            // If this is the user's first check with 0 recorded sips, avoid attributing stale sips
            if (lastSavedSipTimestampMs == 0L && currentSipTimestampMs > 0L) {
                val now = System.currentTimeMillis()
                if (now - currentSipTimestampMs > 15_000L) {
                    lastSavedSipTimestampMs = currentSipTimestampMs
                }
            }

            // Detect genuinely new sip and save PER USER
            if (currentSipTimestampMs > lastSavedSipTimestampMs && sipAmount > 0) {
                recordSipForUser(sipAmount, currentSipTimestampMs, ambientTemp)
            }
        }
    }

    private fun startPollingBackend() {
        Thread {
            while (isPolling) {
                try {
                    // If BLE is already actively connected, we do not need to poll HTTP
                    if (bleManager?.isConnected == true) {
                        Thread.sleep(3000)
                        continue
                    }

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
                            processTelemetryData(dataObject)
                        }
                    } else {
                        activity?.runOnUiThread {
                            if (isAdded && bleManager?.isConnected != true) {
                                updateDeviceConnectionBadge(isConnected = false)
                                (activity as? MainActivity)?.updateWifiStatus(false)
                            }
                        }
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    activity?.runOnUiThread {
                        if (isAdded && bleManager?.isConnected != true) {
                            updateDeviceConnectionBadge(isConnected = false)
                            (activity as? MainActivity)?.updateWifiStatus(false)
                        }
                    }
                }
                Thread.sleep(3000)
            }
        }.start()
    }

    private fun parseIsoTimestamp(isoString: String): Long {
        val patterns = arrayOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS",
            "yyyy-MM-dd'T'HH:mm:ss"
        )
        for (pattern in patterns) {
            try {
                val parser = SimpleDateFormat(pattern, Locale.US)
                parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val date = parser.parse(isoString)
                if (date != null) return date.time
            } catch (_: Exception) {}
        }
        return isoString.toLongOrNull() ?: 0L
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
                val profile = snapshot.getValue(UserProfile::class.java) ?: return
                
                val name = profile.fullName.takeIf { it.isNotBlank() } ?: firstName
                val shortName = name.substringBefore(" ").replaceFirstChar { it.uppercase() }

                activity?.runOnUiThread {
                    if (isAdded) {
                        setupGreeting(shortName)
                        val target = profile.hydrationTarget
                        currentHydrationTarget = target
                        progressHydration.max = target
                        tvProgressMax.text = getString(R.string.progress_max_format_ml, target)
                        updateProgressUI(currentDailyTotal)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                activity?.runOnUiThread {
                    if (isAdded) {
                        Toast.makeText(context, "Failed to load profile", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        
        database?.child("users")?.child(uid)?.addValueEventListener(profileListener!!)
    }

    private fun loadRecentSipFromFirebase() {
        val uid = firebaseAuth.currentUser?.uid ?: run {
            isFirebaseRecentSipLoaded = true
            varFirebaseDailyTotalLoaded = true
            return
        }
        
        // 1. Load Recent Sip
        database?.child("users")?.child(uid)?.child("sip_records")
            ?.orderByChild("timestamp")
            ?.limitToLast(1)
            ?.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    for (child in snapshot.children) {
                        val record = child.getValue(com.sipsense.app.model.SipRecord::class.java)
                        if (record != null) {
                            lastSavedSipTimestampMs = record.timestamp
                            activity?.runOnUiThread {
                                if (isAdded) {
                                    updateRecentSipUI(record.amount, record.timestamp)
                                }
                            }
                        }
                    }
                    isFirebaseRecentSipLoaded = true
                }
                override fun onCancelled(error: DatabaseError) {
                    android.util.Log.w("SipSenseDashboard", "Recent sip read cancelled: ${error.message}")
                    isFirebaseRecentSipLoaded = true
                }
            })
            
        // 2. Load Daily Totals for Streak and Today's Progress
        dailyTotalsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
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
                    if (isAdded) {
                        updateProgressUI(currentDailyTotal)
                        calculateAndDisplayStreak(dailyTotals)
                    }
                }
                varFirebaseDailyTotalLoaded = true
            }
            override fun onCancelled(error: DatabaseError) {
                android.util.Log.w("SipSenseDashboard", "Daily totals read cancelled: ${error.message}")
                varFirebaseDailyTotalLoaded = true
            }
        }
        database?.child("users")?.child(uid)?.child("daily_totals")?.addValueEventListener(dailyTotalsListener!!)
    }

    private fun loadDeviceTelemetryFromFirebase() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        telemetryListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val waterLevel = snapshot.child("waterLevel").getValue(Int::class.java) ?: currentBottleWaterLevel
                val bottleCapacity = snapshot.child("bottleCapacity").getValue(Int::class.java) ?: currentBottleCapacity
                val battery = snapshot.child("batteryLevel").getValue(Int::class.java) ?: currentBatteryLevel
                val ambientTemp = snapshot.child("ambientTemperature").getValue(Int::class.java)
                    ?: snapshot.child("temperature").getValue(Int::class.java)
                    ?: currentAmbientTemp

                latestSensorWaterLevel = waterLevel
                currentBottleCapacity = bottleCapacity
                currentBatteryLevel = battery
                currentAmbientTemp = ambientTemp

                if (isBottleAtRest) {
                    currentBottleWaterLevel = waterLevel
                }

                activity?.runOnUiThread {
                    if (isAdded) {
                        updateDeviceStatusUI(
                            waterLevel = currentBottleWaterLevel,
                            bottleCapacity = bottleCapacity,
                            batteryLevel = battery,
                            ambientTemp = ambientTemp,
                            isConnected = bleManager?.isConnected == true
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
     * 3. Ambient room temperature where bottle is placed
     */
    private fun updateDeviceStatusUI(
        waterLevel: Int,
        bottleCapacity: Int,
        batteryLevel: Int,
        ambientTemp: Int,
        isConnected: Boolean
    ) {
        if (!isAdded) return

        // ── 1. Water inside bottle formatted in Liters ──
        val safeCapacity = if (bottleCapacity > 0) bottleCapacity else 1000
        val safeWaterLevel = waterLevel.coerceAtLeast(0)
        val liters = safeWaterLevel / 1000.0
        tvDeviceWaterLiters.text = String.format(Locale.US, "%.2f L", liters)
        tvDeviceWaterMl.text = if (isBottleAtRest) {
            "$safeWaterLevel ml / $safeCapacity ml"
        } else {
            "$safeWaterLevel ml (Paused)"
        }
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

        // ── 3. Ambient Room Temperature ──
        val safeTemp = ambientTemp.coerceIn(0, 50)
        tvDeviceAmbientTemp.text = "$safeTemp°C"
        pbBottleTemp.progress = safeTemp
        tvDeviceTempStatus.text = when {
            safeTemp < 20 -> getString(R.string.ambient_temp_status_cool)
            safeTemp > 28 -> getString(R.string.ambient_temp_status_warm)
            else -> getString(R.string.ambient_temp_status_normal)
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