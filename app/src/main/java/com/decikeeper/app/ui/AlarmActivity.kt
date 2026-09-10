package com.decikeeper.app.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.decikeeper.app.R
import com.decikeeper.app.data.AndroidLogcatNormalEventSink
import com.decikeeper.app.data.DefaultLogDebugConfig
import com.decikeeper.app.data.DefaultNormalEventRenderer
import com.decikeeper.app.data.LogExporter
import com.decikeeper.app.data.NormalEventLogger
import com.decikeeper.app.data.SystemEventClock
import com.decikeeper.app.manager.AlarmProtectionEventBridge
import com.decikeeper.app.manager.AlarmScheduler
import com.decikeeper.app.manager.AlarmWindowManager
import com.decikeeper.app.service.AlarmChallengeEmergencyGuardService
import com.decikeeper.app.service.AlarmVibrationService
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.camera.CameraSettings












class AlarmActivity : AppCompatActivity(), LockWindowManager.AlarmOverlayController {

    private var alarmId: Int = -1
    private var snoozeMinutes: Int = 10
    private var isLaunchingScanner = false
    private var isScanMode = false
    private var isLockedScreenMode = false
    private var newAlarmSession = false
    private var barcodeView: DecoratedBarcodeView? = null
    private var torchOn = false
    private var scanGeneration = 0L
    private var scanSessionToken = ""

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
        setupWindow()
        window.decorView.setBackgroundColor(ThemeManager.getColorAttr(this, R.attr.zzPageBg))

        android.util.Log.i("AlarmActivity", "onCreate: alarmId=$alarmId, is_locked_screen=${intent.getBooleanExtra("is_locked_screen", false)}, start_scan=${intent.getBooleanExtra("start_scan", false)}, request_camera=${intent.getBooleanExtra("request_camera_permission", false)}")
        LogExporter.logEvent("AlarmOverlay", "ACTIVITY_ON_CREATE id=$alarmId locked=${intent.getBooleanExtra("is_locked_screen", false)} startScan=${intent.getBooleanExtra("start_scan", false)} requestCamera=${intent.getBooleanExtra("request_camera_permission", false)}")

        if (intent.getBooleanExtra("start_scan", false)) {
            isScanMode = true
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
            barcodeView = findViewById(R.id.barcode_scanner)
            findViewById<Button>(R.id.btn_scan_cancel).setOnClickListener {
                barcodeView?.pause()
                finishScanAndRestoreOverlay()
            }
            findViewById<ImageButton>(R.id.btn_torch).setOnClickListener {
                toggleTorch()
            }
            // 请求权限或直接开始扫码
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startBarcodeScanner()
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

    private fun startBarcodeScanner() {
        LockWindowManager.setAlarmOverlayPassthrough(true)
        barcodeView?.let { view ->
            scanSessionToken = AlarmScheduler.getActiveScheduleToken(this)
            val callbackGeneration = ++scanGeneration
            view.setCameraSettings(CameraSettings().apply {
                focusMode = CameraSettings.FocusMode.CONTINUOUS
            })
            view.setDecoderFactory(DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE)))
            if (!qrChallengeStartEmitted) {
                qrChallengeStartEmitted = true
                alarmProtectionEventBridge.recordAlarmChallengeStart(
                    alarmId = alarmId.toString(),
                    challengeId = "QR-$alarmId",
                    totalSteps = 1
                )
            }
            view.resume()
            view.decodeContinuous { result ->
                if (result != null) {
                    val activeToken = AlarmScheduler.getActiveScheduleToken(this@AlarmActivity)
                    val sessionStillCurrent = callbackGeneration == scanGeneration &&
                        (alarmId <= 0 || (AlarmScheduler.isAlarmActive(this@AlarmActivity) &&
                            AlarmScheduler.getActiveAlarmId(this@AlarmActivity) == alarmId &&
                            (scanSessionToken.isBlank() || activeToken == scanSessionToken)))
                    if (!sessionStillCurrent) {
                        android.util.Log.w("AlarmActivity", "Ignoring stale QR callback: id=$alarmId generation=$callbackGeneration currentGeneration=$scanGeneration")
                        view.pause()
                        return@decodeContinuous
                    }
                    view.pause()
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
                    // 两阶段解锁且锁屏显示时：不能 finish，必须把已切换到第二关的 mAlarmView
                    // 重新内嵌回 Activity，否则用户看到的是空白锁屏，需要手动解锁才能完成第二关。
                    if (isLockedScreenMode) {
                        LockWindowManager.showAlarmOverlayInlineInActivity(this, this)
                    } else {
                        finishAndRemoveTask()
                    }
                }
            }
        }
    }

    private fun finishScanAndRestoreOverlay() {
        barcodeView?.pause()
        isScanMode = false
        LockWindowManager.setAlarmOverlayPassthrough(false)
        LockWindowManager.showAlarmOverlayIfHidden()
        finishAndRemoveTask()
    }

    private fun toggleTorch() {
        torchOn = !torchOn
        if (torchOn) {
            barcodeView?.setTorchOn()
        } else {
            barcodeView?.setTorchOff()
        }
    }

    private fun startScanMode() {
        isScanMode = true
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
        barcodeView = findViewById(R.id.barcode_scanner)
        findViewById<Button>(R.id.btn_scan_cancel).setOnClickListener {
            barcodeView?.pause()
            finishScanAndRestoreOverlay()
        }
        findViewById<ImageButton>(R.id.btn_torch).setOnClickListener {
            toggleTorch()
        }
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

    override fun onBackPressed() {
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
        alarmId = intent.getIntExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
        val requestedNewSession = intent.getBooleanExtra("new_alarm_session", false)

        if (requestedNewSession) {
            barcodeView?.pause()
            scanGeneration++
            scanSessionToken = AlarmScheduler.getActiveScheduleToken(this)
        }

        if (intent.getBooleanExtra("start_scan", false)) {
            // SINGLE_TOP 下收到扫码请求：直接进入扫码模式
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
            return
        }

        LockWindowManager.reattachAlarmController(this)
        LockWindowManager.showAlarmOverlayIfHidden()
        // [Fix: scan-vibration-guard] 扫码模式下也需要确保振动服务运行
        if (alarmId >= 0 && AlarmScheduler.isAlarmActive(this)) {
            AlarmVibrationService.start(this, alarmId)
        }
    }

    override fun onResume() {
        super.onResume()
        LockWindowManager.reattachAlarmController(this)
        if (!isScanMode) {
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

    override fun onPause() {
        super.onPause()
        if (!isScanMode) {
            // 非扫码模式下 pause 时暂停条码扫描器
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanGeneration++
        barcodeView?.pause()
        barcodeView = null
        if (isScanMode) {
            LockWindowManager.setAlarmOverlayPassthrough(false)
        }
        LockWindowManager.detachAlarmController(this)
    }
}
