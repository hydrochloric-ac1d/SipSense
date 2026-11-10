package com.sipsense.app

/**
 * MainActivity.kt
 *
 * Primary post-login screen of the SipSense application.
 * Hosts a persistent header bar at the top, a fragment container in
 * the middle, and a bottom navigation bar at the bottom.
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @author SipSense Development Team
 * @version 1.2
 */

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {

    companion object {
        /** Default Smart Water Bottle capacity in milliliters */
        const val DEFAULT_BOTTLE_CAPACITY_ML = 1000
    }

    // ═══════════════════════════════════════════════════════════════════
    // VIEW REFERENCES
    // ═══════════════════════════════════════════════════════════════════

    /** Bottom navigation bar */
    private lateinit var bottomNavigation: BottomNavigationView

    /** SipSense header logo icon for secret developer mode access */
    private lateinit var ivHeaderLogo: ImageView

    /** WiFi/BLE status icon in the header bar */
    private lateinit var ivWifiIcon: ImageView

    /** Connection status text label ("CONNECTED" / "DISCONNECTED") */
    private lateinit var tvWifiStatus: TextView

    /** Shared Bluetooth BLE Manager instance */
    var bleManager: com.sipsense.app.data.SipSenseBleManager? = null

    /**
     * Shared water bottle state across fragments so tab switches
     * between Dashboard and Bottle never lose or double-count water deductions.
     * Starts at 0 ml until the bottle is placed on the base platform.
     */
    var currentBottleWaterLevel: Int = 0
    var lastRestingWaterLevel: Int = 0
    var currentBottleCapacity: Int = DEFAULT_BOTTLE_CAPACITY_ML
    var isBottleAtRest: Boolean = false

    /**
     * Evaluates a newly reported bottle water level and returns the deducted water volume (in ml)
     * whenever the bottle is at rest and the water volume has decreased below [lastRestingWaterLevel].
     *
     * Every time the water volume is deducted, the returned positive volume is counted as
     * drinking water towards the user's Daily Goal.
     */
    @Synchronized
    fun evaluateWaterDeduction(
        newWaterLevel: Int,
        atRest: Boolean,
        capacity: Int = DEFAULT_BOTTLE_CAPACITY_ML
    ): Int {
        currentBottleCapacity = if (capacity > 0) capacity else DEFAULT_BOTTLE_CAPACITY_ML
        isBottleAtRest = atRest
        val clampedLevel = newWaterLevel.coerceIn(0, currentBottleCapacity)

        if (!atRest) {
            if (clampedLevel == 0) {
                currentBottleWaterLevel = 0
                lastRestingWaterLevel = 0
            }
            return 0
        }

        val deductedMl = if (clampedLevel < lastRestingWaterLevel) {
            lastRestingWaterLevel - clampedLevel
        } else {
            0
        }

        lastRestingWaterLevel = clampedLevel
        currentBottleWaterLevel = clampedLevel
        return deductedMl
    }

    fun getOrCreateBleManager(): com.sipsense.app.data.SipSenseBleManager {
        if (bleManager == null) {
            bleManager = com.sipsense.app.data.SipSenseBleManager(
                context = applicationContext,
                onConnectionStateChanged = { isConnected, _ ->
                    updateConnectionStatus(isConnected)
                },
                onTelemetryReceived = { _ -> }
            )
        }
        return bleManager!!
    }

    /** Tap counter and timestamp for developer mode secret unlock (Samsung style: 5 taps) */
    private var logoTapCount = 0
    private var lastLogoTapTime = 0L

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (FirebaseAuth.getInstance().currentUser == null) {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        // Set the system navigation bar to match the gradient background
        window.navigationBarColor = ContextCompat.getColor(this, R.color.gradient_end)

        // ── Initialize views ──
        bottomNavigation = findViewById(R.id.bottom_navigation)
        ivHeaderLogo = findViewById(R.id.iv_header_logo)
        ivWifiIcon = findViewById(R.id.iv_wifi_icon)
        tvWifiStatus = findViewById(R.id.tv_wifi_status)

        // ── Set up secret developer mode on header logo (5 taps for testuser1@sipsense.com) ──
        setupHeaderLogoDeveloperMode()

        // ── Set up bottom navigation listener ──
        setupBottomNavigation()

        // ── Set default device connection status to DISCONNECTED until live/connected ──
        updateConnectionStatus(false)

        // ── Load the default fragment (Dashboard) on first launch ──
        if (savedInstanceState == null) {
            loadFragment(DashboardFragment())
        }
    }

    private fun setupHeaderLogoDeveloperMode() {
        ivHeaderLogo.setOnClickListener {
            val currentUser = FirebaseAuth.getInstance().currentUser
            val email = currentUser?.email

            // Strictly restricted: only testuser1@sipsense.com can access
            if (!email.equals("testuser1@sipsense.com", ignoreCase = true)) {
                return@setOnClickListener
            }

            val now = System.currentTimeMillis()
            if (now - lastLogoTapTime > 2000L) {
                logoTapCount = 0
            }
            lastLogoTapTime = now
            logoTapCount++

            if (logoTapCount in 2..4) {
                val remaining = 5 - logoTapCount
                val msg = if (remaining == 1) {
                    getString(R.string.dev_mode_step_toast)
                } else {
                    getString(R.string.dev_mode_steps_toast, remaining)
                }
                android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
            } else if (logoTapCount >= 5) {
                logoTapCount = 0
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.dev_mode_unlocked_toast),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                openDeveloperMode()
            }
        }
    }

    fun openDeveloperMode() {
        loadFragment(DeveloperModeFragment(), addToBackStack = true)
    }

    private fun setupBottomNavigation() {
        val dpDensity = resources.displayMetrics.density
        bottomNavigation.itemPaddingTop = (14 * dpDensity).toInt()
        bottomNavigation.itemPaddingBottom = (6 * dpDensity).toInt()

        bottomNavigation.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_dashboard -> DashboardFragment()
                R.id.nav_history   -> HistoryFragment()
                R.id.nav_waterbottle -> WaterbottleFragment()
                R.id.nav_notifications -> NotificationsFragment()
                R.id.nav_profile   -> ProfileFragment()
                else -> return@setOnItemSelectedListener false
            }

            loadFragment(fragment)
            true
        }
    }

    fun loadFragment(fragment: Fragment, addToBackStack: Boolean = false) {
        val transaction = supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
        if (addToBackStack) {
            transaction.addToBackStack(null)
        }
        transaction.commit()
    }

    fun updateConnectionStatus(isConnected: Boolean) {
        if (isConnected) {
            tvWifiStatus.text = getString(R.string.wifi_connected)
            tvWifiStatus.setTextColor(ContextCompat.getColor(this, R.color.text_white))
            ivWifiIcon.contentDescription = getString(R.string.wifi_connected)
            ivWifiIcon.setColorFilter(
                ContextCompat.getColor(this, R.color.text_white)
            )
        } else {
            tvWifiStatus.text = getString(R.string.wifi_disconnected)
            tvWifiStatus.setTextColor(ContextCompat.getColor(this, R.color.nav_unselected))
            ivWifiIcon.contentDescription = getString(R.string.wifi_disconnected)
            ivWifiIcon.setColorFilter(
                ContextCompat.getColor(this, R.color.nav_unselected)
            )
        }
    }

    /** Alias for backwards compatibility with existing fragments */
    fun updateWifiStatus(isConnected: Boolean) = updateConnectionStatus(isConnected)
}
