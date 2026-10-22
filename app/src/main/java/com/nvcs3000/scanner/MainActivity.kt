package com.nvcs.scanner

import android.Manifest
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size as AndroidSize
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

// ============================================================
// NVCS 3000 Scanner (Android / Kotlin port)
//
// Controls (on screen buttons, or a hardware keyboard):
//   F = power     E = module list     + / - / 0 = zoom
//   Space Module:  R = movement + LIDAR-style scan,  Q = night vision + distance
//
// Assets (app/src/main/assets/):
//   audio/Scanner_equip.mp3.mp3, audio/Scanner_unequip.mp3.mp3,
//   audio/Scanner_static.mp3.mp3, audio/Scanner_Ambience.mp3
//   audio/Error/BEEPERROR.mp3
//   audio/Space/Space_E.mp3
//   audio/Space/c-moon-gravity-shift.mp3
//   model/efficientdet_lite0.tflite   (optional, used for Q object detection)
// ============================================================

private const val TAG = "NVCS"
private const val W = 640
private const val H = 360
private const val BATTERY_BASE_DRAIN = 0.07      // % per second
private const val BATTERY_CHARGE = 0.30          // % per second while off
private const val POWER_STATIC_MS = 1000L
private const val DETECTOR_MODEL = "model/efficientdet_lite0.tflite"

// Module list: name -> (battery cost in %/s, description)
private data class ModuleDef(val name: String, val cost: Double, val info: String)

private val MODULES = listOf(
    ModuleDef("Nightvision", 0.035, "Boosts visibility with a green night-vision-style filter."),
    ModuleDef("Thermal Vision", 0.065, "Thermal-style false-color filter. It does not measure real temperature."),
    ModuleDef("Edge Detection", 0.050, "Highlights strong edges and outlines in the camera image."),
    ModuleDef("Motion Tracker", 0.055, "Draws boxes and temporary IDs around moving image regions."),
    ModuleDef("Error Module", 0.045, "Purple-to-pink color corruption with RGB split, glitch strips and scanlines. Loops BEEPERROR while enabled."),
    ModuleDef("Space Module", 0.040, "Green low-noise vision. R: movement + LIDAR-style scan. Q: night vision + object/distance estimates. Distances are estimates from a normal camera, not real depth."),
)

// Approximate real-world heights (m) for a pinhole distance estimate.
private val KNOWN_HEIGHTS_M = mapOf(
    "person" to 1.7f, "chair" to 0.9f, "bottle" to 0.25f, "cup" to 0.10f,
    "cell phone" to 0.15f, "tv" to 0.5f, "laptop" to 0.25f, "book" to 0.22f,
)
private const val FOCAL_PX = 560f // rough focal length at 640px width; tweak to calibrate

// RGB colors (frames are processed in RGB order)
private val C_SPACE = Scalar(160.0, 255.0, 120.0)
private val C_SPACE_OBJ = Scalar(170.0, 255.0, 140.0)
private val C_CYAN = Scalar(40.0, 220.0, 255.0)

private class CloudPoint(val x: Int, val y: Int, val t: Long)

class MainActivity : ComponentActivity() {

    // ---------------- State shared between UI thread and camera thread ----------------
    private val enabled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    @Volatile private var powered = false
    @Volatile private var zoom = 1.0f
    @Volatile private var spaceScan = false   // R
    @Volatile private var spaceNight = false  // Q
    @Volatile private var powerStaticStart = 0L
    @Volatile private var fps = 0.0

    private var battery = 100.0
    private var lastTickNs = System.nanoTime()
    private var drainRate = 0.0

    private fun on(name: String) = enabled.contains(name)

    // ---------------- Views ----------------
    private lateinit var imageView: ImageView
    private lateinit var hud: TextView
    private lateinit var btnScan: Button
    private lateinit var btnNight: Button
    private val ui = Handler(Looper.getMainLooper())

    // ---------------- Camera ----------------
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val processor = FrameProcessor()

    // ---------------- Audio ----------------
    private var sndEquip: MediaPlayer? = null
    private var sndUnequip: MediaPlayer? = null
    private var sndStatic: MediaPlayer? = null
    private var sndAmbience: MediaPlayer? = null
    private var sndError: MediaPlayer? = null
    private var sndSpaceE: MediaPlayer? = null
    private var sndCmoon: MediaPlayer? = null

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) togglePower() else toast("Camera permission is required")
        }

    // ============================================================
    // Lifecycle
    // ============================================================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        if (!OpenCVLoader.initLocal()) {
            toast("OpenCV failed to load")
            Log.e(TAG, "OpenCV init failed")
        }

        loadSounds()
        buildUi()
        syncAudio()
        ui.post(tick)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacks(tick)
        cameraProvider?.unbindAll()
        analysisExecutor.shutdown()
        listOf(sndEquip, sndUnequip, sndStatic, sndAmbience, sndError, sndSpaceE, sndCmoon).forEach {
            try { it?.release() } catch (_: Exception) {}
        }
    }

    override fun onPause() {
        super.onPause()
        if (powered) togglePower() // release the camera and sounds when leaving the app
    }

    // ============================================================
    // UI (built in code so no XML layout is needed)
    // ============================================================
    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.rgb(8, 12, 18)) }

        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        root.addView(imageView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        hud = TextView(this).apply {
            setTextColor(Color.rgb(80, 255, 130))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.argb(120, 0, 0, 0))
        }
        root.addView(hud, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        // Button column on the right edge (landscape friendly).
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(150, 8, 16, 22))
            setPadding(8, 8, 8, 8)
        }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text
            textSize = 11f
            setOnClickListener { onClick() }
        }
        column.addView(button("POWER (F)") { togglePower() })
        column.addView(button("MODULES (E)") { showModuleDialog() })
        column.addView(button("ZOOM +") { zoomBy(0.2f) })
        column.addView(button("ZOOM -") { zoomBy(-0.2f) })
        btnScan = button("R: SCAN") { toggleSpaceScan() }
        btnNight = button("Q: NIGHT") { toggleSpaceNight() }
        column.addView(btnScan)
        column.addView(btnNight)
        root.addView(column, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.CENTER_VERTICAL))

        setContentView(root)
        updateSpaceButtons()
    }

    private fun showModuleDialog() {
        val names = MODULES.map { it.name }.toTypedArray()
        val checked = BooleanArray(names.size) { on(names[it]) }
        AlertDialog.Builder(this)
            .setTitle("SCANNER MODULES")
            .setMultiChoiceItems(names, checked) { _, which, isChecked ->
                val def = MODULES[which]
                if (isChecked) {
                    enabled.add(def.name)
                    toast(def.info)
                } else {
                    enabled.remove(def.name)
                }
                syncAudio()
                updateSpaceButtons()
            }
            .setPositiveButton("CLOSE", null)
            .show()
    }

    private fun updateSpaceButtons() {
        val vis = if (on("Space Module")) View.VISIBLE else View.GONE
        btnScan.visibility = vis
        btnNight.visibility = vis
        btnScan.alpha = if (spaceScan) 1f else 0.6f
        btnNight.alpha = if (spaceNight) 1f else 0.6f
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ============================================================
    // Keyboard support
    // ============================================================
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_F -> togglePower()
            KeyEvent.KEYCODE_E -> showModuleDialog()
            KeyEvent.KEYCODE_R -> toggleSpaceScan()
            KeyEvent.KEYCODE_Q -> toggleSpaceNight()
            KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_NUMPAD_ADD -> zoomBy(0.2f)
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> zoomBy(-0.2f)
            KeyEvent.KEYCODE_0 -> zoom = 1.0f
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private fun zoomBy(delta: Float) {
        zoom = (zoom + delta).coerceIn(1.0f, 3.0f)
    }

    // ============================================================
    // Power / battery / ticking
    // ============================================================
    private fun togglePower() {
        if (!powered) {
            if (battery <= 0.0) {
                toast("BATTERY DEPLETED - RECHARGING")
                return
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                cameraPermission.launch(Manifest.permission.CAMERA)
                return
            }
            powered = true
            powerStaticStart = System.currentTimeMillis()
            startCamera()
            restart(sndEquip)
            startLoop(sndStatic)
            startLoop(sndAmbience)
        } else {
            powered = false
            powerStaticStart = System.currentTimeMillis()
            cameraProvider?.unbindAll()
            imageView.setImageBitmap(null)
            stopSnd(sndStatic)
            stopSnd(sndAmbience)
            restart(sndUnequip)
        }
        syncAudio()
        updateSpaceButtons()
    }

    // Runs ~5 times per second: battery, audio sync and HUD.
    private val tick = object : Runnable {
        override fun run() {
            val now = System.nanoTime()
            val dt = ((now - lastTickNs) / 1e9).coerceIn(0.0, 1.0)
            lastTickNs = now

            if (powered) {
                var drain = BATTERY_BASE_DRAIN
                for (def in MODULES) if (on(def.name)) drain += def.cost
                drainRate = max(0.015, drain)
                battery = max(0.0, battery - drainRate * dt)
                if (battery <= 0.0) togglePower() // auto power-off at 0%
            } else {
                drainRate = 0.0
                battery = min(100.0, battery + BATTERY_CHARGE * dt)
            }

            syncAudio()
            updateHud()
            ui.postDelayed(this, 200)
        }
    }

    private fun updateHud() {
        val state = if (powered) "ONLINE" else "STANDBY"
        val batteryState = when {
            powered -> "IN USE  -%.2f%%/s".format(drainRate)
            battery < 100.0 -> "CHARGING"
            else -> "READY"
        }
        val active = if (enabled.isEmpty()) "None" else enabled.joinToString(", ")
        hud.text = "NVCS 3000   SYSTEM: $state   FPS: %.0f\nBATTERY: %d%% (%s)   ZOOM: %.1fx\nMODULES: %s"
            .format(fps, battery.toInt(), batteryState, zoom, active)
        btnScan.alpha = if (spaceScan) 1f else 0.6f
        btnNight.alpha = if (spaceNight) 1f else 0.6f
    }

    // ============================================================
    // Audio
    // ============================================================
    private fun loadPlayer(path: String, loop: Boolean = false): MediaPlayer? = try {
        assets.openFd(path).use { fd ->
            MediaPlayer().apply {
                setDataSource(fd.fileDescriptor, fd.startOffset, fd.length)
                isLooping = loop
                prepare()
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Sound not found/loaded: $path (${e.message})")
        null
    }

    private fun loadSounds() {
        sndEquip = loadPlayer("audio/Scanner_equip.mp3.mp3")
        sndUnequip = loadPlayer("audio/Scanner_unequip.mp3.mp3")
        sndStatic = loadPlayer("audio/Scanner_static.mp3.mp3", loop = true)
        sndAmbience = loadPlayer("audio/Scanner_Ambience.mp3", loop = true)
        sndError = loadPlayer("audio/Error/BEEPERROR.mp3", loop = true)
        sndSpaceE = loadPlayer("audio/Space/Space_E.mp3")
        sndCmoon = loadPlayer("audio/Space/c-moon-gravity-shift.mp3")
    }

    private fun restart(p: MediaPlayer?) {
        try {
            if (p == null) return
            if (p.isPlaying) p.seekTo(0) else p.start()
        } catch (_: IllegalStateException) {}
    }

    private fun startLoop(p: MediaPlayer?) {
        try { if (p != null && !p.isPlaying) p.start() } catch (_: IllegalStateException) {}
    }

    private fun stopSnd(p: MediaPlayer?) {
        try {
            if (p != null && p.isPlaying) {
                p.pause()
                p.seekTo(0)
            }
        } catch (_: IllegalStateException) {}
    }

    // Keeps module sounds and Space modes in sync with module/power state.
    private fun syncAudio() {
        // Error Module: loop BEEPERROR while enabled and powered.
        if (powered && on("Error Module")) startLoop(sndError) else stopSnd(sndError)

        // Space Module: everything stops when the module is off or the scanner is off.
        if (!(powered && on("Space Module"))) {
            if (spaceScan || spaceNight) {
                spaceScan = false
                spaceNight = false
                updateSpaceButtons()
            }
            stopSnd(sndSpaceE)
            stopSnd(sndCmoon)
        }
    }

    private fun toggleSpaceScan() {
        if (!(powered && on("Space Module"))) return
        spaceScan = !spaceScan
        processor.clearCloud()
        if (spaceScan) restart(sndSpaceE) else stopSnd(sndSpaceE)
        updateSpaceButtons()
    }

    private fun toggleSpaceNight() {
        if (!(powered && on("Space Module"))) return
        spaceNight = !spaceNight
        if (spaceNight) restart(sndCmoon) else stopSnd(sndCmoon)
        updateSpaceButtons()
    }

    // ============================================================
    // Camera
    // ============================================================
    @Suppress("DEPRECATION")
    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(AndroidSize(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .build()
                analysis.setAnalyzer(analysisExecutor, processor)

                val useFront = try {
                    provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                } catch (_: Exception) { false }
                val selector = if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA
                else CameraSelector.DEFAULT_BACK_CAMERA
                processor.mirror = useFront

                provider.unbindAll()
                provider.bindToLifecycle(this, selector, analysis)
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                toast("CAMERA UNAVAILABLE")
                powered = false
            }
        }, ContextCompat.getMainExecutor(this))
    }

    // ============================================================
    // Frame processing (runs on the analysis thread)
    // ============================================================
    private inner class FrameProcessor : ImageAnalysis.Analyzer {
        @Volatile var mirror = true

        private var prevBlur: Mat? = null
        private val cloud = ArrayList<CloudPoint>()
        private var sweepY = 0
        private var lastFrameNs = 0L

        private val clahe: CLAHE = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
        private val vignette: Mat = buildVignette()
        private val staticFrames: List<Mat> = List(5) { buildGreenStatic() }
        private var staticIndex = 0

        private val bitmaps = arrayOf(
            Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888),
            Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888),
        )
        private var bitmapIndex = 0

        // Optional TFLite object detector (assets/model/efficientdet_lite0.tflite).
        private var detector: ObjectDetector? = null
        private var detectorTried = false
        private var lastDetect = 0L
        private class Det(val box: Rect, val label: String)
        private var detections: List<Det> = emptyList()

        fun clearCloud() = synchronized(cloud) { cloud.clear() }

        override fun analyze(image: ImageProxy) {
            try {
                val bmp = image.toBitmap()
                process(bmp, image.imageInfo.rotationDegrees)
            } catch (e: Exception) {
                Log.e(TAG, "Frame error", e)
            } finally {
                image.close()
            }
        }

        private fun process(src: Bitmap, rotation: Int) {
            if (!powered) return

            // ---- Bitmap -> upright, mirrored RGB Mat at W x H ----
            val rgba = Mat()
            Utils.bitmapToMat(src, rgba)
            val rgb0 = Mat()
            Imgproc.cvtColor(rgba, rgb0, Imgproc.COLOR_RGBA2RGB)
            val rgb1 = Mat()
            when (rotation) {
                90 -> Core.rotate(rgb0, rgb1, Core.ROTATE_90_CLOCKWISE)
                180 -> Core.rotate(rgb0, rgb1, Core.ROTATE_180)
                270 -> Core.rotate(rgb0, rgb1, Core.ROTATE_90_COUNTERCLOCKWISE)
                else -> rgb0.copyTo(rgb1)
            }
            if (mirror) Core.flip(rgb1, rgb1, 1)
            val resized = Mat()
            Imgproc.resize(rgb1, resized, Size(W.toDouble(), H.toDouble()))
            var frame = zoomMat(resized, zoom)
            rgba.release(); rgb0.release(); rgb1.release()

            val original = frame.clone()
            val gray = Mat()
            Imgproc.cvtColor(frame, gray, Imgproc.COLOR_RGB2GRAY)

            // ---- Motion boxes ----
            val blur = Mat()
            Imgproc.GaussianBlur(gray, blur, Size(21.0, 21.0), 0.0)
            val motionBoxes = ArrayList<Rect>()
            prevBlur?.let { prev ->
                if (prev.size() == blur.size()) {
                    val diff = Mat()
                    Core.absdiff(prev, blur, diff)
                    val mask = Mat()
                    Imgproc.threshold(diff, mask, 24.0, 255.0, Imgproc.THRESH_BINARY)
                    Imgproc.dilate(mask, mask, Mat(), Point(-1.0, -1.0), 2)
                    val contours = ArrayList<MatOfPoint>()
                    Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                    for (c in contours) {
                        if (Imgproc.contourArea(c) > 600) motionBoxes.add(Imgproc.boundingRect(c))
                    }
                    diff.release(); mask.release()
                }
            }
            prevBlur?.release()
            prevBlur = blur

            // ---- Visual filters ----
            if (on("Nightvision")) {
                val eq = Mat()
                Imgproc.equalizeHist(gray, eq)
                val zero = Mat.zeros(frame.size(), CvType.CV_8UC1)
                val green = Mat()
                Core.merge(listOf(zero, eq, zero), green)
                Core.addWeighted(frame, 0.12, green, 0.95, 0.0, frame)
            }

            if (on("Space Module")) {
                val spaceFrame = if (spaceNight) spaceNightVision(frame) else spaceBase(frame)
                frame.release()
                frame = spaceFrame
            }

            if (on("Thermal Vision")) {
                val eq = Mat()
                Imgproc.equalizeHist(gray, eq)
                val colored = Mat()
                Imgproc.applyColorMap(eq, colored, Imgproc.COLORMAP_INFERNO)
                Imgproc.cvtColor(colored, colored, Imgproc.COLOR_BGR2RGB)
                Core.addWeighted(frame, 0.15, colored, 0.85, 0.0, frame)
                text(frame, "THERMAL / FALSE COLOR", 12, 22, Scalar(255.0, 255.0, 255.0))
            }

            if (on("Edge Detection")) {
                val edges = Mat()
                Imgproc.Canny(gray, edges, 65.0, 145.0)
                val edgeRgb = Mat()
                Imgproc.cvtColor(edges, edgeRgb, Imgproc.COLOR_GRAY2RGB)
                Core.addWeighted(frame, 0.45, edgeRgb, 0.9, 0.0, frame)
            }

            if (on("Motion Tracker")) {
                motionBoxes.forEachIndexed { i, r ->
                    Imgproc.rectangle(frame, r.tl(), r.br(), C_CYAN, 2)
                    text(frame, "MOTION #${i + 1}", r.x, max(15, r.y - 6), C_CYAN, 0.5)
                }
            }

            // ---- Space overlays ----
            if (on("Space Module")) {
                if (spaceScan) drawSpaceScan(frame, gray, motionBoxes)
                if (spaceNight) drawSpaceObjects(frame, original, gray, motionBoxes)
                text(
                    frame,
                    "SPACE  [R] SCAN:${if (spaceScan) "ON" else "OFF"}  [Q] NIGHT:${if (spaceNight) "ON" else "OFF"}",
                    W - 330, H - 12, C_SPACE
                )
            }

            // ---- Error Module goes on top of everything else ----
            if (on("Error Module")) applyErrorEffect(frame)

            // ---- Static overlay (Space Module gets much less) ----
            val alpha = if (on("Space Module")) Random.nextDouble(0.01, 0.05)
            else Random.nextDouble(0.10, 0.40)
            staticIndex = (staticIndex + 1) % staticFrames.size
            Core.addWeighted(frame, 1.0 - alpha, staticFrames[staticIndex], alpha, 0.0, frame)

            // Power-on/off burst of static that fades over one second.
            val sinceStart = System.currentTimeMillis() - powerStaticStart
            if (sinceStart in 0 until POWER_STATIC_MS) {
                val a = 0.92 * (1.0 - sinceStart.toDouble() / POWER_STATIC_MS)
                Core.addWeighted(frame, 1.0 - a, staticFrames[staticIndex], a, 0.0, frame)
            }

            // ---- Show it ----
            val out = Mat()
            Imgproc.cvtColor(frame, out, Imgproc.COLOR_RGB2RGBA)
            val bmp = bitmaps[bitmapIndex]
            bitmapIndex = (bitmapIndex + 1) % bitmaps.size
            Utils.matToBitmap(out, bmp)
            runOnUiThread { if (powered) imageView.setImageBitmap(bmp) }

            // FPS (smoothed)
            val nowNs = System.nanoTime()
            if (lastFrameNs != 0L) {
                val inst = 1e9 / (nowNs - lastFrameNs)
                fps = if (fps == 0.0) inst else fps * 0.9 + inst * 0.1
            }
            lastFrameNs = nowNs

            out.release(); frame.release(); original.release(); gray.release(); resized.release()
        }

        // ------------------------------------------------------------
        // Error Module: animated purple->pink tint, RGB split, glitches, scanlines
        // ------------------------------------------------------------
        private fun applyErrorEffect(frame: Mat) {
            val t = System.currentTimeMillis() / 1000.0

            // Purple (140,0,200) to pink (255,105,200) gradient sliding across the image.
            val row = ByteArray(W * 3)
            for (x in 0 until W) {
                val mix = 0.5 + 0.5 * Math.sin(x.toDouble() / W * 2 * Math.PI + t * 2.0)
                row[x * 3] = (140 + (255 - 140) * mix).toInt().toByte()
                row[x * 3 + 1] = (0 + 105 * mix).toInt().toByte()
                row[x * 3 + 2] = 200.toByte()
            }
            val rowMat = Mat(1, W, CvType.CV_8UC3)
            rowMat.put(0, 0, row)
            val tint = Mat()
            Core.repeat(rowMat, frame.rows(), 1, tint)
            Core.addWeighted(frame, 0.62, tint, 0.38, 0.0, frame)
            rowMat.release(); tint.release()

            // RGB channel split.
            val shift = Random.nextInt(3, 10)
            val ch = ArrayList<Mat>()
            Core.split(frame, ch)
            rollRows(ch[0], 0, ch[0].rows(), shift)
            rollRows(ch[2], 0, ch[2].rows(), -shift)
            Core.merge(ch, frame)
            ch.forEach { it.release() }

            // Horizontal glitch strips.
            if (Random.nextDouble() < 0.65) {
                repeat(Random.nextInt(1, 5)) {
                    val y = Random.nextInt(0, frame.rows())
                    val y2 = min(frame.rows(), y + Random.nextInt(2, 13))
                    rollRows(frame, y, y2, Random.nextInt(-35, 36))
                }
            }

            // Displaced block.
            if (Random.nextDouble() < 0.35) {
                val bw = Random.nextInt(60, 221)
                val bh = Random.nextInt(15, 61)
                val x = Random.nextInt(0, max(1, W - bw))
                val y = Random.nextInt(0, max(1, H - bh))
                val x2 = (x + Random.nextInt(-40, 41)).coerceIn(0, W - bw)
                val block = frame.submat(y, y + bh, x, x + bw).clone()
                block.copyTo(frame.submat(y, y + bh, x2, x2 + bw))
                block.release()
            }

            // Pink corruption line.
            if (Random.nextDouble() < 0.4) {
                val y = Random.nextInt(0, H)
                Imgproc.line(frame, Point(0.0, y.toDouble()), Point((W - 1).toDouble(), y.toDouble()),
                    Scalar(255.0, 100.0, 255.0), Random.nextInt(1, 4))
            }

            // Scanlines: darken every third row.
            val dark = Scalar(0.75, 0.75, 0.75)
            var y = 0
            while (y < frame.rows()) {
                val r = frame.row(y)
                Core.multiply(r, dark, r)
                y += 3
            }
        }

        /** Circularly shifts rows [y1, y2) of [m] horizontally by [shift] pixels. */
        private fun rollRows(m: Mat, y1: Int, y2: Int, shift: Int) {
            val w = m.cols()
            val s = ((shift % w) + w) % w
            if (s == 0 || y2 <= y1) return
            val region = m.submat(y1, y2, 0, w)
            val copy = region.clone()
            copy.submat(0, y2 - y1, w - s, w).copyTo(region.submat(0, y2 - y1, 0, s))
            copy.submat(0, y2 - y1, 0, w - s).copyTo(region.submat(0, y2 - y1, s, w))
            copy.release()
        }

        // ------------------------------------------------------------
        // Space Module
        // ------------------------------------------------------------

        /** Green, clearer vision: edge-preserving denoise + sharpen + local contrast. */
        private fun spaceBase(frame: Mat): Mat {
            val clean = Mat()
            Imgproc.bilateralFilter(frame, clean, 5, 40.0, 40.0)
            val soft = Mat()
            Imgproc.GaussianBlur(clean, soft, Size(0.0, 0.0), 1.5)
            Core.addWeighted(clean, 1.5, soft, -0.5, 0.0, clean)
            val g = Mat()
            Imgproc.cvtColor(clean, g, Imgproc.COLOR_RGB2GRAY)
            val g2 = Mat()
            clahe.apply(g, g2)
            clean.release(); soft.release(); g.release()
            return greenFromGray(g2, 0.18, 0.12)
        }

        /** Q mode: strongly amplified night vision with grain and vignette. */
        private fun spaceNightVision(frame: Mat): Mat {
            val g = Mat()
            Imgproc.cvtColor(frame, g, Imgproc.COLOR_RGB2GRAY)
            Imgproc.GaussianBlur(g, g, Size(3.0, 3.0), 0.0)
            Imgproc.equalizeHist(g, g)
            val noise = Mat(g.size(), CvType.CV_8UC1)
            Core.randu(noise, 0.0, 18.0)
            Core.add(g, noise, g)
            Core.multiply(g, vignette, g, 1.0 / 255.0)
            noise.release()
            return greenFromGray(g, 0.10, 0.05)
        }

        private fun greenFromGray(g: Mat, redK: Double, blueK: Double): Mat {
            val r = Mat()
            val b = Mat()
            Core.convertScaleAbs(g, r, redK, 0.0)
            Core.convertScaleAbs(g, b, blueK, 0.0)
            val out = Mat()
            Core.merge(listOf(r, g, b), out)
            r.release(); b.release(); g.release()
            return out
        }

        /** R mode: movement boxes + LIDAR-style point cloud built from edges (not real depth). */
        private fun drawSpaceScan(frame: Mat, gray: Mat, motionBoxes: List<Rect>) {
            val now = System.currentTimeMillis()
            sweepY = (sweepY + 6) % H

            val y0 = max(0, sweepY - 4)
            val y1 = min(H, sweepY + 5)
            val edges = Mat()
            Imgproc.Canny(gray, edges, 80.0, 160.0)
            val band = edges.submat(y0, y1, 0, W)
            if (Core.countNonZero(band) > 0) {
                val locations = Mat()
                Core.findNonZero(band, locations)
                val pts = MatOfPoint(locations).toArray()
                val step = max(1, pts.size / 300)
                synchronized(cloud) {
                    var i = 0
                    while (i < pts.size) {
                        cloud.add(CloudPoint(pts[i].x.toInt(), pts[i].y.toInt() + y0, now))
                        i += step
                    }
                }
                locations.release()
            }
            edges.release()

            synchronized(cloud) {
                cloud.removeAll { now - it.t > 4000 }
                while (cloud.size > 3000) cloud.removeAt(0)
                var i = 0
                while (i < cloud.size) {
                    val p = cloud[i]
                    val fade = max(0.15, 1.0 - (now - p.t) / 4000.0)
                    Imgproc.circle(
                        frame, Point(p.x.toDouble(), p.y.toDouble()), 1,
                        Scalar(120.0 * fade, 255.0 * fade, 90.0 * fade), -1
                    )
                    i += 2
                }
                text(frame, "MOVEMENT: ${motionBoxes.size}  CLOUD: ${cloud.size} PTS", 12, 22, C_SPACE)
            }

            Imgproc.line(frame, Point(0.0, sweepY.toDouble()), Point((W - 1).toDouble(), sweepY.toDouble()), C_SPACE, 2)
            for (r in motionBoxes) {
                Imgproc.rectangle(frame, r.tl(), r.br(), C_SPACE, 2)
                text(frame, "MOVEMENT", r.x, max(15, r.y - 6), C_SPACE)
            }
        }

        /**
         * Q mode: object boxes with estimated distance. Uses the TFLite detector when
         * assets/model/efficientdet_lite0.tflite exists, otherwise falls back to edge blobs.
         */
        private fun drawSpaceObjects(frame: Mat, original: Mat, gray: Mat, motionBoxes: List<Rect>) {
            ensureDetector()
            val det = detector
            val items: List<Det>

            if (det != null) {
                val now = System.currentTimeMillis()
                if (now - lastDetect >= 600) {
                    lastDetect = now
                    try {
                        val rgba = Mat()
                        Imgproc.cvtColor(original, rgba, Imgproc.COLOR_RGB2RGBA)
                        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
                        Utils.matToBitmap(rgba, bmp)
                        rgba.release()
                        detections = det.detect(TensorImage.fromBitmap(bmp)).mapNotNull { d ->
                            val c = d.categories.firstOrNull() ?: return@mapNotNull null
                            val b = d.boundingBox
                            Det(
                                Rect(
                                    b.left.toInt().coerceIn(0, W - 1),
                                    b.top.toInt().coerceIn(0, H - 1),
                                    b.width().toInt().coerceIn(1, W),
                                    b.height().toInt().coerceIn(1, H)
                                ),
                                c.label.lowercase()
                            )
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Object detection issue: ${e.message}")
                        detections = emptyList()
                    }
                }
                items = detections
            } else {
                // Fallback: large edge blobs (works on still objects) + moving regions.
                val edges = Mat()
                Imgproc.Canny(gray, edges, 60.0, 140.0)
                Imgproc.dilate(edges, edges, Mat(), Point(-1.0, -1.0), 3)
                val contours = ArrayList<MatOfPoint>()
                Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                val blobs = contours
                    .map { Imgproc.boundingRect(it) }
                    .filter { it.area() > 1800 }
                    .sortedByDescending { it.area() }
                    .take(10)
                    .map { Det(it, "object") }
                edges.release()
                items = blobs + motionBoxes.map { Det(it, "object") }
            }

            for (d in items) {
                val r = d.box
                val known = KNOWN_HEIGHTS_M[d.label]
                val dist = if (known != null) {
                    "~%.1fm".format(known * FOCAL_PX / max(1, r.height))
                } else {
                    val ratio = r.area() / (W * H).toDouble()
                    if (ratio > 0.12) "NEAR" else if (ratio > 0.035) "MID" else "FAR"
                }
                Imgproc.rectangle(frame, r.tl(), r.br(), C_SPACE_OBJ, 2)
                text(frame, "${d.label.uppercase()} $dist", r.x, max(16, r.y - 6), C_SPACE_OBJ, 0.5)
            }
            text(frame, "NIGHT VISION / DISTANCE ESTIMATE", 12, 42, C_SPACE_OBJ)
        }

        private fun ensureDetector() {
            if (detectorTried) return
            detectorTried = true
            try {
                val options = ObjectDetector.ObjectDetectorOptions.builder()
                    .setMaxResults(8)
                    .setScoreThreshold(0.4f)
                    .build()
                detector = ObjectDetector.createFromFileAndOptions(this@MainActivity, DETECTOR_MODEL, options)
                Log.i(TAG, "Object detector loaded")
            } catch (e: Exception) {
                Log.w(TAG, "No object detector ($DETECTOR_MODEL): ${e.message}. Using edge-blob fallback.")
            }
        }

        // ------------------------------------------------------------
        // Helpers
        // ------------------------------------------------------------
        private fun zoomMat(src: Mat, z: Float): Mat {
            if (z <= 1.001f) return src
            val cw = max(1, (src.cols() / z).toInt())
            val ch = max(1, (src.rows() / z).toInt())
            val x = (src.cols() - cw) / 2
            val y = (src.rows() - ch) / 2
            val out = Mat()
            Imgproc.resize(src.submat(y, y + ch, x, x + cw), out, Size(src.cols().toDouble(), src.rows().toDouble()))
            src.release()
            return out
        }

        private fun text(m: Mat, s: String, x: Int, y: Int, color: Scalar, scale: Double = 0.45) {
            Imgproc.putText(m, s, Point(x.toDouble(), y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, scale, color, 1)
        }

        private fun buildVignette(): Mat {
            val bytes = ByteArray(W * H)
            for (y in 0 until H) {
                for (x in 0 until W) {
                    val dx = (x - W / 2.0) / (W / 2.0)
                    val dy = (y - H / 2.0) / (H / 2.0)
                    val d = sqrt(dx * dx + dy * dy)
                    val v = (1.25 - 0.55 * d).coerceIn(0.45, 1.0)
                    bytes[y * W + x] = (v * 255).toInt().toByte()
                }
            }
            val m = Mat(H, W, CvType.CV_8UC1)
            m.put(0, 0, bytes)
            return m
        }

        private fun buildGreenStatic(): Mat {
            val small = Mat(H / 4, W / 4, CvType.CV_8UC1)
            Core.randu(small, 0.0, 255.0)
            val r = Mat(); val g = Mat(); val b = Mat()
            Core.convertScaleAbs(small, r, 0.12, 0.0)
            Core.convertScaleAbs(small, g, 0.78, 0.0)
            Core.convertScaleAbs(small, b, 0.035, 0.0)
            val merged = Mat()
            Core.merge(listOf(r, g, b), merged)
            val out = Mat()
            Imgproc.resize(merged, out, Size(W.toDouble(), H.toDouble()), 0.0, 0.0, Imgproc.INTER_NEAREST)
            small.release(); r.release(); g.release(); b.release(); merged.release()
            return out
        }
    }
}
