package com.screencast.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "screencast_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.screencast.app.START"
        const val ACTION_STOP = "com.screencast.app.STOP"
        const val ACTION_UPDATE_SETTINGS = "com.screencast.app.UPDATE_SETTINGS"

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_WIDTH = "width"
        const val EXTRA_HEIGHT = "height"
        const val EXTRA_DENSITY = "density"
        const val EXTRA_QUALITY = "quality"
        const val EXTRA_FPS = "fps"
        const val EXTRA_SCALE = "scale"
        const val EXTRA_PORT = "port"

        var isRunning = false
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var streamServer: StreamServer

    @Volatile private var jpegQuality = 70
    @Volatile private var targetFps = 15
    @Volatile private var resolutionScale = 0.75f

    private var captureThread: Thread? = null
    @Volatile private var capturing = false

    override fun onCreate() {
        super.onCreate()
        streamServer = StreamServer(8080)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            ACTION_UPDATE_SETTINGS -> handleUpdateSettings(intent)
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return

        jpegQuality = intent.getIntExtra(EXTRA_QUALITY, 70)
        targetFps = intent.getIntExtra(EXTRA_FPS, 15)
        resolutionScale = intent.getFloatExtra(EXTRA_SCALE, 0.75f)
        val port = intent.getIntExtra(EXTRA_PORT, 8080)

        val screenWidth = intent.getIntExtra(EXTRA_WIDTH, 1080)
        val screenHeight = intent.getIntExtra(EXTRA_HEIGHT, 1920)
        val density = intent.getIntExtra(EXTRA_DENSITY, DisplayMetrics.DENSITY_DEFAULT)

        startForeground(NOTIFICATION_ID, buildNotification("جارِ البث..."))

        streamServer = StreamServer(port)
        streamServer.start()

        val projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, resultData)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i("ScreenCaptureService", "تم إيقاف إذن التقاط الشاشة من النظام")
                handleStop()
            }
        }, null)

        setupCapture(screenWidth, screenHeight, density)
        isRunning = true
    }

    private fun setupCapture(screenWidth: Int, screenHeight: Int, density: Int) {
        val scaledWidth = (screenWidth * resolutionScale).toInt().coerceAtLeast(2) and 0xFFFFFFFE.toInt()
        val scaledHeight = (screenHeight * resolutionScale).toInt().coerceAtLeast(2) and 0xFFFFFFFE.toInt()

        imageReader = ImageReader.newInstance(
            scaledWidth, scaledHeight, ImageFormat.YUV_420_888, 2
        )

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "ScreenCastVirtualDisplay",
            scaledWidth, scaledHeight, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )

        capturing = true
        captureThread = Thread {
            runCaptureLoop()
        }.also { it.start() }
    }

    private fun runCaptureLoop() {
        while (capturing) {
            val frameIntervalMs = (1000L / targetFps.coerceAtLeast(1))
            val startTime = System.currentTimeMillis()

            try {
                val image = imageReader?.acquireLatestImage()
                if (image != null) {
                    val jpegBytes = imageToJpeg(image, jpegQuality)
                    image.close()
                    if (jpegBytes != null) {
                        streamServer.pushFrame(jpegBytes)
                    }
                }
            } catch (e: Exception) {
                Log.e("ScreenCaptureService", "خطأ أثناء التقاط إطار: ${e.message}")
            }

            val elapsed = System.currentTimeMillis() - startTime
            val sleepTime = frameIntervalMs - elapsed
            if (sleepTime > 0) {
                try { Thread.sleep(sleepTime) } catch (_: InterruptedException) {}
            }
        }
    }

    private fun imageToJpeg(image: Image, quality: Int): ByteArray? {
        return try {
            val nv21 = yuv420ToNv21(image)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), quality, out)
            out.toByteArray()
        } catch (e: Exception) {
            Log.e("ScreenCaptureService", "فشل تحويل الإطار: ${e.message}")
            null
        }
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4
        val nv21 = ByteArray(ySize + uvSize * 2)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        var pos = 0
        val yBuffer = yPlane.buffer
        val yRowStride = yPlane.rowStride
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, pos, width)
            pos += width
        }

        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        var uvPos = ySize
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                val uIndex = row * uvRowStride + col * uvPixelStride
                val vIndex = row * uvRowStride + col * uvPixelStride
                nv21[uvPos++] = vBuffer.get(vIndex)
                nv21[uvPos++] = uBuffer.get(uIndex)
            }
        }
        return nv21
    }

    private fun handleUpdateSettings(intent: Intent) {
        if (intent.hasExtra(EXTRA_QUALITY)) jpegQuality = intent.getIntExtra(EXTRA_QUALITY, jpegQuality)
        if (intent.hasExtra(EXTRA_FPS)) targetFps = intent.getIntExtra(EXTRA_FPS, targetFps)
        Log.i("ScreenCaptureService", "تحديث الإعدادات: جودة=$jpegQuality, FPS=$targetFps")
    }

    private fun handleStop() {
        capturing = false
        captureThread?.join(500)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        streamServer.stop()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handleStop()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "بث الشاشة", NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ScreenCast")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_slideshow)
            .setOngoing(true)
            .build()
    }

    fun getConnectedClientsCount(): Int = streamServer.connectedClients
}
