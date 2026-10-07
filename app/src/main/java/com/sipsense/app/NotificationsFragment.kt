package com.sipsense.app

/**
 * NotificationsFragment.kt
 *
 * Placeholder fragment for the Notifications tab in the bottom navigation.
 * Displays hydration reminders and smart bottle alerts.
 *
 * This fragment will be expanded later with:
 * - Hydration reminder history
 * - Device alerts (low battery, disconnected, refill needed)
 * - Goal achievement notifications
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 1.0
 */

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class NotificationsFragment : Fragment() {

    /**
     * Inflates the Notifications fragment layout.
     *
     * @param inflater LayoutInflater to inflate the XML layout
     * @param container Parent ViewGroup (the fragment container in MainActivity)
     * @param savedInstanceState Previously saved state (unused for now)
     * @return The inflated fragment view
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_notifications, container, false)
    }
}
