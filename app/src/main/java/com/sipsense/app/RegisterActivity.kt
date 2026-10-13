package com.sipsense.app

/**
 * RegisterActivity.kt
 *
 * Handles new user registration for the SipSense application.
 * Provides a comprehensive sign-up form with real-time validation
 * and a password strength indicator.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * FEATURES:
 * ──────────────────────────────────────────────────────────────────────────
 * 1. First Name, Middle Name (optional), Last Name, Email, Password,
 *    and Confirm Password fields
 * 2. Real-time password strength indicator:
 *    - 4-segment color-coded strength bar
 *    - 4 criteria checklist (length, case, number, symbol)
 *    - Text label updates (Weak / Fair / Good / Strong)
 * 3. Password confirmation with mismatch detection
 * 4. Daily Hydration Target input (number with mL unit)
 * 5. Terms of Service checkbox with clickable links
 * 6. Comprehensive form validation on submit
 *
 * ══════════════════════════════════════════════════════════════════════════
 * LAYOUT: res/layout/activity_register.xml (ConstraintLayout-based)
 * ══════════════════════════════════════════════════════════════════════════
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @author SipSense Development Team
 * @version 1.0
 * @since 2026-09-22
 */

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.util.Log
import android.util.Patterns
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import com.google.firebase.database.DatabaseReference
import com.sipsense.app.data.FirebaseDatabaseProvider
import com.sipsense.app.model.UserProfile

class RegisterActivity : AppCompatActivity() {

    // ═══════════════════════════════════════════════════════════════════
    // VIEW REFERENCES
    // ═══════════════════════════════════════════════════════════════════

    // ── Name Fields ──────────────────────────────────────────────────
    /** First Name field container and input */
    private lateinit var tilFirstName: TextInputLayout
    private lateinit var etFirstName: TextInputEditText

    /** Middle Name field container and input (optional) */
    private lateinit var tilMiddleName: TextInputLayout
    private lateinit var etMiddleName: TextInputEditText

    /** Last Name field container and input */
    private lateinit var tilLastName: TextInputLayout
    private lateinit var etLastName: TextInputEditText

    /** Email field container and input */
    private lateinit var tilEmail: TextInputLayout
    private lateinit var etEmail: TextInputEditText

    /** Password field container and input */
    private lateinit var tilPassword: TextInputLayout
    private lateinit var etPassword: TextInputEditText

    /** Confirm Password field container and input */
    private lateinit var tilConfirmPassword: TextInputLayout
    private lateinit var etConfirmPassword: TextInputEditText

    /** Daily Hydration Target field container and input */
    private lateinit var tilHydration: TextInputLayout
    private lateinit var etHydration: TextInputEditText

    // ── Password Strength UI ─────────────────────────────────────────
    /** Displays the strength level text ("--", "Weak", "Fair", "Good", "Strong") */
    private lateinit var tvStrengthValue: TextView

    /** Four strength bar segments (tinted programmatically) */
    private lateinit var strengthSegment1: View
    private lateinit var strengthSegment2: View
    private lateinit var strengthSegment3: View
    private lateinit var strengthSegment4: View

    /** Password criteria checklist TextViews (○/● prefix toggled in code) */
    private lateinit var tvCriteriaLength: TextView
    private lateinit var tvCriteriaCase: TextView
    private lateinit var tvCriteriaNumber: TextView
    private lateinit var tvCriteriaSymbol: TextView

    // ── Other Controls ───────────────────────────────────────────────
    /** Terms of Service agreement checkbox (must be checked to register) */
    private lateinit var cbTerms: CheckBox

    /** "Create SipSense Account" button */
    private lateinit var btnCreateAccount: Button

    /** "Already have an account? Log In" navigation prompt */
    private lateinit var tvLoginPrompt: TextView

    // ── Firebase ─────────────────────────────────────────────────────
    /** Firebase Authentication instance for creating new user accounts */
    private lateinit var firebaseAuth: FirebaseAuth

    /** Realtime Database root for storing user profiles, or null if the project has none */
    private var database: DatabaseReference? = null

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // Initialize Firebase services
        firebaseAuth = FirebaseAuth.getInstance()
        database = FirebaseDatabaseProvider.reference()

        // Bind all view references from the layout XML
        initializeViews()

        // Configure all interactive elements
        setupPasswordStrengthWatcher()
        setupTermsCheckbox()
        setupCreateAccountButton()
        setupLoginPrompt()
    }

    // ═══════════════════════════════════════════════════════════════════
    // VIEW INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════

    private fun initializeViews() {
        // First Name field
        tilFirstName = findViewById(R.id.tilFirstName)
        etFirstName = findViewById(R.id.etFirstName)

        // Middle Name field (optional)
        tilMiddleName = findViewById(R.id.tilMiddleName)
        etMiddleName = findViewById(R.id.etMiddleName)

        // Last Name field
        tilLastName = findViewById(R.id.tilLastName)
        etLastName = findViewById(R.id.etLastName)

        // Email field
        tilEmail = findViewById(R.id.tilEmail)
        etEmail = findViewById(R.id.etEmail)

        // Password field
        tilPassword = findViewById(R.id.tilPassword)
        etPassword = findViewById(R.id.etPassword)

        // Confirm Password field
        tilConfirmPassword = findViewById(R.id.tilConfirmPassword)
        etConfirmPassword = findViewById(R.id.etConfirmPassword)

        // Hydration target field
        tilHydration = findViewById(R.id.tilHydration)
        etHydration = findViewById(R.id.etHydration)

        // Password strength indicator components
        tvStrengthValue = findViewById(R.id.tvStrengthValue)
        strengthSegment1 = findViewById(R.id.strengthSegment1)
        strengthSegment2 = findViewById(R.id.strengthSegment2)
        strengthSegment3 = findViewById(R.id.strengthSegment3)
        strengthSegment4 = findViewById(R.id.strengthSegment4)

        // Password criteria checklist
        tvCriteriaLength = findViewById(R.id.tvCriteriaLength)
        tvCriteriaCase = findViewById(R.id.tvCriteriaCase)
        tvCriteriaNumber = findViewById(R.id.tvCriteriaNumber)
        tvCriteriaSymbol = findViewById(R.id.tvCriteriaSymbol)

        // Other controls
        cbTerms = findViewById(R.id.cbTerms)
        btnCreateAccount = findViewById(R.id.btnCreateAccount)
        tvLoginPrompt = findViewById(R.id.tvLoginPrompt)
    }

    // ═══════════════════════════════════════════════════════════════════
    // PASSWORD STRENGTH MONITORING (unchanged)
    // ═══════════════════════════════════════════════════════════════════

    private fun setupPasswordStrengthWatcher() {
        etPassword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {
                updatePasswordStrength(s.toString())
            }
        })
    }

    private fun updatePasswordStrength(password: String) {
        var score = 0

        val hasLength = password.length >= 8
        if (hasLength) score++
        updateCriteriaIndicator(tvCriteriaLength, hasLength, getString(R.string.criteria_length))

        val hasUppercase = password.any { it.isUpperCase() }
        val hasLowercase = password.any { it.isLowerCase() }
        val hasCase = hasUppercase && hasLowercase
        if (hasCase) score++
        updateCriteriaIndicator(tvCriteriaCase, hasCase, getString(R.string.criteria_case))

        val hasNumber = password.any { it.isDigit() }
        if (hasNumber) score++
        updateCriteriaIndicator(tvCriteriaNumber, hasNumber, getString(R.string.criteria_number))

        val hasSymbol = password.any { !it.isLetterOrDigit() }
        if (hasSymbol) score++
        updateCriteriaIndicator(tvCriteriaSymbol, hasSymbol, getString(R.string.criteria_symbol))

        updateStrengthBar(score)
        updateStrengthLabel(score)
    }

    private fun updateCriteriaIndicator(textView: TextView, isMet: Boolean, baseText: String) {
        if (isMet) {
            textView.text = baseText.replaceFirst("○", "●")
            textView.setTextColor(ContextCompat.getColor(this, R.color.strength_good))
        } else {
            textView.text = baseText.replaceFirst("●", "○")
            textView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun updateStrengthBar(score: Int) {
        val segments = listOf(strengthSegment1, strengthSegment2, strengthSegment3, strengthSegment4)

        val activeColorRes = when (score) {
            1 -> R.color.strength_weak
            2 -> R.color.strength_fair
            3 -> R.color.strength_good
            4 -> R.color.strength_strong
            else -> R.color.strength_inactive
        }

        val inactiveColorRes = R.color.strength_inactive

        for (i in segments.indices) {
            val colorRes = if (i < score) activeColorRes else inactiveColorRes
            val color = ContextCompat.getColor(this, colorRes)
            segments[i].backgroundTintList = ColorStateList.valueOf(color)
        }
    }

    private fun updateStrengthLabel(score: Int) {
        tvStrengthValue.text = when (score) {
            1 -> getString(R.string.strength_weak)
            2 -> getString(R.string.strength_fair)
            3 -> getString(R.string.strength_good)
            4 -> getString(R.string.strength_strong)
            else -> getString(R.string.strength_placeholder)
        }

        val colorRes = when (score) {
            1 -> R.color.strength_weak
            2 -> R.color.strength_fair
            3 -> R.color.strength_good
            4 -> R.color.strength_strong
            else -> R.color.text_secondary
        }
        tvStrengthValue.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    // ═══════════════════════════════════════════════════════════════════
    // TERMS OF SERVICE CHECKBOX (unchanged)
    // ═══════════════════════════════════════════════════════════════════

    private fun setupTermsCheckbox() {
        val fullText = getString(R.string.terms_agreement)
        val spannable = SpannableString(fullText)

        val tosText = getString(R.string.terms_of_service)
        val tosStart = fullText.indexOf(tosText)
        if (tosStart >= 0) {
            spannable.setSpan(
                createLinkSpan("Terms of Service"),
                tosStart,
                tosStart + tosText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        val ppText = getString(R.string.privacy_policy)
        val ppStart = fullText.indexOf(ppText)
        if (ppStart >= 0) {
            spannable.setSpan(
                createLinkSpan("Privacy Policy"),
                ppStart,
                ppStart + ppText.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        cbTerms.text = spannable
        cbTerms.movementMethod = LinkMovementMethod.getInstance()
        cbTerms.highlightColor = ContextCompat.getColor(this, android.R.color.transparent)
    }

    private fun createLinkSpan(linkName: String): ClickableSpan {
        return object : ClickableSpan() {
            override fun onClick(widget: View) {
                Toast.makeText(
                    this@RegisterActivity,
                    "$linkName – Coming soon!",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.color = ContextCompat.getColor(
                    this@RegisterActivity, R.color.teal_primary
                )
                ds.isUnderlineText = true
                ds.isFakeBoldText = true
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CREATE ACCOUNT BUTTON
    // ═══════════════════════════════════════════════════════════════════

    private fun setupCreateAccountButton() {
        btnCreateAccount.setOnClickListener {
            // ── Clear all previous error states ──
            tilFirstName.error = null
            tilMiddleName.error = null
            tilLastName.error = null
            tilEmail.error = null
            tilPassword.error = null
            tilConfirmPassword.error = null
            tilHydration.error = null

            // ── Extract input values ──
            val firstName = etFirstName.text.toString().trim()
            val middleName = etMiddleName.text.toString().trim()
            val lastName = etLastName.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString()
            val confirmPassword = etConfirmPassword.text.toString()
            val hydrationText = etHydration.text.toString().trim()

            // ── Validation 1a: First Name ──
            if (firstName.isEmpty()) {
                tilFirstName.error = getString(R.string.error_first_name_empty)
                etFirstName.requestFocus()
                return@setOnClickListener
            }

            // Middle name is optional — no validation

            // ── Validation 1b: Last Name ──
            if (lastName.isEmpty()) {
                tilLastName.error = getString(R.string.error_last_name_empty)
                etLastName.requestFocus()
                return@setOnClickListener
            }

            // Combine into a single display full name.
            // Middle name is only included if the user provided it.
            val fullName = if (middleName.isEmpty()) {
                "$firstName $lastName"
            } else {
                "$firstName $middleName $lastName"
            }

            // ── Validation 2: Email ──
            if (email.isEmpty()) {
                tilEmail.error = getString(R.string.error_email_empty)
                etEmail.requestFocus()
                return@setOnClickListener
            }
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                tilEmail.error = getString(R.string.error_email_invalid)
                etEmail.requestFocus()
                return@setOnClickListener
            }

            // ── Validation 3: Password ──
            if (password.isEmpty()) {
                tilPassword.error = getString(R.string.error_password_empty)
                etPassword.requestFocus()
                return@setOnClickListener
            }
            if (password.length < 8) {
                tilPassword.error = getString(R.string.error_password_short)
                etPassword.requestFocus()
                return@setOnClickListener
            }

            // ── Validation 4: Confirm Password ──
            if (confirmPassword != password) {
                tilConfirmPassword.error = getString(R.string.error_password_mismatch)
                etConfirmPassword.requestFocus()
                return@setOnClickListener
            }

            // ── Validation 5: Hydration Target ──
            if (hydrationText.isEmpty()) {
                tilHydration.error = getString(R.string.error_hydration_empty)
                etHydration.requestFocus()
                return@setOnClickListener
            }
            val hydrationValue = hydrationText.toIntOrNull()
            if (hydrationValue == null || hydrationValue < 500 || hydrationValue > 10000) {
                tilHydration.error = getString(R.string.error_hydration_range)
                etHydration.requestFocus()
                return@setOnClickListener
            }

            // ── Validation 6: Terms of Service ──
            if (!cbTerms.isChecked) {
                Toast.makeText(
                    this,
                    getString(R.string.error_terms_required),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // ══════════════════════════════════════════════════════════
            // ALL VALIDATIONS PASSED – Register with Firebase
            // ══════════════════════════════════════════════════════════
            btnCreateAccount.isEnabled = false

            firebaseAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        val user = firebaseAuth.currentUser

                        if (user != null) {
                            val uid = user.uid

                            // Build the user profile data object
                            // NOTE: UserProfile currently only has `fullName`.
                            // If you want to store the fields separately, update
                            // UserProfile.kt to add firstName/middleName/lastName.
                            val userProfile = UserProfile(
                                fullName = fullName,
                                email = email,
                                hydrationTarget = hydrationValue,
                                createdAt = System.currentTimeMillis()
                            )

                            val database = this.database
                            if (database != null) {
                                database.child("users").child(uid).setValue(userProfile)
                                    .addOnFailureListener { e ->
                                        e.printStackTrace()
                                    }
                            } else {
                                Log.e(
                                    "SipSenseDatabase",
                                    "Profile for the new account was not stored: no Realtime " +
                                            "Database is configured for this build."
                                )
                            }

                            // Save the display name on the Auth user
                            val profileUpdate = UserProfileChangeRequest.Builder()
                                .setDisplayName(fullName)
                                .build()
                            user.updateProfile(profileUpdate)
                                .addOnCompleteListener(this) { updateTask ->
                                    if (!updateTask.isSuccessful) {
                                        Log.e(
                                            "SipSenseAuth",
                                            "Failed to save display name",
                                            updateTask.exception
                                        )
                                    }
                                    navigateToLogin()
                                }
                        } else {
                            navigateToLogin()
                        }
                    } else {
                        Toast.makeText(
                            this,
                            "Registration failed: ${task.exception?.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        btnCreateAccount.isEnabled = true
                    }
                }
        }
    }

    private fun navigateToLogin() {
        Toast.makeText(
            this,
            getString(R.string.msg_register_success),
            Toast.LENGTH_SHORT
        ).show()

        firebaseAuth.signOut()

        val intent = Intent(this, LoginActivity::class.java)
        startActivity(intent)
        finish()
    }

    // ═══════════════════════════════════════════════════════════════════
    // LOGIN NAVIGATION PROMPT (unchanged)
    // ═══════════════════════════════════════════════════════════════════

    private fun setupLoginPrompt() {
        val prefix = getString(R.string.prompt_has_account)
        val linkText = getString(R.string.link_log_in)
        val fullText = "$prefix $linkText"

        val spannable = SpannableString(fullText)
        val linkStart = fullText.indexOf(linkText)
        val linkEnd = linkStart + linkText.length

        spannable.setSpan(
            object : ClickableSpan() {
                override fun onClick(widget: View) {
                    val intent = Intent(this@RegisterActivity, LoginActivity::class.java)
                    startActivity(intent)
                    finish()
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = ContextCompat.getColor(
                        this@RegisterActivity, R.color.teal_primary
                    )
                    ds.isUnderlineText = true
                    ds.isFakeBoldText = true
                }
            },
            linkStart,
            linkEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        tvLoginPrompt.text = spannable
        tvLoginPrompt.movementMethod = LinkMovementMethod.getInstance()
        tvLoginPrompt.highlightColor = ContextCompat.getColor(this, android.R.color.transparent)
    }
}