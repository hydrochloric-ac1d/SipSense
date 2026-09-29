package com.sipsense.app

/**
 * ProfileFragment.kt
 *
 * Placeholder fragment for the Profile tab in the bottom navigation.
 * Displays user account information and app settings.
 *
 * This fragment will be expanded later with:
 * - User avatar and name display
 * - Hydration goal settings
 * - Notification preferences
 * - Account management (edit profile, change password)
 * - Logout functionality
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 1.0
 */

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

class ProfileFragment : Fragment() {

    /**
     * Inflates the Profile fragment layout.
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
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }
}
