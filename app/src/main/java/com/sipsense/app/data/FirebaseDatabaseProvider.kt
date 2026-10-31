package com.sipsense.app.data

import android.util.Log
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

/**
 * FirebaseDatabaseProvider.kt
 *
 * Single point at which SipSense obtains its Firebase Realtime Database reference.
 * Supports regional Realtime Database instances (e.g. asia-southeast1).
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 1.0
 */
object FirebaseDatabaseProvider {

    private const val TAG = "SipSenseDatabase"
    const val DATABASE_URL = "https://sipsense-17a90-default-rtdb.asia-southeast1.firebasedatabase.app"

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
            try {
                FirebaseDatabase.getInstance(DATABASE_URL).reference
            } catch (fallbackEx: Exception) {
                Log.e(
                    TAG,
                    "No Realtime Database is configured for this build. Attempted $DATABASE_URL",
                    fallbackEx
                )
                null
            }
        }
}
