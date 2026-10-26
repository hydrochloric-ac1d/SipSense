package com.sipsense.app.data

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * SipSenseBleManager.kt
 *
 * Manages the Bluetooth Low Energy (BLE) lifecycle for the SipSense Smart Bottle:
 * - Runtime permission validation
 * - BLE discovery and device scanning (matching SipSense-Bottle)
 * - GATT connection and MTU negotiation (512 bytes for full telemetry JSON)
 * - Enabling live telemetry notifications via GATT CCCD descriptor
 * - Thread-safe dispatch of telemetry data and connection state to the UI thread
 *
 * @project SipSense - Smart Bottle Ecosystem
 */
class SipSenseBleManager(
    private val context: Context,
    private val onConnectionStateChanged: (isConnected: Boolean, deviceName: String?) -> Unit,
    private val onTelemetryReceived: (telemetryJson: JSONObject) -> Unit,
    private val onLogMessage: (message: String) -> Unit = {}
) {

    companion object {
        private const val TAG = "SipSenseBle"

        /**
         * Returns the list of runtime permissions required for BLE on the current Android version.
         */
        fun getRequiredPermissions(): Array<String> {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                arrayOf(
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            }
        }

        /**
         * Checks whether all necessary BLE permissions have been granted by the user.
         */
        fun hasPermissions(context: Context): Boolean {
            return getRequiredPermissions().all { perm ->
                ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private var bleScanner: BluetoothLeScanner? = null

    private var bluetoothGatt: BluetoothGatt? = null
    private var isScanning = false
    private var connectedDeviceName: String? = null

    val isBluetoothEnabled: Boolean
        get() = bluetoothAdapter?.isEnabled == true

    val isConnected: Boolean
        get() = bluetoothGatt != null && connectedDeviceName != null

    val isScanningActive: Boolean
        get() = isScanning

    private val scanTimeoutHandler = Handler(Looper.getMainLooper())
    private val scanTimeoutRunnable = Runnable {
        if (isScanning) {
            Log.i(TAG, "BLE scan timed out after 10s without finding ${BleConstants.DEVICE_NAME}.")
            stopScan()
            Esp32SignalHub.logInfo(
                "BLE_SCAN",
                "Scan timed out after 10s: '${BleConstants.DEVICE_NAME}' not found nearby. Status set to STANDBY."
            )
            Esp32SignalHub.updateDeviceInfo { info ->
                if (info.connectionState == "SCANNING") {
                    info.copy(connectionState = "STANDBY")
                } else {
                    info
                }
            }
        }
    }

    // ── Scanning ─────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasPermissions(context)) {
            Log.w(TAG, "Cannot start BLE scan: permissions not granted.")
            onLogMessage("Bluetooth permissions not granted.")
            return
        }

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Log.w(TAG, "Cannot start BLE scan: Bluetooth adapter is disabled or unavailable.")
            onLogMessage("Bluetooth is disabled. Please enable Bluetooth.")
            return
        }

        if (isScanning) {
            Log.d(TAG, "Scan already in progress.")
            return
        }

        bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) {
            Log.e(TAG, "Failed to get BluetoothLeScanner.")
            return
        }

        val filters = mutableListOf<ScanFilter>()
        // Filter by Service UUID
        filters.add(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
                .build()
        )
        // Also filter by device name for maximum compatibility
        filters.add(
            ScanFilter.Builder()
                .setDeviceName(BleConstants.DEVICE_NAME)
                .build()
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        onLogMessage("Scanning for ${BleConstants.DEVICE_NAME}...")
        Log.i(TAG, "Starting BLE scan for ${BleConstants.DEVICE_NAME}...")
        Esp32SignalHub.logTx(
            "BLE_SCAN",
            "CMD: bleScanner.startScan() [Filter Service: ${BleConstants.SERVICE_UUID}, Name: ${BleConstants.DEVICE_NAME}]"
        )
        Esp32SignalHub.updateDeviceInfo { it.copy(connectionState = "SCANNING") }

        // Automatically stop scanning after 10 seconds if device is not in range
        scanTimeoutHandler.removeCallbacks(scanTimeoutRunnable)
        scanTimeoutHandler.postDelayed(scanTimeoutRunnable, 10000L)

        bleScanner?.startScan(filters, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanTimeoutHandler.removeCallbacks(scanTimeoutRunnable)
        if (!isScanning) return
        isScanning = false
        if (hasPermissions(context)) {
            try {
                bleScanner?.stopScan(scanCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping scan", e)
            }
        }
        Log.i(TAG, "Stopped BLE scan.")
        Esp32SignalHub.logInfo("BLE_SCAN", "CMD: bleScanner.stopScan() [Scan stopped]")
        Esp32SignalHub.updateDeviceInfo { info ->
            if (info.connectionState == "SCANNING") {
                info.copy(connectionState = if (isConnected) "CONNECTED" else "STANDBY")
            } else {
                info
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            scanTimeoutHandler.removeCallbacks(scanTimeoutRunnable)
            val device = result?.device ?: return
            val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown Device"
            val rssiVal = result.rssi

            Log.i(TAG, "Discovered device: $name (${device.address})")
            Esp32SignalHub.logRx(
                "SCAN_RESULT",
                "ADV: Discovered '$name' (${device.address}) RSSI=$rssiVal dBm"
            )
            Esp32SignalHub.updateDeviceInfo { info ->
                info.copy(
                    deviceName = name,
                    macAddress = device.address,
                    rssi = rssiVal,
                    connectionState = "CONNECTING"
                )
            }

            // Stop scanning and connect
            stopScan()
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            Log.e(TAG, "BLE Scan failed with error code: $errorCode")
            onLogMessage("Scan failed (error $errorCode)")
            Esp32SignalHub.logError("BLE_SCAN", "ERR: Scan failed with error code $errorCode")
            Esp32SignalHub.updateDeviceInfo { it.copy(connectionState = "SCAN_FAILED") }
        }
    }

    // ── Connection & GATT Callbacks ──────────────────────────────────────

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasPermissions(context)) return

        disconnect()

        val name = device.name ?: BleConstants.DEVICE_NAME
        onLogMessage("Connecting to $name...")
        Log.i(TAG, "Connecting to GATT on ${device.address}...")

        Esp32SignalHub.logTx(
            "GATT_CONNECT",
            "CMD: device.connectGatt(address=${device.address}, autoConnect=false)"
        )
        Esp32SignalHub.updateDeviceInfo { info ->
            info.copy(
                deviceName = name,
                macAddress = device.address,
                connectionState = "CONNECTING"
            )
        }

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        try {
            Esp32SignalHub.logTx("GATT_DISCONNECT", "CMD: gatt.disconnect() & close()")
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting GATT", e)
        } finally {
            bluetoothGatt = null
            connectedDeviceName = null
            Esp32SignalHub.updateDeviceInfo { it.copy(connectionState = "DISCONNECTED") }
            notifyConnectionState(false, null)
        }
    }

    /**
     * Issues an explicit GATT characteristic read request to the ESP32.
     * Logs the TX request and handles the RX response in onCharacteristicRead.
     */
    @SuppressLint("MissingPermission")
    fun requestTelemetryRead(): Boolean {
        val gatt = bluetoothGatt ?: run {
            Esp32SignalHub.logError("GATT_READ", "Cannot read: GATT is not connected")
            return false
        }
        val service = gatt.getService(BleConstants.SERVICE_UUID) ?: run {
            Esp32SignalHub.logError("GATT_READ", "SipSense Service not found on peripheral")
            return false
        }
        val characteristic = service.getCharacteristic(BleConstants.TELEMETRY_CHAR_UUID) ?: run {
            Esp32SignalHub.logError("GATT_READ", "Telemetry characteristic not found")
            return false
        }

        Esp32SignalHub.logTx(
            "GATT_READ",
            "CMD: gatt.readCharacteristic(UUID=${BleConstants.TELEMETRY_CHAR_UUID})"
        )
        return gatt.readCharacteristic(characteristic)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDeviceName = gatt.device.name ?: BleConstants.DEVICE_NAME
                Log.i(TAG, "✅ Connected to GATT server on $connectedDeviceName")
                onLogMessage("Connected to $connectedDeviceName. Requesting MTU...")

                Esp32SignalHub.logRx(
                    "GATT_STATE",
                    "EVT: STATE_CONNECTED (device='${gatt.device.name}', address=${gatt.device.address}, status=$status)"
                )
                Esp32SignalHub.updateDeviceInfo { info ->
                    info.copy(
                        deviceName = connectedDeviceName ?: info.deviceName,
                        macAddress = gatt.device.address,
                        connectionState = "CONNECTED"
                    )
                }

                // Request larger MTU size (512 bytes) to accommodate JSON telemetry strings
                Esp32SignalHub.logTx("GATT_MTU", "CMD: gatt.requestMtu(512)")
                gatt.requestMtu(512)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(TAG, "❌ Disconnected from GATT server.")
                onLogMessage("Disconnected from device.")
                Esp32SignalHub.logRx(
                    "GATT_STATE",
                    "EVT: STATE_DISCONNECTED (status=$status)"
                )
                Esp32SignalHub.updateDeviceInfo { it.copy(connectionState = "DISCONNECTED") }
                connectedDeviceName = null
                gatt.close()
                bluetoothGatt = null
                notifyConnectionState(false, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU changed to $mtu (status: $status). Discovering services...")
            Esp32SignalHub.logRx(
                "GATT_MTU",
                "EVT: onMtuChanged(mtu=$mtu, status=${if (status == BluetoothGatt.GATT_SUCCESS) "SUCCESS" else "FAIL_$status"})"
            )
            Esp32SignalHub.updateDeviceInfo { it.copy(mtuSize = mtu) }

            Esp32SignalHub.logTx("GATT_DISCOVERY", "CMD: gatt.discoverServices()")
            gatt.discoverServices()
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed with status $status")
                Esp32SignalHub.logError("GATT_DISCOVERY", "ERR: Service discovery failed with status $status")
                return
            }

            Esp32SignalHub.logRx("GATT_DISCOVERY", "EVT: Services discovered successfully (count=${gatt.services.size})")

            val service = gatt.getService(BleConstants.SERVICE_UUID)
            if (service == null) {
                Log.e(TAG, "SipSense Service UUID (${BleConstants.SERVICE_UUID}) not found on device!")
                Esp32SignalHub.logError("GATT_SERVICE", "ERR: SipSense Service UUID ${BleConstants.SERVICE_UUID} not found")
                return
            }

            val characteristic = service.getCharacteristic(BleConstants.TELEMETRY_CHAR_UUID)
            if (characteristic == null) {
                Log.e(TAG, "Telemetry Characteristic (${BleConstants.TELEMETRY_CHAR_UUID}) not found!")
                Esp32SignalHub.logError("GATT_CHAR", "ERR: Telemetry Characteristic not found")
                return
            }

            // Enable local notifications for this characteristic
            val notificationSet = gatt.setCharacteristicNotification(characteristic, true)
            Log.i(TAG, "Characteristic notification set locally: $notificationSet")
            Esp32SignalHub.logGatt("GATT_NOTIFY", "Local characteristic notification enabled=$notificationSet")

            // Write to the Client Characteristic Configuration Descriptor (CCCD) on the ESP32
            val descriptor = characteristic.getDescriptor(BleConstants.CCCD_UUID)
            if (descriptor != null) {
                Esp32SignalHub.logTx(
                    "GATT_CCCD",
                    "CMD: writeDescriptor(CCCD: 0x0001 ENABLE_NOTIFICATION on ${BleConstants.TELEMETRY_CHAR_UUID})"
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                Log.i(TAG, "CCCD descriptor written to subscribe to notifications.")
            } else {
                Log.w(TAG, "CCCD descriptor was null on characteristic.")
                Esp32SignalHub.logError("GATT_CCCD", "ERR: CCCD descriptor was null on telemetry characteristic")
            }

            notifyConnectionState(true, connectedDeviceName)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val statusStr = if (status == BluetoothGatt.GATT_SUCCESS) "SUCCESS" else "FAIL_$status"
            Esp32SignalHub.logRx(
                "GATT_CCCD",
                "EVT: onDescriptorWrite (UUID=${descriptor.uuid}, status=$statusStr)"
            )
        }

        // For Android 12 and lower
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("DEPRECATION")
            val data = characteristic.value ?: return
            handleIncomingBytes(data)
        }

        // For Android 13+ (API 33+)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleIncomingBytes(value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                @Suppress("DEPRECATION")
                val data = characteristic.value ?: return
                val text = String(data, Charsets.UTF_8).trim()
                Esp32SignalHub.logRx(
                    "GATT_READ_OK",
                    "EVT: onCharacteristicRead [${data.size} bytes]: $text",
                    rawPayload = text
                )
                handleIncomingBytes(data)
            } else {
                Esp32SignalHub.logError("GATT_READ_ERR", "EVT: onCharacteristicRead failed with status $status")
            }
        }
    }

    private fun handleIncomingBytes(bytes: ByteArray) {
        try {
            val jsonString = String(bytes, Charsets.UTF_8).trim()
            Log.d(TAG, "📥 BLE Telemetry received: $jsonString")
            Esp32SignalHub.logRx(
                "TELEMETRY",
                "RX: JSON Telemetry Notification [${bytes.size} bytes]: $jsonString",
                rawPayload = jsonString
            )
            val jsonObject = JSONObject(jsonString)

            mainHandler.post {
                onTelemetryReceived(jsonObject)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse incoming BLE telemetry bytes as JSON", e)
            Esp32SignalHub.logError("TELEMETRY", "Failed to parse incoming BLE telemetry as JSON: ${e.message}")
        }
    }

    private fun notifyConnectionState(isConnected: Boolean, deviceName: String?) {
        mainHandler.post {
            onConnectionStateChanged(isConnected, deviceName)
        }
    }
}
