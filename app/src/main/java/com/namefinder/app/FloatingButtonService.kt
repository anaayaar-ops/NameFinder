package com.namefinder.app

import android.app.*
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.*

/**
 * خدمة تعرض زر عائم فوق كل الشاشات.
 * عند الضغط عليه: تلتقط سكرين شوت للشاشة الحالية، ترسله لخدمة تعرّف الصور،
 * وتعرض النتيجة في فقاعة صغيرة يمكن نسخها بالضغط عليها.
 */
class FloatingButtonService : Service() {

    companion object {
        const val ACTION_START = "START"
        const val CHANNEL_ID = "namefinder_channel"
    }

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var resultView: View? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START) {
            startForeground(1, buildNotification())

            val resultCode = intent.getIntExtra("resultCode", Activity.RESULT_OK)
            val data = intent.getParcelableExtra<Intent>("data")

            if (data != null) {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = mpm.getMediaProjection(resultCode, data)
            }

            showFloatingButton()
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompatBuilder(this)
    }

    private fun NotificationCompatBuilder(ctx: Context): Notification {
        val builder = Notification.Builder(ctx, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_search_circle)
            .setOngoing(true)
        return builder.build()
    }

    // ---------------- الزر العائم ----------------

    private fun showFloatingButton() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val inflater = LayoutInflater.from(this)
        floatingView = inflater.inflate(R.layout.floating_button, null)

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 300

        windowManager.addView(floatingView, params)

        val icon = floatingView!!.findViewById<ImageView>(R.id.floatingIcon)

        // نجعل الزر قابل للسحب، وإذا كانت الحركة بسيطة جدًا نعتبرها ضغطة
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        icon.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        isDragging = true
                    }
                    params.x = initialX + dx
                    params.y = initialY + dy
                    windowManager.updateViewLayout(floatingView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onFloatingButtonClicked()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ---------------- عند الضغط: التقاط الشاشة وتحليلها ----------------

    private fun onFloatingButtonClicked() {
        if (mediaProjection == null) {
            Toast.makeText(this, "لازم تعيد فتح التطبيق وتوافق على مشاركة الشاشة", Toast.LENGTH_LONG).show()
            return
        }
        showResultBubble(loading = true)
        captureScreen { bitmap ->
            if (bitmap == null) {
                showResultBubble(loading = false, text = getString(R.string.no_result))
                return@captureScreen
            }
            scope.launch {
                val apiKey = getSharedPreferences("namefinder", MODE_PRIVATE).getString("api_key", "") ?: ""
                val name = withContext(Dispatchers.IO) {
                    VisionApiClient.detectName(bitmap, apiKey)
                }
                showResultBubble(loading = false, text = name ?: getString(R.string.no_result))
            }
        }
    }

    private fun captureScreen(callback: (Bitmap?) -> Unit) {
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "NameFinderCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, null
        )

        // نؤخر لحظة بسيطة عشان الفريم يجهز، ثم نلتقط صورة واحدة وننظف الموارد
        Handler(Looper.getMainLooper()).postDelayed({
            val image: Image? = try {
                imageReader?.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            var bitmap: Bitmap? = null
            if (image != null) {
                val planes = image.planes
                val buffer = planes[0].buffer
                val pixelStride = planes[0].pixelStride
                val rowStride = planes[0].rowStride
                val rowPadding = rowStride - pixelStride * width

                bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                image.close()
            }
            virtualDisplay?.release()
            imageReader?.close()
            callback(bitmap)
        }, 300)
    }

    // ---------------- فقاعة عرض النتيجة ----------------

    private fun showResultBubble(loading: Boolean, text: String = "") {
        if (resultView == null) {
            val inflater = LayoutInflater.from(this)
            resultView = inflater.inflate(R.layout.floating_result, null)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 100
            params.y = 400
            windowManager.addView(resultView, params)
        }

        val progressBar = resultView!!.findViewById<ProgressBar>(R.id.progressBar)
        val tvResult = resultView!!.findViewById<TextView>(R.id.tvResult)

        if (loading) {
            progressBar.visibility = View.VISIBLE
            tvResult.text = getString(R.string.analyzing)
        } else {
            progressBar.visibility = View.GONE
            tvResult.text = text
            resultView!!.setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("name", text))
                Toast.makeText(this, getString(R.string.copied), Toast.LENGTH_SHORT).show()
                windowManager.removeView(resultView)
                resultView = null
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        floatingView?.let { windowManager.removeView(it) }
        resultView?.let { windowManager.removeView(it) }
        mediaProjection?.stop()
        scope.cancel()
    }
}
