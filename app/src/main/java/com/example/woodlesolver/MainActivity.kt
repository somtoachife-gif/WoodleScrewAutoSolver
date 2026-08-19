package com.example.woodlesolver

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var projectionManager: MediaProjectionManager

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val serviceIntent = Intent(this, ProjectionService::class.java).apply {
                    action = ProjectionService.ACTION_START
                    putExtra(ProjectionService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ProjectionService.EXTRA_RESULT_DATA, result.data)
                }
                ContextCompat.startForegroundService(this, serviceIntent)
                status.text = "Solver started. Switch to Woodle Screw."
            } else {
                status.text = "Screen capture permission was not granted."
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        projectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
        }

        val title = TextView(this).apply {
            text = "Woodle Screw Auto Solver"
            textSize = 24f
        }

        status = TextView(this).apply {
            text = "1) Enable tap service\n2) Start solver\n3) Open Woodle Screw"
            textSize = 16f
            setPadding(0, 24, 0, 24)
        }

        val accessibilityButton = Button(this).apply {
            text = "1. Enable Tap Service"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val startButton = Button(this).apply {
            text = "2. Start Solver"
            setOnClickListener {
                projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop Solver"
            setOnClickListener {
                startService(Intent(this@MainActivity, ProjectionService::class.java).apply {
                    action = ProjectionService.ACTION_STOP
                })
                status.text = "Solver stopped."
            }
        }

        layout.addView(title)
        layout.addView(status)
        layout.addView(accessibilityButton)
        layout.addView(startButton)
        layout.addView(stopButton)
        setContentView(layout)
    }
}
