package com.android.tv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.fragment.app.FragmentActivity

class SplashActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        window.decorView.postDelayed({
            val intent: Intent
            if (isTelevision(this)) {
                intent = Intent(this, MainActivity::class.java)
            } else {
                intent = Intent(this, PhoneMainActivity::class.java)
            }
            startActivity(intent)
            finish()
        }, 1000) // 1000ms delay
    }

    fun isTelevision(context: Context): Boolean {
        val pm = context.packageManager

        // 1. 检查 ro.build.characteristics 系统属性
        try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getMethod("get", String::class.java, String::class.java)
            val characteristics = getMethod.invoke(null, "ro.build.characteristics", "") as String
            if (characteristics.contains("tv", ignoreCase = true)) {
                Log.d("DeviceUtil", "isTelevision: ro.build.characteristics contains 'tv'")
                return true
            }
        } catch (e: Exception) {
            Log.e("DeviceUtil", "Failed to read ro.build.characteristics using reflection: ${e.message}")
        }

        // 2. 检查 UI 模式
        if ((context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION) {
            Log.d("DeviceUtil", "isTelevision: UI_MODE_TYPE_TELEVISION detected")
            return true
        }

        // 3. 检查 PackageManager.FEATURE_TELEVISION 功能
        if (pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION)) {
            Log.d("DeviceUtil", "isTelevision: FEATURE_TELEVISION detected")
            return true
        }

        // 4. (辅助判断) 检查是否存在触摸屏，如果不是触摸屏设备，通常是电视
        if (!pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) &&
            (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) || pm.hasSystemFeature(PackageManager.FEATURE_TELEVISION))) {
            Log.d("DeviceUtil", "isTelevision: No touchscreen and LEANBACK/TELEVISION features detected")
            return true
        }

        Log.d("DeviceUtil", "isTelevision: Not a television device")
        return false
    }
}
