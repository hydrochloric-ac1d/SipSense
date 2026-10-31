package com.sipsense.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import com.google.firebase.auth.FirebaseAuth
import com.sipsense.app.adapter.BleDeviceAdapter
import com.sipsense.app.data.DiscoveredBleDevice
import com.sipsense.app.data.FirebaseDatabaseProvider
import com.sipsense.app.data.SipSenseBleManager
import org.json.JSONObject
import java.util.Locale

/**
 * WaterbottleFragment.kt
 *
 * Dedicated Water Bottle Tab in the SipSense ecosystem:
 * - Scans nearby BLE signals from the ESP32 smart water bottle without auto-connecting
 * - Allows the user to manually select and connect to discovered ESP32 signals
 * - Provides an explicit Disconnect option
 * - Displays ESP32 Hardware Status LED State (Blinking = Advertising, Steady = Connected)
 * - Streams live telemetry (Water Level in Liters/ml, Battery %, Ambient Temperature, RSSI)
 *
 * @project SipSense - Smart Bottle Ecosystem
 */
open class WaterbottleFragment : Fragment() {

    // ── View References: Active Bottle Card ──────────────────────────────
    private lateinit var ivActiveBottleIcon: ImageView
    private lateinit var tvActiveBottleName: TextView
    private lateinit var tvActiveBottleAddress: TextView
    private lateinit var vConnectionDot: View
    private lateinit var tvConnectionBadgeText: TextView
    private lateinit var tvEsp32LedStatus: TextView

    private lateinit var ivMetricWater: ImageView
    private lateinit var tvBottleWaterLiters: TextView
    private lateinit var tvBottleWaterMl: TextView

    private lateinit var ivMetricBattery: ImageView
    private lateinit var tvBottleBattery: TextView
    private lateinit var tvBottleBatteryStatus: TextView

    private lateinit var ivMetricTemp: ImageView
    private lateinit var tvBottleAmbientTemp: TextView
    private lateinit var tvBottleTempStatus: TextView

    private lateinit var layoutConnectedSignal: LinearLayout
    private lateinit var tvConnectedRssi: TextView
    private lateinit var btnDisconnectBottle: MaterialButton

    // ── View References: Scanner Card ────────────────────────────────────
    private lateinit var pbScanning: ProgressBar
    private lateinit var btnScanBle: MaterialButton
    private lateinit var tvDiscoveredHeader: TextView
    private lateinit var layoutEmptyDevices: LinearLayout
    private lateinit var tvEmptyDevicesText: TextView
    private lateinit var rvDiscoveredDevices: RecyclerView

    private lateinit var deviceAdapter: BleDeviceAdapter
    private var bleManager: SipSenseBleManager? = null

    private val firebaseAuth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabaseProvider.reference()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var rssiPollRunnable: Runnable? = null

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

        // Bind Active Bottle Card
        ivActiveBottleIcon = view.findViewById(R.id.iv_active_bottle_icon)
        tvActiveBottleName = view.findViewById(R.id.tv_active_bottle_name)
        tvActiveBottleAddress = view.findViewById(R.id.tv_active_bottle_address)
        vConnectionDot = view.findViewById(R.id.v_connection_dot)
        tvConnectionBadgeText = view.findViewById(R.id.tv_connection_badge_text)
        tvEsp32LedStatus = view.findViewById(R.id.tv_esp32_led_status)

        ivMetricWater = view.findViewById(R.id.iv_metric_water)
        tvBottleWaterLiters = view.findViewById(R.id.tv_bottle_water_liters)
        tvBottleWaterMl = view.findViewById(R.id.tv_bottle_water_ml)

        ivMetricBattery = view.findViewById(R.id.iv_metric_battery)
        tvBottleBattery = view.findViewById(R.id.tv_bottle_battery)
        tvBottleBatteryStatus = view.findViewById(R.id.tv_bottle_battery_status)

        ivMetricTemp = view.findViewById(R.id.iv_metric_temp)
        tvBottleAmbientTemp = view.findViewById(R.id.tv_bottle_ambient_temp)
        tvBottleTempStatus = view.findViewById(R.id.tv_bottle_temp_status)

        layoutConnectedSignal = view.findViewById(R.id.layout_connected_signal)
        tvConnectedRssi = view.findViewById(R.id.tv_connected_rssi)
        btnDisconnectBottle = view.findViewById(R.id.btn_disconnect_bottle)

        // Bind Scanner Card
        pbScanning = view.findViewById(R.id.pb_scanning)
        btnScanBle = view.findViewById(R.id.btn_scan_ble)
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
            connectToDiscoveredDevice(selectedDevice)
        }
        rvDiscoveredDevices.layoutManager = LinearLayoutManager(requireContext())
        rvDiscoveredDevices.adapter = deviceAdapter
    }

    private fun setupListeners() {
        // Scan Button: Starts or stops BLE scanning
        btnScanBle.setOnClickListener {
            val manager = bleManager ?: return@setOnClickListener
            if (manager.isScanningActive) {
                manager.stopScan()
            } else {
                checkPermissionsAndScan()
            }
        }

        // Disconnect Button: Manually disconnects the connected bottle
        btnDisconnectBottle.setOnClickListener {
            bleManager?.let { manager ->
                if (manager.isConnected) {
                    val name = manager.connectedDeviceName ?: "Device"
                    manager.disconnect()
                    Toast.makeText(requireContext(), "Disconnected from $name", Toast.LENGTH_SHORT).show()
                }
            }
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
                    deviceAdapter.addOrUpdateDevice(discovered)
                    updateDevicesListVisibility()
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

        bleManager?.onRssiUpdated = { rssi ->
            activity?.runOnUiThread {
                if (isAdded && bleManager?.isConnected == true) {
                    updateSignalIndicator(rssi)
                }
            }
        }

        // Initialize UI with existing state
        val isConn = bleManager?.isConnected == true
        updateConnectionStateUi(isConn, bleManager?.connectedDeviceName)
        updateScanUiState(bleManager?.isScanningActive == true)

        // If devices were already found, populate adapter
        val existing = bleManager?.discoveredDevices.orEmpty()
        if (existing.isNotEmpty()) {
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
                    startRssiPolling()
                } else {
                    stopRssiPolling()
                }
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
        Toast.makeText(requireContext(), "Scanning for nearby ESP32 water bottles...", Toast.LENGTH_SHORT).show()
    }

    private fun connectToDiscoveredDevice(device: DiscoveredBleDevice) {
        val manager = bleManager ?: return
        deviceAdapter.setConnectingAddress(device.address)
        Toast.makeText(requireContext(), "Connecting to ${device.name}...", Toast.LENGTH_SHORT).show()
        manager.connect(device.device)
    }

    private fun updateScanUiState(isScanning: Boolean) {
        if (isScanning) {
            pbScanning.visibility = View.VISIBLE
            btnScanBle.text = getString(R.string.waterbottle_stop_scan)
            btnScanBle.setIconResource(R.drawable.ic_error)
            btnScanBle.backgroundTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_fair)
            tvEmptyDevicesText.text = "Scanning nearby BLE signals...\nESP32 built-in LED blinks while broadcasting."
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
            tvDiscoveredHeader.text = "Discovered ESP32 Signals (${deviceAdapter.itemCount})"
        } else {
            layoutEmptyDevices.visibility = View.VISIBLE
            rvDiscoveredDevices.visibility = View.GONE
            tvDiscoveredHeader.text = "Discovered ESP32 Signals"
        }
    }

    private fun updateConnectionStateUi(isConnected: Boolean, deviceName: String?) {
        if (isConnected) {
            val name = if (!deviceName.isNullOrBlank()) deviceName else getString(R.string.label_device_name)
            val address = bleManager?.connectedDeviceAddress ?: "ESP32 DevKit"

            tvActiveBottleName.text = name
            tvActiveBottleAddress.text = "Model SB-01 • $address"

            vConnectionDot.setBackgroundResource(R.drawable.bg_circle_green)
            tvConnectionBadgeText.text = getString(R.string.waterbottle_connected)
            tvConnectionBadgeText.setTextColor(ContextCompat.getColor(requireContext(), R.color.teal_primary))

            // ESP32 LED Status Indicator: Steady ON when connected
            tvEsp32LedStatus.text = getString(R.string.waterbottle_led_steady_hint)

            ivActiveBottleIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.teal_primary)
            btnDisconnectBottle.visibility = View.VISIBLE

            // Request initial RSSI
            bleManager?.readRssi()
        } else {
            tvActiveBottleName.text = getString(R.string.label_device_name)
            tvActiveBottleAddress.text = "ESP32 IoT Bottle • Disconnected"

            vConnectionDot.setBackgroundResource(R.drawable.bg_circle_inactive)
            tvConnectionBadgeText.text = getString(R.string.waterbottle_disconnected)
            tvConnectionBadgeText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))

            // ESP32 LED Status Indicator: Blinking while broadcasting
            tvEsp32LedStatus.text = getString(R.string.waterbottle_led_blinking_hint)

            ivActiveBottleIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
            layoutConnectedSignal.visibility = View.GONE
            btnDisconnectBottle.visibility = View.GONE

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
    }

    private fun displayTelemetry(json: JSONObject) {
        val waterLevel = json.optInt("waterLevel", 750)
        val capacity = json.optInt("bottleCapacity", 1000)
        val battery = json.optInt("batteryLevel", 95)
        val temp = if (json.has("ambientTemperature")) {
            json.optDouble("ambientTemperature", 24.0)
        } else {
            json.optDouble("temperature", 24.0)
        }

        // 1. Water metric
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

    private fun updateSignalIndicator(rssi: Int) {
        layoutConnectedSignal.visibility = View.VISIBLE
        val quality = when {
            rssi >= -60 -> "Excellent Signal"
            rssi >= -75 -> "Good Signal"
            rssi >= -85 -> "Fair Signal"
            else        -> "Weak Signal"
        }
        tvConnectedRssi.text = "Signal: $rssi dBm • $quality"
    }

    private fun startRssiPolling() {
        stopRssiPolling()
        rssiPollRunnable = object : Runnable {
            override fun run() {
                if (isAdded && bleManager?.isConnected == true) {
                    bleManager?.readRssi()
                    mainHandler.postDelayed(this, 3000L)
                }
            }
        }
        rssiPollRunnable?.let { mainHandler.postDelayed(it, 3000L) }
    }

    private fun stopRssiPolling() {
        rssiPollRunnable?.let { mainHandler.removeCallbacks(it) }
        rssiPollRunnable = null
    }

    private fun syncTelemetryToFirebase(json: JSONObject) {
        val uid = firebaseAuth.currentUser?.uid ?: return
        val map = HashMap<String, Any>()
        if (json.has("waterLevel")) map["waterLevel"] = json.getInt("waterLevel")
        if (json.has("bottleCapacity")) map["bottleCapacity"] = json.getInt("bottleCapacity")
        if (json.has("batteryLevel")) map["batteryLevel"] = json.getInt("batteryLevel")
        if (json.has("ambientTemperature")) map["ambientTemperature"] = json.getDouble("ambientTemperature")
        if (json.has("temperature")) map["temperature"] = json.getDouble("temperature")
        if (json.has("isBottleAtRest")) map["isBottleAtRest"] = json.getBoolean("isBottleAtRest")
        if (json.has("lastSipAmount")) map["lastSipAmount"] = json.getInt("lastSipAmount")
        if (json.has("totalConsumedToday")) map["totalConsumedToday"] = json.getInt("totalConsumedToday")
        map["updatedAt"] = System.currentTimeMillis()

        database?.child("users")?.child(uid)?.child("device_telemetry")?.updateChildren(map)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopRssiPolling()
        bleManager?.removeConnectionStateListener(connectionCallback)
        bleManager?.removeTelemetryListener(telemetryCallback)
    }
}
