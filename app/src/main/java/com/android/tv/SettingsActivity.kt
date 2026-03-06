package com.android.tv

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val editSourceUrl = findViewById<EditText>(R.id.edit_source_url)
        val btnSave = findViewById<Button>(R.id.btn_save)

        // 读取当前保存的 URL
        val prefs = getSharedPreferences("tv_prefs", Context.MODE_PRIVATE)
        val currentUrl = prefs.getString("tv_source_url", "https://raw.githubusercontent.com/HaidyCao/configs/refs/heads/main/tvlist.txt")
        editSourceUrl.setText(currentUrl)

        btnSave.setOnClickListener {
            val newUrl = editSourceUrl.text.toString().trim()
            if (newUrl.isNotEmpty()) {
                prefs.edit().putString("tv_source_url", newUrl).apply()
                Toast.makeText(this, "保存成功！重启后生效", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
