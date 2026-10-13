package com.sipsense.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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

    private lateinit var tvWaterLiters: TextView
    private lateinit var tvWaterMl: TextView
    private lateinit var tvBattery: TextView
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

        tvWaterLiters = view.findViewById(R.id.tv_device_tab_water_liters)
        tvWaterMl = view.findViewById(R.id.tv_device_tab_water_ml)
        tvBattery = view.findViewById(R.id.tv_device_tab_battery)
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
                val ambientTemp = snapshot.child("ambientTemperature").getValue(Int::class.java)
                    ?: snapshot.child("temperature").getValue(Int::class.java)
                    ?: 24

                val liters = waterLevel / 1000.0
                tvWaterLiters.text = String.format(Locale.US, "%.2f L", liters)
                tvWaterMl.text = "$waterLevel ml / $capacity ml"
                tvBattery.text = "$battery%"
                tvAmbientTemp.text = "$ambientTemp°C"
                tvTempStatus.text = when {
                    ambientTemp < 20 -> getString(R.string.ambient_temp_status_cool)
                    ambientTemp > 28 -> getString(R.string.ambient_temp_status_warm)
                    else -> getString(R.string.ambient_temp_status_normal)
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // Keep default layout values
            }
        }
        database?.child("users")?.child(uid)?.child("device_telemetry")?.addValueEventListener(telemetryListener!!)
    }
}
