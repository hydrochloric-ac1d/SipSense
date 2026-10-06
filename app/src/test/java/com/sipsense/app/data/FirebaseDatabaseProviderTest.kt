package com.sipsense.app.data

import com.google.firebase.database.DatabaseException
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test

/**
 * FirebaseDatabaseProviderTest.kt
 *
 * Verifies how SipSense resolves its Realtime Database reference from the bundled
 * Firebase configuration.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * WHY THESE TESTS EXIST:
 * ──────────────────────────────────────────────────────────────────────────
 * A Firebase project only has a database URL once a Realtime Database has been
 * created in it. When it has none, the SDK cannot resolve a URL and throws.
 * The provider must absorb that and report "no database" instead, because the
 * two screens that use it build their reference during construction - an escaping
 * exception would take a screen down rather than show an error.
 *
 * The success path is covered too: without it, the failure test would still pass
 * if resolution were broken in every case.
 *
 * ══════════════════════════════════════════════════════════════════════════
 * NOTE ON STATIC STUBBING:
 * ──────────────────────────────────────────────────────────────────────────
 * FirebaseDatabase.getInstance() is a static call with no injection seam, so it
 * is stubbed statically. Static stubs are global while active, so [tearDown]
 * removes it after every test - otherwise a stub would leak into later tests and
 * make results depend on execution order.
 *
 * @project SipSense - Smart Bottle Ecosystem
 * @version 1.0
 */
class FirebaseDatabaseProviderTest {

    @Before
    fun setUp() {
        mockkStatic(FirebaseDatabase::class)
    }

    @After
    fun tearDown() {
        unmockkStatic(FirebaseDatabase::class)
    }

    /**
     * The configured Firebase project has no Realtime Database.
     *
     * This is the state a developer hits before creating the database, and the
     * reason the provider returns a nullable reference at all.
     */
    @Test
    fun `returns null when the configured project has no database`() {
        every { FirebaseDatabase.getInstance() } throws
            DatabaseException("Can't determine Firebase Database URL.")

        val result = FirebaseDatabaseProvider.reference()

        assertNull("No database is configured, so no reference should be returned", result)
    }

    /**
     * The happy path. Guards against the failure test passing vacuously because
     * resolution never succeeds.
     */
    @Test
    fun `returns the root reference when a database is configured`() {
        val expectedReference = mockk<DatabaseReference>()
        val database = mockk<FirebaseDatabase> {
            every { reference } returns expectedReference
        }
        every { FirebaseDatabase.getInstance() } returns database

        val result = FirebaseDatabaseProvider.reference()

        assertNotNull("A configured database should produce a reference", result)
        assertSame(
            "The provider should return the configured database's root reference",
            expectedReference,
            result
        )
    }

    /**
     * Resolution fails in a way the SDK does not document.
     *
     * This is the only test that pins the deliberately broad `catch (e: Exception)`.
     * Narrowing it to DatabaseException would reintroduce a crash on any other
     * failure, and this test is what would catch that.
     */
    @Test
    fun `returns null when resolution fails unexpectedly`() {
        every { FirebaseDatabase.getInstance() } throws
            IllegalStateException("FirebaseApp was not initialized")

        val result = FirebaseDatabaseProvider.reference()

        assertNull("Any resolution failure should yield no reference, not an exception", result)
    }
}
