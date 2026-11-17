package com.screencast.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.screencast.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var hotspotManager: HotspotManager
    private lateinit var projectionManager: MediaProjectionManager

    private var currentQuality = 70
    private var currentFps = 15
    private var currentScale = 0.75f
    private var currentPort = 8080

    private val mainHandler = Handler(Looper.getMainLooper())
    private var statusUpdater: Runnable? = null

    private val screenCaptureLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            launchCaptureService(result.resultCode, result.data!!)
        } else {
            binding.startStopButton.text = "بدء البث الحي"
        }
    }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startHotspotInternal()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hotspotManager = HotspotManager(this)
        projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        setupListeners()
        startStatusPolling()
    }

    private fun setupListeners() {
        binding.toggleHotspotButton.setOnClickListener {
            if (hotspotManager.isActive()) {
                hotspotManager.stopHotspot()
                updateHotspotUi(active = false)
            } else {
                requestLocationPermissionThenStart()
            }
        }

        binding.startStopButton.setOnClickListener {
            if (ScreenCaptureService.isRunning) {
                stopCapture()
            } else {
                currentPort = binding.portEditText.text.toString().toIntOrNull() ?: 8080
                screenCaptureLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        binding.qualitySeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentQuality = progress.coerceAtLeast(10)
                binding.qualityLabel.text = "جودة الصورة: $currentQuality%"
                if (fromUser) pushSettingsUpdate()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.fpsSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentFps = progress.coerceAtLeast(1)
                binding.fpsLabel.text = "معدل الإطارات: $currentFps FPS"
                if (fromUser) pushSettingsUpdate()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.resolutionSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val percent = progress.coerceAtLeast(25)
                currentScale = percent / 100f
                binding.resolutionLabel.text = "الدقة: $percent% من الأصلية"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun requestLocationPermissionThenStart() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startHotspotInternal()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun startHotspotInternal() {
        hotspotManager.startHotspot(
            onStarted = { info ->
                runOnUiThread {
                    binding.hotspotStatusText.text = "مُفعّلة ✓"
                    binding.hotspotCredentialsText.text =
                        "الاسم: ${info.ssid}\nكلمة المرور: ${info.password}"
                    binding.toggleHotspotButton.text = "إيقاف نقطة الاتصال"
                }
            },
            onFailed = { error ->
                runOnUiThread {
                    binding.hotspotStatusText.text = "فشل: $error"
                }
            }
        )
    }

    private fun updateHotspotUi(active: Boolean) {
        binding.hotspotStatusText.text = if (active) "مُفعّلة ✓" else "غير مُفعّلة"
        binding.hotspotCredentialsText.text = ""
        binding.toggleHotspotButton.text =
            if (active) "إيقاف نقطة الاتصال" else "تشغيل نقطة الاتصال"
    }

    private fun launchCaptureService(resultCode: Int, data: Intent) {
        val metrics = resources.displayMetrics
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_WIDTH, metrics.widthPixels)
            putExtra(ScreenCaptureService.EXTRA_HEIGHT, metrics.heightPixels)
            putExtra(ScreenCaptureService.EXTRA_DENSITY, metrics.densityDpi)
            putExtra(ScreenCaptureService.EXTRA_QUALITY, currentQuality)
            putExtra(ScreenCaptureService.EXTRA_FPS, currentFps)
            putExtra(ScreenCaptureService.EXTRA_SCALE, currentScale)
            putExtra(ScreenCaptureService.EXTRA_PORT, currentPort)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        binding.startStopButton.text = "إيقاف البث"
        val ip = hotspotManager.getLocalIpAddress()
        binding.streamUrlText.text = "http://$ip:$currentPort/stream"
    }

    private fun pushSettingsUpdate() {
        if (!ScreenCaptureService.isRunning) return
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_UPDATE_SETTINGS
            putExtra(ScreenCaptureService.EXTRA_QUALITY, currentQuality)
            putExtra(ScreenCaptureService.EXTRA_FPS, currentFps)
        }
        startService(intent)
    }

    private fun stopCapture() {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(intent)
        binding.startStopButton.text = "بدء البث الحي"
        binding.streamUrlText.text = "غير متاح — شغّل البث أولًا"
    }

    private fun startStatusPolling() {
        statusUpdater = object : Runnable {
            override fun run() {
                if (ScreenCaptureService.isRunning) {
                    binding.connectedClientsText.text =
                        "البث يعمل حاليًا على المنفذ $currentPort"
                }
                mainHandler.postDelayed(this, 2000)
            }
        }
        mainHandler.post(statusUpdater!!)
    }

    override fun onDestroy() {
        statusUpdater?.let { mainHandler.removeCallbacks(it) }
        super.onDestroy()
    }
}
