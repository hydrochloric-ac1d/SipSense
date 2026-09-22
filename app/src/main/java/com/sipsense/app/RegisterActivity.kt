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
 * 1. Full Name, Email, Password, and Confirm Password fields
 * 2. Real-time password strength indicator:
 *    - 4-segment color-coded strength bar
 *    - 4 criteria checklist (length, case, number, symbol)
 *    - Text label updates (Weak / Fair / Good / Strong)
 * 3. Password confirmation with mismatch detection
 * 4. Daily Hydration Target input (number with mL unit)
 * 5. Terms of Service checkbox with clickable links
 * 6. Comprehensive form validation on submit
 * 7. Tab-based navigation to LoginActivity ("Log In" tab)
 *
 * ══════════════════════════════════════════════════════════════════════════
 * PASSWORD STRENGTH SCORING:
 * ──────────────────────────────────────────────────────────────────────────
 * Score is calculated based on 4 independent criteria:
 * | Score | Criteria Met    | Label  | Bar Color | Segments Lit |
 * |-------|-----------------|--------|-----------|--------------|
 * | 0     | None            | --     | Grey      | 0/4          |
 * | 1     | 1 of 4          | Weak   | Red       | 1/4          |
 * | 2     | 2 of 4          | Fair   | Orange    | 2/4          |
 * | 3     | 3 of 4          | Good   | Teal      | 3/4          |
 * | 4     | All 4           | Strong | Green     | 4/4          |
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

class RegisterActivity : AppCompatActivity() {

    // ═══════════════════════════════════════════════════════════════════
    // VIEW REFERENCES
    // ═══════════════════════════════════════════════════════════════════

    // ── Tab Navigation ───────────────────────────────────────────────
    /** "Log In" tab – inactive on this screen, navigates to LoginActivity */
    private lateinit var tabLogin: TextView

    /** "Sign Up" tab – active on this screen (teal text, white bg) */
    private lateinit var tabSignUp: TextView

    // ── Form Input Fields ────────────────────────────────────────────
    /** Full Name field container and input */
    private lateinit var tilFullName: TextInputLayout
    private lateinit var etFullName: TextInputEditText

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
    private lateinit var tvCriteriaLength: TextView   // "○  8+ characters"
    private lateinit var tvCriteriaCase: TextView     // "○  Upper & Lowercase"
    private lateinit var tvCriteriaNumber: TextView   // "○  1+ number"
    private lateinit var tvCriteriaSymbol: TextView   // "○  1+ symbol (!@#$)"

    // ── Other Controls ───────────────────────────────────────────────
    /** Terms of Service agreement checkbox (must be checked to register) */
    private lateinit var cbTerms: CheckBox

    /** "Create SipSense Account" button */
    private lateinit var btnCreateAccount: Button

    /** "Already have an account? Log In" navigation prompt */
    private lateinit var tvLoginPrompt: TextView

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Called when the activity is first created.
     * Initializes all views, configures event listeners, and sets up
     * the password strength monitoring system.
     *
     * @param savedInstanceState Bundle containing previously saved state (if any)
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_register)

        // Bind all view references from the layout XML
        initializeViews()

        // Configure all interactive elements
        setupTabNavigation()
        setupPasswordStrengthWatcher()
        setupTermsCheckbox()
        setupCreateAccountButton()
        setupLoginPrompt()
    }

    // ═══════════════════════════════════════════════════════════════════
    // VIEW INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Binds all UI elements from the layout XML to their Kotlin references.
     * Organized by section: tabs, form fields, strength indicator, controls.
     */
    private fun initializeViews() {
        // Tab navigation
        tabLogin = findViewById(R.id.tabLogin)
        tabSignUp = findViewById(R.id.tabSignUp)

        // Full Name field
        tilFullName = findViewById(R.id.tilFullName)
        etFullName = findViewById(R.id.etFullName)

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

        // Tint the login tab icon to grey (inactive) on this screen
        // since the default drawable fill is teal (active on login screen)
        tabLogin.compoundDrawablesRelative[0]?.mutate()?.setTint(
            ContextCompat.getColor(this, R.color.text_secondary)
        )

        // Tint the sign up tab icon to teal (active) on this screen
        // since the default drawable fill is grey (inactive on login screen)
        tabSignUp.compoundDrawablesRelative[0]?.mutate()?.setTint(
            ContextCompat.getColor(this, R.color.teal_primary)
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // TAB NAVIGATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sets up tab navigation to switch between Login and Registration screens.
     *
     * On this screen (RegisterActivity):
     * - "Sign Up" tab is active (no action on tap)
     * - "Log In" tab navigates to LoginActivity
     */
    private fun setupTabNavigation() {
        tabLogin.setOnClickListener {
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish() // Close registration to prevent back-stack buildup
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // PASSWORD STRENGTH MONITORING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Attaches a TextWatcher to the password input field that triggers
     * real-time password strength evaluation on every keystroke.
     *
     * The TextWatcher calls [updatePasswordStrength] after each text change,
     * which updates the strength bar, label, and criteria indicators.
     */
    private fun setupPasswordStrengthWatcher() {
        etPassword.addTextChangedListener(object : TextWatcher {
            // Not used – required by interface
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            // Not used – required by interface
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            /**
             * Called after the password text has changed.
             * Triggers a full password strength re-evaluation.
             */
            override fun afterTextChanged(s: Editable?) {
                updatePasswordStrength(s.toString())
            }
        })
    }

    /**
     * Evaluates password strength based on 4 independent criteria and
     * updates all related UI elements accordingly.
     *
     * Criteria:
     * 1. **Length**: Password is at least 8 characters long
     * 2. **Case**: Contains both uppercase AND lowercase letters
     * 3. **Number**: Contains at least one digit (0-9)
     * 4. **Symbol**: Contains at least one special character (!@#$%^&*...)
     *
     * Each met criterion contributes 1 point to the score (0-4).
     * The score determines the strength label, bar color, and lit segments.
     *
     * @param password The current password text to evaluate
     */
    private fun updatePasswordStrength(password: String) {
        var score = 0

        // ── Criterion 1: Minimum length of 8 characters ──
        val hasLength = password.length >= 8
        if (hasLength) score++
        updateCriteriaIndicator(tvCriteriaLength, hasLength, getString(R.string.criteria_length))

        // ── Criterion 2: Contains both upper and lowercase letters ──
        val hasUppercase = password.any { it.isUpperCase() }
        val hasLowercase = password.any { it.isLowerCase() }
        val hasCase = hasUppercase && hasLowercase
        if (hasCase) score++
        updateCriteriaIndicator(tvCriteriaCase, hasCase, getString(R.string.criteria_case))

        // ── Criterion 3: Contains at least one digit ──
        val hasNumber = password.any { it.isDigit() }
        if (hasNumber) score++
        updateCriteriaIndicator(tvCriteriaNumber, hasNumber, getString(R.string.criteria_number))

        // ── Criterion 4: Contains at least one special character ──
        val hasSymbol = password.any { !it.isLetterOrDigit() }
        if (hasSymbol) score++
        updateCriteriaIndicator(tvCriteriaSymbol, hasSymbol, getString(R.string.criteria_symbol))

        // Update the visual strength bar and text label
        updateStrengthBar(score)
        updateStrengthLabel(score)
    }

    /**
     * Updates a single criteria indicator's visual state.
     *
     * When the criterion is met:
     * - Circle prefix changes from ○ (outline) to ● (filled)
     * - Text color changes to teal (strength_good)
     *
     * When the criterion is NOT met:
     * - Circle prefix shows ○ (outline)
     * - Text color is grey (text_secondary)
     *
     * @param textView The criteria TextView to update
     * @param isMet Whether the criterion is currently satisfied
     * @param baseText The original criteria text from strings.xml (e.g., "○  8+ characters")
     */
    private fun updateCriteriaIndicator(textView: TextView, isMet: Boolean, baseText: String) {
        if (isMet) {
            // Replace outline circle with filled circle and apply teal color
            textView.text = baseText.replaceFirst("○", "●")
            textView.setTextColor(ContextCompat.getColor(this, R.color.strength_good))
        } else {
            // Ensure outline circle is shown with grey color
            textView.text = baseText.replaceFirst("●", "○")
            textView.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    /**
     * Updates the 4-segment password strength bar based on the current score.
     *
     * Segments are lit from left to right. All lit segments share the same
     * color corresponding to the current strength level:
     * - Score 0: All grey (inactive)
     * - Score 1: 1 red segment, 3 grey
     * - Score 2: 2 orange segments, 2 grey
     * - Score 3: 3 teal segments, 1 grey
     * - Score 4: 4 green segments (all lit)
     *
     * Uses backgroundTintList to change segment colors while preserving
     * the rounded corner shape defined in bg_password_strength_segment.xml.
     *
     * @param score The number of criteria met (0-4)
     */
    private fun updateStrengthBar(score: Int) {
        // Collect all 4 segments into a list for indexed iteration
        val segments = listOf(strengthSegment1, strengthSegment2, strengthSegment3, strengthSegment4)

        // Determine the color for lit segments based on score
        val activeColorRes = when (score) {
            1 -> R.color.strength_weak      // Red – weak
            2 -> R.color.strength_fair      // Orange – fair
            3 -> R.color.strength_good      // Teal – good
            4 -> R.color.strength_strong    // Green – strong
            else -> R.color.strength_inactive // Grey – none met
        }

        // Inactive segment color (grey)
        val inactiveColorRes = R.color.strength_inactive

        // Update each segment: lit if index < score, otherwise grey
        for (i in segments.indices) {
            val colorRes = if (i < score) activeColorRes else inactiveColorRes
            val color = ContextCompat.getColor(this, colorRes)

            // Use backgroundTintList to preserve the drawable shape
            segments[i].backgroundTintList = ColorStateList.valueOf(color)
        }
    }

    /**
     * Updates the password strength text label based on the current score.
     *
     * Label values and their corresponding colors:
     * - 0: "--" (grey) – no criteria met
     * - 1: "Weak" (red)
     * - 2: "Fair" (orange)
     * - 3: "Good" (teal)
     * - 4: "Strong" (green)
     *
     * @param score The number of criteria met (0-4)
     */
    private fun updateStrengthLabel(score: Int) {
        // Set the strength text
        tvStrengthValue.text = when (score) {
            1 -> getString(R.string.strength_weak)
            2 -> getString(R.string.strength_fair)
            3 -> getString(R.string.strength_good)
            4 -> getString(R.string.strength_strong)
            else -> getString(R.string.strength_placeholder)
        }

        // Set the text color to match the strength level
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
    // TERMS OF SERVICE CHECKBOX
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Configures the Terms of Service checkbox with clickable links.
     *
     * Builds a SpannableString:
     * "I agree to the **Terms of Service** and **Privacy Policy**."
     *
     * Both "Terms of Service" and "Privacy Policy" are rendered as
     * clickable, teal-colored links. Currently show toast placeholders.
     */
    private fun setupTermsCheckbox() {
        val fullText = getString(R.string.terms_agreement)
        val spannable = SpannableString(fullText)

        // "Terms of Service" clickable span
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

        // "Privacy Policy" clickable span
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

        // Apply the spannable text to the checkbox
        cbTerms.text = spannable
        cbTerms.movementMethod = LinkMovementMethod.getInstance()
        cbTerms.highlightColor = ContextCompat.getColor(this, android.R.color.transparent)
    }

    /**
     * Creates a ClickableSpan for terms/privacy links in the checkbox text.
     *
     * The link is styled with teal color and underline, and displays
     * a toast with the link name when tapped.
     *
     * @param linkName The name of the link (for the toast message)
     * @return A configured ClickableSpan instance
     */
    private fun createLinkSpan(linkName: String): ClickableSpan {
        return object : ClickableSpan() {
            override fun onClick(widget: View) {
                // TODO: Open Terms of Service or Privacy Policy page
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

    /**
     * Configures the "Create SipSense Account" button with comprehensive
     * form validation.
     *
     * Validation flow (stops at first failure):
     * 1. Full Name – must not be empty
     * 2. Email – must not be empty and must be valid format
     * 3. Password – must not be empty and must be >= 8 characters
     * 4. Confirm Password – must match the password field
     * 5. Hydration Target – must not be empty and within valid range (500-10000 mL)
     * 6. Terms of Service – checkbox must be checked
     *
     * If all validations pass, displays a success toast.
     * TODO: Replace with actual registration API call.
     */
    private fun setupCreateAccountButton() {
        btnCreateAccount.setOnClickListener {
            // ── Clear all previous error states ──
            tilFullName.error = null
            tilEmail.error = null
            tilPassword.error = null
            tilConfirmPassword.error = null
            tilHydration.error = null

            // ── Extract input values ──
            val fullName = etFullName.text.toString().trim()
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString()
            val confirmPassword = etConfirmPassword.text.toString()
            val hydrationText = etHydration.text.toString().trim()

            // ── Validation 1: Full Name ──
            if (fullName.isEmpty()) {
                tilFullName.error = getString(R.string.error_name_empty)
                etFullName.requestFocus()
                return@setOnClickListener
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
            // ALL VALIDATIONS PASSED – Process registration
            // ══════════════════════════════════════════════════════════

            // Show success feedback
            Toast.makeText(
                this,
                getString(R.string.msg_register_success),
                Toast.LENGTH_SHORT
            ).show()

            // TODO: Replace with actual registration logic
            // Example: FirebaseAuth.getInstance().createUserWithEmailAndPassword(email, password)
            // Example: Retrofit API call to POST /api/auth/register
            //          with body: { fullName, email, password, hydrationTarget }

            // Navigate to Login screen after successful registration
            val intent = Intent(this, LoginActivity::class.java)
            startActivity(intent)
            finish()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // LOGIN NAVIGATION PROMPT
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Creates a SpannableString for the login navigation prompt:
     * "Already have an account? **Log In**"
     *
     * The "Log In" portion is a clickable, teal-colored link that
     * navigates back to LoginActivity.
     */
    private fun setupLoginPrompt() {
        val prefix = getString(R.string.prompt_has_account)
        val linkText = getString(R.string.link_log_in)
        val fullText = "$prefix $linkText"

        val spannable = SpannableString(fullText)
        val linkStart = fullText.indexOf(linkText)
        val linkEnd = linkStart + linkText.length

        spannable.setSpan(
            object : ClickableSpan() {
                /**
                 * Called when "Log In" text is tapped.
                 * Navigates to LoginActivity and closes RegisterActivity.
                 */
                override fun onClick(widget: View) {
                    val intent = Intent(this@RegisterActivity, LoginActivity::class.java)
                    startActivity(intent)
                    finish()
                }

                /**
                 * Styles the "Log In" link with teal color, underline, and bold.
                 */
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
