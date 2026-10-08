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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class DashboardFragment : Fragment() {

    private lateinit var tvGreeting: TextView
    private lateinit var progressHydration: CircularProgressIndicator
    private lateinit var tvProgressCurrent: TextView
    private lateinit var tvProgressMax: TextView
    private lateinit var tvRecentSipTime: TextView
    private lateinit var tvRecentSipAmount: TextView

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
        tvRecentSipAmount = view.findViewById(R.id.tv_recent_sip_amount)

        setupGreeting()
        setupRecentSipTime()
        loadUserProfile()
        loadRecentSipFromFirebase()

        return view
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        val uid = firebaseAuth.currentUser?.uid
        if (uid != null && profileListener != null) {
            database?.child("users")?.child(uid)?.removeEventListener(profileListener!!)
        }
    }

    private var isPolling = false
    private var lastSavedSipTimestampMs: Long = 0L

    override fun onResume() {
        super.onResume()
        isPolling = true
        startPollingBackend()
    }

    override fun onPause() {
        super.onPause()
        isPolling = false
    }

    private fun startPollingBackend() {
        Thread {
            while (isPolling) {
                try {
                    val url = java.net.URL("http://10.0.2.2:3000/api/hydration")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 2000
                    connection.readTimeout = 2000

                    if (connection.responseCode == 200) {
                        val response = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonObject = JSONObject(response)
                        val dataObject = jsonObject.optJSONObject("data")
                        
                        if (dataObject != null) {
                            val totalConsumed = dataObject.optInt("totalConsumedToday", 0)
                            val sipAmount = dataObject.optInt("sipAmount", 0)
                            val lastSipTimestampStr = dataObject.optString("lastSipTimestamp", "")
                            
                            var currentSipTimestampMs = 0L
                            if (lastSipTimestampStr.isNotEmpty()) {
                                try {
                                    val parser = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.getDefault())
                                    parser.timeZone = java.util.TimeZone.getTimeZone("UTC")
                                    val date = parser.parse(lastSipTimestampStr)
                                    if (date != null) {
                                        currentSipTimestampMs = date.time
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }

                            // Detect a genuinely new sip and save to Firebase
                            if (currentSipTimestampMs > lastSavedSipTimestampMs && sipAmount > 0) {
                                lastSavedSipTimestampMs = currentSipTimestampMs
                                
                                val uid = firebaseAuth.currentUser?.uid
                                if (uid != null && database != null) {
                                    val recordRef = database.child("users").child(uid).child("sip_records").push()
                                    val newRecord = com.sipsense.app.model.SipRecord(sipAmount, currentSipTimestampMs)
                                    recordRef.setValue(newRecord)
                                }
                            }
                            
                            activity?.runOnUiThread {
                                if (isAdded) {
                                    progressHydration.progress = totalConsumed
                                    tvProgressCurrent.text = getString(R.string.progress_format_ml, totalConsumed)
                                    
                                    if (currentSipTimestampMs > 0) {
                                        updateRecentSipUI(sipAmount, currentSipTimestampMs)
                                    }
                                }
                            }
                        }
                    }
                    connection.disconnect()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                Thread.sleep(3000)
            }
        }.start()
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
                
                // The current progress is now fetched live from the backend API
            }

            override fun onCancelled(error: DatabaseError) {
                if (!isAdded) return
                Toast.makeText(context, "Failed to load profile", Toast.LENGTH_SHORT).show()
            }
        }
        
        database?.child("users")?.child(uid)?.addValueEventListener(profileListener!!)
    }

    private fun loadRecentSipFromFirebase() {
        val uid = firebaseAuth.currentUser?.uid ?: return
        database?.child("users")?.child(uid)?.child("sip_records")
            ?.orderByChild("timestamp")
            ?.limitToLast(1)
            ?.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded) return
                    for (child in snapshot.children) {
                        val record = child.getValue(com.sipsense.app.model.SipRecord::class.java)
                        if (record != null) {
                            lastSavedSipTimestampMs = record.timestamp
                            updateRecentSipUI(record.amount, record.timestamp)
                        }
                    }
                }
                override fun onCancelled(error: DatabaseError) {}
            })
    }

    private fun updateRecentSipUI(amount: Int, timestampMs: Long) {
        if (amount > 0) {
            tvRecentSipAmount.text = "$amount ml"
        }
        
        val sipCalendar = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val todayCalendar = Calendar.getInstance()
        
        val isToday = sipCalendar.get(Calendar.YEAR) == todayCalendar.get(Calendar.YEAR) &&
                      sipCalendar.get(Calendar.DAY_OF_YEAR) == todayCalendar.get(Calendar.DAY_OF_YEAR)
                      
        todayCalendar.add(Calendar.DAY_OF_YEAR, -1)
        val isYesterday = sipCalendar.get(Calendar.YEAR) == todayCalendar.get(Calendar.YEAR) &&
                          sipCalendar.get(Calendar.DAY_OF_YEAR) == todayCalendar.get(Calendar.DAY_OF_YEAR)

        val timeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
        val timeString = timeFormat.format(sipCalendar.time)
        
        val displayString = when {
            isToday -> "Today, $timeString"
            isYesterday -> "Yesterday, $timeString"
            else -> {
                val dateFormat = java.text.SimpleDateFormat("MMM d, h:mm a", java.util.Locale.getDefault())
                dateFormat.format(sipCalendar.time)
            }
        }
        
        tvRecentSipTime.text = displayString
    }
}
