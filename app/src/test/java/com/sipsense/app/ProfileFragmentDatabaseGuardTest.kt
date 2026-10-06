/*
 * ═══════════════════════════════════════════════════════════════════════════
 *  ProfileFragmentDatabaseGuardTest.kt
 * ───────────────────────────────────────────────────────────────────────────
 *  Verifies the user-visible half of Feature 002's failure path: when the
 *  configured Firebase project resolves no Realtime Database reference,
 *  ProfileFragment must inform the user and return, never dereference null.
 *
 *  Each guard is covered twice - once where it must trigger, and once where it
 *  must NOT. The negative case is what proves a test detects the branch rather
 *  than passing because the code was never reached.
 *
 *  Runs on Robolectric because the code under test lives in a Fragment and
 *  reports failure through a Toast, both of which need real Android behaviour.
 *
 *  @project SipSense - Smart Bottle Ecosystem
 * ═══════════════════════════════════════════════════════════════════════════
 */
package com.sipsense.app

import com.google.android.material.slider.Slider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.database.DatabaseReference
import com.sipsense.app.data.FirebaseDatabaseProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
class ProfileFragmentDatabaseGuardTest {

    private lateinit var user: FirebaseUser

    @Before
    fun setUp() {
        // ProfileFragment resolves FirebaseAuth and the database reference in field
        // initializers, so both must be stubbed before the fragment is constructed.
        mockkStatic(FirebaseAuth::class)
        user = mockk(relaxed = true)
        every { user.uid } returns TEST_UID
        every { user.email } returns "test@example.com"
        val auth = mockk<FirebaseAuth>(relaxed = true)
        every { auth.currentUser } returns user
        every { FirebaseAuth.getInstance() } returns auth

        mockkObject(FirebaseDatabaseProvider)
    }

    @After
    fun tearDown() = unmockkAll()

    // ── Profile load ────────────────────────────────────────────────────────

    @Test
    fun `load reports failure to the user when no database reference resolves`() {
        every { FirebaseDatabaseProvider.reference() } returns null

        attachProfileFragment()

        assertEquals("Failed to load profile", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `load does not report failure when a database reference resolves`() {
        every { FirebaseDatabaseProvider.reference() } returns mockk<DatabaseReference>(relaxed = true)

        attachProfileFragment()

        // The negative control. If this ever shows the failure message, the guard is
        // firing when a database is available, which would break the working path.
        assertNull(ShadowToast.getTextOfLatestToast())
    }

    // ── Hydration target save ───────────────────────────────────────────────

    @Test
    fun `save reports failure to the user when no database reference resolves`() {
        every { FirebaseDatabaseProvider.reference() } returns null
        val fragment = attachProfileFragment()
        ShadowToast.reset()

        invokeSaveHydrationTarget(fragment, 3000)

        assertEquals("Failed to update target", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `save does not report failure when a database reference resolves`() {
        every { FirebaseDatabaseProvider.reference() } returns mockk<DatabaseReference>(relaxed = true)
        val fragment = attachProfileFragment()
        ShadowToast.reset()

        invokeSaveHydrationTarget(fragment, 3000)

        assertNull(ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun `releasing the slider routes through the guard`() {
        every { FirebaseDatabaseProvider.reference() } returns null
        val fragment = attachProfileFragment()
        val slider = fragment.requireView().findViewById<Slider>(R.id.slider_hydration)
        ShadowToast.reset()

        // Covers the wiring, not just the method: a real touch release must reach
        // saveHydrationTarget via Slider.OnSliderTouchListener. The slider needs a
        // measured, laid-out width before it will process touches.
        layOut(slider)
        dispatchTouchRelease(slider)

        assertEquals("Failed to update target", ShadowToast.getTextOfLatestToast())
    }

    // ── Guard placement ─────────────────────────────────────────────────────

    @Test
    fun `an unresolved reference still leaves the screen usable`() {
        every { FirebaseDatabaseProvider.reference() } returns null

        val fragment = attachProfileFragment()

        // FR-004's real requirement: degrade, do not crash. Reaching this assertion at
        // all proves no exception escaped, and the view is present and interactive.
        val slider = fragment.requireView().findViewById<Slider>(R.id.slider_hydration)
        assertEquals(true, slider.isEnabled)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Hosts ProfileFragment in MainActivity, which is the real host in the app. */
    private fun attachProfileFragment(): ProfileFragment {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val fragment = ProfileFragment()
        activity.supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commitNow()
        return fragment
    }

    /**
     * `saveHydrationTarget` is private and wired to the slider's touch listener.
     * Invoking it directly tests the guard inside the method. The slider-to-method
     * wiring is deliberately not covered here - see the feature's tasks.md.
     */
    private fun invokeSaveHydrationTarget(fragment: ProfileFragment, target: Int) {
        val method = ProfileFragment::class.java
            .getDeclaredMethod("saveHydrationTarget", Int::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(fragment, target)
    }

    /** A view only processes touches once it has a non-zero measured size. */
    private fun layOut(view: android.view.View) {
        view.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(SLIDER_WIDTH, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(SLIDER_HEIGHT, android.view.View.MeasureSpec.EXACTLY)
        )
        view.layout(0, 0, SLIDER_WIDTH, SLIDER_HEIGHT)
    }

    /** Press and release at the centre, which is what triggers onStopTrackingTouch. */
    private fun dispatchTouchRelease(view: android.view.View) {
        val x = SLIDER_WIDTH / 2f
        val y = SLIDER_HEIGHT / 2f
        val down = android.view.MotionEvent.obtain(0L, 0L, android.view.MotionEvent.ACTION_DOWN, x, y, 0)
        val up = android.view.MotionEvent.obtain(0L, 10L, android.view.MotionEvent.ACTION_UP, x, y, 0)
        try {
            view.dispatchTouchEvent(down)
            view.dispatchTouchEvent(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private companion object {
        const val TEST_UID = "test-uid"
        const val SLIDER_WIDTH = 600
        const val SLIDER_HEIGHT = 100
    }
}
