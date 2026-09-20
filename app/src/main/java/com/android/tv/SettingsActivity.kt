package com.android.tv

import android.os.Bundle
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

        editSourceUrl.setText(TvDataManager.getSourceUrl(this))

        btnSave.setOnClickListener {
            val newUrl = editSourceUrl.text.toString().trim()
            if (TvDataManager.isValidSourceUrl(newUrl)) {
                TvDataManager.saveSourceUrl(this, newUrl)
                ChannelRepository.invalidate()
                ChannelRepository.refresh(applicationContext, force = true)
                Toast.makeText(this, getString(R.string.source_saved_refreshing), Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, getString(R.string.source_url_invalid), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
