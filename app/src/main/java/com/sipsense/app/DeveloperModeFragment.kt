package com.sipsense.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.sipsense.app.data.Esp32SignalHub
import com.sipsense.app.data.Esp32SignalHub.SignalDirection
import com.sipsense.app.data.Esp32SignalHub.SignalLogEntry

/**
 * DeveloperModeFragment.kt
 *
 * Secret Developer Mode restricted strictly to testuser1@sipsense.com.
 *
 * Features:
 * 1. Live Terminal View:
 *    - Real-time display of send (TX) and receive (RX) Bluetooth signals to/from the ESP32.
 *    - Color-coded monospace log lines (TX: Cyan, RX: Lime Green, GATT: Amber, ERROR: Red).
 *    - Filter by signal direction (ALL, TX, RX, GATT).
 *    - Manual signal transmission tools: Send Ping (TX), Simulate Sip (RX).
 *    - Terminal management: Clear output, Copy logs to clipboard, Auto-scroll.
 *
 * 2. ESP32 Hardware Details (Read-Only):
 *    - Displays hardware serial number, firmware version, flash code / IC, silicon model,
 *      CPU architecture, BLE MAC address, GATT UUIDs, MTU size, and pinout assignments.
 *    - Strictly Read-Only: all edit capabilities are prohibited.
 *
 * Security:
 * - Access is validated against the authenticated Firebase user email.
 * - Non-authorized users are immediately dismissed back to the main navigation.
 *
 * @project SipSense - Smart Bottle Ecosystem
 */
class DeveloperModeFragment : Fragment() {

    companion object {
        const val AUTHORIZED_EMAIL = "testuser1@sipsense.com"

        fun isUserAuthorized(): Boolean {
            val email = FirebaseAuth.getInstance().currentUser?.email
            return email?.equals(AUTHORIZED_EMAIL, ignoreCase = true) == true
        }
    }

    // ── View References ──────────────────────────────────────────────────
    private lateinit var btnBack: ImageView
    private lateinit var tabTerminal: TextView
    private lateinit var tabDetails: TextView
    private lateinit var layoutTerminalTab: View
    private lateinit var layoutDetailsTab: View
    private lateinit var tvStatusPill: TextView

    // Terminal views
    private lateinit var btnScanBle: MaterialButton
    private lateinit var btnSendPing: MaterialButton
    private lateinit var btnSimulateSip: MaterialButton
    private lateinit var btnClearTerminal: MaterialButton
    private lateinit var btnCopyLogs: MaterialButton
    private lateinit var filterAll: TextView
    private lateinit var filterTx: TextView
    private lateinit var filterRx: TextView
    private lateinit var tvSignalCounter: TextView
    private lateinit var scrollTerminal: ScrollView
    private lateinit var tvTerminalOutput: TextView

    // Hardware Details views (Read-Only)
    private lateinit var tvDetailDeviceName: TextView
    private lateinit var tvDetailSerialNumber: TextView
    private lateinit var tvDetailFirmwareVer: TextView
    private lateinit var tvDetailFlashCode: TextView
    private lateinit var tvDetailChipModel: TextView
    private lateinit var tvDetailCpuArch: TextView
    private lateinit var tvDetailMacAddress: TextView
    private lateinit var tvDetailServiceUuid: TextView
    private lateinit var tvDetailCharUuid: TextView
    private lateinit var tvDetailCccdUuid: TextView
    private lateinit var tvDetailMtuSize: TextView
    private lateinit var tvDetailConnectionState: TextView
    private lateinit var tvDetailRssi: TextView
    private lateinit var tvDetailHx711: TextView
    private lateinit var tvDetailDht22: TextView
    private lateinit var tvDetailBatteryAdc: TextView
    private lateinit var tvDetailStatusLed: TextView

    // Active filter: null = ALL, otherwise specific SignalDirection
    private var activeFilter: SignalDirection? = null

    // Listeners
    private val signalListener: (SignalLogEntry) -> Unit = { entry ->
        if (isAdded) {
            updateTerminalWithNewSignal(entry)
        }
    }

    private val detailsListener: (Esp32SignalHub.Esp32DeviceInfo) -> Unit = { info ->
        if (isAdded) {
            bindDeviceInfo(info)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Enforce strict security guard: testuser1@sipsense.com only
        if (!isUserAuthorized()) {
            Toast.makeText(
                requireContext(),
                getString(R.string.dev_mode_restricted_toast),
                Toast.LENGTH_LONG
            ).show()
            parentFragmentManager.popBackStack()
            return null
        }

        val view = inflater.inflate(R.layout.fragment_developer_mode, container, false)
        initializeViews(view)
        setupTabSwitching()
        setupFilters()
        setupActionButtons()
        renderAllLogs()

        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Synchronize state with active BLE manager
        val ble = (activity as? MainActivity)?.bleManager
        if (ble != null) {
            if (ble.isConnected) {
                Esp32SignalHub.updateDeviceInfo { it.copy(connectionState = "CONNECTED") }
            } else if (ble.isScanningActive) {
                Esp32SignalHub.updateDeviceInfo { it.copy(connectionState = "SCANNING") }
            } else {
                Esp32SignalHub.updateDeviceInfo { 
                    if (it.connectionState == "SCANNING") it.copy(connectionState = "STANDBY") else it 
                }
            }
        } else {
            Esp32SignalHub.updateDeviceInfo { 
                if (it.connectionState == "SCANNING") it.copy(connectionState = "STANDBY") else it 
            }
        }

        Esp32SignalHub.addSignalListener(signalListener)
        Esp32SignalHub.addDeviceInfoListener(detailsListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        Esp32SignalHub.removeSignalListener(signalListener)
        Esp32SignalHub.removeDeviceInfoListener(detailsListener)
    }

    private fun initializeViews(view: View) {
        btnBack = view.findViewById(R.id.btn_dev_back)
        tabTerminal = view.findViewById(R.id.tab_signals_terminal)
        tabDetails = view.findViewById(R.id.tab_esp32_details)
        layoutTerminalTab = view.findViewById(R.id.layout_terminal_tab)
        layoutDetailsTab = view.findViewById(R.id.layout_details_tab)
        tvStatusPill = view.findViewById(R.id.tv_dev_status_pill)

        // Terminal views
        btnScanBle = view.findViewById(R.id.btn_scan_ble)
        btnSendPing = view.findViewById(R.id.btn_send_ping)
        btnSimulateSip = view.findViewById(R.id.btn_simulate_sip)
        btnClearTerminal = view.findViewById(R.id.btn_clear_terminal)
        btnCopyLogs = view.findViewById(R.id.btn_copy_logs)
        filterAll = view.findViewById(R.id.filter_all)
        filterTx = view.findViewById(R.id.filter_tx)
        filterRx = view.findViewById(R.id.filter_rx)
        tvSignalCounter = view.findViewById(R.id.tv_signal_counter)
        scrollTerminal = view.findViewById(R.id.scroll_terminal)
        tvTerminalOutput = view.findViewById(R.id.tv_terminal_output)

        // Read-only Hardware Details views
        tvDetailDeviceName = view.findViewById(R.id.tv_detail_device_name)
        tvDetailSerialNumber = view.findViewById(R.id.tv_detail_serial_number)
        tvDetailFirmwareVer = view.findViewById(R.id.tv_detail_firmware_ver)
        tvDetailFlashCode = view.findViewById(R.id.tv_detail_flash_code)
        tvDetailChipModel = view.findViewById(R.id.tv_detail_chip_model)
        tvDetailCpuArch = view.findViewById(R.id.tv_detail_cpu_arch)
        tvDetailMacAddress = view.findViewById(R.id.tv_detail_mac_address)
        tvDetailServiceUuid = view.findViewById(R.id.tv_detail_service_uuid)
        tvDetailCharUuid = view.findViewById(R.id.tv_detail_char_uuid)
        tvDetailCccdUuid = view.findViewById(R.id.tv_detail_cccd_uuid)
        tvDetailMtuSize = view.findViewById(R.id.tv_detail_mtu_size)
        tvDetailConnectionState = view.findViewById(R.id.tv_detail_connection_state)
        tvDetailRssi = view.findViewById(R.id.tv_detail_rssi)
        tvDetailHx711 = view.findViewById(R.id.tv_detail_hx711)
        tvDetailDht22 = view.findViewById(R.id.tv_detail_dht22)
        tvDetailBatteryAdc = view.findViewById(R.id.tv_detail_battery_adc)
        tvDetailStatusLed = view.findViewById(R.id.tv_detail_status_led)

        btnBack.setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupTabSwitching() {
        tabTerminal.setOnClickListener {
            selectTab(isTerminal = true)
        }

        tabDetails.setOnClickListener {
            selectTab(isTerminal = false)
        }
    }

    private fun selectTab(isTerminal: Boolean) {
        if (isTerminal) {
            tabTerminal.setBackgroundResource(R.drawable.bg_chip_selected)
            tabTerminal.setTextColor(Color.WHITE)
            tabDetails.setBackgroundResource(R.drawable.bg_chip_unselected)
            tabDetails.setTextColor(Color.parseColor("#94A3B8"))

            layoutTerminalTab.visibility = View.VISIBLE
            layoutDetailsTab.visibility = View.GONE
        } else {
            tabDetails.setBackgroundResource(R.drawable.bg_chip_selected)
            tabDetails.setTextColor(Color.WHITE)
            tabTerminal.setBackgroundResource(R.drawable.bg_chip_unselected)
            tabTerminal.setTextColor(Color.parseColor("#94A3B8"))

            layoutTerminalTab.visibility = View.GONE
            layoutDetailsTab.visibility = View.VISIBLE
        }
    }

    private fun setupFilters() {
        filterAll.setOnClickListener {
            activeFilter = null
            updateFilterChipUI()
            renderAllLogs()
        }

        filterTx.setOnClickListener {
            activeFilter = SignalDirection.TX
            updateFilterChipUI()
            renderAllLogs()
        }

        filterRx.setOnClickListener {
            activeFilter = SignalDirection.RX
            updateFilterChipUI()
            renderAllLogs()
        }
    }

    private fun updateFilterChipUI() {
        val selectedBg = R.drawable.bg_chip_selected
        val unselectedBg = R.drawable.bg_chip_unselected
        val activeTextColor = Color.WHITE
        val inactiveTextColor = Color.parseColor("#94A3B8")

        filterAll.setBackgroundResource(if (activeFilter == null) selectedBg else unselectedBg)
        filterAll.setTextColor(if (activeFilter == null) activeTextColor else inactiveTextColor)

        filterTx.setBackgroundResource(if (activeFilter == SignalDirection.TX) selectedBg else unselectedBg)
        filterTx.setTextColor(if (activeFilter == SignalDirection.TX) activeTextColor else inactiveTextColor)

        filterRx.setBackgroundResource(if (activeFilter == SignalDirection.RX) selectedBg else unselectedBg)
        filterRx.setTextColor(if (activeFilter == SignalDirection.RX) activeTextColor else inactiveTextColor)
    }

    private fun setupActionButtons() {
        // Scan / Stop Scan BLE
        btnScanBle.setOnClickListener {
            val mainActivity = activity as? MainActivity
            val bleManager = mainActivity?.bleManager
            if (bleManager != null) {
                if (bleManager.isScanningActive) {
                    bleManager.stopScan()
                    Toast.makeText(context, "Stopped BLE scan", Toast.LENGTH_SHORT).show()
                } else {
                    bleManager.startScan()
                    Toast.makeText(context, "Scanning for SipSense-Bottle (10s timeout)...", Toast.LENGTH_SHORT).show()
                }
            } else {
                val appCtx = context?.applicationContext
                if (appCtx != null) {
                    val manager = com.sipsense.app.data.SipSenseBleManager(
                        context = appCtx,
                        onConnectionStateChanged = { _, _ -> },
                        onTelemetryReceived = { _ -> }
                    )
                    mainActivity?.bleManager = manager
                    manager.startScan()
                    Toast.makeText(context, "Scanning for SipSense-Bottle (10s timeout)...", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // Send Ping (TX)
        btnSendPing.setOnClickListener {
            val mainActivity = activity as? MainActivity
            val bleManager = mainActivity?.bleManager
            if (bleManager != null && bleManager.isConnected) {
                val sent = bleManager.requestTelemetryRead()
                if (sent) {
                    Toast.makeText(context, "Sent GATT Read Request (TX)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Read request queued", Toast.LENGTH_SHORT).show()
                }
            } else {
                // If physical device is not currently connected, trigger simulated ping exchange
                Esp32SignalHub.simulatePingExchange()
                Toast.makeText(context, "Ping transmitted (TX ▶)", Toast.LENGTH_SHORT).show()
            }
        }

        // Simulate Sip (RX)
        btnSimulateSip.setOnClickListener {
            Esp32SignalHub.simulateSipEvent(amountMl = 35)
            Toast.makeText(context, "Simulated sip packet received (RX ◀)", Toast.LENGTH_SHORT).show()
        }

        // Clear Terminal
        btnClearTerminal.setOnClickListener {
            Esp32SignalHub.clearLogs()
            renderAllLogs()
            Toast.makeText(context, "Terminal cleared", Toast.LENGTH_SHORT).show()
        }

        // Copy Logs
        btnCopyLogs.setOnClickListener {
            val logs = Esp32SignalHub.getLogs()
            val textToCopy = logs.joinToString("\n") { it.toConsoleString() }
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("ESP32_Terminal_Logs", textToCopy)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied ${logs.size} log entries to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Log Rendering & Terminal Formatting ──────────────────────────────

    private fun renderAllLogs() {
        val allLogs = Esp32SignalHub.getLogs()
        val txCount = allLogs.count { it.direction == SignalDirection.TX }
        val rxCount = allLogs.count { it.direction == SignalDirection.RX }
        tvSignalCounter.text = "TX: $txCount | RX: $rxCount | TOTAL: ${allLogs.size}"

        val filteredLogs = if (activeFilter == null) {
            allLogs
        } else {
            allLogs.filter { it.direction == activeFilter }
        }

        if (filteredLogs.isEmpty()) {
            tvTerminalOutput.text = getString(R.string.dev_mode_terminal_empty)
            return
        }

        val builder = SpannableStringBuilder()
        for (log in filteredLogs) {
            appendLogSpan(builder, log)
        }

        tvTerminalOutput.text = builder
        scrollToBottom()
    }

    private fun updateTerminalWithNewSignal(entry: SignalLogEntry) {
        val allLogs = Esp32SignalHub.getLogs()
        val txCount = allLogs.count { it.direction == SignalDirection.TX }
        val rxCount = allLogs.count { it.direction == SignalDirection.RX }
        tvSignalCounter.text = "TX: $txCount | RX: $rxCount | TOTAL: ${allLogs.size}"

        // Check filter
        if (activeFilter != null && entry.direction != activeFilter) {
            return
        }

        val currentText = tvTerminalOutput.text
        val builder = if (currentText is SpannableStringBuilder) {
            currentText
        } else {
            SpannableStringBuilder(if (currentText.toString() == getString(R.string.dev_mode_terminal_empty)) "" else currentText)
        }

        appendLogSpan(builder, entry)
        tvTerminalOutput.text = builder
        scrollToBottom()
    }

    private fun appendLogSpan(builder: SpannableStringBuilder, entry: SignalLogEntry) {
        val colorSkyBlue = Color.parseColor("#38BDF8")  // TX
        val colorEmerald = Color.parseColor("#4ADE80")  // RX
        val colorAmber = Color.parseColor("#FBBF24")    // GATT
        val colorCoral = Color.parseColor("#F87171")    // ERROR
        val colorSlate = Color.parseColor("#94A3B8")    // INFO / Timestamp

        val start = builder.length

        // Timestamp
        builder.append("[${entry.formattedTime()}] ")
        builder.setSpan(
            ForegroundColorSpan(colorSlate),
            start,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Direction Tag
        val tagStart = builder.length
        val (dirText, dirColor) = when (entry.direction) {
            SignalDirection.TX -> Pair("[TX ▶] ", colorSkyBlue)
            SignalDirection.RX -> Pair("[RX ◀] ", colorEmerald)
            SignalDirection.GATT -> Pair("[GATT] ", colorAmber)
            SignalDirection.ERROR -> Pair("[ERR!] ", colorCoral)
            SignalDirection.INFO -> Pair("[INFO] ", colorSlate)
        }
        builder.append(dirText)
        builder.setSpan(
            ForegroundColorSpan(dirColor),
            tagStart,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Tag Name
        val nameStart = builder.length
        builder.append("[${entry.tag}] ")
        builder.setSpan(
            ForegroundColorSpan(Color.parseColor("#CBD5E1")),
            nameStart,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Message
        val msgStart = builder.length
        builder.append(entry.message).append("\n")
        builder.setSpan(
            ForegroundColorSpan(Color.parseColor("#F1F5F9")),
            msgStart,
            builder.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
    }

    private fun scrollToBottom() {
        scrollTerminal.post {
            scrollTerminal.fullScroll(View.FOCUS_DOWN)
        }
    }

    // ── Bind ESP32 Hardware Details (Strictly Read-Only) ──────────────────

    private fun bindDeviceInfo(info: Esp32SignalHub.Esp32DeviceInfo) {
        val isScanning = info.connectionState == "SCANNING"
        btnScanBle.text = if (isScanning) "Stop Scan" else "Scan BLE"
        btnScanBle.backgroundTintList = ContextCompat.getColorStateList(
            requireContext(),
            if (isScanning) R.color.strength_weak else R.color.teal_primary
        )

        tvStatusPill.text = info.connectionState
        tvStatusPill.setTextColor(
            if (info.connectionState.startsWith("CONNECTED"))
                ContextCompat.getColor(requireContext(), R.color.teal_primary)
            else if (isScanning)
                Color.parseColor("#38BDF8")
            else
                Color.parseColor("#94A3B8")
        )

        tvDetailDeviceName.text = info.deviceName
        tvDetailSerialNumber.text = info.serialNumber
        tvDetailFirmwareVer.text = info.firmwareVersion
        tvDetailFlashCode.text = info.flashCode
        tvDetailChipModel.text = info.chipModel
        tvDetailCpuArch.text = info.cpuArchitecture
        tvDetailMacAddress.text = info.macAddress
        tvDetailServiceUuid.text = info.serviceUuid
        tvDetailCharUuid.text = info.telemetryCharUuid
        tvDetailCccdUuid.text = info.cccdDescriptorUuid
        tvDetailMtuSize.text = "${info.mtuSize} Bytes"
        tvDetailConnectionState.text = info.connectionState
        tvDetailRssi.text = "${info.rssi} dBm"
        tvDetailHx711.text = info.hx711Status
        tvDetailDht22.text = info.dht22Status
        tvDetailBatteryAdc.text = info.batteryAdcStatus
        tvDetailStatusLed.text = info.statusLedPin
    }
}
