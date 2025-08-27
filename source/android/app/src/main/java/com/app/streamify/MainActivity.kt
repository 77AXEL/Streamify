package com.app.streamify

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.app.streamify.ui.theme.StreamifyTheme
import androidx.compose.ui.res.colorResource
import androidx.compose.foundation.isSystemInDarkTheme

class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        const val ACTION_VNC_STATUS_CHANGED = "com.app.streamify.VNC_STATUS_CHANGED"
        const val EXTRA_IS_RUNNING = "is_running"
        const val ACTION_START_VNC_ADB = "com.app.streamify.START_VNC_ADB"
        const val ACTION_STOP_VNC_ADB = "com.app.streamify.STOP_VNC_ADB"

        // Add new action for service to request permission
        const val ACTION_REQUEST_PERMISSION = "com.app.streamify.REQUEST_PERMISSION"
    }

    private var isVncRunning by mutableStateOf(false)
    private var hasScreenCapturePermission by mutableStateOf(false)
    private var pendingAdbStart by mutableStateOf(false) // Track if we're waiting for permission for ADB start

    private val vncStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_VNC_STATUS_CHANGED) {
                val newStatus = intent.getBooleanExtra(EXTRA_IS_RUNNING, false)
                Log.d(TAG, "VNC status updated via broadcast: $newStatus")
                isVncRunning = newStatus
            }
        }
    }

    private val adbCommandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_START_VNC_ADB -> {
                    Log.d(TAG, "Received ADB start command")
                    handleAdbStartVnc()
                }
                ACTION_STOP_VNC_ADB -> {
                    Log.d(TAG, "Received ADB stop command")
                    handleAdbStopVnc()
                }
                ACTION_REQUEST_PERMISSION -> {
                    Log.d(TAG, "Service requesting permission")
                    pendingAdbStart = true
                    requestScreenCapturePermission()
                }
            }
        }
    }

    private val startVncLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Log.d(TAG, "Media projection permission granted")
            hasScreenCapturePermission = true

            if (pendingAdbStart) {
                // This was triggered by ADB command, start the service with permission
                pendingAdbStart = false
                startVncServerWithPermission(result.resultCode, result.data)
            }
        } else {
            Log.w(TAG, "Media projection permission denied")
            hasScreenCapturePermission = false
            pendingAdbStart = false
            Toast.makeText(this, "Screen recording permission is required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check current service status early
        checkVncServiceStatus()

        // Request permissions on startup
        if (!checkPermissions()) {
            requestPermissions()
        }

        setContent {
            StreamifyTheme {
                StreamifyApp(
                    isVncRunning = isVncRunning,
                    hasPermission = hasScreenCapturePermission
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        try {
            // Register broadcast receiver for VNC status updates
            val statusFilter = IntentFilter(ACTION_VNC_STATUS_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(vncStatusReceiver, statusFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(vncStatusReceiver, statusFilter)
            }

            // Register broadcast receiver for ADB commands
            val adbFilter = IntentFilter().apply {
                addAction(ACTION_START_VNC_ADB)
                addAction(ACTION_STOP_VNC_ADB)
                addAction(ACTION_REQUEST_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(adbCommandReceiver, adbFilter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(adbCommandReceiver, adbFilter)
            }

            Log.d(TAG, "Broadcast receivers registered successfully")

            // Check service status when activity starts
            checkServiceStatusDelayed()

        } catch (e: Exception) {
            Log.e(TAG, "Error registering broadcast receivers: ${e.message}", e)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            // Unregister broadcast receivers
            unregisterReceiver(vncStatusReceiver)
            unregisterReceiver(adbCommandReceiver)
            Log.d(TAG, "Broadcast receivers unregistered successfully")
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering receivers: ${e.message}")
        }
    }

    private fun handleAdbStartVnc() {
        try {
            Log.d(TAG, "Handling ADB start VNC command")

            // Check permissions first
            if (!checkPermissions()) {
                Log.e(TAG, "Missing required permissions for VNC")
                Toast.makeText(this, "Missing required permissions", Toast.LENGTH_LONG).show()
                // Send failure status
                sendStatusBroadcast(false)
                return
            }

            // If we don't have screen capture permission, request it
            if (!hasScreenCapturePermission) {
                Log.d(TAG, "Requesting screen capture permission for ADB command")
                pendingAdbStart = true
                requestScreenCapturePermission()
                return
            }

            // Start VNC server directly since we have permission
            startVncServerDirect()

        } catch (e: Exception) {
            Log.e(TAG, "Error handling ADB start VNC: ${e.message}", e)
            Toast.makeText(this, "Error starting VNC: ${e.message}", Toast.LENGTH_LONG).show()
            sendStatusBroadcast(false)
        }
    }

    private fun handleAdbStopVnc() {
        try {
            Log.d(TAG, "Handling ADB stop VNC command")
            stopVncServer()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ADB stop VNC: ${e.message}", e)
            Toast.makeText(this, "Error stopping VNC: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun requestScreenCapturePermission() {
        try {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startVncLauncher.launch(projectionManager.createScreenCaptureIntent())
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting screen capture permission: ${e.message}", e)
            Toast.makeText(this, "Error requesting screen capture permission", Toast.LENGTH_LONG).show()
            sendStatusBroadcast(false)
        }
    }

    private fun startVncServerDirect() {
        try {
            Log.d(TAG, "Starting VNC server service directly")

            val intent = Intent(this, VncServerService::class.java).apply {
                action = "START_VNC"
            }

            startForegroundService(intent)
            Log.d(TAG, "VNC server service started successfully")
            Toast.makeText(this, "Starting VNC server...", Toast.LENGTH_SHORT).show()

            // Update UI state immediately (optimistic update)
            isVncRunning = true

            // Send status broadcast
            sendStatusBroadcast(true)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VNC server: ${e.message}", e)
            Toast.makeText(this, "Error starting VNC server: ${e.message}", Toast.LENGTH_LONG).show()
            isVncRunning = false
            sendStatusBroadcast(false)
        }
    }

    private fun startVncServerWithPermission(resultCode: Int, data: Intent?) {
        try {
            Log.d(TAG, "Starting VNC server service with fresh permission")

            val intent = Intent(this, VncServerService::class.java).apply {
                action = "START_VNC"
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }

            startForegroundService(intent)
            Log.d(TAG, "VNC server service started successfully")
            Toast.makeText(this, "Starting VNC server...", Toast.LENGTH_SHORT).show()

            // Update UI state immediately
            isVncRunning = true
            sendStatusBroadcast(true)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting VNC server with permission: ${e.message}", e)
            Toast.makeText(this, "Error starting VNC server: ${e.message}", Toast.LENGTH_LONG).show()
            isVncRunning = false
            sendStatusBroadcast(false)
        }
    }

    private fun stopVncServer() {
        try {
            Log.d(TAG, "Stopping VNC server service")

            val intent = Intent(this, VncServerService::class.java).apply {
                action = "STOP_VNC"
            }
            startService(intent)
            Log.d(TAG, "VNC server service stop command sent")
            Toast.makeText(this, "Stopping VNC server...", Toast.LENGTH_SHORT).show()

            // Update UI state immediately
            isVncRunning = false
            sendStatusBroadcast(false)

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VNC server: ${e.message}", e)
            Toast.makeText(this, "Error stopping VNC server: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sendStatusBroadcast(isRunning: Boolean) {
        try {
            val intent = Intent(ACTION_VNC_STATUS_CHANGED).apply {
                putExtra(EXTRA_IS_RUNNING, isRunning)
            }
            sendBroadcast(intent)
            Log.d(TAG, "Status broadcast sent: $isRunning")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending status broadcast: ${e.message}", e)
        }
    }

    private fun checkPermissions(): Boolean {
        val permissions = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.POST_NOTIFICATIONS
        )

        return permissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.POST_NOTIFICATIONS
        )

        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d(TAG, "All permissions granted")
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show()
            } else {
                Log.w(TAG, "Some permissions denied")
                Toast.makeText(this, "Some permissions are required for VNC server", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun checkVncServiceStatus() {
        try {
            // For now, just assume the service is not running on startup
            // The actual status will be updated via broadcast receiver when the service starts/stops
            isVncRunning = false
            Log.d(TAG, "Service status check: assuming not running on startup")
        } catch (e: Exception) {
            Log.w(TAG, "Error checking service status: ${e.message}")
            isVncRunning = false
        }
    }

    private fun checkServiceStatusDelayed() {
        // Add a small delay to allow service to send status if it's running
        try {
            Log.d(TAG, "Requesting current service status")
            val intent = Intent(this, VncServerService::class.java).apply {
                action = "GET_STATUS"
            }
            startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Error requesting service status: ${e.message}")
        }
    }
}

@Composable
fun StreamifyApp(
    isVncRunning: Boolean,
    hasPermission: Boolean
) {
    val context = LocalContext.current
    var ipAddress by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val wifiManager = context.getSystemService(WifiManager::class.java)
        val connectionInfo = wifiManager.connectionInfo
        if (connectionInfo != null) {
            ipAddress = Formatter.formatIpAddress(connectionInfo.ipAddress)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = if (isSystemInDarkTheme())
            colorResource(id = R.color.dark)
        else
            colorResource(id = R.color.light)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = "Streamify",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 24.dp),
                color = colorResource(id = R.color.green)
            )

            // Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isVncRunning)
                        colorResource(id = R.color.green)
                    else
                        if (isSystemInDarkTheme())
                            colorResource(id = R.color.card_dark)
                        else
                            colorResource(id = R.color.card_light)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,

                ) {
                    Text(
                        text = "VNC Server Status",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = if (isVncRunning)
                            colorResource(id = R.color.light)
                        else
                            if (isSystemInDarkTheme())
                                colorResource(id = R.color.light)
                            else
                                colorResource(id = R.color.dark)
                    )

                    Text(
                        text = if (isVncRunning) "RUNNING" else "STOPPED",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isVncRunning)
                            colorResource(id = R.color.light)
                        else
                            if (isSystemInDarkTheme())
                                colorResource(id = R.color.light)
                            else
                                colorResource(id = R.color.dark)
                    )

                    if (ipAddress.isNotEmpty()) {
                        Text(
                            text = "IP Address: $ipAddress",
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 8.dp),
                            color = if (isVncRunning)
                                colorResource(id = R.color.light)
                            else
                                if (isSystemInDarkTheme())
                                    colorResource(id = R.color.light)
                                else
                                    colorResource(id = R.color.dark)
                        )
                    }

                    Text(
                        text = "Screen Capture Permission: ${if (hasPermission) "GRANTED" else "NOT GRANTED"}",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 4.dp),
                        color = if (isVncRunning)
                            colorResource(id = R.color.light)
                        else
                            if (isSystemInDarkTheme())
                                colorResource(id = R.color.light)
                            else
                                colorResource(id = R.color.dark)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Control Method Info Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(
                    if (isSystemInDarkTheme())
                        colorResource(id = R.color.card_dark)
                    else
                        colorResource(id = R.color.card_light)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Instructions",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 12.dp),
                        color = if (isSystemInDarkTheme())
                            colorResource(id = R.color.light)
                        else
                            colorResource(id = R.color.dark)
                    )

                    Text(
                        text = "1. Plug your Android device into the PC using a USB cable",
                        fontSize = 14.sp,
                        modifier = Modifier.padding(bottom = 12.dp),
                        color = if (isSystemInDarkTheme())
                            colorResource(id = R.color.light)
                        else
                            colorResource(id = R.color.dark)
                    )

                    Text(
                        text = "2. Make sure you have enabled ADB debugging in your developer settings",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isSystemInDarkTheme())
                            colorResource(id = R.color.light)
                        else
                            colorResource(id = R.color.dark)
                    )
                }
            }
        }
    }
}