package com.bearinmind.equalizer314

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class CustomPresetsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_custom_presets)

        findViewById<android.widget.ImageButton>(R.id.presetsBackButton).setOnClickListener {
            finish()
            overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
        }
    }
}
