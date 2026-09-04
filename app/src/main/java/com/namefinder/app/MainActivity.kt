package com.namefinder.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var etApiKey: EditText

    // نتيجة طلب إذن تسجيل الشاشة تُستخدم مرة عند كل ضغطة على الزر العائم
    private val projectionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result: androidx.activity.result.ActivityResult ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            // مررنا نتيجة الإذن للخدمة العائمة
            val intent = Intent(this, FloatingButtonService::class.java).apply {
                action = FloatingButtonService.ACTION_START
                putExtra("resultCode", result.resultCode)
                putExtra("data", result.data)
            }
            startForegroundService(intent)
            finish()
        } else {
            Toast.makeText(this, "لازم توافق على مشاركة الشاشة", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        etApiKey = findViewById(R.id.etApiKey)
        val prefs = getSharedPreferences("namefinder", MODE_PRIVATE)
        etApiKey.setText(prefs.getString("api_key", ""))

        findViewById<Button>(R.id.btnSaveKey).setOnClickListener {
            prefs.edit().putString("api_key", etApiKey.text.toString().trim()).apply()
            Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnGrantOverlay).setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "الإذن موجود بالفعل ✓", Toast.LENGTH_SHORT).show()
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }

        findViewById<Button>(R.id.btnStartService).setOnClickListener {
            val prefsKey = getSharedPreferences("namefinder", MODE_PRIVATE).getString("api_key", "")
            if (prefsKey.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.error_no_key), Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "أول لازم تسمح بالظهور فوق التطبيقات", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            // نطلب إذن تسجيل الشاشة (يُسأل مرة واحدة عند بدء الخدمة)
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projectionLauncher.launch(mpm.createScreenCaptureIntent())
        }
    }
}
