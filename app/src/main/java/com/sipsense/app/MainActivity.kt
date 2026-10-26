package com.sipsense.app

/**
 * MainActivity.kt
 *
 * Primary post-login screen of the SipSense application.
 * Hosts a persistent header bar at the top, a fragment container in
 * the middle, and a bottom navigation bar at the bottom.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * HEADER BAR (always visible):
 * ──────────────────────────────────────────────────────────────────────────
 * - Left side:  SipSense logo + "SipSense" title
 * - Right side: WiFi icon + "CONNECTED" / "DISCONNECTED" badge
 *
 * ══════════════════════════════════════════════════════════════════════════
 * NAVIGATION TABS (bottom):
 * ──────────────────────────────────────────────────────────────────────────
 * 1. Dashboard  (droplet icon)       – Hydration overview / home
 * 2. History    (calendar icon)      – Intake tracking logs
 * 3. Notifications (bell icon)       – Hydration reminders & alerts
 * 4. Profile    (user icon)          – Account & settings
 *
 * ══════════════════════════════════════════════════════════════════════════
 * BEHAVIOR:
 * ──────────────────────────────────────────────────────────────────────────
 * - Dashboard is the default tab shown on launch
 * - Fragments are replaced (not added) to keep memory usage low
 * - Selected tab icon/text is tinted teal; unselected tabs are grey
 * - Header and bottom nav are always visible across all fragments
 * - WiFi status defaults to "CONNECTED" and can be toggled via
 *   updateWifiStatus(isConnected: Boolean)
 *
 * ══════════════════════════════════════════════════════════════════════════
 * LAYOUT: res/layout/activity_main.xml (ConstraintLayout-based)
 * ══════════════════════════════════════════════════════════════════════════
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @author SipSense Development Team
 * @version 1.1
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

    // ═══════════════════════════════════════════════════════════════════
    // VIEW REFERENCES
    // ═══════════════════════════════════════════════════════════════════

    /** Bottom navigation bar with 4 tabs */
    private lateinit var bottomNavigation: BottomNavigationView

    /** SipSense header logo icon for secret developer mode access */
    private lateinit var ivHeaderLogo: ImageView

    /** WiFi status icon in the header bar */
    private lateinit var ivWifiIcon: ImageView

    /** WiFi status text label ("CONNECTED" / "DISCONNECTED") */
    private lateinit var tvWifiStatus: TextView

    /** Shared Bluetooth BLE Manager instance */
    var bleManager: com.sipsense.app.data.SipSenseBleManager? = null

    /** Tap counter and timestamp for developer mode secret unlock (Samsung style: 5 taps) */
    private var logoTapCount = 0
    private var lastLogoTapTime = 0L

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Initializes the activity, sets up the header bar, bottom navigation,
     * and loads the default Dashboard fragment.
     *
     * @param savedInstanceState Previously saved state bundle
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (FirebaseAuth.getInstance().currentUser == null) {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
            return
        }
        setContentView(R.layout.activity_main)

        // Set the system navigation bar to white to match the bottom nav surface
        window.navigationBarColor = ContextCompat.getColor(this, R.color.card_background)

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

    /**
     * Sets up the tap gesture listener on the header logo.
     * Tapping 5 times activates Developer Mode, exclusively for testuser1@sipsense.com.
     */
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

    /**
     * Opens the DeveloperModeFragment into the fragment container.
     */
    fun openDeveloperMode() {
        loadFragment(DeveloperModeFragment(), addToBackStack = true)
    }

    // ═══════════════════════════════════════════════════════════════════
    // BOTTOM NAVIGATION SETUP
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Configures the BottomNavigationView item selection listener.
     *
     * When a tab is tapped, the corresponding fragment is loaded into
     * the fragment container. The listener returns true to indicate
     * the selection was handled and the tab should be visually updated.
     *
     * Tab-to-Fragment mapping:
     * - nav_dashboard → DashboardFragment
     * - nav_history   → HistoryFragment
     * - nav_notifications → NotificationsFragment
     * - nav_profile   → ProfileFragment
     */
    private fun setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_dashboard -> DashboardFragment()
                R.id.nav_history   -> HistoryFragment()
                R.id.nav_notifications -> NotificationsFragment()
                R.id.nav_profile   -> ProfileFragment()
                else -> return@setOnItemSelectedListener false
            }

            loadFragment(fragment)
            true
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // FRAGMENT MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Replaces the current fragment in the container with the given fragment.
     *
     * Uses FragmentTransaction.replace() to swap the entire contents
     * of the fragment container.
     *
     * @param fragment The fragment to display in the container
     * @param addToBackStack Whether to add this transaction to the fragment back stack
     */
    fun loadFragment(fragment: Fragment, addToBackStack: Boolean = false) {
        val transaction = supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
        if (addToBackStack) {
            transaction.addToBackStack(null)
        }
        transaction.commit()
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONNECTION STATUS MANAGEMENT (Bluetooth / IoT Device)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Updates the Bluetooth/IoT device connection status indicator in the header bar.
     *
     * When connected (BLE active or simulation live):
     * - Displays "CONNECTED" text in white
     * - Bluetooth icon is tinted white
     *
     * When disconnected:
     * - Displays "DISCONNECTED" text in muted grey
     * - Bluetooth icon is tinted in muted grey
     *
     * @param isConnected true if Bluetooth/IoT device is actively connected or simulation live
     */
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
