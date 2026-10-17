
package com.nvcs3000.scanner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(24, 32, 24, 32)
            setBackgroundColor(Color.rgb(8, 10, 14))
        }

        val title = TextView(this).apply {
            text = "N V C S   3 0 0 0"
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "NEURAL VISUAL CLASSIFICATION SYSTEM"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(150, 220, 255))
            setPadding(0, 12, 0, 28)
        }
        root.addView(subtitle)

        status = TextView(this).apply {
            text = "SYSTEM INITIALIZING..."
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 24, 0, 24)
        }
        root.addView(status)

        val cameraButton = Button(this).apply {
            text = "CHECK CAMERA"
            setOnClickListener { checkCamera() }
        }
        root.addView(cameraButton)

        setContentView(root)
        checkCamera()
    }

    private fun checkCamera() {
        if (checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            status.text =
                "SYSTEM READY\nCamera permission granted.\nDetection module not connected yet."
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                3000
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults
        )

        if (requestCode == 3000) {
            status.text =
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    "SYSTEM READY\nCamera permission granted.\nDetection module not connected yet."
                } else {
                    "CAMERA ACCESS DENIED\nAllow camera access in Android settings."
                }
        }
    }
}
