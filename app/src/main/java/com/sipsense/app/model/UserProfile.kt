package com.sipsense.app.model

/**
 * UserProfile.kt
 *
 * Data class representing a user's profile in the SipSense application.
 * This model is stored in Firebase Realtime Database under the path:
 *   /users/{uid}
 *
 * where {uid} is the Firebase Authentication unique user ID.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * DATABASE STRUCTURE:
 * ──────────────────────────────────────────────────────────────────────────
 *   users/
 *   └── {uid}/
 *       ├── fullName: "John Doe"
 *       ├── email: "john@example.com"
 *       ├── hydrationTarget: 2500
 *       └── createdAt: 1727654400000
 *
 * ══════════════════════════════════════════════════════════════════════════
 * USAGE:
 * ──────────────────────────────────────────────────────────────────────────
 * - Written to the database during registration (RegisterActivity)
 * - Read from the database to display user info (ProfileFragment)
 * - All default values are required for Firebase deserialization
 *
 * @property fullName   The user's display name (entered during registration)
 * @property email      The user's email address (also used for authentication)
 * @property hydrationTarget  Daily water intake goal in milliliters (default: 2500 mL)
 * @property createdAt  Unix timestamp (ms) of when the account was created
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 1.0
 */
data class UserProfile(
    val fullName: String = "",
    val email: String = "",
    val hydrationTarget: Int = 2500,
    val createdAt: Long = System.currentTimeMillis()
)
