package com.nvcs3000.scanner

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF

import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    // ==========================================
    // NVCS 3000 VARIABLES
    // ==========================================

    private lateinit var status: TextView
    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var modulePanel: LinearLayout
    private lateinit var powerButton: Button

    @Volatile
    private var scannerPowered = true

    @Volatile
    private var selectedModule = "Human Detector"

    private var menuVisible = false
    private var detector: ObjectDetector? = null
    private var lastUpdateTime = 0L

    // ==========================================
    // APPLICATION START
    // ==========================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        cameraExecutor = Executors.newSingleThreadExecutor()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18, 16, 18, 12)
            setBackgroundColor(Color.rgb(8, 10, 14))
        }

        // NVCS TITLE
        val title = TextView(this).apply {
            text = "N V C S   3 0 0 0"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }

        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // SUBTITLE
        val subtitle = TextView(this).apply {
            text = "NEURAL VISUAL CLASSIFICATION SYSTEM"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(150, 220, 255))
            setPadding(0, 8, 0, 14)
        }

        root.addView(subtitle)

        // ======================================
        // CAMERA PREVIEW
        // ======================================

        previewView = PreviewView(this).apply {
            implementationMode =
                PreviewView.ImplementationMode.COMPATIBLE
        }

        root.addView(
            previewView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        // ======================================
        // STATUS DISPLAY
        // ======================================

        status = TextView(this).apply {
            text = "SYSTEM INITIALIZING..."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(4, 12, 4, 8)
        }

        root.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        // ======================================
        // TOUCHSCREEN CONTROLS
        // ======================================

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        powerButton = Button(this).apply {
            text = "POWER"

            setOnClickListener {
                scannerPowered = !scannerPowered

                text = if (scannerPowered) {
                    "POWER"
                } else {
                    "POWER ON"
                }

                status.text = if (scannerPowered) {
                    "NVCS 3000 ONLINE\nModule: $selectedModule"
                } else {
                    "NVCS 3000 PAUSED"
                }
            }
        }

        val interactButton = Button(this).apply {
            text = "INTERACT"

            setOnClickListener {
                menuVisible = !menuVisible

                modulePanel.visibility =
                    if (menuVisible) View.VISIBLE else View.GONE
            }
        }

        controls.addView(
            powerButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        controls.addView(
            interactButton,
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        root.addView(controls)

        // ======================================
        // MODULE MENU
        // ======================================

        modulePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE

            setPadding(8, 8, 8, 8)
            setBackgroundColor(Color.rgb(18, 24, 32))
        }

        val moduleScroll = ScrollView(this)

        val moduleList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Modules currently implemented in this version.
        val moduleNames = listOf(
            "Human Detector",
            "Object Detector"
        )

        moduleNames.forEach { moduleName ->

            val moduleButton = Button(this).apply {
                text = moduleName

                setOnClickListener {
                    selectedModule = moduleName

                    menuVisible = false
                    modulePanel.visibility = View.GONE

                    status.text =
                        "MODULE SELECTED\n$selectedModule"
                }
            }

            moduleList.addView(
                moduleButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        moduleScroll.addView(moduleList)

        modulePanel.addView(
            moduleScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                140
            )
        )

        root.addView(modulePanel)

        // SHOW APPLICATION
        setContentView(root)

        checkCameraPermission()
    }

    // ==========================================
    // CAMERA PERMISSION
    // ==========================================

    private fun checkCameraPermission() {

        if (
            checkSelfPermission(Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            initializeDetectorAndCamera()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_REQUEST
            )
        }
    }

    // ==========================================
    // LOAD AI MODEL
    // ==========================================

    private fun initializeDetectorAndCamera() {

        try {
            val options =
                ObjectDetector.ObjectDetectorOptions.builder()
                    .setScoreThreshold(0.45f)
                    .setMaxResults(8)
                    .build()

            detector = ObjectDetector.createFromFileAndOptions(
                this,
                "efficientdet-lite0.tflite",
                options
            )

            status.text = "AI MODEL LOADED\nStarting camera..."

            startCamera()

        } catch (e: Exception) {

            val error = StringBuilder("MODEL LOAD FAILED")
            var cause: Throwable? = e
            var level = 0

            while (cause != null && level < 6) {
                error.append("\n\n")
                error.append(cause.javaClass.name)
                error.append("\n")
                error.append(cause.message ?: "No message")

                cause = cause.cause
                level++
            }

            status.text = error.toString()
        }
    }

    // ==========================================
    // START CAMERA
    // ==========================================

    private fun startCamera() {

        val providerFuture =
            ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({

            try {
                val cameraProvider = providerFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(
                            previewView.surfaceProvider
                        )
                    }

                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(
                        ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                    )
                    .setOutputImageFormat(
                        ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                    )
                    .build()

                analysis.setAnalyzer(cameraExecutor) { imageProxy ->

                    try {
                        analyzeFrame(imageProxy)

                    } catch (e: Exception) {

                        runOnUiThread {
                            status.text =
                                "DETECTION ERROR\n" +
                                (e.localizedMessage ?: "Unknown error")
                        }

                    } finally {
                        imageProxy.close()
                    }
                }

                cameraProvider.unbindAll()

                val lifecycleOwner: LifecycleOwner =
                    this@MainActivity

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                status.text = "CAMERA ACTIVE\nInitializing scanner..."

            } catch (e: Exception) {

                runOnUiThread {
                    status.text =
                        "CAMERA START FAILED\n" +
                        (e.localizedMessage ?: "Unknown error")
                }
            }

        }, ContextCompat.getMainExecutor(this))
    }

    // ==========================================
    // AI IMAGE ANALYSIS
    // ==========================================

    private fun analyzeFrame(imageProxy: ImageProxy) {

        // Pause AI analysis when the scanner is powered off.
        if (!scannerPowered) {
            return
        }

        val activeDetector = detector ?: return

        val now = System.currentTimeMillis()

        // Limit processing to reduce phone workload.
        if (now - lastUpdateTime < 250) {
            return
        }

        lastUpdateTime = now

        val bitmap = rgbaImageToBitmap(imageProxy)

        val rotation = imageProxy.imageInfo.rotationDegrees

        val rotatedBitmap = if (rotation == 0) {
            bitmap
        } else {
            val matrix = Matrix().apply {
                postRotate(rotation.toFloat())
            }

            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                matrix,
                true
            ).also { rotated ->
                if (rotated !== bitmap) {
                    bitmap.recycle()
                }
            }
        }

        try {
            val results = activeDetector.detect(
                TensorImage.fromBitmap(rotatedBitmap)
            )

            val detectedObjects = results
                .flatMap { detection ->
                    detection.categories.map { category ->

                        val label = category.label
                            .ifBlank { "Unknown object" }

                        val confidence =
                            (category.score * 100).toInt()

                        "$label: $confidence%"
                    }
                }
                .distinct()
                .take(8)

            // ==================================
            // SELECTED DETECTION MODE
            // ==================================

            val output = when (selectedModule) {

                "Human Detector" -> {

                    val humans = detectedObjects.filter {
                        val label = it.substringBefore(":")
                            .trim()
                            .lowercase()

                        label == "person" ||
                        label == "people" ||
                        label == "human"
                    }

                    if (humans.isEmpty()) {
                        "HUMAN DETECTOR\nNo person recognized."
                    } else {
                        "HUMANS DETECTED: ${humans.size}\n" +
                            humans.joinToString("\n")
                    }
                }

                "Object Detector" -> {

                    if (detectedObjects.isEmpty()) {
                        "OBJECT DETECTOR\nNo recognized objects."
                    } else {
                        "OBJECTS DETECTED: ${detectedObjects.size}\n" +
                            detectedObjects.joinToString("\n")
                    }
                }

                else -> "UNKNOWN MODULE"
            }

            runOnUiThread {

                if (scannerPowered) {
                    status.text =
                        "NVCS 3000 ONLINE\n$output"
                }
            }

        } finally {
            rotatedBitmap.recycle()
        }
    }

    // ==========================================
    // CAMERA IMAGE CONVERSION
    // ==========================================

    private fun rgbaImageToBitmap(
        image: ImageProxy
    ): Bitmap {

        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()

        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride

        val width = image.width
        val height = image.height

        val pixels = IntArray(width * height)

        val bufferStart = buffer.position()

        for (y in 0 until height) {

            val rowStart = bufferStart + y * rowStride

            for (x in 0 until width) {

                val index = rowStart + x * pixelStride

                val r = buffer.get(index).toInt() and 0xFF
                val g = buffer.get(index + 1).toInt() and 0xFF
                val b = buffer.get(index + 2).toInt() and 0xFF
                val a = buffer.get(index + 3).toInt() and 0xFF

                pixels[y * width + x] =
                    (a shl 24) or
                    (r shl 16) or
                    (g shl 8) or
                    b
            }
        }

        return Bitmap.createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888
        ).also { result ->

            result.setPixels(
                pixels,
                0,
                width,
                0,
                0,
                width,
                height
            )
        }
    }

    // ==========================================
    // HANDLE PERMISSION RESULT
    // ==========================================

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == CAMERA_REQUEST) {

            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                initializeDetectorAndCamera()

            } else {
                status.text =
                    "CAMERA ACCESS DENIED\n" +
                    "Allow camera access in Android settings."
            }
        }
    }

    // ==========================================
    // CLEANUP
    // ==========================================

    override fun onDestroy() {

        super.onDestroy()

        detector?.close()

        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
        }
    }

    companion object {
        private const val CAMERA_REQUEST = 3000
    }
}

private class BatteryIndicatorView(
    context: android.content.Context
) : View(context) {

    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
        color = Color.rgb(117, 245, 165)
    }

    private val litPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(117, 245, 165)
    }

    private val unlitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(25, 42, 32)
    }

    private val holePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(8, 10, 14)
    }

    private var batteryLevel = 100f

    fun setLevel(level: Float) {
        batteryLevel = level.coerceIn(0f, 100f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val shell = RectF(
            12f, 10f, width - 18f, height - 10f
        )

        // Outer battery shell
        canvas.drawRoundRect(shell, 5f, 5f, shellPaint)

        // Battery terminal
        canvas.drawRoundRect(
            RectF(
                width - 18f,
                height * 0.36f,
                width - 8f,
                height * 0.64f
            ),
            2f, 2f, litPaint
        )

        // Four separate battery chunks
        val left = shell.left + 7f
        val right = shell.right - 7f
        val top = shell.top + 7f
        val bottom = shell.bottom - 7f
        val gap = 3f
        val chunkWidth = (right - left - gap * 3) / 4f
        val litChunks = kotlin.math.ceil(
            batteryLevel / 25f
        ).toInt().coerceIn(0, 4)

        for (i in 0 until 4) {
            val x = left + i * (chunkWidth + gap)

            canvas.drawRoundRect(
                RectF(x, top, x + chunkWidth, bottom),
                2f,
                2f,
                if (i < litChunks) litPaint else unlitPaint
            )
        }

        // Hollow square in the center
        val holeSize = (bottom - top) * 0.55f
        val hole = RectF(
            width / 2f - holeSize / 2f,
            height / 2f - holeSize / 2f,
            width / 2f + holeSize / 2f,
            height / 2f + holeSize / 2f
        )

        canvas.drawRect(hole, holePaint)
        canvas.drawRect(hole, shellPaint)
    }
}
