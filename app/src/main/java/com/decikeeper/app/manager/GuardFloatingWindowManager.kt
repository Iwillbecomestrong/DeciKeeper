/**
 * =================================================================================
 * [Context Header]
 * * @file: GuardFloatingWindowManager.kt
 * * @role: Domain Skill / System Window Protection
 * * @description: 常驻守护微型小浮窗（自律守护盾）。
 * * @responsibilities:
 * 1. 利用 WindowManager 的 TYPE_APPLICATION_OVERLAY 挂载常驻系统窗口，
 *    使 Android 底层 AMS 将进程评定为 VISIBLE_APP，彻底免疫多任务卡片误划/强杀导致的 force-stop！
 * 2. 贴边自律小盾牌交互：轻触查看守护状态，支持手指随意拖拽与屏幕左右边缘磁吸吸附。
 * 3. 贴边静止自动低透光（透明度 0.45），极简不挡视线。
 * * @constraints:
 * 1. 依赖 SYSTEM_ALERT_WINDOW 权限，未授权时安全降级不崩溃。
 * 2. 必须在主线程操作 WindowManager。
 * =================================================================================
 */
package com.decikeeper.app.manager

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

object GuardFloatingWindowManager {

    private const val TAG = "GuardFloatingWindow"
    private const val PREFS_NAME = "guard_floating_window_prefs"
    private const val KEY_ENABLED = "floating_guard_enabled"
    private const val KEY_LAST_X = "floating_last_x"
    private const val KEY_LAST_Y = "floating_last_y"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var floatingRootView: View? = null
    private var windowManager: WindowManager? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var isShowing = false

    /**
     * 判断当前是否已开启常驻守护小浮窗功能（默认推荐开启以实现保活防护）。
     */
    fun isFloatingGuardEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    /**
     * 设置常驻守护小浮窗开启/关闭状态。
     */
    fun setFloatingGuardEnabled(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()

        if (enabled) {
            show(appContext)
        } else {
            hide(appContext)
        }
    }

    /**
     * 检查是否已具备悬浮窗权限（SYSTEM_ALERT_WINDOW）。
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * 跳转至系统悬浮窗权限授予设置页。
     */
    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            RecentTasksHideManager.markNavigating()
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open overlay permission settings", e)
                RecentTasksHideManager.clearNavigating()
            }
        }
    }

    /**
     * 在屏幕上展示常驻守护小浮窗。
     */
    fun show(context: Context) {
        val appContext = context.applicationContext
        if (!canDrawOverlays(appContext)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted, skipping show")
            return
        }

        mainHandler.post {
            try {
                if (isShowing && floatingRootView != null) return@post

                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post
                windowManager = wm

                val dm = appContext.resources.displayMetrics
                val screenWidth = dm.widthPixels
                val screenHeight = dm.heightPixels

                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val initialX = prefs.getInt(KEY_LAST_X, 0)
                val initialY = prefs.getInt(KEY_LAST_Y, screenHeight / 3)

                val windowType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = initialX
                    y = initialY
                }
                layoutParams = params

                val view = createFloatingPillView(appContext, params, screenWidth)
                floatingRootView = view

                wm.addView(view, params)
                isShowing = true
                Log.i(TAG, "Guard floating window attached successfully! (Process now prioritized as VISIBLE_APP)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add floating guard window", e)
            }
        }
    }

    /**
     * 隐藏并移除常驻守护小浮窗。
     */
    fun hide(context: Context) {
        mainHandler.post {
            try {
                floatingRootView?.let { v ->
                    windowManager?.removeView(v)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove floating guard window", e)
            } finally {
                floatingRootView = null
                isShowing = false
            }
        }
    }

    /**
     * 构建质感贴边自律小盾牌 View。
     */
    private fun createFloatingPillView(
        context: Context,
        params: WindowManager.LayoutParams,
        screenWidth: Int
    ): View {
        val root = FrameLayout(context)

        val dp44 = dpToPx(context, 44f)
        val dp12 = dpToPx(context, 12f)
        val dp8 = dpToPx(context, 8f)

        // 核心微型胶囊容器
        val pillContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp8, dp8, dp8, dp8)

            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6182230")) // 质感暗夜深海色
                cornerRadius = dpToPx(context, 22f).toFloat()
                setStroke(dpToPx(context, 1f), Color.parseColor("#4D5E81AC")) // 优雅边框
            }
            alpha = 0.55f // 默认贴边静止半透明
        }

        // 盾牌图标 / Emoji
        val tvIcon = TextView(context).apply {
            text = "🛡️"
            textSize = 18f
            gravity = Gravity.CENTER
        }
        pillContainer.addView(tvIcon)

        // 展开文字（轻点时展开提示）
        val tvLabel = TextView(context).apply {
            text = " 决定守护中"
            textSize = 12f
            setTextColor(Color.parseColor("#ECEFF4"))
            visibility = View.GONE
        }
        pillContainer.addView(tvLabel)

        root.addView(pillContainer)

        // 自动折叠与低透光任务
        var collapseRunnable: Runnable? = null
        fun scheduleFadeOut() {
            collapseRunnable?.let { mainHandler.removeCallbacks(it) }
            val r = Runnable {
                tvLabel.visibility = View.GONE
                pillContainer.animate().alpha(0.40f).setDuration(400).start()
            }
            collapseRunnable = r
            mainHandler.postDelayed(r, 2500L)
        }

        scheduleFadeOut()

        // 触摸拖拽与左右贴边
        root.setOnTouchListener(object : View.OnTouchListener {
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var initialX = 0
            private var initialY = 0
            private var isMoved = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        collapseRunnable?.let { mainHandler.removeCallbacks(it) }
                        pillContainer.alpha = 0.95f
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - initialTouchX
                        val dy = event.rawY - initialTouchY
                        if (Math.abs(dx) > 10 || Math.abs(dy) > 10) {
                            isMoved = true
                        }
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        try {
                            windowManager?.updateViewLayout(root, params)
                        } catch (e: Exception) {
                            // Ignored during drag
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isMoved) {
                            // 单击事件：展开状态提示
                            if (tvLabel.visibility == View.GONE) {
                                tvLabel.visibility = View.VISIBLE
                                pillContainer.alpha = 0.95f
                                scheduleFadeOut()
                            } else {
                                tvLabel.visibility = View.GONE
                                scheduleFadeOut()
                            }
                        } else {
                            // 拖拽松手：自动吸附至左侧或右侧
                            val currentX = params.x
                            val targetX = if (currentX + dp44 / 2 < screenWidth / 2) {
                                0 // 吸附左侧
                            } else {
                                screenWidth - pillContainer.width // 吸附右侧
                            }
                            params.x = targetX
                            try {
                                windowManager?.updateViewLayout(root, params)
                            } catch (e: Exception) {
                                // Ignored
                            }

                            // 保存最后停留位置
                            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putInt(KEY_LAST_X, params.x)
                                .putInt(KEY_LAST_Y, params.y)
                                .apply()

                            scheduleFadeOut()
                        }
                        return true
                    }
                }
                return false
            }
        })

        return root
    }

    private fun dpToPx(context: Context, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        ).toInt()
    }
}
