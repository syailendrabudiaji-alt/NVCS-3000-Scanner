package com.nvcs3000.scanner

import android.Manifest
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * NVCS 3000 - camera scanner.
 *
 * REAL features (computed from camera frames):
 *  - Camera preview (CameraX), camera zoom (CameraX zoom ratio)
 *  - Human / Object / Threat detectors: real TFLite EfficientDet-Lite0 inference, ONLY if the model file exists.
 *    "Threat" is just a label heuristic (knife / scissors / baseball bat). It is NOT real threat assessment.
 *  - Edge Detection: real Sobel filter on a downscaled frame.
 *  - Motion Tracker: real, but basic frame differencing on a downscaled frame.
 *  - Event Recorder / Record Event: real text log in app-private storage.
 *
 * SIMULATED visual effects (not real sensors):
 *  - Nightvision: green colour filter + scanlines. No real low-light enhancement.
 *  - Thermal Vision: false-colour filter. It does NOT measure heat.
 *  - Radar Scanner: animated sweep that plots detector/motion positions on a fake radar. No real ranging.
 */
class MainActivity : ComponentActivity() {

    // Exactly 44 modules. Modules marked simulated provide their named UI toggle/visual placeholder;
    // they do not claim to be real sensors unless implemented in the detector pipeline.
    private enum class Module(val label: String, val simulated: Boolean) {
        NIGHT("Nightvision", true),
        HUMAN("Human Detector", false),
        THREAT("Threat Detector", true),
        THERMAL("Thermal Vision", true),
        MOTION("Motion Tracker", false),
        ZOOM("Digital Zoom", false),
        RADAR("Radar Scanner", true),
        EDGE("Edge Detection", false),
        RECORDER("Event Recorder", false),
        TARGET_LOCK("Target Lock", true),
        RANGE_FINDER("Range Finder", true),
        SIGNAL_SCANNER("Signal Scanner", true),
        SNAPSHOT("Snapshot / Freeze Frame", true),
        IMAGE_STABILIZER("Image Stabilizer", true),
        SYSTEM_DIAGNOSTICS("System Diagnostics", true),
        ECHO_SCAN("Echo Scan", true),
        GHOST_TRAIL("Ghost Trail", true),
        POWER_SAVER("Power Saver", true),
        LIDAR_SCAN("LIDAR Scan", true),
        OBJECT("Object Classification", false),
        MOTION_VECTOR("Motion Vector", true),
        COORDINATE_GRID("Coordinate Grid", true),
        LIGHT_ANALYZER("Light Analyzer", true),
        PROXIMITY_ALERT("Proximity Alert", true),
        INTERFERENCE_DETECTOR("Interference Detector", true),
        SESSION_TIMELINE("Session Timeline", true),
        SPLIT_VIEW("Split View", true),
        DETAIL_ENHANCER("Detail Enhancer", true),
        OVERLOAD_PROTECTION("Overload Protection", true),
        TRACK_HISTORY("Track History", true),
        MINIMAL_HUD("Minimal HUD", true),
        LIDAR_2("LIDAR 2.0 Ã¢â‚¬â€ Point Cloud", true),
        SURFACE_RECONSTRUCTION("Surface Reconstruction", true),
        MULTI_PASS_SCAN("Multi-Pass Scan", true),
        PIXEL_INSPECTOR("Pixel Inspector", true),
        PIXEL_COUNTER("Pixel Counter", true),
        OBJECT_RULER("Object Ruler", true),
        RESOLUTION_DESTROYER("Resolution Destroyer", true),
        BINARY_VISION("Binary Vision", true),
        BRIGHTNESS_SEISMOGRAPH("Brightness Seismograph", true),
        QUANTUM_SCANNER("Quantum Scanner", true),
        RANSOM("Ransom Module", true),
        ERROR("Error Module", true),
        SPACE("Space Module", true)
    }

    private enum class ModelStatus { LOADING, READY, MISSING, FAILED }

    // ---------- UI ----------
    private lateinit var previewView: PreviewView
    private lateinit var overlay: ScanOverlay
    private lateinit var hudText: TextView
    private lateinit var offView: TextView
    private lateinit var staticView: ImageView
    private lateinit var sidePanel: LinearLayout
    private lateinit var arrowTab: TextView
    private lateinit var powerButton: Button
    private lateinit var zoomText: TextView
    private val moduleButtons = LinkedHashMap<Module, Button>()
    private val powerGated = ArrayList<View>()
    private var sidebarOpen = false
    private var statusMessage: String? = null
    private var aboutDialog: AlertDialog? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setSidebar(false)
        }
    }

    // ---------- State ----------
    private val mainHandler = Handler(Looper.getMainLooper())
    private val moduleOn = ConcurrentHashMap<Module, Boolean>()

    @Volatile private var powerOn = false
    @Volatile private var destroyed = false
    @Volatile private var modelStatus = ModelStatus.LOADING
    private var zoomRatio = 1f
    private var latest: AnalysisResult? = null
    private var lastAutoLogMs = 0L

    // ---------- Camera / analysis ----------
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // Only touched on analysisExecutor thread:
    private var detector: ObjectDetector? = null
    private var prevGray: IntArray? = null
    private var lastAnalysisMs = 0L

    // ---------- Audio / static image (main thread only) ----------
    private var ambience: MediaPlayer? = null
    private var staticAmbience: MediaPlayer? = null
    private var ambienceFailed = false
    private var lowAmbienceButton: Button? = null
    private var staticAnimator: ValueAnimator? = null

    private val lowAmbienceController by lazy {
        LowAmbienceController(
            getPlayers = { listOf(ambience, staticAmbience) },
            onModeChanged = { enabled ->
                lowAmbienceButton?.text =
                    if (enabled) "LOW AMBIENCE: ON"
                    else "LOW AMBIENCE: OFF"
    
                showMessage(
                    if (enabled) "LOW AMBIENCE ENABLED"
                    else "LOW AMBIENCE DISABLED"
                )
            }
        )
    }
    private val oneShots = HashSet<MediaPlayer>()
    private var staticBitmap: Bitmap? = null
    private var staticLoadTried = false

    private val clearMessage = Runnable {
        statusMessage = null
        refreshHud()
    }

    // While powered on, vary static opacity every 120 ms. Alpha is the inverse
    // of transparency: 60â€“90% transparent means 10â€“40% visible.
    private val staticFlicker = object : Runnable {
        override fun run() {
            if (!::staticView.isInitialized || destroyed) return
            if (powerOn && staticView.visibility == View.VISIBLE) {
                staticView.alpha = 0.1f + Random.nextFloat() * 0.3f
                mainHandler.postDelayed(this, 120L)
            }
        }
    }

    private val hideStatic = Runnable {
        staticAnimator = null
        if (::staticView.isInitialized) {
            if (!powerOn) {
                staticView.alpha = 1f
                staticView.visibility = View.GONE
                mainHandler.removeCallbacks(staticFlicker)
            } else {
                staticView.alpha = 0.1f + Random.nextFloat() * 0.3f
                mainHandler.removeCallbacks(staticFlicker)
                mainHandler.postDelayed(staticFlicker, 120L)
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                powerOnNow()
            } else {
                offView.text = "CAMERA PERMISSION DENIED\nGRANT IT IN SETTINGS, THEN PRESS POWER"
                showMessage("CAMERA PERMISSION DENIED")
            }
        }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        for (m in Module.values()) moduleOn[m] = false

        setContentView(buildUi())

        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } catch (e: Throwable) {
            Log.w(TAG, "Immersive mode failed", e)
        }

        onBackPressedDispatcher.addCallback(this, backCallback)

        initDetectorAsync()
        refreshControls()

        if (hasCameraPermission()) {
            powerOnNow()
        } else {
            offView.text = "CAMERA PERMISSION REQUIRED"
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (powerOn) startAmbience()
    }

    override fun onPause() {
        pauseAmbienceAtStart()
        super.onPause()
    }

    override fun onDestroy() {
        destroyed = true
        powerOn = false
        mainHandler.removeCallbacksAndMessages(null)
        staticAnimator?.cancel()
        staticAnimator = null
        mainHandler.removeCallbacks(staticFlicker)

        try {
            imageAnalysis?.clearAnalyzer()
        } catch (e: Throwable) {
            Log.w(TAG, "clearAnalyzer failed", e)
        }
        try {
            cameraProvider?.unbindAll()
        } catch (e: Throwable) {
            Log.w(TAG, "unbindAll failed", e)
        }
        imageAnalysis = null
        camera = null

        // Detector is only used on the analysis thread, so close it there, then stop the thread.
        try {
            analysisExecutor.execute { closeDetector() }
            analysisExecutor.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Analysis executor shutdown failed", e)
        }
        try {
            ioExecutor.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "IO executor shutdown failed", e)
        }

        for (p in oneShots.toList()) safeRelease(p)
        oneShots.clear()
        safeRelease(ambience)
        ambience = null
        safeRelease(staticAmbience)
        staticAmbience = null

        try {
            aboutDialog?.dismiss()
        } catch (e: Throwable) {
            Log.w(TAG, "Dialog dismiss failed", e)
        }
        aboutDialog = null

        if (::staticView.isInitialized) staticView.setImageDrawable(null)
        staticBitmap = null
        latest = null

        super.onDestroy()
    }

    // =====================================================================
    // Power
    // =====================================================================

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun togglePower() {
        if (powerOn) {
            powerOffNow()
        } else if (hasCameraPermission()) {
            powerOnNow()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun powerOnNow() {
        if (destroyed) return
        powerOn = true
        offView.visibility = View.GONE
        playOnce(AUDIO_EQUIP)
        startAmbience()
        bindCamera()
        flashStatic()
        refreshControls()
    }

    private fun powerOffNow() {
        powerOn = false
        stopAmbienceAtStart()
        playOnce(AUDIO_UNEQUIP)
        try {
            imageAnalysis?.clearAnalyzer()
        } catch (e: Throwable) {
            Log.w(TAG, "clearAnalyzer failed", e)
        }
        try {
            cameraProvider?.unbindAll()
        } catch (e: Throwable) {
            Log.w(TAG, "unbindAll failed", e)
        }
        imageAnalysis = null
        camera = null
        zoomRatio = 1f
        for (m in Module.values()) moduleOn[m] = false
        applyVisualFilter()
        latest = null
        overlay.setResult(null)
        offView.text = "POWER OFF"
        offView.visibility = View.VISIBLE
        flashStatic()
        refreshControls()
    }

    // =====================================================================
    // Camera
    // =====================================================================

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                if (destroyed || !powerOn) return@addListener
                val provider = future.get()
                cameraProvider = provider

                val selector = when {
                    provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) ->
                        CameraSelector.DEFAULT_BACK_CAMERA
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) ->
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> {
                        showMessage("NO CAMERA FOUND")
                        return@addListener
                    }
                }

                val rotation = previewView.display?.rotation ?: Surface.ROTATION_90

                val previewSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
                val preview = Preview.Builder()
                    .setResolutionSelector(previewSelector)
                    .setTargetRotation(rotation)
                    .build()
                preview.setSurfaceProvider(previewView.surfaceProvider)

                val analysisSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(640, 480),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisSelector)
                    .setTargetRotation(rotation)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyzeFrame(proxy) }

                provider.unbindAll()
                try {
                    camera = provider.bindToLifecycle(this, selector, preview, analysis)
                    imageAnalysis = analysis
                } catch (e: Exception) {
                    // Analysis not supported/failed: keep the preview alive on its own.
                    Log.e(TAG, "Bind with analysis failed, falling back to preview only", e)
                    try {
                        analysis.clearAnalyzer()
                    } catch (ignored: Throwable) {
                    }
                    imageAnalysis = null
                    provider.unbindAll()
                    camera = provider.bindToLifecycle(this, selector, preview)
                    showMessage("ANALYSIS UNAVAILABLE - PREVIEW ONLY")
                }

                zoomRatio = 1f
                camera?.cameraControl?.setZoomRatio(1f)
                refreshControls()
            } catch (t: Throwable) {
                Log.e(TAG, "Camera start failed", t)
                showMessage("CAMERA ERROR")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun changeZoom(factor: Float) {
        if (!powerOn) return
        val cam = camera ?: return
        if (!isOn(Module.ZOOM)) {
            moduleOn[Module.ZOOM] = true
        }
        try {
            val state = cam.cameraInfo.zoomState.value
            val minZ = state?.minZoomRatio ?: 1f
            val maxZ = minOf(state?.maxZoomRatio ?: 1f, MAX_ZOOM).coerceAtLeast(minZ)
            zoomRatio = (zoomRatio * factor).coerceIn(minZ, maxZ)
            cam.cameraControl.setZoomRatio(zoomRatio)
        } catch (e: Throwable) {
            Log.w(TAG, "Zoom failed", e)
        }
        refreshControls()
    }

    private fun resetZoom() {
        zoomRatio = 1f
        try {
            camera?.cameraControl?.setZoomRatio(1f)
        } catch (e: Throwable) {
            Log.w(TAG, "Zoom reset failed", e)
        }
    }

    // =====================================================================
    // Frame analysis (runs on analysisExecutor)
    // =====================================================================

    private fun analyzeFrame(proxy: ImageProxy) {
        try {
            if (destroyed || !powerOn) return

            val wantDetect = isOn(Module.HUMAN) || isOn(Module.OBJECT) || isOn(Module.THREAT)
            val wantEdge = isOn(Module.EDGE)
            val wantMotion = isOn(Module.MOTION)
            if (!wantMotion) prevGray = null
            if (!wantDetect && !wantEdge && !wantMotion) return

            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysisMs < ANALYSIS_INTERVAL_MS) return
            lastAnalysisMs = now

            val raw = proxy.toBitmap()
            val small = rotateAndScale(raw, proxy.imageInfo.rotationDegrees)
            val w = small.width
            val h = small.height

            var detections: List<Det> = emptyList()
            val det = detector
            if (wantDetect && det != null) {
                val found = det.detect(TensorImage.fromBitmap(small))
                detections = found.mapNotNull { d ->
                    val c = d.categories.firstOrNull() ?: return@mapNotNull null
                    val b = d.boundingBox
                    Det(
                        c.label ?: "?",
                        c.score,
                        RectF(b.left / w, b.top / h, b.right / w, b.bottom / h)
                    )
                }
            }

            var edges: Bitmap? = null
            var motion: RectF? = null
            if (wantEdge || wantMotion) {
                val gray = toGray(small, w, h)
                if (wantEdge) edges = sobel(gray, w, h)
                if (wantMotion) {
                    motion = diffMotion(prevGray, gray, w, h)
                    prevGray = gray
                }
            }
            small.recycle()

            val result = AnalysisResult(w, h, detections, edges, motion)
            mainHandler.post {
                if (!destroyed && powerOn) handleResult(result)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Frame analysis failed", t)
        } finally {
            proxy.close()
        }
    }

    private fun rotateAndScale(src: Bitmap, rotation: Int): Bitmap {
        val rotatedWidth = if (rotation % 180 == 0) src.width else src.height
        val scale = TARGET_WIDTH.toFloat() / rotatedWidth.toFloat()
        val m = Matrix()
        m.postRotate(rotation.toFloat())
        m.postScale(scale, scale)
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    private fun toGray(bmp: Bitmap, w: Int, h: Int): IntArray {
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            px[i] = (r * 77 + g * 151 + b * 28) shr 8
        }
        return px
    }

    private fun sobel(g: IntArray, w: Int, h: Int): Bitmap {
        val out = IntArray(w * h)
        val edgeColor = Color.argb(255, 0, 255, 255)
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val gx = -g[i - w - 1] + g[i - w + 1] - 2 * g[i - 1] + 2 * g[i + 1] -
                    g[i + w - 1] + g[i + w + 1]
                val gy = -g[i - w - 1] - 2 * g[i - w] - g[i - w + 1] +
                    g[i + w - 1] + 2 * g[i + w] + g[i + w + 1]
                if (abs(gx) + abs(gy) > EDGE_THRESHOLD) out[i] = edgeColor
            }
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun diffMotion(prev: IntArray?, cur: IntArray, w: Int, h: Int): RectF? {
        if (prev == null || prev.size != cur.size) return null
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        var count = 0
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if (abs(cur[row + x] - prev[row + x]) > MOTION_DIFF) {
                    count++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (count < (w * h) / 100 || maxX < 0) return null
        return RectF(
            minX.toFloat() / w,
            minY.toFloat() / h,
            (maxX + 1).toFloat() / w,
            (maxY + 1).toFloat() / h
        )
    }

    // =====================================================================
    // TensorFlow Lite model
    // =====================================================================

    private fun initDetectorAsync() {
        try {
            analysisExecutor.execute {
                try {
                    val exists = try {
                        assets.list("")?.contains(MODEL_NAME) == true
                    } catch (e: IOException) {
                        false
                    }
                    if (!exists) {
                        modelStatus = ModelStatus.MISSING
                    } else if (!destroyed) {
                        val options = ObjectDetector.ObjectDetectorOptions.builder()
                            .setBaseOptions(BaseOptions.builder().setNumThreads(2).build())
                            .setMaxResults(6)
                            .setScoreThreshold(0.45f)
                            .build()
                        detector = ObjectDetector.createFromFileAndOptions(
                            this@MainActivity, MODEL_NAME, options
                        )
                        modelStatus = ModelStatus.READY
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Model init failed", t)
                    detector = null
                    modelStatus = ModelStatus.FAILED
                }
                mainHandler.post { if (!destroyed) refreshHud() }
            }
        } catch (e: Exception) {
            modelStatus = ModelStatus.FAILED
        }
    }

    private fun closeDetector() {
        try {
            detector?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Detector close failed", e)
        }
        detector = null
    }

    // =====================================================================
    // Results / modules
    // =====================================================================

    private fun isOn(m: Module): Boolean = moduleOn[m] == true

    private fun isVisible(d: Det): Boolean =
        isOn(Module.OBJECT) ||
            (isOn(Module.HUMAN) && d.isPerson) ||
            (isOn(Module.THREAT) && d.isThreat)

    private fun handleResult(r: AnalysisResult) {
        latest = r
        overlay.setResult(r)

        if (isOn(Module.RECORDER)) {
            val hasEvent = r.motion != null || r.detections.any { isVisible(it) }
            val now = SystemClock.elapsedRealtime()
            if (hasEvent && now - lastAutoLogMs > AUTO_LOG_INTERVAL_MS) {
                lastAutoLogMs = now
                recordEvent("AUTO")
            }
        }
        refreshHud()
    }

    private fun toggleModule(m: Module) {
        if (!powerOn) return
        val newState = !isOn(m)
        moduleOn[m] = newState
        if (newState) {
            when (m) {
                Module.NIGHT -> moduleOn[Module.THERMAL] = false
                Module.THERMAL -> moduleOn[Module.NIGHT] = false
                else -> Unit
            }
        }
        if (m == Module.ZOOM && !newState) resetZoom()
        if (!newState) {
            // Drop stale overlay results for the module just switched off.
            overlay.setResult(latest)
        }
        applyVisualFilter()
        flashStatic()
        refreshControls()
        overlay.invalidate()
    }

    private fun applyVisualFilter() {
        try {
            when {
                isOn(Module.NIGHT) -> setPreviewMatrix(NIGHT_MATRIX)
                isOn(Module.THERMAL) -> setPreviewMatrix(THERMAL_MATRIX)
                else -> previewView.setLayerType(View.LAYER_TYPE_NONE, null)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Visual filter failed", e)
        }
    }

    private fun setPreviewMatrix(values: FloatArray) {
        val paint = Paint()
        paint.colorFilter = ColorMatrixColorFilter(ColorMatrix(values))
        previewView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
    }

    // =====================================================================
    // Event log (app-private file)
    // =====================================================================

    private fun recordEvent(source: String) {
        if (!powerOn || destroyed) {
            showMessage("POWER ON FIRST")
            return
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val modules = Module.values().filter { isOn(it) }.joinToString(",") { it.name }
        val r = latest
        val dets = r?.detections?.filter { isVisible(it) }
            ?.joinToString(", ") { "${it.label} ${(it.score * 100).toInt()}%" } ?: ""
        val motion = if (r?.motion != null) "yes" else "no"
        val line = "$stamp | $source | modules=[$modules] | zoom=x" +
            String.format(Locale.US, "%.1f", zoomRatio) +
            " | detections=[$dets] | motion=$motion\n"
        try {
            ioExecutor.execute {
                try {
                    File(filesDir, EVENT_FILE).appendText(line)
                    mainHandler.post { if (!destroyed) showMessage("EVENT SAVED ($source)") }
                } catch (e: Throwable) {
                    Log.e(TAG, "Event write failed", e)
                    mainHandler.post { if (!destroyed) showMessage("EVENT SAVE FAILED") }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Event executor rejected", e)
        }
    }

    // =====================================================================
    // Audio / static image (all optional)
    // =====================================================================

    private fun createPlayer(path: String, loop: Boolean, volume: Float): MediaPlayer? {
        var created: MediaPlayer? = null
        return try {
            assets.openFd(path).use { afd ->
                val p = MediaPlayer()
                created = p
                p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                p.isLooping = loop
                p.setVolume(volume, volume)
                p.prepare()
                p
            }
        } catch (e: Throwable) {
            safeRelease(created)
            null
        }
    }

    private fun safeRelease(p: MediaPlayer?) {
        try {
            p?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "MediaPlayer release failed", e)
        }
    }

    private fun playOnce(path: String, volume: Float = 0.8f) {
        if (destroyed || oneShots.size >= 4) return
        val p = createPlayer(path, false, volume) ?: return
        oneShots.add(p)
        p.setOnCompletionListener { mp ->
            oneShots.remove(mp)
            safeRelease(mp)
        }
        p.setOnErrorListener { mp, _, _ ->
            oneShots.remove(mp)
            safeRelease(mp)
            true
        }
        try {
            p.start()
        } catch (e: Throwable) {
            oneShots.remove(p)
            safeRelease(p)
        }
    }

    private fun startAmbience() {
        if (destroyed || ambienceFailed || !powerOn) return
        try {
            if (ambience == null) ambience = createPlayer(AUDIO_AMBIENCE, true, 0.4f)
            if (staticAmbience == null) staticAmbience = createPlayer(AUDIO_STATIC, true, 0.4f)
            if (ambience == null && staticAmbience == null) {
                ambienceFailed = true
                return
            }
            lowAmbienceController.syncPlayers()
            listOf(ambience, staticAmbience).filterNotNull().forEach { player ->
                if (!player.isPlaying) player.start()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Ambience start failed", e)
            pauseAmbienceAtStart()
        }
    }

    private fun pauseAmbienceAtStart() {
        listOf(ambience, staticAmbience).filterNotNull().forEach { player ->
            try {
                if (player.isPlaying) player.pause()
                player.seekTo(0)
            } catch (e: Throwable) {
                Log.w(TAG, "Ambience pause failed", e)
            }
        }
    }

    private fun stopAmbienceAtStart() {
        // Stop both ambience loops audibly and rewind them for the next power-on.
        pauseAmbienceAtStart()
    }

    private fun flashStatic() {
        if (!staticLoadTried) {
            staticLoadTried = true
            staticBitmap = try {
                val opts = BitmapFactory.Options()
                opts.inSampleSize = 2
                assets.open(IMAGE_STATIC).use { BitmapFactory.decodeStream(it, null, opts) }
            } catch (e: Throwable) {
                Log.w(TAG, "Static image could not be loaded from assets/$IMAGE_STATIC", e)
                null
            }
            staticBitmap?.let {
                staticView.setImageBitmap(it)
                // Tint monochrome/static artwork green for the NVCS scanner look.
                staticView.setColorFilter(Color.rgb(0, 255, 70), PorterDuff.Mode.MULTIPLY)
            }
        }

        if (staticBitmap == null || !::staticView.isInitialized) return

        mainHandler.removeCallbacks(hideStatic)
        mainHandler.removeCallbacks(staticFlicker)
        staticAnimator?.cancel()
        staticAnimator = null

        // Put static above the powered-off black screen during the transition, while
        // restoring the HUD and sidebar above it so controls remain usable.
        staticView.bringToFront()
        if (::hudText.isInitialized) hudText.bringToFront()
        if (::sidePanel.isInitialized) sidePanel.bringToFront()

        staticView.visibility = View.VISIBLE
        staticView.alpha = 1f

        // Begin fully visible, then fade to a random level. 60â€“90% transparency
        // means Android alpha/visibility of 0.10â€“0.40. Power-off fades to zero.
        val targetAlpha = if (powerOn) {
            0.1f + (Random.nextFloat() * 0.3f)
        } else {
            0f
        }

        staticAnimator = ValueAnimator.ofFloat(1f, targetAlpha).apply {
            duration = 450L
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                if (::staticView.isInitialized) {
                    staticView.alpha = animation.animatedValue as Float
                }
            }
            start()
        }

        mainHandler.postDelayed(hideStatic, 460L)
    }

    // =====================================================================
    // HUD / controls
    // =====================================================================

    private fun showMessage(msg: String) {
        statusMessage = msg
        refreshHud()
        mainHandler.removeCallbacks(clearMessage)
        mainHandler.postDelayed(clearMessage, 2500L)
    }

    private fun refreshControls() {
        if (!::powerButton.isInitialized) return
        powerButton.text = if (powerOn) "POWER: ON" else "POWER: OFF"
        powerButton.background = buttonBackground(powerOn)
        for ((m, b) in moduleButtons) {
            val on = isOn(m)
            val tag = if (m.simulated) " [SIM]" else ""
            b.text = m.label + tag + ": " + (if (on) "ON" else "OFF")
            b.background = buttonBackground(on)
        }
        for (v in powerGated) {
            v.isEnabled = powerOn
            v.alpha = if (powerOn) 1f else 0.4f
        }
        zoomText.text = "ZOOM x" + String.format(Locale.US, "%.1f", zoomRatio)
        refreshHud()
    }

    private fun refreshHud() {
        if (!::hudText.isInitialized) return
        val sb = StringBuilder()
        sb.append("NVCS 3000 | ").append(if (powerOn) "POWER ON" else "POWER OFF")
        sb.append("\nAI MODEL: ").append(
            when (modelStatus) {
                ModelStatus.LOADING -> "loading"
                ModelStatus.READY -> "ready"
                ModelStatus.MISSING -> "not found (detectors inactive)"
                ModelStatus.FAILED -> "failed to load (detectors inactive)"
            }
        )
        if (powerOn) {
            if (isOn(Module.RECORDER)) sb.append("\nREC: auto event log on")
            if (isOn(Module.ZOOM)) {
                sb.append("\nZOOM x").append(String.format(Locale.US, "%.1f", zoomRatio))
            }
            if (isOn(Module.NIGHT)) sb.append("\nNIGHTVISION: simulated filter")
            if (isOn(Module.THERMAL)) sb.append("\nTHERMAL: simulated false-colour")
            if (isOn(Module.RADAR)) sb.append("\nRADAR: simulated display")
            if (isOn(Module.EDGE)) sb.append("\nEDGE: Sobel filter")
            val r = latest
            if (r != null) {
                if (isOn(Module.HUMAN) && modelStatus == ModelStatus.READY) {
                    sb.append("\nHUMANS: ").append(r.detections.count { it.isPerson })
                }
                if (isOn(Module.THREAT) && modelStatus == ModelStatus.READY) {
                    sb.append("\nTHREAT CANDIDATES (label heuristic): ")
                        .append(r.detections.count { it.isThreat })
                }
                if (isOn(Module.OBJECT) && modelStatus == ModelStatus.READY) {
                    sb.append("\nOBJECTS: ").append(r.detections.size)
                }
                if (isOn(Module.MOTION)) {
                    sb.append("\nMOTION: ").append(if (r.motion != null) "detected" else "none")
                }
            }
        }
        statusMessage?.let { sb.append("\n").append(it) }
        hudText.text = sb.toString()
    }

    private fun showAbout() {
        try {
            aboutDialog?.dismiss()
            val text = "NVCS 3000 - Neural Visual Classification System\n\n" +
                "LIVE: camera preview, zoom, Human/Object detection (needs " + MODEL_NAME +
                "), Edge Detection (Sobel), Motion Tracker (frame difference), event log.\n\n" +
                "SIMULATED: Nightvision, Thermal Vision, Radar Scanner, and the Threat " +
                "Detector (label heuristic only). These are visual effects, not real sensors " +
                "or real threat assessment."
            aboutDialog = AlertDialog.Builder(this)
                .setTitle("About NVCS 3000")
                .setMessage(text)
                .setPositiveButton("Close", null)
                .show()
        } catch (e: Throwable) {
            Log.w(TAG, "About dialog failed", e)
        }
    }

    // =====================================================================
    // UI construction
    // =====================================================================

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun buttonBackground(active: Boolean): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(if (active) Color.argb(235, 20, 95, 35) else Color.argb(235, 28, 32, 28))
        d.setStroke(dp(1), Color.rgb(80, 200, 90))
        d.cornerRadius = dp(4).toFloat()
        return d
    }

    private fun makeButton(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.isAllCaps = false
        b.typeface = Typeface.MONOSPACE
        b.textSize = 12f
        b.setTextColor(Color.rgb(160, 255, 160))
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(dp(8), dp(4), dp(8), dp(4))
        b.background = buttonBackground(false)
        b.setOnClickListener { onClick() }
        return b
    }

    private fun rowParams(): LinearLayout.LayoutParams {
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38))
        lp.topMargin = dp(4)
        return lp
    }

    private fun buildUi(): View {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)

        previewView = PreviewView(this)
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER
        previewView.setBackgroundColor(Color.BLACK)
        root.addView(
            previewView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        overlay = ScanOverlay()
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        staticView = ImageView(this)
        staticView.scaleType = ImageView.ScaleType.CENTER_CROP
        staticView.alpha = 0.75f
        staticView.visibility = View.GONE
        root.addView(
            staticView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        offView = TextView(this)
        offView.setBackgroundColor(Color.BLACK)
        offView.setTextColor(Color.rgb(90, 160, 90))
        offView.typeface = Typeface.MONOSPACE
        offView.textSize = 18f
        offView.gravity = Gravity.CENTER
        offView.text = "POWER OFF"
        root.addView(
            offView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        hudText = TextView(this)
        hudText.setTextColor(Color.rgb(150, 255, 150))
        hudText.typeface = Typeface.MONOSPACE
        hudText.textSize = 11f
        hudText.setShadowLayer(3f, 1f, 1f, Color.BLACK)
        hudText.setPadding(dp(14), dp(8), dp(8), dp(8))
        root.addView(
            hudText,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            )
        )

        val panelW = dp(PANEL_WIDTH_DP)
        val tabW = dp(30)
        sidePanel = buildSidebar(panelW, tabW)
        root.addView(
            sidePanel,
            FrameLayout.LayoutParams(
                panelW + tabW, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END
            )
        )
        sidePanel.translationX = panelW.toFloat() // starts hidden; only the arrow tab is visible
        return root
    }

    private fun buildSidebar(panelW: Int, tabW: Int): LinearLayout {
        val column = LinearLayout(this)
        column.orientation = LinearLayout.VERTICAL
        column.setPadding(dp(8), dp(8), dp(8), dp(12))

        // Header: title + About
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = "NVCS 3000"
        title.setTextColor(Color.rgb(160, 255, 160))
        title.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        title.textSize = 16f
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(
            makeButton("About") { showAbout() },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34))
        )
        column.addView(header)

                // Power
        powerButton = makeButton("POWER: OFF") { togglePower() }
        column.addView(powerButton, rowParams())

        // Low ambience audio control
        lowAmbienceButton = makeButton(
            if (lowAmbienceController.enabled) "LOW AMBIENCE: ON"
            else "LOW AMBIENCE: OFF"
        ) {
            lowAmbienceController.toggle()
        }
        column.addView(lowAmbienceButton, rowParams())

        // Modules
        for (m in Module.values()) {
            val b = makeButton(m.label) { toggleModule(m) }
            moduleButtons[m] = b
            powerGated.add(b)
            column.addView(b, rowParams())
        }

        // Zoom controls
        val zoomRow = LinearLayout(this)
        zoomRow.orientation = LinearLayout.HORIZONTAL
        zoomRow.gravity = Gravity.CENTER_VERTICAL
        val zoomOut = makeButton("ZOOM -") { changeZoom(1f / ZOOM_STEP) }
        val zoomIn = makeButton("ZOOM +") { changeZoom(ZOOM_STEP) }
        powerGated.add(zoomOut)
        powerGated.add(zoomIn)
        zoomText = TextView(this)
        zoomText.setTextColor(Color.rgb(160, 255, 160))
        zoomText.typeface = Typeface.MONOSPACE
        zoomText.textSize = 11f
        zoomText.gravity = Gravity.CENTER
        zoomRow.addView(zoomOut, LinearLayout.LayoutParams(0, dp(38), 1f))
        zoomRow.addView(zoomText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        zoomRow.addView(zoomIn, LinearLayout.LayoutParams(0, dp(38), 1f))
        val zoomParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        zoomParams.topMargin = dp(4)
        column.addView(zoomRow, zoomParams)

        // Record Event
        val recordButton = makeButton("Record Event") { recordEvent("MANUAL") }
        powerGated.add(recordButton)
        column.addView(recordButton, rowParams())

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.argb(225, 8, 12, 8))
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // Arrow tab on the right edge
        val tabFrame = FrameLayout(this)
        arrowTab = TextView(this)
        arrowTab.text = "\u25C0"
        arrowTab.setTextColor(Color.rgb(160, 255, 160))
        arrowTab.textSize = 14f
        arrowTab.gravity = Gravity.CENTER
        val tabBg = GradientDrawable()
        tabBg.setColor(Color.argb(200, 8, 12, 8))
        tabBg.setStroke(dp(1), Color.rgb(80, 200, 90))
        tabBg.cornerRadii = floatArrayOf(
            dp(8).toFloat(), dp(8).toFloat(), 0f, 0f, 0f, 0f, dp(8).toFloat(), dp(8).toFloat()
        )
        arrowTab.background = tabBg
        arrowTab.setOnClickListener { setSidebar(!sidebarOpen) }
        tabFrame.addView(
            arrowTab,
            FrameLayout.LayoutParams(tabW, dp(64), Gravity.CENTER_VERTICAL)
        )

        val container = LinearLayout(this)
        container.orientation = LinearLayout.HORIZONTAL
        container.addView(tabFrame, LinearLayout.LayoutParams(tabW, ViewGroup.LayoutParams.MATCH_PARENT))
        container.addView(scroll, LinearLayout.LayoutParams(panelW, ViewGroup.LayoutParams.MATCH_PARENT))
        return container
    }

    private fun setSidebar(open: Boolean) {
        sidebarOpen = open
        backCallback.isEnabled = open
        arrowTab.text = if (open) "\u25B6" else "\u25C0"
        val target = if (open) 0f else dp(PANEL_WIDTH_DP).toFloat()
        sidePanel.animate().translationX(target).setDuration(200L).start()
    }

    // =====================================================================
    // Overlay (draws on top of the preview; does not resize it)
    // =====================================================================

    private inner class ScanOverlay : View(this@MainActivity) {
        private var result: AnalysisResult? = null

        private val boxPaint = Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(2).toFloat()
            isAntiAlias = true
        }
        private val textPaint = Paint().apply {
            textSize = dp(11).toFloat()
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }
        private val edgePaint = Paint().apply { alpha = 200 }
        private val scanPaint = Paint().apply {
            color = Color.argb(40, 0, 0, 0)
            strokeWidth = dp(1).toFloat()
        }
        private val radarFill = Paint().apply {
            color = Color.argb(150, 0, 20, 0)
            style = Paint.Style.FILL
        }
        private val radarLine = Paint().apply {
            color = Color.argb(200, 80, 255, 100)
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
            isAntiAlias = true
        }
        private val sweepPaint = Paint().apply {
            color = Color.argb(255, 120, 255, 140)
            strokeWidth = dp(2).toFloat()
            isAntiAlias = true
        }
        private val blipPaint = Paint().apply {
            color = Color.argb(255, 255, 220, 60)
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        fun setResult(r: AnalysisResult?) {
            result = r
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!powerOn) return
            val vw = width.toFloat()
            val vh = height.toFloat()

            if (isOn(Module.NIGHT)) {
                canvas.drawColor(Color.argb(45, 0, 255, 40))
                var y = 0f
                val step = dp(4).toFloat()
                while (y < vh) {
                    canvas.drawLine(0f, y, vw, y, scanPaint)
                    y += step
                }
            }
            if (isOn(Module.THERMAL)) {
                canvas.drawColor(Color.argb(30, 255, 90, 0))
            }

            val r = result
            if (r != null && r.srcW > 0 && r.srcH > 0) {
                // Same mapping as PreviewView FILL_CENTER (analysis frame is 4:3 like the preview).
                val scale = max(vw / r.srcW, vh / r.srcH)
                val dx = (vw - r.srcW * scale) / 2f
                val dy = (vh - r.srcH * scale) / 2f
                val fw = r.srcW * scale
                val fh = r.srcH * scale

                if (isOn(Module.EDGE)) {
                    r.edges?.let { e ->
                        if (!e.isRecycled) {
                            canvas.drawBitmap(e, null, RectF(dx, dy, dx + fw, dy + fh), edgePaint)
                        }
                    }
                }

                if (isOn(Module.MOTION)) {
                    r.motion?.let { m ->
                        boxPaint.color = Color.rgb(255, 160, 0)
                        canvas.drawRect(
                            dx + m.left * fw, dy + m.top * fh,
                            dx + m.right * fw, dy + m.bottom * fh, boxPaint
                        )
                        textPaint.color = Color.rgb(255, 160, 0)
                        canvas.drawText(
                            "MOTION", dx + m.left * fw, dy + m.top * fh - dp(3), textPaint
                        )
                    }
                }

                for (d in r.detections) {
                    if (!isVisible(d)) continue
                    val color = when {
                        isOn(Module.THREAT) && d.isThreat -> Color.rgb(255, 50, 50)
                        d.isPerson -> Color.rgb(80, 255, 120)
                        else -> Color.rgb(255, 230, 80)
                    }
                    boxPaint.color = color
                    textPaint.color = color
                    val l = dx + d.box.left * fw
                    val t = dy + d.box.top * fh
                    canvas.drawRect(l, t, dx + d.box.right * fw, dy + d.box.bottom * fh, boxPaint)
                    val tag = if (isOn(Module.THREAT) && d.isThreat) "THREAT? " else ""
                    canvas.drawText(
                        tag + d.label + " " + (d.score * 100).toInt() + "%",
                        l, t - dp(3), textPaint
                    )
                }
            }

            if (isOn(Module.RADAR)) {
                drawRadar(canvas, r)
                postInvalidateOnAnimation()
            }
        }

        private fun drawRadar(canvas: Canvas, r: AnalysisResult?) {
            val radius = dp(56).toFloat()
            val cx = dp(16) + radius
            val cy = height - dp(16) - radius
            canvas.drawCircle(cx, cy, radius, radarFill)
            canvas.drawCircle(cx, cy, radius, radarLine)
            canvas.drawCircle(cx, cy, radius * 0.66f, radarLine)
            canvas.drawCircle(cx, cy, radius * 0.33f, radarLine)
            canvas.drawLine(cx - radius, cy, cx + radius, cy, radarLine)
            canvas.drawLine(cx, cy - radius, cx, cy + radius, radarLine)

            val angle = (SystemClock.uptimeMillis() % 3000L) / 3000f * 360f
            val rad = Math.toRadians(angle.toDouble())
            canvas.drawLine(
                cx, cy,
                cx + radius * cos(rad).toFloat(), cy + radius * sin(rad).toFloat(), sweepPaint
            )

            if (r != null) {
                // Blips are placed from screen position of detections/motion: purely illustrative.
                val points = ArrayList<Pair<Float, Float>>()
                for (d in r.detections) {
                    if (isVisible(d)) {
                        points.add(Pair((d.box.left + d.box.right) / 2f, d.box.bottom))
                    }
                }
                r.motion?.let { m ->
                    if (isOn(Module.MOTION)) {
                        points.add(Pair((m.left + m.right) / 2f, m.bottom))
                    }
                }
                for ((nx, ny) in points) {
                    val bearing = Math.toRadians(-90.0 + (nx - 0.5) * 100.0)
                    val dist = radius * (0.15f + 0.8f * (1f - ny.coerceIn(0f, 1f)))
                    canvas.drawCircle(
                        cx + dist * cos(bearing).toFloat(),
                        cy + dist * sin(bearing).toFloat(),
                        dp(3).toFloat(), blipPaint
                    )
                }
            }
            textPaint.color = Color.rgb(120, 255, 140)
            canvas.drawText("RADAR (SIMULATED)", cx - radius, cy - radius - dp(4), textPaint)
        }
    }

    // =====================================================================
    // Data + constants
    // =====================================================================

    private class Det(val label: String, val score: Float, val box: RectF) {
        val isPerson: Boolean get() = label.equals("person", ignoreCase = true)
        val isThreat: Boolean get() = THREAT_LABELS.contains(label.lowercase(Locale.US))
    }

    private class AnalysisResult(
        val srcW: Int,
        val srcH: Int,
        val detections: List<Det>,
        val edges: Bitmap?,
        val motion: RectF?
    )

    companion object {
        private const val TAG = "NVCS3000"

        private const val MODEL_NAME = "efficientdet-lite0.tflite"
        private const val AUDIO_EQUIP = "audio/Scanner_equip.mp3.mp3"
        private const val AUDIO_UNEQUIP = "audio/Scanner_unequip.mp3.mp3"
        private const val AUDIO_STATIC = "audio/Scanner_static.mp3.mp3"
        private const val AUDIO_AMBIENCE = "audio/Scanner_Ambience.mp3"
        private const val IMAGE_STATIC = "images/static.jpg"
        private const val EVENT_FILE = "nvcs_events.txt"

        private const val PANEL_WIDTH_DP = 240
        private const val TARGET_WIDTH = 320
        private const val ANALYSIS_INTERVAL_MS = 120L
        private const val AUTO_LOG_INTERVAL_MS = 3000L
        private const val EDGE_THRESHOLD = 140
        private const val MOTION_DIFF = 28
        private const val ZOOM_STEP = 1.25f
        private const val MAX_ZOOM = 8f

        // Label heuristic only (COCO labels). This is not real threat assessment.
        private val THREAT_LABELS = setOf("knife", "scissors", "baseball bat")

        // Simulated nightvision: luminance -> green, brightened.
        private val NIGHT_MATRIX = floatArrayOf(
            0.10f, 0.20f, 0.04f, 0f, 0f,
            0.45f, 0.90f, 0.17f, 0f, 25f,
            0.06f, 0.12f, 0.02f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )

        // Simulated thermal: luminance -> false colour. Does NOT measure temperature.
        private val THERMAL_MATRIX = floatArrayOf(
            0.66f, 1.30f, 0.24f, 0f, -30f,
            0.48f, 0.94f, 0.18f, 0f, -100f,
            -0.30f, -0.59f, -0.11f, 0f, 150f,
            0f, 0f, 0f, 1f, 0f
        )
    }
}