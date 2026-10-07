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
 * 3. Devices    (water-bottle icon)  – Connected IoT bottle management
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

import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    // ═══════════════════════════════════════════════════════════════════
    // VIEW REFERENCES
    // ═══════════════════════════════════════════════════════════════════

    /** Bottom navigation bar with 4 tabs */
    private lateinit var bottomNavigation: BottomNavigationView

    /** WiFi status icon in the header bar */
    private lateinit var ivWifiIcon: ImageView

    /** WiFi status text label ("CONNECTED" / "DISCONNECTED") */
    private lateinit var tvWifiStatus: TextView

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
        setContentView(R.layout.activity_main)

        // Set the system navigation bar to white to match the bottom nav surface
        window.navigationBarColor = ContextCompat.getColor(this, R.color.card_background)

        // ── Initialize views ──
        bottomNavigation = findViewById(R.id.bottom_navigation)
        ivWifiIcon = findViewById(R.id.iv_wifi_icon)
        tvWifiStatus = findViewById(R.id.tv_wifi_status)

        // ── Set up bottom navigation listener ──
        setupBottomNavigation()

        // ── Set default WiFi status to connected ──
        updateWifiStatus(true)

        // ── Load the default fragment (Dashboard) on first launch ──
        if (savedInstanceState == null) {
            loadFragment(DashboardFragment())
        }
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
     * - nav_devices   → DevicesFragment
     * - nav_notifications → NotificationsFragment
     * - nav_profile   → ProfileFragment
     */
    private fun setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_dashboard -> DashboardFragment()
                R.id.nav_history   -> HistoryFragment()
                R.id.nav_devices   -> DevicesFragment()
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
     * of the fragment container. No back stack is maintained for
     * top-level navigation destinations.
     *
     * @param fragment The fragment to display in the container
     */
    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    // ═══════════════════════════════════════════════════════════════════
    // WIFI STATUS MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Updates the WiFi connection status indicator in the header bar.
     *
     * When connected:
     * - Displays "CONNECTED" text
     * - WiFi icon is tinted white (normal)
     *
     * When disconnected:
     * - Displays "DISCONNECTED" text
     * - WiFi icon is tinted with a muted grey overlay
     *
     * This method can be called from anywhere in the activity or from
     * child fragments via (activity as MainActivity).updateWifiStatus(...)
     *
     * @param isConnected true if WiFi/IoT device is connected, false otherwise
     */
    fun updateWifiStatus(isConnected: Boolean) {
        if (isConnected) {
            tvWifiStatus.text = getString(R.string.wifi_connected)
            ivWifiIcon.contentDescription = getString(R.string.wifi_connected)
            ivWifiIcon.setColorFilter(
                ContextCompat.getColor(this, R.color.text_white)
            )
        } else {
            tvWifiStatus.text = getString(R.string.wifi_disconnected)
            ivWifiIcon.contentDescription = getString(R.string.wifi_disconnected)
            ivWifiIcon.setColorFilter(
                ContextCompat.getColor(this, R.color.nav_unselected)
            )
        }
    }
}
