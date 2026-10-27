package com.sipsense.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.sipsense.app.data.FirebaseDatabaseProvider
import java.util.Locale

/**
 * DevicesFragment.kt
 *
 * Displays paired IoT Smart Bottle status and live telemetry:
 * - Water inside bottle in Liters (and ml)
 * - Battery percentage
 * - Ambient Room Temperature
 *
 * @project SipSense - Smart Bottle Ecosystem
 */
class DevicesFragment : Fragment() {

    private lateinit var ivWaterIcon: ImageView
    private lateinit var tvWaterLiters: TextView
    private lateinit var tvWaterMl: TextView
    private lateinit var ivBatteryIcon: ImageView
    private lateinit var tvBattery: TextView
    private lateinit var tvBatteryStatus: TextView
    private lateinit var ivTempIcon: ImageView
    private lateinit var tvAmbientTemp: TextView
    private lateinit var tvTempStatus: TextView

    private val firebaseAuth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabaseProvider.reference()
    private var telemetryListener: ValueEventListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_devices, container, false)

        ivWaterIcon = view.findViewById(R.id.iv_device_tab_water_icon)
        tvWaterLiters = view.findViewById(R.id.tv_device_tab_water_liters)
        tvWaterMl = view.findViewById(R.id.tv_device_tab_water_ml)
        ivBatteryIcon = view.findViewById(R.id.iv_device_tab_battery_icon)
        tvBattery = view.findViewById(R.id.tv_device_tab_battery)
        tvBatteryStatus = view.findViewById(R.id.tv_device_tab_battery_status)
        ivTempIcon = view.findViewById(R.id.iv_device_tab_temp_icon)
        tvAmbientTemp = view.findViewById(R.id.tv_device_tab_ambient_temp)
        tvTempStatus = view.findViewById(R.id.tv_device_tab_temp_status)

        loadDeviceTelemetry()

        return view
    }

    override fun onDestroyView() {
        super.onDestroyView()
        val uid = firebaseAuth.currentUser?.uid
        if (uid != null && telemetryListener != null) {
            database?.child("users")?.child(uid)?.child("device_telemetry")?.removeEventListener(telemetryListener!!)
        }
    }

    private fun loadDeviceTelemetry() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        telemetryListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return
                val waterLevel = snapshot.child("waterLevel").getValue(Int::class.java) ?: 650
                val capacity = snapshot.child("bottleCapacity").getValue(Int::class.java) ?: 1000
                val battery = snapshot.child("batteryLevel").getValue(Int::class.java) ?: 88

                val isConnected = (activity as? MainActivity)?.bleManager?.isConnected == true

                val isFailed = snapshot.child("tempSensorStatus").getValue(String::class.java).equals("Read Failed", ignoreCase = true)
                    || snapshot.child("status").getValue(String::class.java).equals("Read Failed", ignoreCase = true)
                    || snapshot.child("readFailed").getValue(Boolean::class.java) == true

                val ambientTemp = if (isFailed || !isConnected) {
                    null
                } else {
                    snapshot.child("ambientTemperature").getValue(Int::class.java)
                        ?: snapshot.child("temperature").getValue(Int::class.java)
                }

                if (!isConnected) {
                    ivWaterIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
                    tvWaterLiters.text = "--"
                    tvWaterLiters.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    tvWaterMl.text = "--"

                    ivBatteryIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
                    tvBattery.text = "--"
                    tvBattery.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    tvBatteryStatus.text = getString(R.string.ambient_temp_status_waiting)
                    tvBatteryStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))

                    ivTempIcon.setImageResource(R.drawable.ic_temperature)
                    ivTempIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
                    tvAmbientTemp.text = "--"
                    tvAmbientTemp.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                    tvTempStatus.text = getString(R.string.ambient_temp_status_waiting)
                    tvTempStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                } else {
                    ivWaterIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.teal_primary)
                    val liters = waterLevel / 1000.0
                    tvWaterLiters.text = String.format(Locale.US, "%.2f L", liters)
                    tvWaterLiters.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                    tvWaterMl.text = "$waterLevel ml / $capacity ml"

                    tvBattery.text = "$battery%"
                    tvBattery.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                    if (battery <= 20) {
                        tvBatteryStatus.text = getString(R.string.battery_status_low)
                        tvBatteryStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.strength_weak))
                        ivBatteryIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_weak)
                    } else {
                        tvBatteryStatus.text = getString(R.string.battery_status_good)
                        tvBatteryStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                        ivBatteryIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_strong)
                    }

                    if (isFailed) {
                        ivTempIcon.setImageResource(R.drawable.ic_error)
                        ivTempIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_weak)
                        tvAmbientTemp.text = "--"
                        tvAmbientTemp.setTextColor(ContextCompat.getColor(requireContext(), R.color.strength_weak))
                        tvTempStatus.text = getString(R.string.ambient_temp_status_error)
                        tvTempStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.strength_weak))
                    } else if (ambientTemp != null) {
                        ivTempIcon.setImageResource(R.drawable.ic_temperature)
                        ivTempIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_fair)
                        tvAmbientTemp.text = "$ambientTemp°C"
                        tvAmbientTemp.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                        tvTempStatus.text = when {
                            ambientTemp < 20 -> getString(R.string.ambient_temp_status_cool)
                            ambientTemp > 28 -> getString(R.string.ambient_temp_status_warm)
                            else -> getString(R.string.ambient_temp_status_normal)
                        }
                        tvTempStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                    } else {
                        ivTempIcon.setImageResource(R.drawable.ic_temperature)
                        ivTempIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.strength_fair)
                        tvAmbientTemp.text = "--"
                        tvAmbientTemp.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                        tvTempStatus.text = getString(R.string.ambient_temp_status_waiting)
                        tvTempStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // Keep default layout values
            }
        }
        database?.child("users")?.child(uid)?.child("device_telemetry")?.addValueEventListener(telemetryListener!!)
    }

    override fun onResume() {
        super.onResume()
        val isConnected = (activity as? MainActivity)?.bleManager?.isConnected == true
        if (!isConnected) {
            ivWaterIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
            tvWaterLiters.text = "--"
            tvWaterLiters.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            tvWaterMl.text = "--"

            ivBatteryIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
            tvBattery.text = "--"
            tvBattery.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            tvBatteryStatus.text = getString(R.string.ambient_temp_status_waiting)
            tvBatteryStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))

            ivTempIcon.setImageResource(R.drawable.ic_temperature)
            ivTempIcon.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_secondary)
            tvAmbientTemp.text = "--"
            tvAmbientTemp.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
            tvTempStatus.text = getString(R.string.ambient_temp_status_waiting)
            tvTempStatus.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_hint))
        }
    }
}
