package com.app.streamify

import android.graphics.Bitmap
import android.media.ImageReader
import android.util.Log
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class VncServer(
    private val port: Int,
    private val imageReader: ImageReader?,
    private val screenWidth: Int,
    private val screenHeight: Int
) : Runnable {

    companion object {
        private const val TAG = "VncServer"
        private const val VNC_VERSION = "RFB 003.008\n"
        private const val AUTH_NONE = 1
        private const val AUTH_SUCCESS = 0
        private const val ENCODING_RAW = 0
    }

    init {
        if (port <= 0 || port > 65535) {
            throw IllegalArgumentException("Invalid port number: $port")
        }
        if (screenWidth <= 0 || screenHeight <= 0) {
            throw IllegalArgumentException("Invalid screen dimensions: ${screenWidth}x${screenHeight}")
        }
        Log.d(TAG, "VncServer initialized with port: $port, screen: ${screenWidth}x${screenHeight}")
    }

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private var clientSocket: Socket? = null
    private var outputStream: DataOutputStream? = null
    private var inputStream: DataInputStream? = null

    // Cache the last successful screen capture
    private var lastScreenData: ByteArray? = null
    private var lastScreenWidth = 320
    private var lastScreenHeight = 700

    override fun run() {
        try {
            Log.d(TAG, "VNC server starting on port $port")

            serverSocket = ServerSocket(port)
            isRunning.set(true)
            Log.d(TAG, "VNC server listening on port $port")

            while (isRunning.get()) {
                try {
                    clientSocket = serverSocket?.accept()
                    clientSocket?.let { socket ->
                        Log.d(TAG, "Client connected: ${socket.inetAddress}")
                        handleClient(socket)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Error accepting client: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting VNC server: ${e.message}", e)
        } finally {
            cleanup()
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            outputStream = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
            inputStream = DataInputStream(BufferedInputStream(socket.getInputStream()))

            performVncHandshake()

            while (isRunning.get() && !socket.isClosed) {
                try {
                    val messageType = inputStream?.readByte()?.toInt() ?: break
                    Log.d(TAG, "Received VNC message type: $messageType")

                    when (messageType) {
                        0 -> handleSetPixelFormat()
                        2 -> handleSetEncodings()
                        3 -> handleFramebufferUpdateRequest()
                        4 -> handleKeyEvent()
                        5 -> handlePointerEvent()
                        6 -> handleClientCutText()
                        else -> {
                            Log.w(TAG, "Unknown message type: $messageType")
                            // Skip unknown messages gracefully
                            Thread.sleep(10)
                        }
                    }
                } catch (e: EOFException) {
                    Log.d(TAG, "Client disconnected")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling message: ${e.message}")
                    Thread.sleep(100)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client: ${e.message}")
        } finally {
            cleanupClient()
        }
    }

    private fun performVncHandshake() {
        try {
            // Send VNC version
            outputStream?.writeBytes(VNC_VERSION)
            outputStream?.flush()
            Log.d(TAG, "Sent VNC version")

            // Read client version
            val clientVersionBytes = ByteArray(12)
            inputStream?.readFully(clientVersionBytes)
            val clientVersion = String(clientVersionBytes).trim()
            Log.d(TAG, "Client version: $clientVersion")

            // Send authentication methods
            outputStream?.writeByte(1)        // Number of auth methods
            outputStream?.writeByte(AUTH_NONE) // No authentication
            outputStream?.flush()

            // Read client auth choice
            val authChoice = inputStream?.readByte() ?: 0
            Log.d(TAG, "Client auth choice: $authChoice")

            // Send authentication result
            outputStream?.writeInt(AUTH_SUCCESS)
            outputStream?.flush()

            // Send server initialization
            sendServerInit()

            // Read client initialization (shared flag)
            val sharedFlag = inputStream?.readByte() ?: 0
            Log.d(TAG, "Client shared flag: $sharedFlag")

        } catch (e: Exception) {
            Log.e(TAG, "Error during VNC handshake: ${e.message}", e)
            throw e
        }
    }

    private fun sendServerInit() {
        try {
            // Fixed dimensions for consistent client display
            val displayWidth = 320
            val displayHeight = 700

            // Framebuffer dimensions
            outputStream?.writeShort(displayWidth)
            outputStream?.writeShort(displayHeight)

            // Pixel format - Use 32-bit RGBA format for consistency
            outputStream?.writeByte(32) // bits per pixel (4 bytes per pixel)
            outputStream?.writeByte(24) // depth (24-bit color)
            outputStream?.writeByte(0)  // big endian flag (0 = little endian)
            outputStream?.writeByte(1)  // true color flag
            outputStream?.writeShort(255) // red max
            outputStream?.writeShort(255) // green max
            outputStream?.writeShort(255) // blue max
            outputStream?.writeByte(16) // red shift
            outputStream?.writeByte(8)  // green shift
            outputStream?.writeByte(0)  // blue shift
            // 3 bytes padding
            outputStream?.writeByte(0)
            outputStream?.writeByte(0)
            outputStream?.writeByte(0)

            // Server name
            val serverName = "Streamify Android VNC"
            outputStream?.writeInt(serverName.length)
            outputStream?.writeBytes(serverName)
            outputStream?.flush()

            Log.d(TAG, "Server initialization sent: ${displayWidth}x${displayHeight}, 32bpp")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending server init: ${e.message}")
            throw e
        }
    }

    private fun handleSetPixelFormat() {
        try {
            inputStream?.skipBytes(3) // padding
            inputStream?.skipBytes(16) // pixel format (16 bytes)
            Log.d(TAG, "Set pixel format handled")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling set pixel format: ${e.message}")
        }
    }

    private fun handleSetEncodings() {
        try {
            inputStream?.skipBytes(1) // padding
            val numEncodings = inputStream?.readShort()?.toInt() ?: 0

            for (i in 0 until numEncodings) {
                val encoding = inputStream?.readInt() ?: 0
                Log.d(TAG, "Client supports encoding: $encoding")
            }
            Log.d(TAG, "Set encodings handled: $numEncodings encodings")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling set encodings: ${e.message}")
        }
    }

    private fun handleFramebufferUpdateRequest() {
        try {
            val incremental = inputStream?.readByte() ?: 0
            val x = inputStream?.readShort()?.toInt() ?: 0
            val y = inputStream?.readShort()?.toInt() ?: 0
            val w = inputStream?.readShort()?.toInt() ?: 0
            val h = inputStream?.readShort()?.toInt() ?: 0

            Log.d(TAG, "Framebuffer update request: $x,$y ${w}x$h incremental=$incremental")

            // Send update immediately without delay
            sendFramebufferUpdate(0, 0, 320, 700)

        } catch (e: Exception) {
            Log.e(TAG, "Error handling framebuffer update request: ${e.message}")
        }
    }

    private fun sendFramebufferUpdate(x: Int, y: Int, w: Int, h: Int) {
        try {
            // Framebuffer update message header
            outputStream?.writeByte(0)     // Message type
            outputStream?.writeByte(0)     // Padding
            outputStream?.writeShort(1)    // Number of rectangles

            // Rectangle header
            outputStream?.writeShort(x)    // X position
            outputStream?.writeShort(y)    // Y position
            outputStream?.writeShort(w)    // Width
            outputStream?.writeShort(h)    // Height
            outputStream?.writeInt(ENCODING_RAW) // Encoding type

            // Send pixel data
            captureAndSendScreen(w, h)

            outputStream?.flush()
            Log.d(TAG, "Framebuffer update sent: ${w}x${h}")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending framebuffer update: ${e.message}")
            throw e
        }
    }

    private fun captureAndSendScreen(targetWidth: Int, targetHeight: Int) {
        try {
            // Log phone screen info for debugging
            Log.d(TAG, "Phone screen: ${screenWidth}x${screenHeight}")
            Log.d(TAG, "Target dimensions: ${targetWidth}x${targetHeight}")
            Log.d(TAG, "Aspect ratios - Phone: ${screenWidth.toFloat()/screenHeight}, Target: ${targetWidth.toFloat()/targetHeight}")

            // Try to get the most recent image first
            var image = imageReader?.acquireLatestImage()

            // If no latest image, try a few times with minimal delay
            var attempts = 0
            val maxAttempts = 3

            while (image == null && attempts < maxAttempts) {
                Thread.sleep(5)
                image = imageReader?.acquireLatestImage()
                attempts++
            }

            if (image == null) {
                image = imageReader?.acquireNextImage()
            }

            if (image == null) {
                Log.w(TAG, "No fresh image available")
                if (lastScreenData != null && lastScreenWidth == targetWidth && lastScreenHeight == targetHeight) {
                    Log.d(TAG, "Using cached screen data")
                    outputStream?.write(lastScreenData!!)
                    return
                } else {
                    Log.w(TAG, "No cached screen data available, sending test pattern")
                    sendTestPattern(targetWidth, targetHeight)
                    return
                }
            }

            image.use { img ->
                val planes = img.planes
                if (planes.isEmpty()) {
                    Log.w(TAG, "No image planes available")
                    if (lastScreenData != null) {
                        outputStream?.write(lastScreenData!!)
                    } else {
                        sendTestPattern(targetWidth, targetHeight)
                    }
                    return
                }

                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * screenWidth

                Log.d(TAG, "Image buffer info - stride: $rowStride, pixel: $pixelStride, padding: $rowPadding")
                Log.d(TAG, "Buffer capacity: ${buffer.capacity()}, remaining: ${buffer.remaining()}")

                // Create bitmap from image buffer with improved handling
                val bitmap = createBitmapFromBufferImproved(buffer, rowStride, pixelStride)
                if (bitmap == null) {
                    if (lastScreenData != null) {
                        outputStream?.write(lastScreenData!!)
                    } else {
                        sendTestPattern(targetWidth, targetHeight)
                    }
                    return
                }

                Log.d(TAG, "Created bitmap: ${bitmap.width}x${bitmap.height}")

                // Calculate proper scaling to maintain aspect ratio
                val phoneAspect = screenWidth.toFloat() / screenHeight
                val targetAspect = targetWidth.toFloat() / targetHeight

                val scaledBitmap = if (abs(phoneAspect - targetAspect) > 0.1f) {
                    // Aspect ratios don't match - use proper scaling with letterboxing
                    Log.d(TAG, "Aspect ratio mismatch, using letterbox scaling")
                    createLetterboxedBitmap(bitmap, targetWidth, targetHeight)
                } else {
                    // Aspect ratios match - direct scaling
                    Log.d(TAG, "Aspect ratios match, using direct scaling")
                    Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                }

                Log.d(TAG, "Scaled bitmap: ${scaledBitmap.width}x${scaledBitmap.height}")

                // Convert to RGBA and send
                val rgbaData = bitmapToRgbBytes(scaledBitmap)

                // Cache the screen data for future use
                lastScreenData = rgbaData
                lastScreenWidth = targetWidth
                lastScreenHeight = targetHeight

                outputStream?.write(rgbaData)

                bitmap.recycle()
                scaledBitmap.recycle()

                Log.d(TAG, "Screen captured and sent: ${rgbaData.size} bytes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error capturing screen: ${e.message}")
            sendTestPattern(targetWidth, targetHeight)
        }
    }

    private fun createBitmapFromBufferImproved(buffer: ByteBuffer, rowStride: Int, pixelStride: Int): Bitmap? {
        try {
            Log.d(TAG, "Creating bitmap from buffer - capacity: ${buffer.capacity()}")

            val bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(screenWidth * screenHeight)

            buffer.rewind()

            // Handle different pixel formats more carefully
            when (pixelStride) {
                4 -> {
                    // Standard RGBA format
                    for (row in 0 until screenHeight) {
                        for (col in 0 until screenWidth) {
                            val pixelPos = row * rowStride + col * pixelStride

                            if (pixelPos + 3 >= buffer.capacity()) {
                                Log.w(TAG, "Buffer overflow at pixel ($col,$row), using black")
                                pixels[row * screenWidth + col] = 0xFF000000.toInt()
                                continue
                            }

                            val r = buffer.get(pixelPos).toInt() and 0xFF
                            val g = buffer.get(pixelPos + 1).toInt() and 0xFF
                            val b = buffer.get(pixelPos + 2).toInt() and 0xFF
                            val a = buffer.get(pixelPos + 3).toInt() and 0xFF

                            pixels[row * screenWidth + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
                1 -> {
                    // Packed format - might need different handling
                    Log.w(TAG, "Packed pixel format detected, using alternative method")
                    var bufferIndex = 0
                    for (row in 0 until screenHeight) {
                        val rowStart = row * rowStride
                        for (col in 0 until screenWidth) {
                            val pixelPos = rowStart + col * 4 // Assume 4 bytes per pixel

                            if (pixelPos + 3 >= buffer.capacity()) {
                                pixels[row * screenWidth + col] = 0xFF000000.toInt()
                                continue
                            }

                            val pixel = buffer.getInt(pixelPos)
                            // Handle different byte orders
                            val r = (pixel shr 0) and 0xFF
                            val g = (pixel shr 8) and 0xFF
                            val b = (pixel shr 16) and 0xFF
                            val a = (pixel shr 24) and 0xFF

                            pixels[row * screenWidth + col] = (a shl 24) or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
                else -> {
                    Log.e(TAG, "Unsupported pixel stride: $pixelStride")
                    return null
                }
            }

            bitmap.setPixels(pixels, 0, screenWidth, 0, 0, screenWidth, screenHeight)
            Log.d(TAG, "Successfully created bitmap: ${bitmap.width}x${bitmap.height}")
            return bitmap

        } catch (e: Exception) {
            Log.e(TAG, "Error creating improved bitmap from buffer: ${e.message}", e)
            return null
        }
    }

    private fun createLetterboxedBitmap(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val sourceAspect = source.width.toFloat() / source.height
        val targetAspect = targetWidth.toFloat() / targetHeight

        val scaledWidth: Int
        val scaledHeight: Int

        if (sourceAspect > targetAspect) {
            // Source is wider - fit to width
            scaledWidth = targetWidth
            scaledHeight = (targetWidth / sourceAspect).toInt()
        } else {
            // Source is taller - fit to height
            scaledHeight = targetHeight
            scaledWidth = (targetHeight * sourceAspect).toInt()
        }

        Log.d(TAG, "Letterbox scaling: ${source.width}x${source.height} -> ${scaledWidth}x${scaledHeight} in ${targetWidth}x${targetHeight}")

        // Create the target bitmap with black background
        val result = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(result)
        canvas.drawColor(android.graphics.Color.BLACK)

        // Scale source image
        val scaledSource = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true)

        // Center the scaled image
        val left = (targetWidth - scaledWidth) / 2
        val top = (targetHeight - scaledHeight) / 2

        canvas.drawBitmap(scaledSource, left.toFloat(), top.toFloat(), null)

        scaledSource.recycle()
        return result
    }

    private fun bitmapToRgbBytes(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Use 32-bit format (4 bytes per pixel: RGBA)
        val rgbaBytes = ByteArray(width * height * 4)
        var index = 0

        for (pixel in pixels) {
            rgbaBytes[index++] = ((pixel shr 16) and 0xFF).toByte() // Red
            rgbaBytes[index++] = ((pixel shr 8) and 0xFF).toByte()  // Green
            rgbaBytes[index++] = (pixel and 0xFF).toByte()          // Blue
            rgbaBytes[index++] = ((pixel shr 24) and 0xFF).toByte() // Alpha
        }

        return rgbaBytes
    }

    private fun sendTestPattern(width: Int, height: Int) {
        try {
            // Use 32-bit format (4 bytes per pixel: RGBA)
            val rgbaData = ByteArray(width * height * 4)
            var index = 0

            for (y in 0 until height) {
                for (x in 0 until width) {
                    // Create a visible test pattern
                    val red = if (x < width / 3) 255.toByte() else 0.toByte()
                    val green = if (x >= width / 3 && x < 2 * width / 3) 255.toByte() else 0.toByte()
                    val blue = if (x >= 2 * width / 3) 255.toByte() else 0.toByte()

                    rgbaData[index++] = red     // Red
                    rgbaData[index++] = green   // Green
                    rgbaData[index++] = blue    // Blue
                    rgbaData[index++] = 255.toByte() // Alpha (fully opaque)
                }
            }

            outputStream?.write(rgbaData)
            Log.d(TAG, "Test pattern sent: ${rgbaData.size} bytes (${width}x${height}x4)")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending test pattern: ${e.message}")
        }
    }

    private fun handleKeyEvent() {
        try {
            inputStream?.skipBytes(1) // padding
            inputStream?.readInt() // key
            inputStream?.readInt() // down flag
        } catch (e: Exception) {
            Log.e(TAG, "Error handling key event: ${e.message}")
        }
    }

    private fun handlePointerEvent() {
        try {
            inputStream?.readByte() // button mask
            inputStream?.readShort() // x
            inputStream?.readShort() // y
        } catch (e: Exception) {
            Log.e(TAG, "Error handling pointer event: ${e.message}")
        }
    }

    private fun handleClientCutText() {
        try {
            inputStream?.skipBytes(3) // padding
            val length = inputStream?.readInt() ?: 0
            inputStream?.skipBytes(length)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client cut text: ${e.message}")
        }
    }

    fun stop() {
        isRunning.set(false)
        cleanup()
    }

    private fun cleanup() {
        try {
            cleanupClient()
            serverSocket?.close()
            serverSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup: ${e.message}")
        }
    }

    private fun cleanupClient() {
        try {
            outputStream?.close()
            inputStream?.close()
            clientSocket?.close()
            outputStream = null
            inputStream = null
            clientSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up client: ${e.message}")
        }
    }
}