package com.sipsense.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.sipsense.app.R
import com.sipsense.app.data.DiscoveredBleDevice

/**
 * BleDeviceAdapter.kt
 *
 * Displays scanned BLE devices / ESP32 smart bottles with their name, MAC address,
 * RSSI signal strength in dBm, and a manual "Connect" action button.
 *
 * @project SipSense - Smart Bottle Ecosystem
 */
class BleDeviceAdapter(
    private val onConnectClicked: (DiscoveredBleDevice) -> Unit
) : RecyclerView.Adapter<BleDeviceAdapter.DeviceViewHolder>() {

    private val devices = mutableListOf<DiscoveredBleDevice>()
    private var connectingAddress: String? = null

    fun setDevices(newDevices: List<DiscoveredBleDevice>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    fun addOrUpdateDevice(device: DiscoveredBleDevice) {
        val index = devices.indexOfFirst { it.address == device.address }
        if (index >= 0) {
            devices[index] = device
            notifyItemChanged(index)
        } else {
            devices.add(device)
            notifyItemInserted(devices.size - 1)
        }
    }

    fun clearDevices() {
        val count = devices.size
        devices.clear()
        notifyItemRangeRemoved(0, count)
    }

    fun setConnectingAddress(address: String?) {
        connectingAddress = address
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ble_device, parent, false)
        return DeviceViewHolder(view)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(devices[position], connectingAddress == devices[position].address)
    }

    override fun getItemCount(): Int = devices.size

    inner class DeviceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tv_discovered_name)
        private val tvAddress: TextView = itemView.findViewById(R.id.tv_discovered_address)
        private val tvRssi: TextView = itemView.findViewById(R.id.tv_discovered_rssi)
        private val ivSignalIcon: ImageView = itemView.findViewById(R.id.iv_signal_icon)
        private val btnConnect: MaterialButton = itemView.findViewById(R.id.btn_device_connect)

        fun bind(device: DiscoveredBleDevice, isConnecting: Boolean) {
            val context = itemView.context
            tvName.text = if (device.name.isNotBlank()) device.name else "ESP32 Waterbottle"
            tvAddress.text = device.address

            val (qualityLabel, signalColorRes) = when {
                device.rssi >= -60 -> Pair("Excellent", R.color.strength_strong)
                device.rssi >= -75 -> Pair("Good", R.color.teal_primary)
                device.rssi >= -85 -> Pair("Fair", R.color.strength_fair)
                else               -> Pair("Weak", R.color.strength_weak)
            }

            tvRssi.text = "${device.rssi} dBm • $qualityLabel"
            val color = ContextCompat.getColor(context, signalColorRes)
            tvRssi.setTextColor(color)
            ivSignalIcon.setColorFilter(color)

            if (isConnecting) {
                btnConnect.text = context.getString(R.string.waterbottle_connecting)
                btnConnect.isEnabled = false
                btnConnect.alpha = 0.7f
            } else {
                btnConnect.text = context.getString(R.string.waterbottle_connect)
                btnConnect.isEnabled = true
                btnConnect.alpha = 1.0f
                btnConnect.setOnClickListener {
                    onConnectClicked(device)
                }
            }
        }
    }
}

