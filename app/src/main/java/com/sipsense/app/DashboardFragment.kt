package com.sipsense.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import com.sipsense.app.data.FirebaseDatabaseProvider
import com.sipsense.app.model.UserProfile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DashboardFragment : Fragment() {

    private lateinit var tvGreeting: TextView
    private lateinit var progressHydration: CircularProgressIndicator
    private lateinit var tvProgressCurrent: TextView
    private lateinit var tvProgressMax: TextView
    private lateinit var tvRecentSipTime: TextView

    private val firebaseAuth = FirebaseAuth.getInstance()
    private val database = FirebaseDatabaseProvider.reference()
    
    private var profileListener: ValueEventListener? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_dashboard, container, false)
        
        tvGreeting = view.findViewById(R.id.tv_greeting)
        progressHydration = view.findViewById(R.id.progress_hydration)
        tvProgressCurrent = view.findViewById(R.id.tv_progress_current)
        tvProgressMax = view.findViewById(R.id.tv_progress_max)
        tvRecentSipTime = view.findViewById(R.id.tv_recent_sip_time)

        setupGreeting()
        setupRecentSipTime()
        loadUserProfile()

        return view
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        val uid = firebaseAuth.currentUser?.uid
        if (uid != null && profileListener != null) {
            database?.child("users")?.child(uid)?.removeEventListener(profileListener!!)
        }
    }

    private fun setupGreeting(firstName: String = "User") {
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)

        val greetingRes = when (hour) {
            in 0..11 -> R.string.greeting_morning
            in 12..16 -> R.string.greeting_afternoon
            else -> R.string.greeting_evening
        }

        tvGreeting.text = getString(greetingRes, firstName)
    }

    private fun setupRecentSipTime() {
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        val currentTime = sdf.format(Calendar.getInstance().time)
        tvRecentSipTime.text = currentTime
    }

    private fun loadUserProfile() {
        val user = firebaseAuth.currentUser ?: return
        val uid = user.uid

        // Set default name first
        val authName = user.displayName?.takeIf { it.isNotBlank() }
            ?: user.email?.substringBefore('@').orEmpty().ifBlank { "User" }
        val firstName = authName.substringBefore(" ").replaceFirstChar { it.uppercase() }
        setupGreeting(firstName)

        profileListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!isAdded) return

                val profile = snapshot.getValue(UserProfile::class.java) ?: return
                
                val name = profile.fullName.takeIf { it.isNotBlank() } ?: firstName
                val shortName = name.substringBefore(" ").replaceFirstChar { it.uppercase() }
                setupGreeting(shortName)

                val target = profile.hydrationTarget
                progressHydration.max = target
                tvProgressMax.text = getString(R.string.progress_max_format_ml, target)
                
                // Placeholder for current progress
                val currentProgress = 1750
                progressHydration.progress = currentProgress
                tvProgressCurrent.text = getString(R.string.progress_format_ml, currentProgress)
            }

            override fun onCancelled(error: DatabaseError) {
                if (!isAdded) return
                Toast.makeText(context, "Failed to load profile", Toast.LENGTH_SHORT).show()
            }
        }
        
        database?.child("users")?.child(uid)?.addValueEventListener(profileListener!!)
    }
}
