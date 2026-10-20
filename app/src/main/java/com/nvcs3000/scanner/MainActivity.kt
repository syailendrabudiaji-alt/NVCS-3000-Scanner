package com.nvcs3000.scanner

import android.Manifest
import androidx.activity.ComponentActivity
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
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

    private lateinit var status: TextView
    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService

    private var detector: ObjectDetector? = null
    private var lastUpdateTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        cameraExecutor = Executors.newSingleThreadExecutor()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(18, 24, 18, 20)
            setBackgroundColor(Color.rgb(8, 10, 14))
        }

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

        val subtitle = TextView(this).apply {
            text = "NEURAL VISUAL CLASSIFICATION SYSTEM"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(150, 220, 255))
            setPadding(0, 8, 0, 18)
        }

        root.addView(subtitle)

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

        status = TextView(this).apply {
            text = "SYSTEM INITIALIZING..."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(4, 18, 4, 8)
        }

        root.addView(
            status,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)

        checkCameraPermission()
    }

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

            status.text = "MODEL LOADED\nStarting camera..."

            startCamera()

        } catch (e: Exception) {
            status.text =
                "MODEL LOAD FAILED\n${e.localizedMessage ?: "Unknown error"}"
        }
    }

    private fun startCamera() {
        val providerFuture =
            ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({

            try {
                val cameraProvider = providerFuture.get()

                val preview = Preview.Builder()
                    .build()
                    .also { previewUseCase ->
                        previewUseCase.setSurfaceProvider(
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

                // Explicitly specify the lifecycle owner.
                val lifecycleOwner: LifecycleOwner = this@MainActivity

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                status.text = "CAMERA ACTIVE\nLooking for objects..."

            } catch (e: Exception) {
                runOnUiThread {
                    status.text =
                        "CAMERA START FAILED\n" +
                        (e.localizedMessage ?: "Unknown error")
                }
            }

        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val activeDetector = detector ?: return

        val now = System.currentTimeMillis()

        // Limit detection frequency to reduce phone workload.
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

            val lines = results
                .flatMap { detection ->
                    detection.categories.map { category ->
                        val label = category.label
                            .ifBlank { "Unknown object" }

                        "$label: ${(category.score * 100).toInt()}%"
                    }
                }
                .distinct()
                .take(8)

            runOnUiThread {
                status.text = if (lines.isEmpty()) {
                    "SCANNING LIVE\nNo recognized objects detected."
                } else {
                    "OBJECTS DETECTED: ${lines.size}\n" +
                    lines.joinToString("\n")
                }
            }

        } finally {
            rotatedBitmap.recycle()
        }
    }

    private fun rgbaImageToBitmap(image: ImageProxy): Bitmap {
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
