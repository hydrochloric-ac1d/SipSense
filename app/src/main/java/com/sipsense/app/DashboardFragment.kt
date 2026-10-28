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
import com.sipsense.app.data.NotificationRepository
import com.sipsense.app.data.SipSenseBleManager
import com.sipsense.app.data.Esp32SignalHub
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
    private lateinit var ivDeviceWaterIcon: ImageView
    private lateinit var tvDeviceWaterLiters: TextView
    private lateinit var tvDeviceWaterMl: TextView
    private lateinit var pbBottleWaterLevel: ProgressBar
    private lateinit var tvDeviceBattery: TextView
    private lateinit var tvDeviceBatteryStatus: TextView
    private lateinit var pbBottleBattery: ProgressBar
    private lateinit var ivBatteryIcon: ImageView
    private lateinit var ivTempIcon: ImageView
    private lateinit var tvDeviceAmbientTemp: TextView
    private lateinit var tvDeviceTempStatus: TextView
    private lateinit var pbBottleTemp: ProgressBar

    // ── Bottle Base Rest Views (HX711 Load Cell) ──
    private lateinit var layoutBaseIconContainer: View
    private lateinit var ivBaseRestIcon: ImageView

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
    private var currentAmbientTemp: Int? = null
    private var isTempReadFailed: Boolean = false
    private var isDeviceConnected: Boolean = false

    private fun isDeviceOnline(): Boolean = bleManager?.isConnected == true || isDeviceConnected

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
        val mainAct = activity as? MainActivity
        val appContext = context?.applicationContext ?: return

        val connCallback: (Boolean, String?) -> Unit = { isConnected, deviceName ->
            activity?.runOnUiThread {
                if (isAdded) {
                    isDeviceConnected = isConnected
                    updateDeviceConnectionBadge(isConnected)
                    (activity as? MainActivity)?.updateWifiStatus(isConnected)
                    if (isConnected && deviceName != null) {
                        tvDeviceName.text = deviceName
                    }
                    if (!isConnected) {
                        currentAmbientTemp = null
                        isTempReadFailed = false
                    }
                    updateDeviceStatusUI(
                        waterLevel = currentBottleWaterLevel,
                        bottleCapacity = currentBottleCapacity,
                        batteryLevel = currentBatteryLevel,
                        ambientTemp = if (isConnected) currentAmbientTemp else null,
                        isReadFailed = if (isConnected) isTempReadFailed else false,
                        isConnected = isConnected
                    )
                    val currentUid = firebaseAuth.currentUser?.uid
                    if (currentUid != null) {
                        NotificationRepository.evaluateConnectionAlert(currentUid, isConnected)
                        if (!isConnected && database != null) {
                            database.child("users").child(currentUid).child("device_telemetry").child("ambientTemperature").removeValue()
                            database.child("users").child(currentUid).child("device_telemetry").child("temperature").removeValue()
                            database.child("users").child(currentUid).child("device_telemetry").child("waterLevel").removeValue()
                            database.child("users").child(currentUid).child("device_telemetry").child("batteryLevel").removeValue()
                        }
                    }
                }
            }
        }

        val telemetryCallback: (JSONObject) -> Unit = { telemetryJson ->
            activity?.runOnUiThread {
                if (isAdded) {
                    processTelemetryData(telemetryJson)
                }
            }
        }

        if (mainAct?.bleManager != null) {
            bleManager = mainAct.bleManager
            bleManager?.onConnectionStateChanged = connCallback
            bleManager?.onTelemetryReceived = telemetryCallback
            val isConn = bleManager?.isConnected == true
            isDeviceConnected = isConn
            updateDeviceConnectionBadge(isConn)
            mainAct.updateWifiStatus(isConn)
            return
        }

        bleManager = SipSenseBleManager(
            context = appContext,
            onConnectionStateChanged = connCallback,
            onTelemetryReceived = telemetryCallback,
            onLogMessage = { msg ->
                android.util.Log.d("SipSenseDashboard", "BLE: $msg")
            }
        )
        mainAct?.bleManager = bleManager
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
        ivDeviceWaterIcon = view.findViewById(R.id.iv_device_water_icon)
        tvDeviceWaterLiters = view.findViewById(R.id.tv_device_water_liters)
        tvDeviceWaterMl = view.findViewById(R.id.tv_device_water_ml)
        pbBottleWaterLevel = view.findViewById(R.id.pb_bottle_water_level)
        tvDeviceBattery = view.findViewById(R.id.tv_device_battery)
        tvDeviceBatteryStatus = view.findViewById(R.id.tv_device_battery_status)
        pbBottleBattery = view.findViewById(R.id.pb_bottle_battery)
        ivBatteryIcon = view.findViewById(R.id.iv_battery_icon)
        ivTempIcon = view.findViewById(R.id.iv_temp_icon)
        tvDeviceAmbientTemp = view.findViewById(R.id.tv_device_ambient_temp)
        tvDeviceTempStatus = view.findViewById(R.id.tv_device_temp_status)
        pbBottleTemp = view.findViewById(R.id.pb_bottle_temp)

        // Bottle base rest indicator view beside tv_device_name (interactive simulation)
        layoutBaseIconContainer = view.findViewById(R.id.layout_base_icon_container)
        ivBaseRestIcon = view.findViewById(R.id.iv_base_rest_icon)

        layoutBaseIconContainer.setOnClickListener {
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
        val uid = firebaseAuth.currentUser?.uid
        if (activity?.isFinishing == true || uid == null) {
            bleManager?.disconnect()
            bleManager = null
            (activity as? MainActivity)?.bleManager = null
        }
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
        bleManager?.stopScan(cancelAutoRetry = true)
    }

    private fun setupInitialDeviceStatus() {
        updateBaseRestIndicatorUI(isBottleAtRest)
        updateDeviceStatusUI(
            waterLevel = currentBottleWaterLevel,
            bottleCapacity = currentBottleCapacity,
            batteryLevel = currentBatteryLevel,
            ambientTemp = currentAmbientTemp,
            isReadFailed = isTempReadFailed,
            isConnected = isDeviceOnline()
        )
    }

    /**
     * Toggles the bottle's resting state on the base platform.
     * When lifted: measurement pauses and water level display is held steady.
     * When placed at rest on base: calculates fluid volume if telemetry changed while off-base.
     * Does NOT fabricate artificial sips.
     */
    private fun toggleBottleRestState() {
        isBottleAtRest = !isBottleAtRest
        updateBaseRestIndicatorUI(isBottleAtRest)

        val now = System.currentTimeMillis()
        val uid = firebaseAuth.currentUser?.uid

        if (isBottleAtRest) {
            // Bottle has returned to rest on the base:
            // Calculate fluid volume ONLY if sensor telemetry actually changed while lifted
            if (latestSensorWaterLevel != currentBottleWaterLevel) {
                val sipConsumed = (currentBottleWaterLevel - latestSensorWaterLevel).coerceAtLeast(0)
                currentBottleWaterLevel = latestSensorWaterLevel
                if (sipConsumed > 0) {
                    recordSipForUser(sipConsumed, now, currentAmbientTemp ?: 0)
                }
            }

            // Persist the at-rest status to Firebase telemetry
            if (uid != null && database != null) {
                database.child("users").child(uid).child("device_telemetry").child("isBottleAtRest").setValue(true)
            }

            // Immediately refresh the water level display now that the bottle is resting
            updateDeviceStatusUI(
                waterLevel = currentBottleWaterLevel,
                bottleCapacity = currentBottleCapacity,
                batteryLevel = currentBatteryLevel,
                ambientTemp = currentAmbientTemp,
                isReadFailed = isTempReadFailed,
                isConnected = isDeviceOnline()
            )

            val msg = getString(R.string.msg_bottle_placed_on_base, currentBottleWaterLevel)
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        } else {
            // Bottle lifted from base: measurement paused, displayed water level held steady
            if (uid != null && database != null) {
                database.child("users").child(uid).child("device_telemetry").child("isBottleAtRest").setValue(false)
            }

            updateDeviceStatusUI(
                waterLevel = currentBottleWaterLevel,
                bottleCapacity = currentBottleCapacity,
                batteryLevel = currentBatteryLevel,
                ambientTemp = currentAmbientTemp,
                isReadFailed = isTempReadFailed,
                isConnected = isDeviceOnline()
            )
            Toast.makeText(requireContext(), getString(R.string.msg_bottle_lifted), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Updates the visual indicator for bottle resting on base vs lifted.
     * When lifted: uses layout_base_icon_container beside tv_device_name, colored orange.
     * When at rest: uses teal theme.
     */
    private fun updateBaseRestIndicatorUI(isAtRest: Boolean) {
        if (!isAdded) return
        if (isAtRest) {
            layoutBaseIconContainer.setBackgroundResource(R.drawable.bg_circle_light_teal)
            ivBaseRestIcon.setImageResource(R.drawable.ic_base_rest)
            ivBaseRestIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.teal_primary)
            layoutBaseIconContainer.contentDescription = getString(R.string.bottle_status_at_rest)
        } else {
            layoutBaseIconContainer.setBackgroundResource(R.drawable.bg_circle_light_amber)
            ivBaseRestIcon.setImageResource(R.drawable.ic_base_lifted)
            ivBaseRestIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.status_lifted_orange)
            layoutBaseIconContainer.contentDescription = getString(R.string.bottle_status_lifted)
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

        // Trigger daily goal reached notification if achieved
        val currentUid = firebaseAuth.currentUser?.uid
        if (currentUid != null) {
            NotificationRepository.evaluateTelemetryForAlerts(
                userId = currentUid,
                waterLevel = currentBottleWaterLevel,
                bottleCapacity = currentBottleCapacity,
                batteryLevel = currentBatteryLevel,
                ambientTemp = currentAmbientTemp ?: 0,
                dailyTotal = currentDailyTotal,
                dailyTarget = currentHydrationTarget,
                isAtRest = isBottleAtRest
            )
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

        // Check if ESP32 reported a sensor read failure
        val isReadFailed = dataObject.optString("status").equals("Read Failed", ignoreCase = true)
            || dataObject.optString("tempSensorStatus").equals("Read Failed", ignoreCase = true)
            || dataObject.optString("error").contains("Read Failed", ignoreCase = true)
            || dataObject.optBoolean("readFailed", false)
            || dataObject.optBoolean("isReadFailed", false)

        if (isReadFailed) {
            isTempReadFailed = true
            currentAmbientTemp = null
            Esp32SignalHub.logError("DHT11", "❌ [SENSOR ERROR] DHT11 Read Failed signal received from ESP32")
            android.util.Log.e("SipSenseDashboard", "DHT11 Sensor Read Failed signal received from ESP32")
        } else {
            val hasTemp = (dataObject.has("ambientTemperature") && !dataObject.isNull("ambientTemperature"))
                || (dataObject.has("temperature") && !dataObject.isNull("temperature"))
            if (hasTemp) {
                val ambientTemp = dataObject.optInt(
                    "ambientTemperature",
                    dataObject.optInt("temperature", Int.MIN_VALUE)
                )
                if (ambientTemp != Int.MIN_VALUE) {
                    isTempReadFailed = false
                    currentAmbientTemp = ambientTemp

                    // Log temperature readings to the terminal
                    val tempCStr = if (dataObject.has("temperatureC")) {
                        String.format(Locale.US, "%.1f°C", dataObject.optDouble("temperatureC"))
                    } else {
                        "${ambientTemp}°C"
                    }
                    val humStr = if (dataObject.has("humidity")) " | Humidity: ${dataObject.optInt("humidity")}%" else ""
                    Esp32SignalHub.logRx("TEMP", "🌡️ Temperature Reading: $tempCStr$humStr", rawPayload = dataObject.toString())
                    android.util.Log.i("SipSenseDashboard", "🌡️ Temperature Reading: $tempCStr$humStr")
                }
            }
        }

        // Log HX711 load cell / scale readings to the developer mode terminal
        val isHx711Failed = dataObject.optString("hx711Status").equals("Read Failed", ignoreCase = true)
            || dataObject.optBoolean("hx711ReadFailed", false)
            || (dataObject.has("hx711Error") && dataObject.optString("hx711Error").isNotBlank())

        if (isHx711Failed) {
            Esp32SignalHub.logError("HX711", "❌ [SENSOR ERROR] HX711 Load Cell Read Failed signal from ESP32")
            android.util.Log.e("SipSenseDashboard", "HX711 Sensor Read Failed signal received from ESP32")
        } else {
            val hasWeight = dataObject.has("weightGrams")
                || dataObject.has("weight")
                || dataObject.has("rawCounts")
                || (dataObject.has("waterLevel") && !dataObject.isNull("waterLevel"))

            if (hasWeight) {
                val weightGrams = if (dataObject.has("weightGrams")) {
                    dataObject.optDouble("weightGrams")
                } else if (dataObject.has("weight")) {
                    dataObject.optDouble("weight")
                } else {
                    waterLevel.toDouble()
                }

                val weightStr = String.format(Locale.US, "%.1f g", weightGrams)
                val calibStr = if (dataObject.has("isCalibrated")) {
                    if (dataObject.optBoolean("isCalibrated")) " | Calibrated" else " | Uncalibrated"
                } else ""
                val rawStr = if (dataObject.has("rawCounts")) " | Raw: ${dataObject.optLong("rawCounts")}" else ""
                val restState = if (dataObject.has("isBottleAtRest")) {
                    dataObject.optBoolean("isBottleAtRest")
                } else isBottleAtRest
                val restStr = if (restState) " | At Rest" else " | Lifted"

                Esp32SignalHub.logRx(
                    "HX711",
                    "⚖️ Scale Reading: $weightStr ($waterLevel ml)$calibStr$rawStr$restStr",
                    rawPayload = dataObject.toString()
                )
                android.util.Log.i("SipSenseDashboard", "⚖️ Scale Reading: $weightStr ($waterLevel ml)")
            }
        }

        latestSensorWaterLevel = waterLevel
        currentBottleCapacity = bottleCapacity
        currentBatteryLevel = battery

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
            val telemetryMap = mutableMapOf<String, Any>(
                "waterLevel" to currentBottleWaterLevel,
                "rawSensorWaterLevel" to latestSensorWaterLevel,
                "bottleCapacity" to bottleCapacity,
                "batteryLevel" to battery,
                "humidity" to dataObject.optInt("humidity", 55),
                "isBottleAtRest" to isBottleAtRest,
                "tempSensorStatus" to if (isTempReadFailed) "Read Failed" else "OK",
                "readFailed" to isTempReadFailed,
                "lastUpdated" to System.currentTimeMillis()
            )
            if (!isTempReadFailed && currentAmbientTemp != null) {
                telemetryMap["ambientTemperature"] = currentAmbientTemp!!
                telemetryMap["temperature"] = currentAmbientTemp!!
            }
            database.child("users").child(uid).child("device_telemetry").setValue(telemetryMap)
                .addOnFailureListener { e ->
                    android.util.Log.e("SipSenseDashboard", "Failed to persist telemetry for user $uid", e)
                }

            if (isTempReadFailed) {
                database.child("users").child(uid).child("device_telemetry").child("ambientTemperature").removeValue()
                database.child("users").child(uid).child("device_telemetry").child("temperature").removeValue()
            }

            // Evaluate smart alerts for water refill, low battery, high ambient temp, etc.
            if (currentAmbientTemp != null) {
                NotificationRepository.evaluateTelemetryForAlerts(
                    userId = uid,
                    waterLevel = currentBottleWaterLevel,
                    bottleCapacity = bottleCapacity,
                    batteryLevel = battery,
                    ambientTemp = currentAmbientTemp!!,
                    dailyTotal = currentDailyTotal,
                    dailyTarget = currentHydrationTarget,
                    isAtRest = isBottleAtRest
                )
            }
        }

        activity?.runOnUiThread {
            if (isAdded) {
                updateDeviceStatusUI(
                    waterLevel = currentBottleWaterLevel,
                    bottleCapacity = bottleCapacity,
                    batteryLevel = battery,
                    ambientTemp = currentAmbientTemp,
                    isReadFailed = isTempReadFailed,
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
                recordSipForUser(sipAmount, currentSipTimestampMs, currentAmbientTemp ?: 0)
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
                    }
                    connection.disconnect()
                } catch (_: Exception) {
                    // Silent fallback: HTTP backend polling is an optional fallback and does not override connection state
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
                val isOnline = isDeviceOnline()
                val isFailed = snapshot.child("tempSensorStatus").getValue(String::class.java).equals("Read Failed", ignoreCase = true)
                    || snapshot.child("status").getValue(String::class.java).equals("Read Failed", ignoreCase = true)
                    || snapshot.child("readFailed").getValue(Boolean::class.java) == true

                val ambientTemp = if (isFailed || !isOnline) {
                    null
                } else {
                    snapshot.child("ambientTemperature").getValue(Int::class.java)
                        ?: snapshot.child("temperature").getValue(Int::class.java)
                }

                latestSensorWaterLevel = waterLevel
                currentBottleCapacity = bottleCapacity
                currentBatteryLevel = battery
                currentAmbientTemp = ambientTemp
                isTempReadFailed = isFailed

                if (isFailed && database != null) {
                    database.child("users").child(uid).child("device_telemetry").child("ambientTemperature").removeValue()
                    database.child("users").child(uid).child("device_telemetry").child("temperature").removeValue()
                }

                if (snapshot.hasChild("isBottleAtRest")) {
                    val atRest = snapshot.child("isBottleAtRest").getValue(Boolean::class.java) ?: isBottleAtRest
                    isBottleAtRest = atRest
                    activity?.runOnUiThread {
                        if (isAdded) {
                            updateBaseRestIndicatorUI(isBottleAtRest)
                        }
                    }
                }

                if (isBottleAtRest) {
                    currentBottleWaterLevel = waterLevel
                }

                activity?.runOnUiThread {
                    if (isAdded) {
                        updateDeviceStatusUI(
                            waterLevel = currentBottleWaterLevel,
                            bottleCapacity = bottleCapacity,
                            batteryLevel = battery,
                            ambientTemp = currentAmbientTemp,
                            isReadFailed = isTempReadFailed,
                            isConnected = isDeviceOnline()
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
        ambientTemp: Int?,
        isReadFailed: Boolean = false,
        isConnected: Boolean
    ) {
        if (!isAdded) return

        // ── 1. Water inside bottle formatted in Liters ──
        if (!isConnected) {
            ivDeviceWaterIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
            tvDeviceWaterLiters.text = "--"
            tvDeviceWaterLiters.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            tvDeviceWaterMl.text = "--"
            tvDeviceWaterMl.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
            pbBottleWaterLevel.progress = 0
            pbBottleWaterLevel.progressTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
        } else {
            ivDeviceWaterIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.teal_primary)
            val safeCapacity = if (bottleCapacity > 0) bottleCapacity else 1000
            val safeWaterLevel = waterLevel.coerceAtLeast(0)
            val liters = safeWaterLevel / 1000.0
            tvDeviceWaterLiters.text = String.format(Locale.US, "%.2f L", liters)
            tvDeviceWaterLiters.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            tvDeviceWaterMl.text = if (isBottleAtRest) {
                "$safeWaterLevel ml / $safeCapacity ml"
            } else {
                "$safeWaterLevel ml (Paused)"
            }
            tvDeviceWaterMl.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
            val waterPercent = ((safeWaterLevel.toFloat() / safeCapacity) * 100).toInt().coerceIn(0, 100)
            pbBottleWaterLevel.progress = waterPercent
            pbBottleWaterLevel.progressTintList = ContextCompat.getColorStateList(requireContext(), R.color.teal_primary)
        }

        // ── 2. Battery of the device ──
        if (!isConnected) {
            ivBatteryIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
            tvDeviceBattery.text = "--"
            tvDeviceBattery.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            tvDeviceBatteryStatus.text = getString(R.string.ambient_temp_status_waiting)
            tvDeviceBatteryStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
            pbBottleBattery.progress = 0
            pbBottleBattery.progressTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
        } else {
            val safeBattery = batteryLevel.coerceIn(0, 100)
            tvDeviceBattery.text = "$safeBattery%"
            tvDeviceBattery.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
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
        }

        // ── 3. Ambient Room Temperature ──
        if (!isConnected) {
            ivTempIcon.setImageResource(R.drawable.ic_temperature)
            ivTempIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
            tvDeviceAmbientTemp.text = "--"
            tvDeviceAmbientTemp.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            tvDeviceTempStatus.text = getString(R.string.ambient_temp_status_waiting)
            tvDeviceTempStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
            pbBottleTemp.progress = 0
            pbBottleTemp.progressTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
        } else if (isReadFailed) {
            ivTempIcon.setImageResource(R.drawable.ic_error)
            ivTempIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_weak)
            tvDeviceAmbientTemp.text = "--"
            tvDeviceAmbientTemp.setTextColor(ContextCompat.getColor(requireContext(), R.color.strength_weak))
            tvDeviceTempStatus.text = getString(R.string.ambient_temp_status_error)
            tvDeviceTempStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.strength_weak))
            pbBottleTemp.progress = 0
            pbBottleTemp.progressTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_weak)
        } else if (ambientTemp == null) {
            ivTempIcon.setImageResource(R.drawable.ic_temperature)
            ivTempIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_fair)
            tvDeviceAmbientTemp.text = "--"
            tvDeviceAmbientTemp.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            tvDeviceTempStatus.text = getString(R.string.ambient_temp_status_waiting)
            tvDeviceTempStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
            pbBottleTemp.progress = 0
            pbBottleTemp.progressTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_fair)
        } else {
            ivTempIcon.setImageResource(R.drawable.ic_temperature)
            ivTempIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_fair)
            val safeTemp = ambientTemp.coerceIn(0, 50)
            tvDeviceAmbientTemp.text = "$safeTemp°C"
            tvDeviceAmbientTemp.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
            pbBottleTemp.progress = safeTemp
            pbBottleTemp.progressTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_fair)
            tvDeviceTempStatus.text = when {
                safeTemp < 20 -> getString(R.string.ambient_temp_status_cool)
                safeTemp > 28 -> getString(R.string.ambient_temp_status_warm)
                else -> getString(R.string.ambient_temp_status_normal)
            }
            tvDeviceTempStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
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