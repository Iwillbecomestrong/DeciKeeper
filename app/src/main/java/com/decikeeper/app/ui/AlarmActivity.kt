package com.decikeeper.app.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.decikeeper.app.R
import com.decikeeper.app.data.AndroidLogcatNormalEventSink
import com.decikeeper.app.data.DefaultLogDebugConfig
import com.decikeeper.app.data.DefaultNormalEventRenderer
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.data.NormalEventLogger
import com.decikeeper.app.data.SystemEventClock
import com.decikeeper.app.manager.AlarmProtectionEventBridge
import com.decikeeper.app.manager.AlarmOverlayRequirement
import com.decikeeper.app.manager.AlarmScheduler
import com.decikeeper.app.manager.AlarmWindowManager
import com.decikeeper.app.service.AlarmChallengeEmergencyGuardService
import com.decikeeper.app.service.AlarmVibrationService

internal fun shouldFinishExternalAlarmHost(
    isScanMode: Boolean,
    isLockedScreenMode: Boolean,
    isLaunchingScanner: Boolean
): Boolean = !isScanMode && !isLockedScreenMode && !isLaunchingScanner












class AlarmActivity : AppCompatActivity(), LockWindowManager.AlarmOverlayController {

    private var alarmId: Int = -1
    private var snoozeMinutes: Int = 10
    private var isLaunchingScanner = false
    private var isScanMode = false
    private var isLockedScreenMode = false
    private var newAlarmSession = false
    private var barcodeView: CameraXScanManager? = null
    private var torchOn = false
    private var scanGeneration = 0L
    private var scanSessionToken = ""
    private var scanPreviewView: PreviewView? = null
    private var scanPreviewObserver: Observer<PreviewView.StreamState>? = null
    private var scanPreviewTimeout: Runnable? = null
    private var scanPreviewStreaming = false

    private val alarmProtectionNormalEventLogger by lazy {
        NormalEventLogger(
            renderer = DefaultNormalEventRenderer,
            sinks = listOf(LogExporter.normalEventSink(), AndroidLogcatNormalEventSink())
        )
    }
    private val alarmProtectionEventBridge by lazy {
        AlarmProtectionEventBridge(
            clock = SystemEventClock,
            emit = alarmProtectionNormalEventLogger::emit,
            debugConfig = DefaultLogDebugConfig
        )
    }
    private var qrChallengeStartEmitted = false
    private var qrChallengeResultEmitted = false

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        isLaunchingScanner = false
        AlarmChallengeEmergencyGuardService.setPermissionRequestInProgress(false)
        if (granted) {
            startScanMode()
        } else {
            fallbackToAlternativeChallenge()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        alarmId = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, -1)
        snoozeMinutes = AlarmScheduler.getSnoozeMinutes(this)
        newAlarmSession = intent.getBooleanExtra("new_alarm_session", false)
        isLockedScreenMode = intent.getBooleanExtra("is_locked_screen", false)
        setupWindow()
        window.decorView.setBackgroundColor(ThemeManager.getColorAttr(this, R.attr.zzPageBg))

        android.util.Log.i("AlarmActivity", "onCreate: alarmId=$alarmId, is_locked_screen=$isLockedScreenMode, start_scan=${intent.getBooleanExtra("start_scan", false)}, request_camera=${intent.getBooleanExtra("request_camera_permission", false)}")
        LogExporter.logEvent("AlarmOverlay", "ACTIVITY_ON_CREATE id=$alarmId locked=${intent.getBooleanExtra("is_locked_screen", false)} startScan=${intent.getBooleanExtra("start_scan", false)} requestCamera=${intent.getBooleanExtra("request_camera_permission", false)}")

        // AlarmActivity 的所有进入路径都必须经过悬浮窗前置条件；没有权限时直接结束，
        // 不能让页面版挑战继续走到 TYPE_APPLICATION_OVERLAY 的 addView 崩溃点。
        if (!AlarmOverlayRequirement.isGranted(this)) {
            Log.w("AlarmActivity", "onCreate: overlay permission missing, refusing page challenge")
            LogExporter.logEvent("AlarmOverlay", "ACTIVITY_REFUSED_NO_OVERLAY id=$alarmId")
            Toast.makeText(this, AlarmOverlayRequirement.MISSING_RUNTIME_MESSAGE, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        LockWindowManager.reattachAlarmController(this)
        if (intent.getBooleanExtra("start_scan", false)) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startScanMode()
            } else {
                launchQrScan()
            }
        } else {
            isLockedScreenMode = intent.getBooleanExtra("is_locked_screen", false)
            LockWindowManager.showAlarmOverlay(this, alarmId, snoozeMinutes, this, useInline = isLockedScreenMode, newSession = newAlarmSession)
            newAlarmSession = false
            AlarmChallengeEmergencyGuardService.start(this)
            LogExporter.logEvent("AlarmOverlay", "ACTIVITY_SHOW_OVERLAY_RETURNED id=$alarmId lockedMode=$isLockedScreenMode")
            if (intent.getBooleanExtra("request_camera_permission", false)) {
                launchQrScan()
            }
        }
    }

    /**
     * 相机预览必须渲染在完整的闹钟 Overlay 窗口内。
     * 不能依赖下面的 Activity 预览：紧急白名单进入分屏后，Activity 可能只有半屏，
     * 全屏悬浮窗的透明开孔会同时暴露另外一个分屏应用。
     */
    private fun startBarcodeScanner() {
        LockWindowManager.setAlarmOverlayPassthrough(true)
        val alarmView = LockWindowManager.getAlarmView()
        val previewView = alarmView?.findViewById<PreviewView>(R.id.preview_alarm_overlay)
        if (previewView == null) {
            Log.e("AlarmActivity", "SCAN_OVERLAY_PREVIEW_MISSING; cancelling QR scan safely")
            LogExporter.logEvent("AlarmOverlay", "SCAN_OVERLAY_PREVIEW_MISSING")
            Toast.makeText(this, "摄像头界面暂不可用，请返回挑战重试", Toast.LENGTH_LONG).show()
            isScanMode = false
            LockWindowManager.cancelAlarmScan()
            return
        }

        clearScanPreviewObservation()
        scanPreviewStreaming = false
        val status = alarmView?.findViewById<TextView>(R.id.tv_alarm_scan_preview_status)
        status?.text = "正在打开摄像头…"
        status?.visibility = View.VISIBLE
        // TextureView 与扫码 UI 在同一个 Overlay 窗口，不再依赖下层分屏坐标。
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        val multiWindow = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInMultiWindowMode else false
        LogExporter.logEvent("AlarmOverlay",
            "SCAN_PREVIEW_HOST=OVERLAY multiWindow=$multiWindow attached=${previewView.isAttachedToWindow} activityBounds=${window.decorView.width}x${window.decorView.height} overlayBounds=${alarmView?.width}x${alarmView?.height}")
        val observer = Observer<PreviewView.StreamState> { state ->
            if (isScanMode) {
                scanPreviewStreaming = state == PreviewView.StreamState.STREAMING
                if (scanPreviewStreaming) {
                    status?.visibility = View.GONE
                    barcodeView?.resume()
                    LogExporter.logEvent("AlarmOverlay", "SCAN_OVERLAY_PREVIEW_STREAMING")
                } else {
                    barcodeView?.pause()
                    status?.text = "正在连接摄像头…"
                    status?.visibility = View.VISIBLE
                }
            }
        }
        scanPreviewView = previewView
        scanPreviewObserver = observer
        previewView.previewStreamState.observe(this, observer)
        val timeout = Runnable {
            if (isScanMode && !scanPreviewStreaming) {
                barcodeView?.pause()
                status?.text = "暂时无法显示摄像头画面，请返回挑战后重试"
                status?.visibility = View.VISIBLE
                LogExporter.logEvent("AlarmOverlay", "SCAN_OVERLAY_PREVIEW_TIMEOUT")
            }
        }
        scanPreviewTimeout = timeout
        previewView.postDelayed(timeout, 8000L)

        val manager = barcodeView ?: CameraXScanManager(this, this).also { barcodeView = it }
        scanSessionToken = AlarmScheduler.getActiveScheduleToken(this)
        val callbackGeneration = ++scanGeneration
        if (!qrChallengeStartEmitted) {
            qrChallengeStartEmitted = true
            alarmProtectionEventBridge.recordAlarmChallengeStart(
                alarmId = alarmId.toString(),
                challengeId = "QR-$alarmId",
                totalSteps = 1
            )
        }
        manager.start(previewView) { result ->
            // 画面未实际显示时，即使 ImageAnalysis 检出二维码也不能盲扫通过。
            if (!scanPreviewStreaming) {
                Log.w("AlarmActivity", "Ignoring QR result before visible overlay preview")
                return@start
            }
            val activeToken = AlarmScheduler.getActiveScheduleToken(this@AlarmActivity)
            val sessionStillCurrent = callbackGeneration == scanGeneration &&
                (alarmId <= 0 || (AlarmScheduler.isAlarmActive(this@AlarmActivity) &&
                    AlarmScheduler.getActiveAlarmId(this@AlarmActivity) == alarmId &&
                    (scanSessionToken.isBlank() || activeToken == scanSessionToken)))
            if (!sessionStillCurrent) {
                android.util.Log.w("AlarmActivity", "Ignoring stale QR callback: id=$alarmId generation=$callbackGeneration currentGeneration=$scanGeneration")
                manager.pause()
                return@start
            }
            if (torchOn) {
                torchOn = false
                manager.setTorch(false)
                updateTorchUi(false)
            }
            manager.pause()
            clearScanPreviewObservation()
            isScanMode = false
            android.util.Log.i("AlarmActivity", "Scan success, updating alarm challenge stage")
            if (!qrChallengeResultEmitted) {
                qrChallengeResultEmitted = true
                alarmProtectionEventBridge.recordAlarmChallengeResult(
                    alarmId = alarmId.toString(),
                    challengeId = "QR-$alarmId",
                    step = 1,
                    result = "FINAL_CORRECT",
                    cumulativeProgress = 1,
                    totalSteps = 1
                )
            }
            LockWindowManager.setAlarmOverlayPassthrough(false)
            LockWindowManager.showAlarmOverlayIfHidden()
            LockWindowManager.onScanChallengePassed()
            // [Fix: overlay detach / white screen]
            // 若覆盖层已在独立窗口模式（!isCurrentOverlayInline()）或挑战已完成，
            // 绝不能调用 showAlarmOverlayInlineInActivity 把已在 WindowManager
            // 渲染好的独立窗口摘下来塞回 Activity（removeView 的异步 MSG_DIE 会摧毁
            // CanvasContext 导致白屏死锁）。直接 finishAndRemoveTask 退出扫码器，
            // 露出已渲染好完成态/第二关的独立覆盖层窗口即可。
            // 仅当覆盖层依然是 inline 模式、锁屏且挑战未全部完成（需进入第二关）时才内嵌回 Activity。
            val isInline = LockWindowManager.isCurrentOverlayInline()
            val isCompleted = AlarmScheduler.isChallengeCompleted(this@AlarmActivity)
            if (isLockedScreenMode && isInline && !isCompleted) {
                LockWindowManager.showAlarmOverlayInlineInActivity(this, this)
            } else if (!isLockedScreenMode) {
                finishAndRemoveTask()
            }
        }
    }

    override fun onScanCancel() {
        android.util.Log.i("AlarmActivity", "onScanCancel: isLockedScreenMode=$isLockedScreenMode")
        if (torchOn) {
            torchOn = false
            barcodeView?.setTorch(false)
            updateTorchUi(false)
        }
        barcodeView?.pause()
        clearScanPreviewObservation()
        isScanMode = false
        if (!isLockedScreenMode) {
            finishAndRemoveTask()
        }
    }

    private fun clearScanPreviewObservation() {
        scanPreviewTimeout?.let { scanPreviewView?.removeCallbacks(it) }
        scanPreviewObserver?.let { observer ->
            scanPreviewView?.previewStreamState?.removeObserver(observer)
        }
        scanPreviewTimeout = null
        scanPreviewObserver = null
        scanPreviewView = null
        scanPreviewStreaming = false
    }

    private fun finishScanAndRestoreOverlay() {
        if (torchOn) {
            torchOn = false
            barcodeView?.setTorch(false)
            updateTorchUi(false)
        }
        LockWindowManager.cancelAlarmScan()
    }

    override fun toggleTorch(): Boolean {
        torchOn = !torchOn
        barcodeView?.setTorch(torchOn)
        updateTorchUi(torchOn)
        return torchOn
    }

    override fun isTorchOn(): Boolean {
        return torchOn
    }

    private fun updateTorchUi(isOn: Boolean) {
        val btnTorch = findViewById<ImageButton>(R.id.btn_torch)
        val tvHint = findViewById<TextView>(R.id.tv_torch_hint)
        btnTorch?.isSelected = isOn
        if (isOn) {
            btnTorch?.setColorFilter(android.graphics.Color.parseColor("#FFD600"))
            tvHint?.text = "轻触关闭"
            tvHint?.setTextColor(android.graphics.Color.parseColor("#FFD600"))
        } else {
            btnTorch?.clearColorFilter()
            tvHint?.text = "轻触照亮"
            tvHint?.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
        }
        LockWindowManager.updateAlarmOverlayTorchState(isOn)
    }

    private fun startScanMode() {
        isScanMode = true
        // [Fix: overlay detach] setContentView() 会替换整个内容视图，把 inline 挂载的
        // 覆盖层一起带离窗口（它仍保留 parent，但整棵子树已脱窗，此后无法渲染，
        // 表现为扫码成功后白屏且无法退出）。因此必须在替换之前先把覆盖层提升为
        // 独立窗口（TYPE_ACCESSIBILITY_OVERLAY），使其不再依赖 Activity 的内容视图。
        LockWindowManager.ensureAlarmOverlayInWindowMode()
        setContentView(R.layout.activity_alarm_scan)
        // [Fix: 扫码界面沉浸模式] setContentView 重置 systemUiVisibility, 重新应用防止上滑退出
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        if (barcodeView == null) {
            barcodeView = CameraXScanManager(this, this)
        }
        findViewById<Button>(R.id.btn_scan_cancel).setOnClickListener {
            barcodeView?.pause()
            finishScanAndRestoreOverlay()
        }
        val onTorchClick = View.OnClickListener {
            toggleTorch()
        }
        findViewById<ImageButton>(R.id.btn_torch)?.setOnClickListener(onTorchClick)
        findViewById<View>(R.id.layout_scan_torch)?.setOnClickListener(onTorchClick)
        startBarcodeScanner()
    }

    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        LogExporter.logEvent("AlarmOverlay", "ACTIVITY_SETUP_WINDOW sdk=${Build.VERSION.SDK_INT}")
    }

    override fun launchQrScan() {
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        android.util.Log.d("AlarmActivity", "launchQrScan: hasPermission=$hasPermission")
        LogExporter.logEvent("AlarmOverlay", "ACTIVITY_LAUNCH_QR_SCAN hasPermission=$hasPermission")
        if (hasPermission) {
            startScanMode()
            return
        }

        val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isKeyguardLocked = km?.isKeyguardLocked ?: false

        if (isKeyguardLocked) {
            // 在锁屏状态下，系统权限弹窗无法显示在锁屏上方。
            // 提示用户需要解锁才能完成授权，并提供切换备用关卡的选项。
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("需要相机权限")
                .setMessage("扫码关闹钟需要相机权限。由于系统安全限制，请先解锁屏幕以完成授权。")
                .setPositiveButton("解锁去授权") { _, _ ->
                    dismissKeyguardAndRequestCameraPermission(km)
                }
                .setNegativeButton("切换做题关闹钟") { _, _ ->
                    fallbackToAlternativeChallenge()
                }
                .setCancelable(false)
                .show()
        } else {
            requestCameraPermissionSafely()
        }
    }

    private fun dismissKeyguardAndRequestCameraPermission(km: KeyguardManager?) {
        if (km != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    super.onDismissSucceeded()
                    runOnUiThread {
                        requestCameraPermissionSafely()
                    }
                }

                override fun onDismissCancelled() {
                    super.onDismissCancelled()
                    runOnUiThread {
                        Toast.makeText(this@AlarmActivity, "未解锁屏幕，已切换为做题挑战", Toast.LENGTH_SHORT).show()
                        fallbackToAlternativeChallenge()
                    }
                }

                override fun onDismissError() {
                    super.onDismissError()
                    runOnUiThread {
                        fallbackToAlternativeChallenge()
                    }
                }
            })
        } else {
            requestCameraPermissionSafely()
        }
    }

    private fun requestCameraPermissionSafely() {
        isLaunchingScanner = true
        AlarmChallengeEmergencyGuardService.setPermissionRequestInProgress(true)
        android.util.Log.d("AlarmActivity", "launchQrScan: requesting CAMERA permission safely")
        LockWindowManager.hideAlarmOverlayForPermission()
        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun fallbackToAlternativeChallenge() {
        LockWindowManager.showAlarmOverlayIfHidden()
        LockWindowManager.fallbackFromQrToMath()
        Toast.makeText(this, "未获得相机权限，已为您切换为做题关闹钟", Toast.LENGTH_SHORT).show()
    }

    override fun onAlarmOverlayFinished() {
        AlarmChallengeEmergencyGuardService.stop(this)
        AlarmWindowManager.dismiss()
        finishAndRemoveTask()
    }

    @Suppress("MissingSuperCall", "GestureBackNavigation")
    override fun onBackPressed() {
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                LockAlarmOverlayController.requestSnoozeFromHardware("VOLUME_KEY")
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            if (LockAlarmOverlayController.requestSnoozeFromHardware("VOLUME_KEY")) return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // [Fix: 扫码界面防逃脱] 扫码期间也允许兜底重启，传 start_scan=true 保持扫码状态。
        // 经由挑战页“应急使用”显式打开白名单 App 时必须放行，否则锁屏模式会立刻把自己拉回。
        if (!isLaunchingScanner &&
            !AlarmChallengeEmergencyGuardService.isEmergencyAccessInProgress() &&
            (AlarmScheduler.isAlarmActive(this) || LockWindowManager.isOverlayActive)
        ) {
            val intent = Intent(this, AlarmActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                if (isScanMode) {
                    putExtra("start_scan", true)
                }
            }
            startActivity(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        android.util.Log.d("AlarmActivity", "onNewIntent called, alarmId=$alarmId, startScan=${intent.getBooleanExtra("start_scan", false)}")
        if (!AlarmOverlayRequirement.isGranted(this)) {
            Log.w("AlarmActivity", "onNewIntent: overlay permission missing, refusing page challenge")
            Toast.makeText(this, AlarmOverlayRequirement.MISSING_RUNTIME_MESSAGE, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        alarmId = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
        isLockedScreenMode = intent.getBooleanExtra("is_locked_screen", isLockedScreenMode)
        val requestedNewSession = intent.getBooleanExtra("new_alarm_session", false)

        if (requestedNewSession) {
            barcodeView?.pause()
            scanGeneration++
            scanSessionToken = AlarmScheduler.getActiveScheduleToken(this)
        }

        if (intent.getBooleanExtra("start_scan", false)) {
            // SINGLE_TOP 下收到扫码请求：直接进入扫码模式
            LockWindowManager.reattachAlarmController(this)
            startScanMode()
            return
        }
        if (intent.getBooleanExtra("request_camera_permission", false)) {
            launchQrScan()
            return
        }

        if (requestedNewSession) {
            newAlarmSession = true
            LockWindowManager.showAlarmOverlay(
                this,
                alarmId,
                snoozeMinutes,
                this,
                useInline = isLockedScreenMode,
                newSession = true
            )
            newAlarmSession = false
            finishExternalOverlayHostIfNeeded()
            return
        }

        LockWindowManager.reattachAlarmController(this)
        LockWindowManager.showAlarmOverlayIfHidden()
        if (finishExternalOverlayHostIfNeeded()) return
        // [Fix: scan-vibration-guard] 扫码模式下也需要确保振动服务运行
        if (alarmId >= 0 && AlarmScheduler.isAlarmActive(this)) {
            AlarmVibrationService.start(this, alarmId)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!AlarmOverlayRequirement.isGranted(this)) {
            Log.w("AlarmActivity", "onResume: overlay permission missing, refusing page challenge")
            Toast.makeText(this, AlarmOverlayRequirement.MISSING_RUNTIME_MESSAGE, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (finishExternalOverlayHostIfNeeded()) return
        LockWindowManager.reattachAlarmController(this)
        if (isScanMode) {
            if (scanPreviewStreaming) barcodeView?.resume()
        } else {
            LockWindowManager.showAlarmOverlayIfHidden()
        }
        // [Fix: scan-vibration-guard] 扫码模式下也需要确保振动服务运行。
        // 之前 !isScanMode 条件导致扫码时 Activity 层完全不做兜底，
        // 一旦 Recovery alarm 未续期且 Vivo 熄屏回收了振动服务，
        // 振动就永久停止。
        if (alarmId >= 0 && AlarmScheduler.isAlarmActive(this)) {
            AlarmVibrationService.start(this, alarmId)
        }
    }

    private fun finishExternalOverlayHostIfNeeded(): Boolean {
        if (!shouldFinishExternalAlarmHost(isScanMode, isLockedScreenMode, isLaunchingScanner)) {
            return false
        }
        Log.i("AlarmActivity", "finishExternalOverlayHostIfNeeded: external overlay owns the UI")
        LogExporter.logEvent("AlarmOverlay", "ACTIVITY_FINISH_EXTERNAL_HOST")
        finishAndRemoveTask()
        return true
    }

    override fun onPause() {
        super.onPause()
        if (isScanMode) {
            barcodeView?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanGeneration++
        clearScanPreviewObservation()
        barcodeView?.pause()
        barcodeView?.release()
        barcodeView = null
        if (isScanMode) {
            LockWindowManager.setAlarmOverlayPassthrough(false)
        }
        LockWindowManager.setLockScreenVisibleForAlarmScan(true)
        LockWindowManager.detachAlarmController(this)
    }
}
