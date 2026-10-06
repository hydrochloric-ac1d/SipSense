package com.sipsense.app.data

import android.util.Log
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

/**
 * FirebaseDatabaseProvider.kt
 *
 * Single point at which SipSense obtains its Firebase Realtime Database reference.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * WHY THIS EXISTS:
 * ──────────────────────────────────────────────────────────────────────────
 * The database URL is resolved from the `app/google-services.json` bundled at
 * build time, exactly like Firebase Authentication. Because both concerns read
 * the same configuration, a build cannot authenticate against one Firebase
 * project while reading and writing another project's database.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * WHEN THE DATABASE CANNOT BE RESOLVED:
 * ──────────────────────────────────────────────────────────────────────────
 * A Firebase project only has a database URL once a Realtime Database has been
 * created in it. If the configured project has none, the SDK cannot determine a
 * URL and [reference] returns null rather than throwing, so a misconfigured
 * build degrades to a visible error instead of terminating a screen.
 *
 * Callers MUST treat null as "database unavailable" and surface their existing
 * failure message.
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 1.0
 */
object FirebaseDatabaseProvider {

    private const val TAG = "SipSenseDatabase"

    /**
     * Returns the root reference of the Realtime Database belonging to the
     * Firebase project this build was configured with.
     *
     * @return the root [DatabaseReference], or null when the configured Firebase
     *         project has no Realtime Database. Never throws.
     */
    fun reference(): DatabaseReference? =
        try {
            FirebaseDatabase.getInstance().reference
        } catch (e: Exception) {
            // The configured project has no Realtime Database, so the SDK has no
            // URL to resolve. Name the fix; never log configuration contents.
            Log.e(
                TAG,
                "No Realtime Database is configured for this build. Create a Realtime " +
                    "Database in the Firebase project used by app/google-services.json, then " +
                    "download that file again so it includes the database URL, and rebuild.",
                e
            )
            null
        }
}
