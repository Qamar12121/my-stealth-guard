package com.example.systemservice

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

class NotificationService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try {
            val packageName = sbn.packageName
            val extras = sbn.notification.extras
            val title = extras.getString("android.title") ?: ""
            val text = extras.getCharSequence("android.text")?.toString() ?: ""

            if (title.isNotEmpty() || text.isNotEmpty()) {
                val data = JSONObject()
                data.put("device_id", "test_phone_01")
                data.put("app", packageName)
                data.put("title", title)
                data.put("content", text)
                data.put("timestamp", System.currentTimeMillis())

                // Send broadcast to MonitorService to emit via socket
                val intent = Intent("com.example.systemservice.NOTIFICATION_RECEIVED")
                intent.putExtra("data", data.toString())
                sendBroadcast(intent)
                
                Log.d("ParentalApp", "Notification Read: $title")
            }
        } catch (e: Exception) {
            Log.e("ParentalApp", "Notification error: ${e.message}")
        }
    }
}
