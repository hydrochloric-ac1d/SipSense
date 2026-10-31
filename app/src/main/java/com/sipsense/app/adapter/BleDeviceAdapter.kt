package com.sipsense.app.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.sipsense.app.R
import com.sipsense.app.data.DiscoveredBleDevice

/**
 * BleDeviceAdapter.kt
 *
 * Displays scanned BLE devices / smart bottles with their name
 * and a manual "Connect" action button.
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
        private val btnConnect: MaterialButton = itemView.findViewById(R.id.btn_device_connect)

        fun bind(device: DiscoveredBleDevice, isConnecting: Boolean) {
            val context = itemView.context
            tvName.text = if (device.name.isNotBlank()) device.name else "SipSense Bottle"

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
