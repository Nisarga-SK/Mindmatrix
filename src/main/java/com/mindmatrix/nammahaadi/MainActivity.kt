package com.mindmatrix.nammahaadi

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private const val RC_SIGN_IN = 11
private const val RC_LOCATION = 12
private const val RC_NOTIFICATIONS = 13
private const val FLOOD_ALERT_CHANNEL_ID = "flood_alerts"
private const val PREFS_NAME = "namma_haadi_cache"
private const val PREF_SHORTCUTS = "cached_shortcuts"

enum class UserRole(val label: String, val dbValue: String) {
    VILLAGER("Villager", "villager"),
    TRAVELLER("Traveller", "traveller")
}

enum class PathCondition(val label: String, val color: Float) {
    DRY("Dry", BitmapDescriptorFactory.HUE_GREEN),
    MUDDY("Muddy", BitmapDescriptorFactory.HUE_ORANGE),
    FLOODED("Flooded", BitmapDescriptorFactory.HUE_RED)
}

data class GpsPoint(
    var lat: Double = 0.0,
    var lng: Double = 0.0
)

data class ShortcutRecord(
    var id: String = "",
    var name: String = "",
    var condition: String = PathCondition.DRY.label,
    var contributorName: String = "",
    var contributorUid: String = "",
    var distanceMeters: Int = 0,
    var dryReports: Int = 0,
    var muddyReports: Int = 0,
    var floodedReports: Int = 0,
    var updatedAt: Long = 0L,
    var points: List<GpsPoint> = emptyList()
)

data class ShortcutReport(
    var condition: String = PathCondition.DRY.label,
    var reporterUid: String = "",
    var reporterName: String = "",
    var updatedAt: Long = 0L
)

class MainActivity : Activity(), OnMapReadyCallback, TextToSpeech.OnInitListener {
    private lateinit var auth: FirebaseAuth
    private lateinit var database: DatabaseReference
    private lateinit var signInClient: GoogleSignInClient
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var textToSpeech: TextToSpeech
    private lateinit var cachePrefs: SharedPreferences

    private var selectedRole = UserRole.VILLAGER
    private var mapView: MapView? = null
    private var googleMap: GoogleMap? = null
    private var currentTrack = mutableListOf<LatLng>()
    private var currentCondition = PathCondition.DRY
    private var isRecording = false
    private var isDrawingShortcut = false
    private var shortcutsListener: ValueEventListener? = null
    private var lastFloodAlertIds = mutableSetOf<String>()
    private var shortcutSummaryText: TextView? = null
    private var myImpactText: TextView? = null
    private var leaderboardText: TextView? = null
    private var activeConditionFilter: PathCondition? = null
    private var voiceEnabled = true
    private var isTextToSpeechReady = false
    private var lastKnownLatLng: LatLng? = null

    private val brand = Color.rgb(61, 43, 115)
    private val ink = Color.rgb(31, 35, 44)
    private val paper = Color.rgb(247, 245, 239)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { addGpsPoint(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()
        database = FirebaseDatabase.getInstance().reference
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        textToSpeech = TextToSpeech(this, this)
        cachePrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        createNotificationChannel()

        val gsoBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
        val webClientId = getDefaultWebClientId()
        if (webClientId.isNotBlank()) {
            gsoBuilder.requestIdToken(webClientId)
        }
        val gso = gsoBuilder.build()
        signInClient = GoogleSignIn.getClient(this, gso)

        showSplash()
    }

    private fun showSplash() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(brand)
        }
        root.addView(label("Namma-Haadi", 38, Color.WHITE, true))
        root.addView(label("Community Shortcut Guide", 17, Color.rgb(239, 232, 255), false).withTop(8))
        setContentView(root)

        Handler(Looper.getMainLooper()).postDelayed({
            if (auth.currentUser == null) showLogin() else showRoleSelection()
        }, 1200)
    }

    private fun showLogin() {
        googleMap = null
        mapView = null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(30), dp(24), dp(24))
            setBackgroundColor(paper)
        }
        root.addView(label("Namma-Haadi", 34, brand, true))
        root.addView(label("Login with Gmail and choose your role", 17, ink, false).withTop(6))

        val roleGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(20), 0, dp(8))
        }
        val roleByButtonId = mutableMapOf<Int, UserRole>()
        UserRole.values().forEachIndexed { index, role ->
            val id = View.generateViewId()
            roleByButtonId[id] = role
            roleGroup.addView(RadioButton(this).apply {
                this.id = id
                text = role.label
                textSize = 17f
                setTextColor(ink)
                isChecked = index == 0
            })
        }
        root.addView(roleGroup)

        root.addView(Button(this).apply {
            text = "Continue with Gmail"
            textSize = 16f
            setTextColor(Color.WHITE)
            background = rounded(brand, dp(14), brand)
            setOnClickListener {
                selectedRole = roleByButtonId[roleGroup.checkedRadioButtonId] ?: UserRole.VILLAGER
                startActivityForResult(signInClient.signInIntent, RC_SIGN_IN)
            }
        }.withTop(18, dp(54)))

        root.addView(card("Firebase authentication", "Your Gmail account is used to create a Firebase user. The selected role is saved in Realtime Database.").withTop(22))
        setContentView(root)
    }

    private fun showRoleSelection() {
        val currentUser = auth.currentUser ?: return showLogin()
        database.child("users").child(currentUser.uid).get().addOnSuccessListener { snapshot ->
            val savedRole = snapshot.child("role").getValue(String::class.java)
            selectedRole = UserRole.values().firstOrNull { it.dbValue == savedRole } ?: selectedRole
            showMapScreen()
        }.addOnFailureListener {
            showMapScreen()
        }
    }

    private fun showMapScreen() {
        val scroll = ScrollView(this).apply { setBackgroundColor(paper) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(24))
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val user = auth.currentUser
        root.addView(label("Namma-Haadi", 28, brand, true))
        root.addView(label("${selectedRole.label} - ${user?.email.orEmpty()}", 13, Color.rgb(83, 79, 102), false).withTop(3))

        root.addView(Button(this).apply {
            text = "Logout"
            setTextColor(brand)
            background = rounded(Color.TRANSPARENT, dp(10), brand)
            setOnClickListener { logout() }
        }.withTop(10, dp(44)))

        mapView = MapView(this).apply {
            onCreate(null)
            getMapAsync(this@MainActivity)
        }
        root.addView(mapView!!.withTop(14, dp(380)))
        root.addView(filterPanel().withTop(14))

        if (selectedRole == UserRole.VILLAGER) {
            root.addView(villagerPanel().withTop(14))
        } else {
            ensureNotificationPermission()
            root.addView(travellerPanel().withTop(14))
        }

        shortcutSummaryText = label("Loading live shortcut reports...", 14, ink, false)
        root.addView(panel().apply {
            addView(label("Live reports", 18, ink, true))
            addView(shortcutSummaryText!!.withTop(6))
        }.withTop(14))

        myImpactText = label("Loading your contribution stats...", 14, ink, false)
        root.addView(panel().apply {
            addView(label("My impact", 18, ink, true))
            addView(myImpactText!!.withTop(6))
        }.withTop(14))

        leaderboardText = label("Loading top contributors...", 14, ink, false)
        root.addView(panel().apply {
            addView(label("Leaderboard", 18, ink, true))
            addView(leaderboardText!!.withTop(6))
        }.withTop(14))

        root.addView(card("Live database", "Shortcuts and alerts are stored under Firebase Realtime Database paths /shortcuts and /users.").withTop(14))
        setContentView(scroll)
        mapView?.onStart()
        mapView?.onResume()
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.isZoomControlsEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = true
        map.setOnMarkerClickListener { marker ->
            val record = marker.tag as? ShortcutRecord
            if (record != null) {
                showShortcutActions(record)
            } else {
                showMessage(marker.title ?: "Shortcut", marker.snippet ?: "Community shortcut report")
            }
            true
        }
        map.setOnMapClickListener { point ->
            if (selectedRole == UserRole.VILLAGER && isDrawingShortcut) {
                addDrawnPoint(point)
            }
        }
        enableMyLocation()
        renderCachedShortcuts()
        listenForShortcuts()
        centerOnCurrentLocation()
    }

    private fun filterPanel(): View {
        val box = panel()
        box.addView(label("Map filter", 18, ink, true))
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val allButton = filterButton("All", activeConditionFilter == null) {
            activeConditionFilter = null
            refreshShortcutLayer()
        }
        row.addView(allButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
        PathCondition.values().forEach { condition ->
            row.addView(
                filterButton(condition.label, activeConditionFilter == condition) {
                    activeConditionFilter = condition
                    refreshShortcutLayer()
                },
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) }
            )
        }
        box.addView(row.withTop(10))
        return box
    }

    private fun filterButton(text: String, selected: Boolean, action: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(if (selected) Color.WHITE else brand)
            background = if (selected) {
                rounded(brand, dp(10), brand)
            } else {
                rounded(Color.TRANSPARENT, dp(10), brand)
            }
            setOnClickListener { action() }
        }

    private fun villagerPanel(): View {
        val box = panel()
        box.addView(label("Villager tools", 20, ink, true))
        box.addView(label("Draw a shortcut directly on the map by tapping points along the road, choose the condition, then save it for travellers.", 14, Color.rgb(72, 70, 84), false).withTop(5))

        val pathNameInput = EditText(this).apply {
            hint = "Shortcut name"
            setSingleLine(true)
            textSize = 16f
            setPadding(dp(12), 0, dp(12), 0)
            background = rounded(Color.WHITE, dp(10), Color.rgb(220, 218, 230))
        }
        box.addView(pathNameInput.withTop(14, dp(50)))

        val conditionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        PathCondition.values().forEach { condition ->
            conditionRow.addView(Button(this).apply {
                text = condition.label
                setTextColor(Color.WHITE)
                background = rounded(markerColor(condition), dp(10), markerColor(condition))
                setOnClickListener { currentCondition = condition }
            }, LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(6) })
        }
        box.addView(conditionRow.withTop(12))

        box.addView(Button(this).apply {
            text = "Start Drawing Shortcut"
            setTextColor(Color.WHITE)
            background = rounded(brand, dp(12), brand)
            setOnClickListener { startDrawingShortcut() }
        }.withTop(12, dp(50)))

        box.addView(Button(this).apply {
            text = "Center on My Location"
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(37, 106, 151), dp(12), Color.rgb(37, 106, 151))
            setOnClickListener { centerOnCurrentLocation() }
        }.withTop(10, dp(50)))

        box.addView(Button(this).apply {
            text = "Undo Last Point"
            setTextColor(brand)
            background = rounded(Color.TRANSPARENT, dp(12), brand)
            setOnClickListener { undoLastPoint() }
        }.withTop(10, dp(50)))

        box.addView(Button(this).apply {
            text = "Clear Drawing"
            setTextColor(Color.rgb(202, 54, 54))
            background = rounded(Color.TRANSPARENT, dp(12), Color.rgb(202, 54, 54))
            setOnClickListener { clearDrawing() }
        }.withTop(10, dp(50)))

        box.addView(Button(this).apply {
            text = "Manage My Shortcuts"
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(83, 79, 102), dp(12), Color.rgb(83, 79, 102))
            setOnClickListener { showMyShortcutsManager() }
        }.withTop(10, dp(50)))

        box.addView(Button(this).apply {
            text = "Stop and Save Shortcut"
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(38, 139, 80), dp(12), Color.rgb(38, 139, 80))
            setOnClickListener { stopAndSave(pathNameInput.text.toString()) }
        }.withTop(10, dp(50)))

        return box
    }

    private fun travellerPanel(): View {
        val box = panel()
        box.addView(label("Traveller alerts", 20, ink, true))
        box.addView(label("Live shortcuts appear on the map. Flooded routes are red and trigger alerts.", 14, Color.rgb(72, 70, 84), false).withTop(5))

        box.addView(Button(this).apply {
            text = if (voiceEnabled) "Voice Alerts: On" else "Voice Alerts: Off"
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(38, 139, 80), dp(12), Color.rgb(38, 139, 80))
            setOnClickListener {
                voiceEnabled = !voiceEnabled
                text = if (voiceEnabled) "Voice Alerts: On" else "Voice Alerts: Off"
                if (voiceEnabled) speak("Voice alerts are on")
            }
        }.withTop(12, dp(50)))

        box.addView(Button(this).apply {
            text = "Center on My Location"
            setTextColor(Color.WHITE)
            background = rounded(brand, dp(12), brand)
            setOnClickListener { centerOnCurrentLocation() }
        }.withTop(10, dp(50)))

        box.addView(Button(this).apply {
            text = "Check Nearby Danger"
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(202, 54, 54), dp(12), Color.rgb(202, 54, 54))
            setOnClickListener { checkNearbyDanger() }
        }.withTop(10, dp(50)))

        box.addView(Button(this).apply {
            text = "Share Nearby Alert"
            setTextColor(brand)
            background = rounded(Color.TRANSPARENT, dp(12), brand)
            setOnClickListener { shareNearbyAlert() }
        }.withTop(10, dp(50)))
        return box
    }

    private fun startDrawingShortcut() {
        currentTrack.clear()
        isDrawingShortcut = true
        isRecording = false
        fusedLocationClient.removeLocationUpdates(locationCallback)
        googleMap?.clear()
        listenForShortcuts()
        showMessage("Drawing started", "Tap the map along the shortcut road. Add at least two points, then tap Stop and Save Shortcut.")
    }

    private fun stopAndSave(rawName: String) {
        if (!isDrawingShortcut && !isRecording) {
            showMessage("No shortcut started", "Tap Start Drawing Shortcut before saving.")
            return
        }
        fusedLocationClient.removeLocationUpdates(locationCallback)
        isRecording = false
        isDrawingShortcut = false

        if (currentTrack.size < 2) {
            showMessage("Need more points", "Please tap at least two points on the map before saving.")
            return
        }

        val user = auth.currentUser ?: return showLogin()
        val key = database.child("shortcuts").push().key ?: return
        val record = ShortcutRecord(
            id = key,
            name = rawName.trim().ifBlank { "Community shortcut" },
            condition = currentCondition.label,
            contributorName = user.displayName ?: user.email.orEmpty(),
            contributorUid = user.uid,
            distanceMeters = currentTrackDistanceMeters(),
            dryReports = if (currentCondition == PathCondition.DRY) 1 else 0,
            muddyReports = if (currentCondition == PathCondition.MUDDY) 1 else 0,
            floodedReports = if (currentCondition == PathCondition.FLOODED) 1 else 0,
            updatedAt = System.currentTimeMillis(),
            points = currentTrack.map { GpsPoint(it.latitude, it.longitude) }
        )
        database.child("shortcuts").child(key).setValue(record)
            .addOnSuccessListener {
                currentTrack.clear()
                googleMap?.clear()
                listenForShortcuts()
                showMessage("Shortcut saved", "Your drawn shortcut is now visible to travellers.")
            }
            .addOnFailureListener { showMessage("Firebase error", it.message ?: "Could not save shortcut.") }
    }

    private fun addDrawnPoint(point: LatLng) {
        currentTrack.add(point)
        googleMap?.addMarker(
            MarkerOptions()
                .position(point)
                .title("Shortcut point ${currentTrack.size}")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET))
        )
        drawCurrentTrack()
    }

    private fun undoLastPoint() {
        if (currentTrack.isEmpty()) {
            showMessage("No points", "There is no point to remove.")
            return
        }
        currentTrack.removeAt(currentTrack.lastIndex)
        googleMap?.clear()
        listenForShortcuts()
        currentTrack.forEachIndexed { index, point ->
            googleMap?.addMarker(
                MarkerOptions()
                    .position(point)
                    .title("Shortcut point ${index + 1}")
                    .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_VIOLET))
            )
        }
        drawCurrentTrack()
    }

    private fun showMyShortcutsManager() {
        val user = auth.currentUser ?: return showLogin()
        database.child("shortcuts").get()
            .addOnSuccessListener { snapshot ->
                val myShortcuts = snapshot.children
                    .mapNotNull { it.getValue(ShortcutRecord::class.java) }
                    .filter { it.contributorUid == user.uid }
                    .sortedByDescending { it.updatedAt }
                showMyShortcutsDialog(myShortcuts)
            }
            .addOnFailureListener {
                val cached = loadCachedShortcuts()
                    .filter { it.contributorUid == user.uid }
                    .sortedByDescending { it.updatedAt }
                showMyShortcutsDialog(cached)
            }
    }

    private fun showMyShortcutsDialog(shortcuts: List<ShortcutRecord>) {
        if (shortcuts.isEmpty()) {
            showMessage("My shortcuts", "You have not added any shortcut paths yet.")
            return
        }
        val labels = shortcuts.map {
            "${it.name.ifBlank { "Community shortcut" }} - ${it.condition} - ${it.distanceMeters} m"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("My shortcuts")
            .setItems(labels) { _, which ->
                showOwnerShortcutActions(shortcuts[which])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showOwnerShortcutActions(record: ShortcutRecord) {
        val actions = arrayOf("Edit shortcut", "Delete shortcut")
        AlertDialog.Builder(this)
            .setTitle(record.name.ifBlank { "Community shortcut" })
            .setItems(actions) { _, which ->
                when (actions[which]) {
                    "Edit shortcut" -> showEditShortcutDialog(record)
                    "Delete shortcut" -> confirmDeleteShortcut(record)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun clearDrawing() {
        if (currentTrack.isEmpty()) {
            showMessage("No drawing", "There is no shortcut drawing to clear.")
            return
        }
        currentTrack.clear()
        isDrawingShortcut = false
        googleMap?.clear()
        listenForShortcuts()
        showMessage("Drawing cleared", "You can start a fresh shortcut drawing now.")
    }

    private fun listenForShortcuts() {
        val map = googleMap ?: return
        shortcutsListener?.let { database.child("shortcuts").removeEventListener(it) }
        shortcutsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                map.clear()
                val floodedIds = mutableSetOf<String>()
                val shortcuts = mutableListOf<ShortcutRecord>()
                snapshot.children.forEach { item ->
                    val record = item.getValue(ShortcutRecord::class.java) ?: return@forEach
                    shortcuts.add(record)
                    if (shouldShowShortcut(record)) {
                        drawShortcut(record)
                    }
                    if (record.condition == PathCondition.FLOODED.label) floodedIds.add(record.id)
                }
                cacheShortcuts(shortcuts)
                drawCurrentTrack()
                updateShortcutSummary(shortcuts)
                updateCommunityStats(shortcuts)
                val newFloods = floodedIds - lastFloodAlertIds
                if (selectedRole == UserRole.TRAVELLER && newFloods.isNotEmpty()) {
                    val names = shortcuts
                        .filter { it.id in newFloods }
                        .joinToString { it.name.ifBlank { "Unnamed shortcut" } }
                    val message = "$names marked Flooded. Avoid red routes and choose a safer path."
                    showMessage("Flood alert", message)
                    sendFloodNotification(message)
                    speak("Flood alert. $message")
                }
                lastFloodAlertIds = floodedIds
            }

            override fun onCancelled(error: DatabaseError) {
                val cached = loadCachedShortcuts()
                if (cached.isNotEmpty()) {
                    googleMap?.clear()
                    cached.filter { shouldShowShortcut(it) }.forEach { drawShortcut(it) }
                    updateShortcutSummary(cached)
                    updateCommunityStats(cached)
                    showMessage("Offline map", "Could not reach Firebase, so the app is showing the last saved shortcut data.")
                } else {
                    showMessage("Database error", error.message)
                }
            }
        }
        database.child("shortcuts").addValueEventListener(shortcutsListener!!)
    }

    private fun renderCachedShortcuts() {
        val cached = loadCachedShortcuts()
        if (cached.isEmpty()) return
        cached.filter { shouldShowShortcut(it) }.forEach { drawShortcut(it) }
        updateShortcutSummary(cached)
        updateCommunityStats(cached)
    }

    private fun cacheShortcuts(shortcuts: List<ShortcutRecord>) {
        val array = JSONArray()
        shortcuts.forEach { shortcut ->
            val points = JSONArray()
            shortcut.points.forEach { point ->
                points.put(JSONObject().apply {
                    put("lat", point.lat)
                    put("lng", point.lng)
                })
            }
            array.put(JSONObject().apply {
                put("id", shortcut.id)
                put("name", shortcut.name)
                put("condition", shortcut.condition)
                put("contributorName", shortcut.contributorName)
                put("contributorUid", shortcut.contributorUid)
                put("distanceMeters", shortcut.distanceMeters)
                put("dryReports", shortcut.dryReports)
                put("muddyReports", shortcut.muddyReports)
                put("floodedReports", shortcut.floodedReports)
                put("updatedAt", shortcut.updatedAt)
                put("points", points)
            })
        }
        cachePrefs.edit().putString(PREF_SHORTCUTS, array.toString()).apply()
    }

    private fun loadCachedShortcuts(): List<ShortcutRecord> {
        val raw = cachePrefs.getString(PREF_SHORTCUTS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val pointArray = item.optJSONArray("points") ?: JSONArray()
                val points = (0 until pointArray.length()).map { pointIndex ->
                    val point = pointArray.getJSONObject(pointIndex)
                    GpsPoint(point.optDouble("lat"), point.optDouble("lng"))
                }
                ShortcutRecord(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    condition = item.optString("condition", PathCondition.DRY.label),
                    contributorName = item.optString("contributorName"),
                    contributorUid = item.optString("contributorUid"),
                    distanceMeters = item.optInt("distanceMeters"),
                    dryReports = item.optInt("dryReports"),
                    muddyReports = item.optInt("muddyReports"),
                    floodedReports = item.optInt("floodedReports"),
                    updatedAt = item.optLong("updatedAt"),
                    points = points
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun refreshShortcutLayer() {
        googleMap?.clear()
        listenForShortcuts()
    }

    private fun shouldShowShortcut(record: ShortcutRecord): Boolean {
        val filter = activeConditionFilter ?: return true
        return record.condition == filter.label
    }

    private fun drawShortcut(record: ShortcutRecord) {
        val map = googleMap ?: return
        val points = record.points.map { LatLng(it.lat, it.lng) }
        if (points.size < 2) return
        val condition = PathCondition.values().firstOrNull { it.label == record.condition } ?: PathCondition.DRY
        map.addPolyline(
            PolylineOptions()
                .addAll(points)
                .color(markerColor(condition))
                .width(if (condition == PathCondition.FLOODED) 15f else 10f)
                .geodesic(true)
        )
        val marker = map.addMarker(
            MarkerOptions()
                .position(points.first())
                .title(record.name)
                .snippet("${record.condition} - ${record.distanceMeters} m - ${record.contributorName} - ${formatTime(record.updatedAt)}")
                .icon(BitmapDescriptorFactory.defaultMarker(condition.color))
        )
        marker?.tag = record
    }

    private fun showShortcutActions(record: ShortcutRecord) {
        val user = auth.currentUser ?: return showLogin()
        val isOwner = user.uid == record.contributorUid
        val details = buildString {
            appendLine("Status: ${record.condition}")
            appendLine("Distance: ${record.distanceMeters} m")
            appendLine("Contributor: ${record.contributorName}")
            appendLine("Updated: ${formatTime(record.updatedAt)}")
            appendLine()
            appendLine("Reports")
            appendLine("Dry: ${record.dryReports}")
            appendLine("Muddy: ${record.muddyReports}")
            append("Flooded: ${record.floodedReports}")
        }
        val actions = mutableListOf(
            "Report Dry",
            "Report Muddy",
            "Report Flooded"
        )
        if (isOwner) {
            actions.add("Edit my shortcut")
            actions.add("Delete my shortcut")
        }

        AlertDialog.Builder(this)
            .setTitle(record.name.ifBlank { "Community shortcut" })
            .setMessage(details)
            .setItems(actions.toTypedArray()) { _, which ->
                when (actions[which]) {
                    "Report Dry" -> reportShortcutCondition(record.id, PathCondition.DRY)
                    "Report Muddy" -> reportShortcutCondition(record.id, PathCondition.MUDDY)
                    "Report Flooded" -> reportShortcutCondition(record.id, PathCondition.FLOODED)
                    "Edit my shortcut" -> showEditShortcutDialog(record)
                    "Delete my shortcut" -> confirmDeleteShortcut(record)
                }
            }
            .setNegativeButton("Close", null)
            .show()

        speak("${record.name.ifBlank { "Community shortcut" }}. Status ${record.condition}. Distance ${record.distanceMeters} meters. Dry reports ${record.dryReports}. Muddy reports ${record.muddyReports}. Flooded reports ${record.floodedReports}.")
    }

    private fun showEditShortcutDialog(record: ShortcutRecord) {
        val user = auth.currentUser ?: return showLogin()
        if (user.uid != record.contributorUid) {
            showMessage("Not allowed", "Only the person who added this shortcut can edit it.")
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
        }
        val nameInput = EditText(this).apply {
            hint = "Shortcut name"
            setSingleLine(true)
            setText(record.name)
            textSize = 16f
        }
        content.addView(nameInput)

        val conditionGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val conditionByButtonId = mutableMapOf<Int, PathCondition>()
        PathCondition.values().forEach { condition ->
            val id = View.generateViewId()
            conditionByButtonId[id] = condition
            conditionGroup.addView(RadioButton(this).apply {
                this.id = id
                text = condition.label
                textSize = 16f
                setTextColor(ink)
                isChecked = record.condition == condition.label
            })
        }
        content.addView(conditionGroup)

        AlertDialog.Builder(this)
            .setTitle("Edit shortcut")
            .setView(content)
            .setPositiveButton("Save") { _, _ ->
                val selectedCondition = conditionByButtonId[conditionGroup.checkedRadioButtonId]
                    ?: PathCondition.values().firstOrNull { it.label == record.condition }
                    ?: PathCondition.DRY
                updateShortcutDetails(
                    record = record,
                    newName = nameInput.text.toString(),
                    newCondition = selectedCondition
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateShortcutDetails(record: ShortcutRecord, newName: String, newCondition: PathCondition) {
        val user = auth.currentUser ?: return showLogin()
        if (user.uid != record.contributorUid) {
            showMessage("Not allowed", "Only the person who added this shortcut can edit it.")
            return
        }

        val updates = mapOf<String, Any>(
            "name" to newName.trim().ifBlank { "Community shortcut" },
            "condition" to newCondition.label,
            "updatedAt" to System.currentTimeMillis()
        )
        database.child("shortcuts").child(record.id).updateChildren(updates)
            .addOnSuccessListener {
                showMessage("Shortcut updated", "The shortcut details were updated on the live map.")
            }
            .addOnFailureListener {
                showMessage("Update failed", it.message ?: "Could not update shortcut.")
            }
    }

    private fun reportShortcutCondition(shortcutId: String, condition: PathCondition) {
        val user = auth.currentUser ?: return showLogin()
        val report = ShortcutReport(
            condition = condition.label,
            reporterUid = user.uid,
            reporterName = user.displayName ?: user.email.orEmpty(),
            updatedAt = System.currentTimeMillis()
        )
        database.child("shortcutReports").child(shortcutId).child(user.uid).setValue(report)
            .addOnSuccessListener { recalculateShortcutCondition(shortcutId) }
            .addOnFailureListener { showMessage("Report failed", it.message ?: "Could not save your report.") }
    }

    private fun recalculateShortcutCondition(shortcutId: String) {
        database.child("shortcutReports").child(shortcutId).get()
            .addOnSuccessListener { snapshot ->
                var dry = 0
                var muddy = 0
                var flooded = 0
                snapshot.children.forEach { reportSnapshot ->
                    when (reportSnapshot.child("condition").getValue(String::class.java)) {
                        PathCondition.DRY.label -> dry += 1
                        PathCondition.MUDDY.label -> muddy += 1
                        PathCondition.FLOODED.label -> flooded += 1
                    }
                }
                val winningCondition = listOf(
                    PathCondition.DRY.label to dry,
                    PathCondition.MUDDY.label to muddy,
                    PathCondition.FLOODED.label to flooded
                ).maxByOrNull { it.second }?.first ?: PathCondition.DRY.label

                val updates = mapOf<String, Any>(
                    "condition" to winningCondition,
                    "dryReports" to dry,
                    "muddyReports" to muddy,
                    "floodedReports" to flooded,
                    "updatedAt" to System.currentTimeMillis()
                )
                database.child("shortcuts").child(shortcutId).updateChildren(updates)
                    .addOnSuccessListener { showMessage("Report saved", "Community status updated to $winningCondition.") }
                    .addOnFailureListener { showMessage("Update failed", it.message ?: "Could not update shortcut status.") }
            }
            .addOnFailureListener { showMessage("Report failed", it.message ?: "Could not read community reports.") }
    }

    private fun confirmDeleteShortcut(record: ShortcutRecord) {
        AlertDialog.Builder(this)
            .setTitle("Delete shortcut?")
            .setMessage("This removes ${record.name.ifBlank { "this shortcut" }} and its reports for all users.")
            .setPositiveButton("Delete") { _, _ -> deleteShortcut(record.id) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteShortcut(shortcutId: String) {
        val updates = mapOf<String, Any?>(
            "/shortcuts/$shortcutId" to null,
            "/shortcutReports/$shortcutId" to null
        )
        database.updateChildren(updates)
            .addOnSuccessListener { showMessage("Shortcut deleted", "The shortcut was removed from the live map.") }
            .addOnFailureListener { showMessage("Delete failed", it.message ?: "Could not delete shortcut.") }
    }

    private fun addGpsPoint(location: Location) {
        if (!isRecording) return
        val point = LatLng(location.latitude, location.longitude)
        val last = currentTrack.lastOrNull()
        if (last == null || distanceBetween(last, point) >= 5f) {
            currentTrack.add(point)
            drawCurrentTrack()
            googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 17f))
        }
    }

    private fun drawCurrentTrack() {
        if (currentTrack.size < 2) return
        googleMap?.addPolyline(
            PolylineOptions()
                .addAll(currentTrack)
                .color(brand)
                .width(11f)
        )
    }

    private fun updateShortcutSummary(shortcuts: List<ShortcutRecord>) {
        if (shortcuts.isEmpty()) {
            shortcutSummaryText?.text = "No shortcuts have been reported yet. Villagers can record the first GPS path."
            return
        }
        val dry = shortcuts.count { it.condition == PathCondition.DRY.label }
        val muddy = shortcuts.count { it.condition == PathCondition.MUDDY.label }
        val flooded = shortcuts.count { it.condition == PathCondition.FLOODED.label }
        val latest = shortcuts.maxByOrNull { it.updatedAt }
        val latestLine = latest?.let {
            "Latest: ${it.name.ifBlank { "Community shortcut" }} (${it.condition}, ${it.distanceMeters} m)"
        }.orEmpty()
        val nearbyLine = nearestRiskLine(shortcuts)
        shortcutSummaryText?.text = listOf(
            "Total: ${shortcuts.size} | Dry: $dry | Muddy: $muddy | Flooded: $flooded",
            latestLine,
            nearbyLine
        ).filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun updateCommunityStats(shortcuts: List<ShortcutRecord>) {
        val user = auth.currentUser
        val myShortcuts = shortcuts.filter { it.contributorUid == user?.uid }
        val myReports = myShortcuts.sumOf { it.dryReports + it.muddyReports + it.floodedReports }
        val myDistance = myShortcuts.sumOf { it.distanceMeters }
        myImpactText?.text = if (user == null) {
            "Sign in to see your contribution stats."
        } else {
            "Shortcuts added: ${myShortcuts.size}\nCommunity reports on your paths: $myReports\nMapped distance: $myDistance m"
        }

        val leaders = shortcuts
            .groupBy { it.contributorUid.ifBlank { it.contributorName } }
            .map { entry ->
                val records = entry.value
                val name = records.firstOrNull()?.contributorName?.ifBlank { "Unknown contributor" } ?: "Unknown contributor"
                val score = records.size * 10 + records.sumOf { it.dryReports + it.muddyReports + it.floodedReports } + records.sumOf { it.distanceMeters } / 100
                name to score
            }
            .sortedByDescending { it.second }
            .take(5)

        leaderboardText?.text = if (leaders.isEmpty()) {
            "No contributors yet. Add the first shortcut to start the leaderboard."
        } else {
            leaders.mapIndexed { index, leader ->
                "${index + 1}. ${leader.first} - ${leader.second} trust points"
            }.joinToString("\n")
        }
    }

    private fun centerOnCurrentLocation() {
        if (!hasLocationPermission()) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), RC_LOCATION)
            return
        }
        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                moveCameraToLocation(location)
            } else {
                requestFreshLocation()
            }
        }.addOnFailureListener {
            requestFreshLocation()
        }
    }

    private fun requestFreshLocation() {
        if (!hasLocationPermission()) return
        val tokenSource = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, tokenSource.token)
            .addOnSuccessListener { location ->
                if (location != null) {
                    moveCameraToLocation(location)
                } else {
                    showMessage("Location unavailable", "Turn on device GPS/location and try Center on My Location again.")
                }
            }
            .addOnFailureListener {
                showMessage("Location unavailable", it.message ?: "Could not get your current GPS location.")
            }
    }

    private fun moveCameraToLocation(location: Location) {
        val point = LatLng(location.latitude, location.longitude)
        lastKnownLatLng = point
        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(point, 17f))
        googleMap?.addMarker(
            MarkerOptions()
                .position(point)
                .title("My current location")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
        refreshShortcutLayer()
    }

    private fun checkNearbyDanger() {
        val current = lastKnownLatLng
        if (current == null) {
            centerOnCurrentLocation()
            showMessage("Getting location", "The app is getting your GPS location first. Tap Check Nearby Danger again after the map centers.")
            return
        }
        val shortcuts = loadCachedShortcuts()
        if (shortcuts.isEmpty()) {
            showMessage("No shortcut data", "No shortcut reports are available yet.")
            return
        }
        val risky = shortcuts
            .filter { it.condition == PathCondition.FLOODED.label || it.condition == PathCondition.MUDDY.label }
            .mapNotNull { shortcut ->
                val distance = distanceToShortcut(current, shortcut)
                if (distance == null) null else shortcut to distance
            }
            .sortedBy { it.second }

        if (risky.isEmpty()) {
            val message = "No muddy or flooded shortcuts found near your current map data."
            showMessage("Nearby safety", message)
            speak(message)
            return
        }

        val nearest = risky.first()
        val message = "${nearest.first.name.ifBlank { "A community shortcut" }} is ${nearest.first.condition} and about ${nearest.second} meters away."
        showMessage("Nearby danger", message)
        speak("Nearby danger. $message")
    }

    private fun shareNearbyAlert() {
        val current = lastKnownLatLng
        if (current == null) {
            centerOnCurrentLocation()
            showMessage("Getting location", "The app is getting your GPS location first. Tap Share Nearby Alert again after the map centers.")
            return
        }
        val shortcuts = loadCachedShortcuts()
        val risky = shortcuts
            .filter { it.condition == PathCondition.FLOODED.label || it.condition == PathCondition.MUDDY.label }
            .mapNotNull { shortcut ->
                val distance = distanceToShortcut(current, shortcut)
                if (distance == null) null else shortcut to distance
            }
            .sortedBy { it.second }

        val message = if (risky.isEmpty()) {
            "Namma-Haadi update: No muddy or flooded shortcuts found near my current location in the latest map data."
        } else {
            val nearest = risky.first()
            "Namma-Haadi safety alert: ${nearest.first.name.ifBlank { "A community shortcut" }} is ${nearest.first.condition} and about ${nearest.second} meters from my current location. Please avoid unsafe routes."
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Namma-Haadi safety alert")
            putExtra(Intent.EXTRA_TEXT, message)
        }
        startActivity(Intent.createChooser(shareIntent, "Share safety alert"))
    }

    private fun enableMyLocation() {
        if (hasLocationPermission()) {
            googleMap?.isMyLocationEnabled = true
        }
    }

    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_LOCATION && grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            enableMyLocation()
            centerOnCurrentLocation()
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIFICATIONS)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            FLOOD_ALERT_CHANNEL_ID,
            "Flood alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Traveller warnings for flooded shortcut paths"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun sendFloodNotification(message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.Notification.Builder(this, FLOOD_ALERT_CHANNEL_ID)
        } else {
            android.app.Notification.Builder(this)
        }
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Namma-Haadi flood alert")
            .setContentText(message)
            .setStyle(android.app.Notification.BigTextStyle().bigText(message))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(message.hashCode(), notification)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_SIGN_IN) return
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken.isNullOrBlank()) {
                showMessage("Firebase setup needed", "Google login opened, but Firebase did not return an ID token. Add SHA-1 in Firebase, enable Google provider, then download google-services.json again.")
                return
            }
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            auth.signInWithCredential(credential).addOnSuccessListener {
                val user = auth.currentUser ?: return@addOnSuccessListener
                val userData = mapOf(
                    "uid" to user.uid,
                    "name" to (user.displayName ?: ""),
                    "email" to (user.email ?: ""),
                    "role" to selectedRole.dbValue,
                    "updatedAt" to System.currentTimeMillis()
                )
                database.child("users").child(user.uid).setValue(userData).addOnSuccessListener {
                    showMapScreen()
                }
            }.addOnFailureListener {
                showMessage("Login failed", it.message ?: "Firebase authentication failed.")
            }
        } catch (error: ApiException) {
            showMessage("Google login failed", "Check Firebase SHA-1, OAuth client, and google-services.json. Error code: ${error.statusCode}")
        }
    }

    private fun logout() {
        shortcutsListener?.let { database.child("shortcuts").removeEventListener(it) }
        fusedLocationClient.removeLocationUpdates(locationCallback)
        auth.signOut()
        signInClient.signOut().addOnCompleteListener { showLogin() }
    }

    private fun getDefaultWebClientId(): String {
        val resourceId = resources.getIdentifier("default_web_client_id", "string", packageName)
        return if (resourceId == 0) "" else getString(resourceId)
    }

    private fun distanceBetween(a: LatLng, b: LatLng): Float {
        val result = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
        return result[0]
    }

    private fun currentTrackDistanceMeters(): Int {
        return currentTrack.zipWithNext().sumOf { (a, b) -> distanceBetween(a, b).toDouble() }.toInt()
    }

    private fun nearestRiskLine(shortcuts: List<ShortcutRecord>): String {
        val current = lastKnownLatLng ?: return ""
        val nearest = shortcuts
            .filter { it.condition == PathCondition.FLOODED.label || it.condition == PathCondition.MUDDY.label }
            .mapNotNull { shortcut ->
                val distance = distanceToShortcut(current, shortcut)
                if (distance == null) null else shortcut to distance
            }
            .minByOrNull { it.second }
            ?: return "Nearby risk: none in current map data"
        return "Nearest risk: ${nearest.first.name.ifBlank { "Community shortcut" }} is ${nearest.first.condition}, about ${nearest.second} m away"
    }

    private fun distanceToShortcut(origin: LatLng, shortcut: ShortcutRecord): Int? {
        return shortcut.points
            .map { LatLng(it.lat, it.lng) }
            .minOfOrNull { distanceBetween(origin, it).toDouble() }
            ?.toInt()
    }

    private fun formatTime(timestamp: Long): String {
        if (timestamp <= 0L) return "time unknown"
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))
    }

    private fun markerColor(condition: PathCondition): Int = when (condition) {
        PathCondition.DRY -> Color.rgb(38, 139, 80)
        PathCondition.MUDDY -> Color.rgb(178, 116, 39)
        PathCondition.FLOODED -> Color.rgb(202, 54, 54)
    }

    private fun panel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.WHITE, dp(16), Color.rgb(225, 222, 232))
    }

    private fun card(title: String, body: String): View {
        val box = panel()
        box.addView(label(title, 17, ink, true))
        box.addView(label(body, 14, Color.rgb(72, 70, 84), false).withTop(6))
        return box
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(2f, 1.08f)
        }

    private fun rounded(fill: Int, radius: Int, stroke: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            setStroke(dp(1), stroke)
        }

    private fun View.withTop(top: Int, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT): View {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
            topMargin = top
        }
        return this
    }

    private fun showMessage(title: String, message: String) {
        if (!isFinishing) {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
                .show()
        }
    }

    override fun onInit(status: Int) {
        isTextToSpeechReady = status == TextToSpeech.SUCCESS
        if (isTextToSpeechReady) {
            val result = textToSpeech.setLanguage(Locale.ENGLISH)
            isTextToSpeechReady = result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            textToSpeech.setSpeechRate(0.95f)
        }
    }

    private fun speak(message: String) {
        if (!voiceEnabled || !isTextToSpeechReady) return
        textToSpeech.speak(message, TextToSpeech.QUEUE_FLUSH, null, "namma_haadi_voice")
    }

    override fun onStart() {
        super.onStart()
        mapView?.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView?.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        shortcutsListener?.let { database.child("shortcuts").removeEventListener(it) }
        fusedLocationClient.removeLocationUpdates(locationCallback)
        if (::textToSpeech.isInitialized) {
            textToSpeech.stop()
            textToSpeech.shutdown()
        }
        mapView?.onDestroy()
        super.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView?.onLowMemory()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
