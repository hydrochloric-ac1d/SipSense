package com.sipsense.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UserProfileTest.kt
 *
 * Pins the field defaults on the profile record stored at /users/{uid}.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * WHY THIS MATTERS:
 * ──────────────────────────────────────────────────────────────────────────
 * Firebase reconstructs a stored profile by calling a no-argument constructor
 * and populating fields, which only works while every property has a default.
 * UserProfile's own documentation states this ("All default values are required
 * for Firebase deserialization"), but nothing enforced it until now.
 *
 * Removing a default breaks reading profiles back at runtime, not at compile
 * time in the screens that write them - so the damage would surface as users
 * seeing empty profiles rather than as a build failure.
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 1.0
 */
class UserProfileTest {

    /**
     * No-argument construction must remain possible, and must yield the
     * documented defaults.
     */
    @Test
    fun `constructs with documented defaults when given no arguments`() {
        val profile = UserProfile()

        assertEquals("fullName should default to empty", "", profile.fullName)
        assertEquals("email should default to empty", "", profile.email)
        assertEquals("hydrationTarget should default to 2500 mL", 2500, profile.hydrationTarget)
    }

    /**
     * createdAt defaults to the current time, so it is asserted as a range
     * rather than a fixed value.
     */
    @Test
    fun `defaults createdAt to the current time`() {
        val before = System.currentTimeMillis()
        val profile = UserProfile()
        val after = System.currentTimeMillis()

        assertTrue(
            "createdAt (${profile.createdAt}) should sit between $before and $after",
            profile.createdAt in before..after
        )
    }

    /**
     * Supplied values must survive construction unchanged - the defaults exist
     * for deserialization, not to override real input.
     */
    @Test
    fun `retains supplied values`() {
        val profile = UserProfile(
            fullName = "Ada Lovelace",
            email = "ada@example.com",
            hydrationTarget = 3000,
            createdAt = 1_727_654_400_000
        )

        assertEquals("Ada Lovelace", profile.fullName)
        assertEquals("ada@example.com", profile.email)
        assertEquals(3000, profile.hydrationTarget)
        assertEquals(1_727_654_400_000, profile.createdAt)
    }
}
