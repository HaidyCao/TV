package com.android.tv

import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import com.android.tv.util.isTelevision

class SplashActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        window.decorView.postDelayed({
            val intent = if (isTelevision(this.applicationContext)) {
                Intent(this, MainActivity::class.java)
            } else {
                Intent(this, PhoneMainActivity::class.java)
            }
            startActivity(intent)
            finish()
        }, 1000) // 1000ms delay
    }
}
