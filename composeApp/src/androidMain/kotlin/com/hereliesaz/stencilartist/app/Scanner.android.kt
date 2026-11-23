package com.hereliesaz.stencilartist.app

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/** Consecutive frames ML Kit may fail on before the reader is reported as unavailable (one bad frame is not an outage). */
private const val MAX_FAILED_FRAMES = 5

@Composable
actual fun ScannerView(active: Boolean, onGtin: (String) -> Unit, onError: (ScannerError) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestGtin = rememberUpdatedState(onGtin)
    val latestError = rememberUpdatedState(onError)
    val sink = remember { ScanSink { latestGtin.value(it) } }
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (!ok) latestError.value(ScannerError.PermissionDenied)
    }

    // The system dialog is shown when a scan starts, not when the app does; a refusal is reported, not hidden.
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            latestError.value(ScannerError.NoCamera)
        } else if (!granted) {
            permission.launch(Manifest.permission.CAMERA)
        }
    }

    if (!active || !granted) {
        Box(modifier)
        return
    }

    val previewView = remember {
        // The texture-based implementation, so the rounded clip the panel puts on the view applies to the picture.
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner, previewView) {
        sink.reset()
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
                .build(),
        )
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build(),
            )
            .build()
        var failedFrames = 0
        analysis.setAnalyzer(executor) { frame ->
            val media = frame.image
            if (media == null || disposed) {
                frame.close()
                return@setAnalyzer
            }
            scanner.process(InputImage.fromMediaImage(media, frame.imageInfo.rotationDegrees))
                .addOnSuccessListener { codes ->
                    failedFrames = 0
                    if (!disposed) for (code in codes) {
                        val raw = code.rawValue ?: continue
                        sink.offer(raw, upcE = code.format == Barcode.FORMAT_UPC_E)
                    }
                }
                .addOnFailureListener { e ->
                    if (!disposed && ++failedFrames == MAX_FAILED_FRAMES) {
                        latestError.value(ScannerError.DecoderUnavailable(e.message ?: "ML Kit failed"))
                    }
                }
                .addOnCompleteListener { frame.close() }
        }

        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameras = future.get().also { provider = it }
                val selector = when {
                    cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    cameras.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> {
                        latestError.value(ScannerError.NoCamera)
                        return@addListener
                    }
                }
                cameras.unbindAll()
                cameras.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            } catch (e: Exception) {
                latestError.value(ScannerError.NoCamera)
            }
        }, ContextCompat.getMainExecutor(context))

        // The camera is let go here: when the scan stops, or the view leaves the screen.
        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            scanner.close()
            executor.shutdown()
        }
    }

    Box(modifier) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth(0.76f).fillMaxHeight(0.38f)
                .border(1.5.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(8.dp)),
        )
    }
}
