package com.sipsense.app

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.sipsense.app.adapter.BleDeviceAdapter
import com.sipsense.app.data.DiscoveredBleDevice
import com.sipsense.app.data.FirebaseDatabaseProvider
import com.sipsense.app.data.SipSenseBleManager
import com.sipsense.app.model.SipRecord
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * WaterbottleFragment.kt
 *
 * Bottle Tab in the SipSense ecosystem:
 * - Unified card displaying connection state, live telemetries (fixed 500 ml capacity), and bottle scanner
 * - Allows user to manually find and connect to smart water bottles (1 bottle per mobile)
 * - Scanner button shows "Disconnect" once connected, and reverts to "Find Bottle" on disconnect
 * - Discovered bottles section is hidden while connected
 * - Streams live telemetry (Water Level in Liters/ml out of 500 ml, Battery %, Ambient Temperature)
 * - Automatically counts any water volume deduction towards the user's Daily Goal even while on the Bottle tab
 *
 * @project SipSense - Smart Bottle Ecosystem
 */
open class WaterbottleFragment : Fragment() {

    // ── View References: Active Bottle Header & Status ───────────────────
    private lateinit var ivActiveBottleIcon: ImageView
    private lateinit var tvActiveBottleName: TextView
    private lateinit var vConnectionDot: View
    private lateinit var tvConnectionBadgeText: TextView

    // ── View References: Telemetry Metrics ───────────────────────────────
    private lateinit var ivMetricWater: ImageView
    private lateinit var tvBottleWaterLiters: TextView
    private lateinit var tvBottleWaterMl: TextView

    private lateinit var ivMetricBattery: ImageView
    private lateinit var tvBottleBattery: TextView
    private lateinit var tvBottleBatteryStatus: TextView

    private lateinit var ivMetricTemp: ImageView
    private lateinit var tvBottleAmbientTemp: TextView
    private lateinit var tvBottleTempStatus: TextView

    // ── View References: Scanner & Discovered Bottles Section ────────────
    private lateinit var pbScanning: ProgressBar
    private lateinit var btnScanBle: MaterialButton
    private lateinit var layoutBottleControls: LinearLayout
    private lateinit var btnTareScale: MaterialButton
    private lateinit var btnSetCapacity: MaterialButton
    private lateinit var layoutTileWater: LinearLayout
    private lateinit var layoutDiscoveredSection: LinearLayout
    private lateinit var tvDiscoveredHeader: TextView
    private lateinit var layoutEmptyDevices: LinearLayout
    private lateinit var tvEmptyDevicesText: TextView
    private lateinit var rvDiscoveredDevices: RecyclerView

    private lateinit var deviceAdapter: BleDeviceAdapter
    private var bleManager: SipSenseBleManager? = null

    private val firebaseAuth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabaseProvider.reference()

    // ── BLE Permission Launcher ──────────────────────────────────────────
    private val blePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startDeviceScan()
        } else {
            Toast.makeText(
                requireContext(),
                "Bluetooth scan and connect permissions are required to detect your water bottle.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_waterbottle, container, false)

        // Bind Active Bottle Header
        ivActiveBottleIcon = view.findViewById(R.id.iv_active_bottle_icon)
        tvActiveBottleName = view.findViewById(R.id.tv_active_bottle_name)
        vConnectionDot = view.findViewById(R.id.v_connection_dot)
        tvConnectionBadgeText = view.findViewById(R.id.tv_connection_badge_text)

        // Bind Telemetry Metrics
        ivMetricWater = view.findViewById(R.id.iv_metric_water)
        tvBottleWaterLiters = view.findViewById(R.id.tv_bottle_water_liters)
        tvBottleWaterMl = view.findViewById(R.id.tv_bottle_water_ml)

        ivMetricBattery = view.findViewById(R.id.iv_metric_battery)
        tvBottleBattery = view.findViewById(R.id.tv_bottle_battery)
        tvBottleBatteryStatus = view.findViewById(R.id.tv_bottle_battery_status)

        ivMetricTemp = view.findViewById(R.id.iv_metric_temp)
        tvBottleAmbientTemp = view.findViewById(R.id.tv_bottle_ambient_temp)
        tvBottleTempStatus = view.findViewById(R.id.tv_bottle_temp_status)

        // Bind Scanner & Discovered Section
        pbScanning = view.findViewById(R.id.pb_scanning)
        btnScanBle = view.findViewById(R.id.btn_scan_ble)
        layoutBottleControls = view.findViewById(R.id.layout_bottle_controls)
        btnTareScale = view.findViewById(R.id.btn_tare_scale)
        btnSetCapacity = view.findViewById(R.id.btn_set_capacity)
        layoutTileWater = view.findViewById(R.id.layout_tile_water)
        layoutDiscoveredSection = view.findViewById(R.id.layout_discovered_section)
        tvDiscoveredHeader = view.findViewById(R.id.tv_discovered_header)
        layoutEmptyDevices = view.findViewById(R.id.layout_empty_devices)
        tvEmptyDevicesText = view.findViewById(R.id.tv_empty_devices_text)
        rvDiscoveredDevices = view.findViewById(R.id.rv_discovered_devices)

        setupRecyclerView()
        setupListeners()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initBleManager()
    }

    private fun setupRecyclerView() {
        deviceAdapter = BleDeviceAdapter { selectedDevice ->
            if (bleManager?.isConnected == true) {
                Toast.makeText(requireContext(), "Only 1 bottle can be connected at a time.", Toast.LENGTH_SHORT).show()
                return@BleDeviceAdapter
            }
            connectToDiscoveredDevice(selectedDevice)
        }
        rvDiscoveredDevices.layoutManager = LinearLayoutManager(requireContext())
        rvDiscoveredDevices.adapter = deviceAdapter
    }

    private fun setupListeners() {
        // Find Bottle / Stop Scan / Disconnect Button
        btnScanBle.setOnClickListener {
            val manager = bleManager ?: return@setOnClickListener
            if (manager.isConnected) {
                val name = manager.connectedDeviceName ?: "Bottle"
                manager.disconnect()
                Toast.makeText(requireContext(), "Disconnected from $name", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (manager.isScanningActive) {
                manager.stopScan()
            } else {
                checkPermissionsAndScan()
            }
        }

        // Tare Scale Button (zeros out the HX711 load cell)
        btnTareScale.setOnClickListener {
            val manager = bleManager ?: return@setOnClickListener
            if (!manager.isConnected) {
                Toast.makeText(requireContext(), "Connect your bottle first to tare.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val options = arrayOf(
                "⚖️ Zero Empty Base (Tare)\nRemove bottle first to set base to 0g",
                "🚰 Bottle on Base (Set as Full / Refill)\nBottle is full on base -> set to 100% capacity"
            )

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Scale Calibration & Tare")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            val success = manager.sendTareCommand()
                            if (success) {
                                Toast.makeText(requireContext(), "⚖️ Zeroing base... Platform set to 0g.", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(requireContext(), "Failed to send tare command. Ensure ESP32 is flashed & connected.", Toast.LENGTH_LONG).show()
                            }
                        }
                        1 -> {
                            val success = manager.sendCommand("r")
                            if (success) {
                                Toast.makeText(requireContext(), "🚰 Bottle set to full capacity!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(requireContext(), "Failed to send refill command.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Set Bottle Capacity Dialog
        btnSetCapacity.setOnClickListener {
            showSetCapacityDialog()
        }
        layoutTileWater.setOnClickListener {
            showSetCapacityDialog()
        }
    }

    private fun initBleManager() {
        val mainActivity = activity as? MainActivity ?: return
        bleManager = mainActivity.getOrCreateBleManager()

        // Configure callbacks
        bleManager?.addConnectionStateListener(connectionCallback)
        bleManager?.addTelemetryListener(telemetryCallback)

        bleManager?.onDeviceDiscovered = { discovered ->
            activity?.runOnUiThread {
                if (isAdded) {
                    if (bleManager?.isConnected != true) {
                        deviceAdapter.addOrUpdateDevice(discovered)
                        updateDevicesListVisibility()
                    }
                }
            }
        }

        bleManager?.onScanStateChanged = { isScanning ->
            activity?.runOnUiThread {
                if (isAdded) {
                    updateScanUiState(isScanning)
                }
            }
        }

        // Initialize UI with existing state
        val isConn = bleManager?.isConnected == true
        updateConnectionStateUi(isConn, bleManager?.connectedDeviceName)
        updateScanUiState(bleManager?.isScanningActive == true)

        // If devices were already found and not connected, populate adapter
        val existing = bleManager?.discoveredDevices.orEmpty()
        if (existing.isNotEmpty() && !isConn) {
            deviceAdapter.setDevices(existing)
            updateDevicesListVisibility()
        }
    }

    private val connectionCallback: (Boolean, String?) -> Unit = { isConnected, deviceName ->
        activity?.runOnUiThread {
            if (isAdded) {
                updateConnectionStateUi(isConnected, deviceName)
                (activity as? MainActivity)?.updateConnectionStatus(isConnected)
                if (isConnected) {
                    deviceAdapter.setConnectingAddress(null)
                    bleManager?.stopScan()
                    deviceAdapter.clearDevices()
                    updateDevicesListVisibility()
                }
                updateScanUiState(bleManager?.isScanningActive == true)
            }
        }
    }

    private val telemetryCallback: (JSONObject) -> Unit = { json ->
        activity?.runOnUiThread {
            if (isAdded) {
                displayTelemetry(json)
                syncTelemetryToFirebase(json)
            }
        }
    }

    private fun checkPermissionsAndScan() {
        val context = context ?: return
        if (SipSenseBleManager.hasPermissions(context)) {
            startDeviceScan()
        } else {
            blePermissionsLauncher.launch(SipSenseBleManager.getRequiredPermissions())
        }
    }

    private fun startDeviceScan() {
        val manager = bleManager ?: return
        if (manager.isConnected) {
            Toast.makeText(
                requireContext(),
                "Already connected to a bottle. Disconnect to scan again.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        if (!manager.isBluetoothEnabled) {
            Toast.makeText(
                requireContext(),
                "Bluetooth is disabled. Please turn on Bluetooth to scan.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        deviceAdapter.clearDevices()
        updateDevicesListVisibility()
        manager.startScan()
        Toast.makeText(requireContext(), "Searching for nearby bottles...", Toast.LENGTH_SHORT).show()
    }

    private fun connectToDiscoveredDevice(device: DiscoveredBleDevice) {
        val manager = bleManager ?: return
        if (manager.isConnected) {
            Toast.makeText(requireContext(), "Only 1 bottle can be connected at a time.", Toast.LENGTH_SHORT).show()
            return
        }
        deviceAdapter.setConnectingAddress(device.address)
        Toast.makeText(requireContext(), "Connecting to ${device.name}...", Toast.LENGTH_SHORT).show()
        manager.connect(device.device)
    }

    private fun updateScanUiState(isScanning: Boolean) {
        val isConnected = bleManager?.isConnected == true
        if (isConnected) {
            pbScanning.visibility = View.GONE
            btnScanBle.text = getString(R.string.waterbottle_disconnect)
            btnScanBle.icon = null
            btnScanBle.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_weak)
        } else if (isScanning) {
            pbScanning.visibility = View.VISIBLE
            btnScanBle.text = getString(R.string.waterbottle_stop_scan)
            btnScanBle.setIconResource(R.drawable.ic_error)
            btnScanBle.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_fair)
            tvEmptyDevicesText.text = "Searching for nearby bottles..."
        } else {
            pbScanning.visibility = View.GONE
            btnScanBle.text = getString(R.string.waterbottle_scan)
            btnScanBle.setIconResource(R.drawable.ic_bluetooth)
            btnScanBle.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.teal_primary)
            if (deviceAdapter.itemCount == 0) {
                tvEmptyDevicesText.text = getString(R.string.waterbottle_no_devices)
            }
        }
    }

    private fun updateDevicesListVisibility() {
        if (deviceAdapter.itemCount > 0) {
            layoutEmptyDevices.visibility = View.GONE
            rvDiscoveredDevices.visibility = View.VISIBLE
            tvDiscoveredHeader.text = "Discovered Bottles (${deviceAdapter.itemCount})"
        } else {
            layoutEmptyDevices.visibility = View.VISIBLE
            rvDiscoveredDevices.visibility = View.GONE
            tvDiscoveredHeader.text = "Discovered Bottles"
        }
    }

    private fun updateConnectionStateUi(isConnected: Boolean, deviceName: String?) {
        if (isConnected) {
            val name = if (!deviceName.isNullOrBlank()) deviceName else getString(R.string.label_device_name)
            tvActiveBottleName.text = name

            vConnectionDot.setBackgroundResource(R.drawable.bg_circle_green)
            tvConnectionBadgeText.text = getString(R.string.waterbottle_connected)
            tvConnectionBadgeText.setTextColor(ContextCompat.getColor(requireContext(), R.color.teal_primary))

            ivActiveBottleIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.teal_primary)

            // Hide Discovered Bottles section once connected
            layoutDiscoveredSection.visibility = View.GONE

            // Show Bottle Controls (Tare Scale & Set Capacity) when connected
            layoutBottleControls.visibility = View.VISIBLE

            // Automatically sync user's configured bottle capacity with the ESP32 on connect
            val userCapacity = (activity as? MainActivity)?.currentBottleCapacity ?: MainActivity.DEFAULT_BOTTLE_CAPACITY_ML
            bleManager?.sendBottleCapacity(userCapacity)
        } else {
            tvActiveBottleName.text = getString(R.string.label_device_name)

            vConnectionDot.setBackgroundResource(R.drawable.bg_circle_inactive)
            tvConnectionBadgeText.text = getString(R.string.waterbottle_disconnected)
            tvConnectionBadgeText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

            ivActiveBottleIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)

            // Show Discovered Bottles section when disconnected
            layoutDiscoveredSection.visibility = View.VISIBLE

            // Hide Bottle Controls when disconnected
            layoutBottleControls.visibility = View.GONE

            // Reset metric tiles to standby
            ivMetricWater.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
            tvBottleWaterLiters.text = "--"
            tvBottleWaterLiters.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            tvBottleWaterMl.text = "--"

            ivMetricBattery.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
            tvBottleBattery.text = "--"
            tvBottleBattery.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            tvBottleBatteryStatus.text = getString(R.string.ambient_temp_status_waiting)

            ivMetricTemp.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
            tvBottleAmbientTemp.text = "--"
            tvBottleAmbientTemp.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            tvBottleTempStatus.text = getString(R.string.ambient_temp_status_waiting)
        }
        updateScanUiState(bleManager?.isScanningActive == true)
    }

    private fun displayTelemetry(json: JSONObject) {
        val mainAct = activity as? MainActivity
        val defaultCap = mainAct?.currentBottleCapacity ?: MainActivity.DEFAULT_BOTTLE_CAPACITY_ML
        val capacity = json.optInt("bottleCapacity", defaultCap)
        mainAct?.currentBottleCapacity = capacity
        val waterLevel = json.optInt("waterLevel", capacity).coerceIn(0, capacity)
        val isAtRest = json.optBoolean("isBottleAtRest", false)
        mainAct?.currentBottleWaterLevel = waterLevel
        mainAct?.isBottleAtRest = isAtRest
        val battery = json.optInt("batteryLevel", 95)
        val temp = if (json.has("ambientTemperature")) {
            json.optDouble("ambientTemperature", 24.0)
        } else {
            json.optDouble("temperature", 24.0)
        }

        // 1. Water metric (500 ml bottle capacity)
        ivMetricWater.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.teal_primary)
        val liters = waterLevel / 1000.0
        tvBottleWaterLiters.text = String.format(Locale.US, "%.2f L", liters)
        tvBottleWaterLiters.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        tvBottleWaterMl.text = "$waterLevel ml / $capacity ml"

        // 2. Battery metric
        tvBottleBattery.text = "$battery%"
        tvBottleBattery.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        if (battery <= 20) {
            ivMetricBattery.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_weak)
            tvBottleBatteryStatus.text = getString(R.string.battery_status_low)
            tvBottleBatteryStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.strength_weak))
        } else {
            ivMetricBattery.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_strong)
            tvBottleBatteryStatus.text = getString(R.string.battery_status_good)
            tvBottleBatteryStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
        }

        // 3. Temperature metric
        ivMetricTemp.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_fair)
        tvBottleAmbientTemp.text = String.format(Locale.US, "%.1f°C", temp)
        tvBottleAmbientTemp.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
        tvBottleTempStatus.text = when {
            temp < 20 -> getString(R.string.ambient_temp_status_cool)
            temp > 28 -> getString(R.string.ambient_temp_status_warm)
            else      -> getString(R.string.ambient_temp_status_normal)
        }
    }

    private fun syncTelemetryToFirebase(json: JSONObject) {
        val uid = firebaseAuth.currentUser?.uid ?: return
        val capacity = json.optInt("bottleCapacity", (activity as? MainActivity)?.currentBottleCapacity ?: MainActivity.DEFAULT_BOTTLE_CAPACITY_ML)
        val waterLevel = json.optInt("waterLevel", capacity).coerceIn(0, capacity)
        val isAtRest = json.optBoolean("isBottleAtRest", true)
        val ambientTemp = json.optInt("ambientTemperature", json.optInt("temperature", 24))

        val map = HashMap<String, Any>()
        map["waterLevel"] = waterLevel
        map["bottleCapacity"] = capacity
        if (json.has("batteryLevel")) map["batteryLevel"] = json.getInt("batteryLevel")
        if (json.has("ambientTemperature")) map["ambientTemperature"] = json.getDouble("ambientTemperature")
        if (json.has("temperature")) map["temperature"] = json.getDouble("temperature")
        map["isBottleAtRest"] = isAtRest
        if (json.has("lastSipAmount")) map["lastSipAmount"] = json.getInt("lastSipAmount")
        if (json.has("totalConsumedToday")) map["totalConsumedToday"] = json.getInt("totalConsumedToday")
        map["updatedAt"] = System.currentTimeMillis()

        database?.child("users")?.child(uid)?.child("device_telemetry")?.updateChildren(map)

        // Evaluate whether the water volume decreased and count the deducted volume towards Daily Goal
        val mainAct = activity as? MainActivity
        val deductedMl = mainAct?.evaluateWaterDeduction(waterLevel, isAtRest, capacity) ?: 0
        if (deductedMl > 0 && database != null) {
            val now = System.currentTimeMillis()
            val newRecord = SipRecord(
                amount = deductedMl,
                timestamp = now,
                ambientTemperature = ambientTemp
            )
            database.child("users").child(uid).child("sip_records").push().setValue(newRecord)

            val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
            val dailyRef = database.child("users").child(uid).child("daily_totals").child(dateKey)
            dailyRef.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val existing = snapshot.getValue(Int::class.java) ?: 0
                    dailyRef.setValue(existing + deductedMl)
                }
                override fun onCancelled(error: DatabaseError) {}
            })
        }
    }

    /**
     * Dialog for user to customize their water bottle's total fluid capacity.
     */
    private fun showSetCapacityDialog() {
        val ctx = context ?: return
        val currentCap = (activity as? MainActivity)?.currentBottleCapacity ?: MainActivity.DEFAULT_BOTTLE_CAPACITY_ML
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "e.g. 750"
            setText(currentCap.toString())
            setSelection(text.length)
        }

        val container = FrameLayout(ctx).apply {
            val padding = (24 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Set Bottle Capacity")
            .setMessage("Enter your smart water bottle's capacity in ml:")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newCap = input.text.toString().trim().toIntOrNull()
                if (newCap != null && newCap in 100..5000) {
                    saveBottleCapacity(newCap)
                } else {
                    Toast.makeText(ctx, "Please enter a valid capacity between 100 and 5,000 ml", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Presets") { _, _ ->
                showPresetCapacityDialog()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showPresetCapacityDialog() {
        val ctx = context ?: return
        val presets = arrayOf("500 ml (Standard)", "650 ml", "750 ml (Sports)", "1000 ml (1 Liter)", "1200 ml", "1500 ml")
        val values = intArrayOf(500, 650, 750, 1000, 1200, 1500)

        MaterialAlertDialogBuilder(ctx)
            .setTitle("Select Bottle Capacity")
            .setItems(presets) { _, which ->
                saveBottleCapacity(values[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveBottleCapacity(newCapacityMl: Int) {
        val mainAct = activity as? MainActivity
        mainAct?.currentBottleCapacity = newCapacityMl

        // 1. Send capacity command to ESP32 over BLE
        if (bleManager?.isConnected == true) {
            bleManager?.sendBottleCapacity(newCapacityMl)
        }

        // 2. Persist to Firebase Realtime Database across all paths
        val uid = firebaseAuth.currentUser?.uid
        if (uid != null && database != null) {
            val updates = mapOf<String, Any>(
                "bottleCapacity" to newCapacityMl,
                "device_telemetry/bottleCapacity" to newCapacityMl
            )
            database.child("users").child(uid).updateChildren(updates)
        }

        // 3. Update UI
        val currentLevel = mainAct?.currentBottleWaterLevel ?: 0
        tvBottleWaterMl.text = "$currentLevel ml / $newCapacityMl ml"
        val liters = currentLevel / 1000.0
        tvBottleWaterLiters.text = String.format(Locale.US, "%.2f L", liters)
        Toast.makeText(requireContext(), "🍾 Bottle capacity set to $newCapacityMl ml!", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        bleManager?.removeConnectionStateListener(connectionCallback)
        bleManager?.removeTelemetryListener(telemetryCallback)
    }
}
