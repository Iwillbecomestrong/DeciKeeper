package com.decikeeper.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.annotation.OptIn
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 基于 CameraX 与 Google ML Kit（完全离线独立版）的现代二维码扫描管理器。
 *
 * 核心优势：
 * 1. 深度学习检测器支持 45°~60° 大角度倾斜、侧倾与梯形透视变形秒扫；
 * 2. 1080P 超清分辨率配置 + 16:9 全屏匹配，高密度点阵二维码在远距离即刻检出；
 * 3. 支持轻触对焦（Tap-to-Focus）、双击快速变焦（Double-tap zoom 2.2x）及双指缩放（Pinch-to-zoom）；
 * 4. 严格执行 ImageAnalysis 缓冲保活，无缝适配悬浮窗开孔取景架构。
 */
class CameraXScanManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    companion object {
        private const val TAG = "CameraXScanManager"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysisExecutor: ExecutorService? = null
    private var barcodeScanner: BarcodeScanner? = null

    private val isAnalyzing = AtomicBoolean(true)
    private var isTorchEnabled = false
    private var loggedResolution = false

    init {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        barcodeScanner = BarcodeScanning.getClient(options)
    }

    /**
     * 绑定相机生命周期并启动二维码实时分析。
     */
    fun start(previewView: PreviewView, onScanSuccess: (String) -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        analysisExecutor = executor
        isAnalyzing.set(true)
        loggedResolution = false

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider

                val resolutionSelector = ResolutionSelector.Builder()
                    .setAspectRatioStrategy(
                        AspectRatioStrategy(
                            AspectRatio.RATIO_16_9,
                            AspectRatioStrategy.FALLBACK_RULE_AUTO
                        )
                    )
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1080, 1920),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                val preview = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)
                    .build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(resolutionSelector)
                    .build()

                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    processImageProxy(imageProxy, onScanSuccess)
                }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )

                setupTouchListeners(previewView)
                Log.i(TAG, "CameraX successfully bound to lifecycle with 1080P/16:9 strategy")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind CameraX use cases", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListeners(previewView: PreviewView) {
        val scaleGestureDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return false
                    val currentRatio = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    val delta = detector.scaleFactor
                    val maxZoom = cam.cameraInfo.zoomState.value?.maxZoomRatio ?: 5f
                    val minZoom = cam.cameraInfo.zoomState.value?.minZoomRatio ?: 1f
                    val newRatio = (currentRatio * delta).coerceIn(minZoom, maxZoom)
                    cam.cameraControl.setZoomRatio(newRatio)
                    return true
                }
            }
        )

        val gestureDetector = GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val cam = camera ?: return false
                    val zoomState = cam.cameraInfo.zoomState.value
                    val currentRatio = zoomState?.zoomRatio ?: 1f
                    val minZoom = zoomState?.minZoomRatio ?: 1f
                    val targetRatio = if (currentRatio > 1.3f) minZoom else (minZoom * 2.2f)
                    cam.cameraControl.setZoomRatio(targetRatio)
                    Log.d(TAG, "Double tap zoom toggled to $targetRatio")
                    return true
                }

                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    val cam = camera ?: return false
                    val factory = previewView.meteringPointFactory
                    val point = factory.createPoint(e.x, e.y)
                    val action = FocusMeteringAction.Builder(
                        point,
                        FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                    ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                    cam.cameraControl.startFocusAndMetering(action)
                    Log.d(TAG, "Single tap focus and metering at (${e.x}, ${e.y})")
                    return true
                }
            }
        )

        previewView.setOnTouchListener { _, event ->
            val scaleHandled = scaleGestureDetector.onTouchEvent(event)
            val gestureHandled = gestureDetector.onTouchEvent(event)
            scaleHandled || gestureHandled || true
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun processImageProxy(imageProxy: ImageProxy, onScanSuccess: (String) -> Unit) {
        val scanner = barcodeScanner
        if (!isAnalyzing.get() || scanner == null) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }

        if (!loggedResolution) {
            loggedResolution = true
            Log.i(TAG, "Analysis frame resolution: ${imageProxy.width}x${imageProxy.height}, rotation=${imageProxy.imageInfo.rotationDegrees}")
        }

        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            imageProxy.imageInfo.rotationDegrees
        )

        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                if (!isAnalyzing.get()) return@addOnSuccessListener
                for (barcode in barcodes) {
                    // 只要检出任意二维码即认定命中
                    val raw = barcode.rawValue ?: barcode.displayValue ?: "QR_DETECTED"
                    Log.i(TAG, "QR code detected successfully by ML Kit: format=${barcode.format}")
                    onScanSuccess(raw)
                    break
                }
            }
            .addOnFailureListener { e ->
                Log.d(TAG, "Barcode analysis failure: ${e.message}")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    /**
     * 暂停扫码分析（用于熄屏、退到后台或识别成功后）
     */
    fun pause() {
        Log.d(TAG, "pause: stopping image analysis")
        isAnalyzing.set(false)
    }

    /**
     * 恢复扫码分析（用于亮屏或回到前台）
     */
    fun resume() {
        Log.d(TAG, "resume: resuming image analysis")
        isAnalyzing.set(true)
    }

    /**
     * 切换闪光灯/手电筒开关
     */
    fun toggleTorch(): Boolean {
        return setTorch(!isTorchEnabled)
    }

    /**
     * 获取闪光灯当前开启状态
     */
    fun isTorchOn(): Boolean = isTorchEnabled

    /**
     * 设置闪光灯/手电筒
     */
    fun setTorch(enable: Boolean): Boolean {
        val cam = camera ?: return false
        if (cam.cameraInfo.hasFlashUnit()) {
            cam.cameraControl.enableTorch(enable)
            isTorchEnabled = enable
            Log.d(TAG, "setTorch: enable=$enable")
            return true
        }
        return false
    }

    /**
     * 释放相机与分析器资源
     */
    fun release() {
        Log.d(TAG, "release: tearing down CameraX and ML Kit")
        isAnalyzing.set(false)
        try {
            cameraProvider?.unbindAll()
            camera = null
            analysisExecutor?.shutdown()
            analysisExecutor = null
            barcodeScanner?.close()
            barcodeScanner = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing CameraX resources", e)
        }
    }
}
