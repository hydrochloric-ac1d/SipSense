package com.sipsense.app

/**
 * LoginActivity.kt
 *
 * Main entry point of the SipSense application. Handles user authentication
 * through the Login screen.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * FEATURES:
 * ──────────────────────────────────────────────────────────────────────────
 * 1. Email & Password login form with real-time input validation
 * 2. Password visibility toggle (via Material TextInputLayout)
 * 3. "Remember me" checkbox with SharedPreferences persistence
 * 4. "Forgot Password?" link (placeholder for future implementation)
 * 5. Tab-based navigation to RegisterActivity ("Sign Up" tab)
 * 6. "Sign up now" clickable SpannableString for alternative navigation
 *
 * ══════════════════════════════════════════════════════════════════════════
 * LAYOUT: res/layout/activity_login.xml (ConstraintLayout-based)
 * MANIFEST: Declared as launcher activity (MAIN + LAUNCHER intent filter)
 * ══════════════════════════════════════════════════════════════════════════
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @author SipSense Development Team
 * @version 1.0
 * @since 2026-09-22
 */

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
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

class LoginActivity : AppCompatActivity() {

    // ═══════════════════════════════════════════════════════════════════
    // VIEW REFERENCES
    // All UI elements are bound in initializeViews() via findViewById().
    // ═══════════════════════════════════════════════════════════════════

    /** TextInputLayout wrapping the email field (provides error display) */
    private lateinit var tilEmail: TextInputLayout

    /** Email address input field */
    private lateinit var etEmail: TextInputEditText

    /** TextInputLayout wrapping the password field (provides toggle + error) */
    private lateinit var tilPassword: TextInputLayout

    /** Password input field */
    private lateinit var etPassword: TextInputEditText

    /** "Remember me" checkbox – persists email via SharedPreferences */
    private lateinit var cbRememberMe: CheckBox

    /** Primary action button – triggers login validation */
    private lateinit var btnLogin: Button

    /** "Log In" tab label (active state on this screen) */
    private lateinit var tabLogin: TextView

    /** "Sign Up" tab label – navigates to RegisterActivity when tapped */
    private lateinit var tabSignUp: TextView

    /** "Forgot Password?" link text */
    private lateinit var tvForgotPassword: TextView

    /** "Don't have an account? Sign up now" prompt with clickable link */
    private lateinit var tvSignUpPrompt: TextView

    // ═══════════════════════════════════════════════════════════════════
    // SHARED PREFERENCES
    // Used to persist the "Remember me" state and saved email address.
    // ═══════════════════════════════════════════════════════════════════

    /** SharedPreferences instance for persisting user preferences */
    private lateinit var sharedPreferences: SharedPreferences

    companion object {
        /** SharedPreferences file name */
        private const val PREFS_NAME = "SipSensePrefs"

        /** Key for storing the "remember me" boolean flag */
        private const val KEY_REMEMBER_ME = "remember_me"

        /** Key for storing the saved email address string */
        private const val KEY_SAVED_EMAIL = "saved_email"
    }

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Called when the activity is first created.
     * Initializes the UI, sets up event listeners, and restores
     * any saved credentials from SharedPreferences.
     *
     * @param savedInstanceState Bundle containing the activity's
     *                           previously saved state (if any)
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Initialize SharedPreferences for "Remember me" feature
        sharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Bind all view references
        initializeViews()

        // Configure interactive elements
        setupTabNavigation()
        setupLoginButton()
        setupSignUpPrompt()
        setupForgotPassword()

        // Restore previously saved email if "Remember me" was checked
        loadSavedCredentials()
    }

    // ═══════════════════════════════════════════════════════════════════
    // VIEW INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Binds all UI elements from the layout XML to their Kotlin references.
     * Called once during onCreate(). Uses findViewById() for each view.
     */
    private fun initializeViews() {
        // Input fields and their containers
        tilEmail = findViewById(R.id.tilEmail)
        etEmail = findViewById(R.id.etEmail)
        tilPassword = findViewById(R.id.tilPassword)
        etPassword = findViewById(R.id.etPassword)

        // Checkbox
        cbRememberMe = findViewById(R.id.cbRememberMe)

        // Buttons and clickable text
        btnLogin = findViewById(R.id.btnLogin)
        tabLogin = findViewById(R.id.tabLogin)
        tabSignUp = findViewById(R.id.tabSignUp)
        tvForgotPassword = findViewById(R.id.tvForgotPassword)
        tvSignUpPrompt = findViewById(R.id.tvSignUpPrompt)
    }

    // ═══════════════════════════════════════════════════════════════════
    // TAB NAVIGATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sets up the tab toggle navigation between Login and Registration screens.
     *
     * On this screen (LoginActivity):
     * - "Log In" tab is active (teal text, white background) – no action
     * - "Sign Up" tab is inactive (grey text) – navigates to RegisterActivity
     */
    private fun setupTabNavigation() {
        // Tapping "Sign Up" tab navigates to the registration screen
        tabSignUp.setOnClickListener {
            val intent = Intent(this, RegisterActivity::class.java)
            startActivity(intent)
            // Finish this activity so pressing Back from Register
            // doesn't return to Login (avoids back-stack buildup)
            finish()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // LOGIN BUTTON
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Configures the "Log In →" button click handler.
     *
     * Validation flow:
     * 1. Clear any existing error messages
     * 2. Validate email is not empty
     * 3. Validate email format using Android's Patterns.EMAIL_ADDRESS
     * 4. Validate password is not empty
     * 5. If all validations pass:
     *    - Save/clear credentials based on "Remember me" state
     *    - Show success toast
     *    - TODO: Call actual authentication API
     */
    private fun setupLoginButton() {
        btnLogin.setOnClickListener {
            // ── Step 1: Clear previous error states ──
            tilEmail.error = null
            tilPassword.error = null

            // ── Step 2: Extract and trim input values ──
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString()

            // ── Step 3: Validate email field ──
            if (email.isEmpty()) {
                tilEmail.error = getString(R.string.error_email_empty)
                etEmail.requestFocus()
                return@setOnClickListener
            }

            // Check email format against standard pattern
            if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                tilEmail.error = getString(R.string.error_email_invalid)
                etEmail.requestFocus()
                return@setOnClickListener
            }

            // ── Step 4: Validate password field ──
            if (password.isEmpty()) {
                tilPassword.error = getString(R.string.error_password_empty)
                etPassword.requestFocus()
                return@setOnClickListener
            }

            // ── Step 5: All validations passed ──
            // Persist or clear email based on "Remember me" checkbox
            saveCredentials(email)

            // Show success feedback to the user
            Toast.makeText(
                this,
                getString(R.string.msg_login_success),
                Toast.LENGTH_SHORT
            ).show()

            // TODO: Replace with actual authentication logic
            // Example: FirebaseAuth.getInstance().signInWithEmailAndPassword(email, password)
            // Example: Retrofit API call to POST /api/auth/login
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SIGN UP PROMPT (SPANNABLE LINK)
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Creates a SpannableString for the sign-up navigation prompt:
     * "Don't have an account? **Sign up now**"
     *
     * The "Sign up now" portion is rendered as a clickable, teal-colored,
     * underlined link that navigates to RegisterActivity when tapped.
     *
     * Uses LinkMovementMethod to make the clickable span interactive.
     */
    private fun setupSignUpPrompt() {
        // Build the full prompt text
        val prefix = getString(R.string.prompt_no_account)
        val linkText = getString(R.string.link_sign_up)
        val fullText = "$prefix $linkText"

        // Create a SpannableString from the full text
        val spannable = SpannableString(fullText)

        // Calculate the start and end indices of the link portion
        val linkStart = fullText.indexOf(linkText)
        val linkEnd = linkStart + linkText.length

        // Apply a ClickableSpan to the "Sign up now" portion
        spannable.setSpan(
            object : ClickableSpan() {
                /**
                 * Called when the user taps the "Sign up now" text.
                 * Navigates to RegisterActivity and closes LoginActivity.
                 */
                override fun onClick(widget: View) {
                    val intent = Intent(this@LoginActivity, RegisterActivity::class.java)
                    startActivity(intent)
                    finish()
                }

                /**
                 * Customizes the appearance of the clickable text:
                 * - Teal color matching the brand
                 * - Underlined for link affordance
                 * - Bold for emphasis
                 */
                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = ContextCompat.getColor(
                        this@LoginActivity, R.color.teal_primary
                    )
                    ds.isUnderlineText = true
                    ds.isFakeBoldText = true
                }
            },
            linkStart,
            linkEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        // Apply the spannable text and enable link handling
        tvSignUpPrompt.text = spannable
        tvSignUpPrompt.movementMethod = LinkMovementMethod.getInstance()

        // Remove the default highlight color for a cleaner look
        tvSignUpPrompt.highlightColor = ContextCompat.getColor(this, android.R.color.transparent)
    }

    // ═══════════════════════════════════════════════════════════════════
    // FORGOT PASSWORD
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Sets up the "Forgot Password?" link click handler.
     *
     * Currently displays a placeholder toast message.
     * TODO: Navigate to a password recovery screen or trigger
     *       a password reset email via the authentication backend.
     */
    private fun setupForgotPassword() {
        tvForgotPassword.setOnClickListener {
            Toast.makeText(
                this,
                getString(R.string.msg_forgot_password),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // SHARED PREFERENCES – REMEMBER ME
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Persists or clears the user's email address based on the
     * "Remember me" checkbox state.
     *
     * When checked:
     * - Saves the remember_me flag as true
     * - Stores the email address for auto-fill on next launch
     *
     * When unchecked:
     * - Sets the remember_me flag to false
     * - Removes any previously saved email address
     *
     * @param email The email address to save (only saved if checkbox is checked)
     */
    private fun saveCredentials(email: String) {
        val editor = sharedPreferences.edit()

        if (cbRememberMe.isChecked) {
            // Save email for auto-fill on next app launch
            editor.putBoolean(KEY_REMEMBER_ME, true)
            editor.putString(KEY_SAVED_EMAIL, email)
        } else {
            // Clear saved credentials
            editor.putBoolean(KEY_REMEMBER_ME, false)
            editor.remove(KEY_SAVED_EMAIL)
        }

        // Apply changes asynchronously (non-blocking)
        editor.apply()
    }

    /**
     * Loads previously saved credentials when the activity starts.
     *
     * If "Remember me" was previously checked:
     * - Auto-fills the email input field with the saved address
     * - Sets the checkbox to checked state
     *
     * This provides a seamless login experience for returning users.
     */
    private fun loadSavedCredentials() {
        val rememberMe = sharedPreferences.getBoolean(KEY_REMEMBER_ME, false)

        if (rememberMe) {
            // Retrieve and populate the saved email
            val savedEmail = sharedPreferences.getString(KEY_SAVED_EMAIL, "")
            etEmail.setText(savedEmail)
            cbRememberMe.isChecked = true
        }
    }
}
