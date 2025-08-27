package com.app.streamify

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

class VncServerService : Service() {
    companion object {
        private const val TAG = "VncServerService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "VncServerChannel"
        private const val DEFAULT_PORT = 5900
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var vncServer: VncServer? = null
    private var vncThread: Thread? = null
    private var isRunning = AtomicBoolean(false)
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = 0
    private val handler = Handler(Looper.getMainLooper())
    private var mediaProjectionCallback: MediaProjection.Callback? = null

    override fun onCreate() {
        super.onCreate()
        try {
            Log.d(TAG, "Service onCreate")
            createNotificationChannel()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            Log.d(TAG, "Service onStartCommand: ${intent?.action}")

            when (intent?.action) {
                "START_VNC" -> {
                    val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED)
                    val data = intent.getParcelableExtra<Intent>("data")
                    Log.d(TAG, "Starting VNC with resultCode: $resultCode")
                    startVncServer(resultCode, data)
                }
                "STOP_VNC" -> {
                    Log.d(TAG, "Stopping VNC")
                    stopVncServer()
                }
                else -> {
                    Log.w(TAG, "Unknown action: ${intent?.action}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand: ${e.message}", e)
            Toast.makeText(this, "Service error: ${e.message}", Toast.LENGTH_LONG).show()
        }
        return START_STICKY
    }

    private fun startVncServer(resultCode: Int, data: Intent?) {
        try {
            Log.d(TAG, "startVncServer called with resultCode: $resultCode")

            if (resultCode != Activity.RESULT_OK) {
                Log.e(TAG, "Invalid result code: $resultCode")
                Toast.makeText(this, "Screen recording permission denied", Toast.LENGTH_LONG).show()
                // Can't start foreground service without valid data, so just stop the service
                stopSelf()
                return
            }

            if (data == null) {
                Log.e(TAG, "No data received")
                Toast.makeText(this, "No screen capture data received", Toast.LENGTH_LONG).show()
                // Can't start foreground service without valid data, so just stop the service
                stopSelf()
                return
            }

            // IMPORTANT: Start foreground service FIRST before creating MediaProjection
            Log.d(TAG, "Starting foreground service with MediaProjection type")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, createNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }

            // Now we can safely create the MediaProjection
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                Log.e(TAG, "Failed to create media projection")
                Toast.makeText(this, "Failed to create media projection", Toast.LENGTH_LONG).show()
                handleVncStartupFailure("Failed to create media projection")
                return
            }

            // Register MediaProjection callback (required for Android 14+)
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped by user")
                    handler.post {
                        Toast.makeText(this@VncServerService, "Screen recording stopped by user", Toast.LENGTH_LONG).show()
                        stopVncServer()
                    }
                }
            }
            mediaProjectionCallback = callback
            mediaProjection?.registerCallback(callback, handler)

            // Get screen dimensions
            val metrics = resources.displayMetrics
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi

            Log.d(TAG, "Screen dimensions: ${screenWidth}x${screenHeight}, density: $screenDensity")

            // Create image reader for screen capture
            imageReader = ImageReader.newInstance(
                screenWidth, screenHeight, PixelFormat.RGBA_8888, 2
            )

            if (imageReader == null) {
                Log.e(TAG, "Failed to create image reader")
                Toast.makeText(this, "Failed to create image reader", Toast.LENGTH_LONG).show()
                handleVncStartupFailure("Failed to create image reader")
                return
            }

            // Create virtual display
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "VncDisplay",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, handler
            )

            if (virtualDisplay == null) {
                Log.e(TAG, "Failed to create virtual display")
                Toast.makeText(this, "Failed to create virtual display", Toast.LENGTH_LONG).show()
                handleVncStartupFailure("Failed to create virtual display")
                return
            }

            // Start VNC server
            startVncServerThread()

            isRunning.set(true)

            // Send broadcast update
            sendVncStatusUpdate(true)

            Log.d(TAG, "VNC server started successfully on port $DEFAULT_PORT")
            Toast.makeText(this, "VNC server started on port $DEFAULT_PORT", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VNC server: ${e.message}", e)
            Toast.makeText(this, "Error starting VNC server: ${e.message}", Toast.LENGTH_LONG).show()
            handleVncStartupFailure("Error: ${e.message}")
        }
    }

    private fun startVncServerThread() {
        try {
            Log.d(TAG, "Starting VNC server thread")
            vncServer = VncServer(DEFAULT_PORT, imageReader, screenWidth, screenHeight)
            vncThread = Thread(vncServer)
            vncThread?.start()
            Log.d(TAG, "VNC server thread started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VNC server thread: ${e.message}", e)
            throw e
        }
    }

    private fun stopVncServer() {
        try {
            Log.d(TAG, "Stopping VNC server")
            isRunning.set(false)

            vncServer?.stop()
            vncServer = null

            vncThread?.interrupt()
            vncThread = null

            virtualDisplay?.release()
            virtualDisplay = null

            imageReader?.close()
            imageReader = null

            // Unregister MediaProjection callback and stop
            mediaProjectionCallback?.let { callback ->
                mediaProjection?.unregisterCallback(callback)
            }
            mediaProjection?.stop()
            mediaProjection = null
            mediaProjectionCallback = null

            // Send broadcast update
            sendVncStatusUpdate(false)

            stopForeground(true)
            stopSelf()

            Log.d(TAG, "VNC server stopped successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VNC server: ${e.message}", e)
        }
    }

    private fun handleVncStartupFailure(reason: String) {
        Log.w(TAG, "VNC startup failed: $reason")

        // Update notification to show failure (foreground service is already running)
        val failureNotification = createFailureNotification(reason)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, failureNotification)

        // Set running to false since VNC didn't start
        isRunning.set(false)

        // Send broadcast update
        sendVncStatusUpdate(false)

        // Don't stop the service - let it run with the failure notification
        // The user can manually stop it or try to restart
    }

    private fun sendVncStatusUpdate(isRunning: Boolean) {
        try {
            val intent = Intent("com.app.streamify.VNC_STATUS_CHANGED").apply {
                putExtra("is_running", isRunning)
            }
            sendBroadcast(intent)
            Log.d(TAG, "Sent VNC status broadcast: $isRunning")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending VNC status broadcast: ${e.message}", e)
        }
    }

    private fun createFailureNotification(reason: String): Notification {
        return try {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Streamify VNC Server")
                .setContentText("Failed to start: $reason")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating failure notification: ${e.message}", e)
            // Return a basic notification if the custom one fails
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VNC Server")
                .setContentText("Failed to start")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .build()
        }
    }

    private fun createNotificationChannel() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "VNC Server",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "VNC screen streaming service"
                }

                val notificationManager = getSystemService(NotificationManager::class.java)
                notificationManager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error creating notification channel: ${e.message}", e)
        }
    }

    private fun createNotification(): Notification {
        return try {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Streamify VNC Server")
                .setContentText("Screen streaming active on port $DEFAULT_PORT")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Error creating notification: ${e.message}", e)
            // Return a basic notification if the custom one fails
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("VNC Server")
                .setContentText("Running")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        try {
            Log.d(TAG, "Service onDestroy")
            stopVncServer()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDestroy: ${e.message}", e)
        }
        super.onDestroy()
    }

}
