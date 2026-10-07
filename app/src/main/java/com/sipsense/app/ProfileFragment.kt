package com.sipsense.app

/**
 * ProfileFragment.kt
 *
 * Displays the current user's profile information fetched from
 * Firebase Realtime Database and allows updating account settings.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * FEATURES:
 * ──────────────────────────────────────────────────────────────────────────
 * 1. Reads user profile from /users/{uid} in Realtime Database
 * 2. Displays full name and email
 * 3. Daily Hydration Target slider – updates Firebase DB when adjusted
 * 4. Account Preferences panel: Units toggle (ml/oz) and Change Password
 * 5. Logout button signs out via FirebaseAuth and returns to LoginActivity
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 1.2
 */

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.sipsense.app.data.FirebaseDatabaseProvider
import com.sipsense.app.model.UserProfile
import kotlin.math.roundToInt

class ProfileFragment : Fragment() {

    // ═══════════════════════════════════════════════════════════════════
    // VIEW REFERENCES
    // ═══════════════════════════════════════════════════════════════════


    private lateinit var tvProfileName: TextView
    private lateinit var tvProfileEmail: TextView
    
    // Hydration Target
    private lateinit var tvTargetIndicator: TextView
    private lateinit var tvTargetMax: TextView
    private lateinit var sliderHydration: Slider
    
    // Account Preferences Rows
    private lateinit var rowUnits: View
    private lateinit var tvUnitValue: TextView
    private lateinit var rowNotifications: View
    private lateinit var rowChangePassword: View
    private lateinit var btnLogout: Button

    // State
    private var currentUnit = "ml"

    // ═══════════════════════════════════════════════════════════════════
    // FIREBASE REFERENCES
    // ═══════════════════════════════════════════════════════════════════

    /** Firebase Authentication instance */
    private val firebaseAuth = FirebaseAuth.getInstance()

    /** Realtime Database root for the configured Firebase project, or null if it has none */
    private val database = FirebaseDatabaseProvider.reference()

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_profile, container, false)

        // ── Bind views ──
        tvProfileName = view.findViewById(R.id.tv_profile_name)
        tvProfileEmail = view.findViewById(R.id.tv_profile_email)
        tvTargetIndicator = view.findViewById(R.id.tv_target_indicator)
        tvTargetMax = view.findViewById(R.id.tv_target_max)
        sliderHydration = view.findViewById(R.id.slider_hydration)
        
        rowUnits = view.findViewById(R.id.row_units)
        tvUnitValue = view.findViewById(R.id.tv_unit_value)
        rowNotifications = view.findViewById(R.id.row_notifications)
        rowChangePassword = view.findViewById(R.id.row_change_password)
        btnLogout = view.findViewById(R.id.btn_logout)

        // ── Load profile data from Firebase ──
        loadUserProfile()

        // ── Setup listeners ──
        setupListeners()

        return view
    }

    // ═══════════════════════════════════════════════════════════════════
    // SETUP
    // ═══════════════════════════════════════════════════════════════════

    private fun setupListeners() {
        // Hydration Slider (Live Update Text). The slider always works in ml;
        // only the displayed text is converted to the selected unit.
        sliderHydration.setLabelFormatter { value -> formatAmount(value.roundToInt()) }
        sliderHydration.addOnChangeListener { _, value, _ ->
            updateTargetIndicator(value.roundToInt())
        }

        // Hydration Slider (Save to DB on stop tracking)
        sliderHydration.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                saveHydrationTarget(slider.value.roundToInt())
            }
        })

        // Units Row
        rowUnits.setOnClickListener {
            currentUnit = if (currentUnit == "ml") "oz" else "ml"
            tvUnitValue.text = currentUnit
            updateTargetIndicator(sliderHydration.value.roundToInt())
            savePreferredUnit(currentUnit)
        }
        
        // Notifications Row (Placeholder)
        rowNotifications.setOnClickListener {
            Toast.makeText(context, "Notification settings coming soon", Toast.LENGTH_SHORT).show()
        }
        
        // Change Password Row (Placeholder)
        rowChangePassword.setOnClickListener {
            Toast.makeText(context, "Change password feature coming soon", Toast.LENGTH_SHORT).show()
        }

        // Logout Button
        btnLogout.setOnClickListener {
            firebaseAuth.signOut()
            val intent = Intent(requireContext(), LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
    }

    /**
     * Formats an amount given in millilitres for display in the current unit.
     * ml is shown as-is; oz is converted (1 oz = 29.5735 ml) and rounded.
     */
    private fun formatAmount(valueMl: Int): String =
        if (currentUnit == "oz") {
            "${(valueMl / ML_PER_OZ).roundToInt()} oz"
        } else {
            "$valueMl ml"
        }

    /** Updates the selected-value text and the max-value label for the current unit. */
    private fun updateTargetIndicator(valueMl: Int) {
        tvTargetIndicator.text = formatAmount(valueMl)
        tvTargetMax.text = formatAmount(sliderHydration.valueTo.roundToInt())
    }

    private companion object {
        /** Millilitres in one US fluid ounce */
        const val ML_PER_OZ = 29.5735f
    }

    // ═══════════════════════════════════════════════════════════════════
    // PROFILE DATA LOADING & SAVING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Displays the current user's name and email.
     *
     * The values stored on the FirebaseAuth account (display name + email) are
     * shown immediately, so the header is never blank. If a Realtime Database
     * profile exists at /users/{uid}, its values override them.
     */
    private fun loadUserProfile() {
        val user = firebaseAuth.currentUser ?: return
        val uid = user.uid

        // ── Show account info from FirebaseAuth right away ──
        val authEmail = user.email.orEmpty()
        val authName = user.displayName?.takeIf { it.isNotBlank() }
            ?: authEmail.substringBefore('@').ifBlank { "User" }
        tvProfileName.text = authName
        tvProfileEmail.text = authEmail
        updateTargetIndicator(sliderHydration.value.roundToInt())

        // ── Override with the Realtime Database profile, if available ──
        val database = this.database ?: return

        database.child("users").child(uid)
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    // Fragment may have been detached while the read was in flight
                    if (!isAdded) return

                    val profile = snapshot.getValue(UserProfile::class.java) ?: return

                    // Handle cases where the DB record is partially created
                    profile.fullName.takeIf { it.isNotBlank() }?.let { tvProfileName.text = it }
                    profile.email.takeIf { it.isNotBlank() }?.let { tvProfileEmail.text = it }
                    
                    currentUnit = profile.preferredUnit
                    tvUnitValue.text = currentUnit

                    // Update slider without triggering the listener save loop.
                    // Snap to the slider's step size (100 ml) – Slider throws otherwise.
                    val step = sliderHydration.stepSize
                    val snapped = ((profile.hydrationTarget / step).roundToInt() * step)
                        .coerceIn(sliderHydration.valueFrom, sliderHydration.valueTo)
                    sliderHydration.value = snapped
                    updateTargetIndicator(snapped.roundToInt())
                }

                override fun onCancelled(error: DatabaseError) {
                    if (!isAdded) return
                    Toast.makeText(context, "Failed to load profile", Toast.LENGTH_SHORT).show()
                }
            })
    }

    /**
     * Saves the newly selected hydration target to the Firebase Realtime Database.
     */
    private fun saveHydrationTarget(newTarget: Int) {
        val uid = firebaseAuth.currentUser?.uid ?: return

        val database = this.database ?: run {
            Toast.makeText(context, "Failed to update target", Toast.LENGTH_SHORT).show()
            return
        }

        database.child("users").child(uid).child("hydrationTarget").setValue(newTarget)
            .addOnFailureListener {
                Toast.makeText(context, "Failed to update target", Toast.LENGTH_SHORT).show()
            }
    }

    /**
     * Saves the newly selected preferred unit to the Firebase Realtime Database.
     */
    private fun savePreferredUnit(unit: String) {
        val uid = firebaseAuth.currentUser?.uid ?: return

        val database = this.database ?: return

        database.child("users").child(uid).child("preferredUnit").setValue(unit)
            .addOnFailureListener {
                Toast.makeText(context, "Failed to update unit preference", Toast.LENGTH_SHORT).show()
            }
    }
}
