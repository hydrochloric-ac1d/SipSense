package com.sipsense.app

/**
 * ProfileFragment.kt
 *
 * Displays the current user's profile information fetched from
 * Firebase Realtime Database and provides a logout button.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * FEATURES:
 * ──────────────────────────────────────────────────────────────────────────
 * 1. Reads user profile from /users/{uid} in Realtime Database
 * 2. Displays full name, email, and daily hydration target
 * 3. Logout button signs out via FirebaseAuth and returns to LoginActivity
 *
 * ══════════════════════════════════════════════════════════════════════════
 * DATA SOURCE:
 * ──────────────────────────────────────────────────────────────────────────
 * Firebase Realtime Database path: /users/{FirebaseAuth.currentUser.uid}
 * Data class: com.sipsense.app.model.UserProfile
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 1.1
 */

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.sipsense.app.model.UserProfile

class ProfileFragment : Fragment() {

    // ═══════════════════════════════════════════════════════════════════
    // VIEW REFERENCES
    // ═══════════════════════════════════════════════════════════════════

    /** Displays the user's full name */
    private lateinit var tvProfileName: TextView

    /** Displays the user's email address */
    private lateinit var tvProfileEmail: TextView

    /** Displays the daily hydration target in mL */
    private lateinit var tvProfileHydration: TextView

    /** Logout button – signs out and navigates to LoginActivity */
    private lateinit var btnLogout: Button

    // ═══════════════════════════════════════════════════════════════════
    // FIREBASE REFERENCES
    // ═══════════════════════════════════════════════════════════════════

    /** Firebase Authentication instance */
    private val firebaseAuth = FirebaseAuth.getInstance()

    /** Firebase Realtime Database instance */
    private val database = FirebaseDatabase.getInstance().reference

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Inflates the Profile fragment layout and initializes view references.
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
        val view = inflater.inflate(R.layout.fragment_profile, container, false)

        // ── Bind views ──
        tvProfileName = view.findViewById(R.id.tv_profile_name)
        tvProfileEmail = view.findViewById(R.id.tv_profile_email)
        tvProfileHydration = view.findViewById(R.id.tv_profile_hydration)
        btnLogout = view.findViewById(R.id.btn_logout)

        // ── Load profile data from Firebase ──
        loadUserProfile()

        // ── Set up logout button ──
        setupLogoutButton()

        return view
    }

    // ═══════════════════════════════════════════════════════════════════
    // PROFILE DATA LOADING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Reads the current user's profile from Firebase Realtime Database.
     *
     * Fetches data from /users/{uid} and deserializes it into a
     * UserProfile object. Updates the UI TextViews with the loaded data.
     *
     * If the read fails or the user is not authenticated, displays
     * an error toast.
     */
    private fun loadUserProfile() {
        val uid = firebaseAuth.currentUser?.uid

        if (uid == null) {
            Toast.makeText(context, "User not authenticated", Toast.LENGTH_SHORT).show()
            return
        }

        database.child("users").child(uid)
            .addListenerForSingleValueEvent(object : ValueEventListener {

                /**
                 * Called when the database read succeeds.
                 * Deserializes the snapshot into a UserProfile and updates the UI.
                 */
                override fun onDataChange(snapshot: DataSnapshot) {
                    val profile = snapshot.getValue(UserProfile::class.java)

                    if (profile != null) {
                        tvProfileName.text = profile.fullName
                        tvProfileEmail.text = profile.email
                        tvProfileHydration.text = "${profile.hydrationTarget} mL"
                    } else {
                        tvProfileName.text = firebaseAuth.currentUser?.email ?: "User"
                        tvProfileEmail.text = ""
                        tvProfileHydration.text = "-- mL"
                    }
                }

                /**
                 * Called when the database read fails.
                 * Displays the error message to the user.
                 */
                override fun onCancelled(error: DatabaseError) {
                    Toast.makeText(
                        context,
                        "Failed to load profile: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
    }

    // ═══════════════════════════════════════════════════════════════════
    // LOGOUT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Configures the logout button.
     *
     * When tapped:
     * 1. Signs out the user from Firebase Authentication
     * 2. Navigates back to LoginActivity
     * 3. Clears the back stack so the user cannot press back to return
     */
    private fun setupLogoutButton() {
        btnLogout.setOnClickListener {
            // Sign out from Firebase
            firebaseAuth.signOut()

            // Navigate to LoginActivity and clear the entire back stack
            val intent = Intent(requireContext(), LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
    }
}
