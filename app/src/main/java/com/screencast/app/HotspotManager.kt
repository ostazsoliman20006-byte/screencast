package com.screencast.app

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log

class HotspotManager(private val context: Context) {

    companion object {
        private const val TAG = "HotspotManager"
    }

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private val wifiManager: WifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    data class HotspotInfo(val ssid: String, val password: String, val isActive: Boolean)

    @SuppressLint("MissingPermission")
    fun startHotspot(
        onStarted: (HotspotInfo) -> Unit,
        onFailed: (String) -> Unit
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onFailed("نقطة الاتصال المحلية تتطلب أندرويد 8 فأعلى")
            return
        }

        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val config = res.wifiConfiguration
                    val ssid = config?.SSID ?: "غير متاح"
                    val password = config?.preSharedKey ?: "غير متاح"
                    Log.i(TAG, "تم تشغيل نقطة الاتصال: $ssid")
                    onStarted(HotspotInfo(ssid, password, true))
                }

                override fun onStopped() {
                    Log.i(TAG, "تم إيقاف نقطة الاتصال")
                    reservation = null
                }

                override fun onFailed(reason: Int) {
                    Log.e(TAG, "فشل تشغيل نقطة الاتصال، السبب: $reason")
                    onFailed("فشل تشغيل نقطة الاتصال (رمز الخطأ: $reason)")
                }
            }, null)
        } catch (e: SecurityException) {
            onFailed("إذن الموقع مطلوب لتفعيل نقطة الاتصال: ${e.message}")
        }
    }

    fun stopHotspot() {
        reservation?.close()
        reservation = null
    }

    fun isActive(): Boolean = reservation != null

    fun getLocalIpAddress(): String {
        return try {
            val wifiInfo = wifiManager.connectionInfo
            val ip = wifiInfo.ipAddress
            String.format(
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
            )
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }
}
