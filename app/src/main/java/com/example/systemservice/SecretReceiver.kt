package com.example.systemservice

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

class SecretReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Code to unhide and open the app
        val p = context.packageManager
        val componentName = ComponentName(context, MainActivity::class.java)
        
        // Enable launcher icon
        p.setComponentEnabledSetting(
            componentName,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )

        // Open the app
        val openIntent = Intent(context, MainActivity::class.java)
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(openIntent)
    }
}
