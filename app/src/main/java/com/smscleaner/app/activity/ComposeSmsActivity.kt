package com.smscleaner.app.activity

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.smscleaner.app.R
import com.google.android.material.button.MaterialButton

class ComposeSmsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.apply {
            statusBarColor = android.graphics.Color.BLACK
            navigationBarColor = android.graphics.Color.BLACK
        }
        setContentView(R.layout.activity_compose)

        findViewById<MaterialButton>(R.id.btnComposeBack).setOnClickListener {
            finish()
        }
    }
}
