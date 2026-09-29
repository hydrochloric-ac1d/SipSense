package com.sipsense.app

/**
 * MainActivity.kt
 *
 * Primary post-login screen of the SipSense application.
 * Hosts the bottom navigation bar and manages fragment switching
 * between the four top-level destinations:
 *
 * ══════════════════════════════════════════════════════════════════════════
 * NAVIGATION TABS:
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
 * - The system navigation bar color matches the white surface
 *
 * ══════════════════════════════════════════════════════════════════════════
 * LAYOUT: res/layout/activity_main.xml (ConstraintLayout-based)
 * ══════════════════════════════════════════════════════════════════════════
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @author SipSense Development Team
 * @version 1.0
 */

import android.os.Bundle
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

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Initializes the activity, sets up the bottom navigation,
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

        // ── Set up bottom navigation listener ──
        setupBottomNavigation()

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
     * - nav_profile   → ProfileFragment
     */
    private fun setupBottomNavigation() {
        bottomNavigation.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_dashboard -> DashboardFragment()
                R.id.nav_history   -> HistoryFragment()
                R.id.nav_devices   -> DevicesFragment()
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
}
