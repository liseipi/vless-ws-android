package com.example.vlessvpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Size
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Full-screen portrait QR scanner: CameraX preview + ML Kit barcode detection.
 * Decodes the whole preview frame (no "aim inside the box" requirement), so it
 * tolerates blur, low light and skewed codes much better than zxing.
 *
 * Returns the raw text via [EXTRA_RESULT]; back button / close = RESULT_CANCELED.
 */
class ScanQrActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var torchButton: ImageButton

    private var camera: Camera? = null
    private var scanner: BarcodeScanner? = null
    private var torchOn = false
    private var resultDelivered = false
    private var analyzing = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.scan_permission_denied, Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_scan_qr)

        previewView = findViewById(R.id.previewView)
        torchButton = findViewById(R.id.torchButton)
        findViewById<ImageButton>(R.id.closeButton).setOnClickListener { finish() }
        torchButton.setOnClickListener { toggleTorch() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        scanner?.close()
        super.onDestroy()
    }

    // ---- Camera + detection ----

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = try {
                providerFuture.get()
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开相机: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
                return@addListener
            }

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // 720p-ish frames are enough for QR detection and keep the analyzer fast.
            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build()
            scanner = BarcodeScanning.getClient(options)

            analysis.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                detectQr(imageProxy)
            }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开相机: ${e.message}", Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun detectQr(imageProxy: ImageProxy) {
        if (resultDelivered || analyzing) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        val client = scanner
        if (mediaImage == null || client == null) {
            imageProxy.close()
            return
        }

        analyzing = true
        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        client.process(image)
            .addOnSuccessListener { barcodes ->
                for (barcode in barcodes) {
                    val raw = barcode.rawValue ?: continue
                    if (!resultDelivered) {
                        resultDelivered = true
                        returnResult(raw)
                    }
                }
            }
            .addOnCompleteListener {
                analyzing = false
                imageProxy.close()
            }
    }

    private fun returnResult(raw: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, raw))
        finish()
    }

    private fun toggleTorch() {
        val cam = camera ?: return
        torchOn = !torchOn
        try {
            cam.cameraControl.enableTorch(torchOn)
                .addListener({ updateTorchIcon() }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            torchOn = false
            updateTorchIcon()
        }
    }

    private fun updateTorchIcon() {
        torchButton.setColorFilter(
            if (torchOn) ContextCompat.getColor(this, R.color.accent_blue) else 0xFFFFFFFF.toInt()
        )
    }

    companion object {
        const val EXTRA_RESULT = "scan_result"
    }
}
