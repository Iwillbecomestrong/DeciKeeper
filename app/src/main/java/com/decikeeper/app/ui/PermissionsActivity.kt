package com.decikeeper.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.decikeeper.app.R
import com.decikeeper.app.manager.AlarmPowerOffProtectionManager
import com.decikeeper.app.manager.AntiUninstallManager
import com.decikeeper.app.manager.CreditManager
import com.decikeeper.app.manager.PermissionManager
import com.decikeeper.app.manager.RecentTasksHideManager
import com.decikeeper.app.manager.RomPermissionDetector
import com.decikeeper.app.manager.SettingsNavigator
import com.decikeeper.app.oem.OemManager
import com.google.android.material.button.MaterialButton

class PermissionsActivity : AppCompatActivity() {

    private var layoutSections: LinearLayout? = null
    private val refreshCallbacks = mutableListOf<() -> Unit>()

    private val cameraPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) {
        refreshAll()
    }

    private var installedAppsRequestedAt = 0L

    private val installedAppsPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        // 系统不再弹窗时会瞬间返回拒绝，此时转去应用详情页手动开启
        if (!granted && System.currentTimeMillis() - installedAppsRequestedAt < 300) {
            openAppDetailsSettings()
        }
        refreshAll()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeManager.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)

        findViewById<ImageView>(R.id.btn_permissions_back)?.setOnClickListener {
            finish()
        }

        layoutSections = findViewById(R.id.layout_permissions_sections)
        buildSections()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun refreshAll() {
        for (callback in refreshCallbacks) {
            callback.invoke()
        }
    }

    private fun showRecentsLockGuideDialog(ctx: Context) {
        val extraWindowTip = if (SettingsNavigator.isOppoOrOnePlusOrRealme()) {
            "\n\n• OPPO 强效保活妙招：\n  ColorOS 支持将决定守护设为「闪达小窗/迷你浮窗」常驻桌面或侧边栏，小窗模式下可完全免疫多任务一键划杀！"
        } else {
            ""
        }
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle("🔒 多任务卡片加锁指引")
            .setMessage(
                "• 作用：防止系统一键清理后台时误杀决定守护；\n\n" +
                "• 操作步骤：\n" +
                "  1. 呼出手机的多任务（最近任务）界面；\n" +
                "  2. 找到「决定守护」卡片；\n" +
                "  3. 下拉卡片，或长按/点击卡片右上角菜单；\n" +
                "  4. 点击【加锁】（卡片上出现 🔒 小锁图标）；" +
                extraWindowTip +
                "\n\n• 设置完成后，回到这里勾选「已开启」即可。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun safeLaunch(intent: Intent) {
        try {
            RecentTasksHideManager.markNavigating()
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (_: Exception) {
            RecentTasksHideManager.clearNavigating()
            Toast.makeText(this, "无法直接打开设置页面，请前往系统设置手动开启", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppDetailsSettings() {
        try {
            RecentTasksHideManager.markNavigating()
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            RecentTasksHideManager.clearNavigating()
            Toast.makeText(this, "无法直接打开应用详情页面", Toast.LENGTH_SHORT).show()
        }
    }

    private sealed class PermItem {
        abstract val title: String
        abstract val desc: String

        /** 系统能自动检测状态的权限 */
        data class Auto(
            override val title: String,
            override val desc: String,
            val actionLabel: String = "去开启",
            val isGranted: (Context) -> Boolean,
            val onAction: (Context) -> Unit
        ) : PermItem()

        /** 系统无法自动检测的权限设置：展示跳转按钮 + 复选框（矩形框） */
        data class Manual(
            override val title: String,
            override val desc: String,
            val actionLabel: String = "去设置",
            val checkboxText: String = "已开启",
            val isConfirmed: (Context) -> Boolean,
            val onConfirmChanged: (Context, Boolean) -> Unit,
            val onAction: (Context) -> Unit
        ) : PermItem()
    }

    private data class PermSection(
        val sectionTitle: String,
        val items: List<PermItem>
    )

    private fun buildSections() {
        val container = layoutSections ?: return
        container.removeAllViews()
        refreshCallbacks.clear()

        val ctx = this
        val density = resources.displayMetrics.density
        val oem = OemManager.current
        val batteryLabel = oem.getBatteryOptimizationLabel()
        val autostartLabel = oem.getAutostartLabel()
        val isChineseRom = SettingsNavigator.isChineseRomDevice()
        val isDeviceAdminRecommended = oem.isDeviceAdminRecommended()

        val sections = listOf(
            PermSection(
                sectionTitle = "一、基础运行（日常拦截与常驻保活）",
                items = listOf(
                    PermItem.Auto(
                        title = "悬浮窗权限",
                        desc = "受限拦截提示与闹钟挑战全屏覆盖",
                        isGranted = { PermissionManager.hasOverlayPermission(it) },
                        onAction = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                safeLaunch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                            }
                        }
                    ),
                    PermItem.Auto(
                        title = "通知权限",
                        desc = "定时提醒与前台常驻服务状态通知",
                        isGranted = { PermissionManager.hasNotificationPermission(it) },
                        onAction = {
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                                }
                            } else {
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                            }
                            safeLaunch(intent)
                        }
                    ),
                    PermItem.Auto(
                        title = "后台耗电管理（$batteryLabel）",
                        desc = "减少系统省电限制，防止核心守护与闹钟被清理",
                        actionLabel = "去设置",
                        isGranted = { PermissionManager.isBatteryOptimizationExempt(it) },
                        onAction = {
                            if (!SettingsNavigator.openBatteryKeepAliveSettings(it)) {
                                SettingsNavigator.openBatteryOptimization(it)
                            }
                        }
                    ),
                    PermItem.Manual(
                        title = "自启动（$autostartLabel）",
                        desc = "允许开机或手机重启后自动恢复守护与闹钟",
                        actionLabel = "去设置",
                        checkboxText = "已开启",
                        isConfirmed = {
                            val prefs = it.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
                            prefs.getBoolean("auto_start_confirmed", false) ||
                                    RomPermissionDetector.detectManualPermission(it, RomPermissionDetector.ManualPermission.AUTO_START) == true
                        },
                        onConfirmChanged = { c, checked ->
                            val prefs = c.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
                            if (checked) {
                                prefs.edit()
                                    .putBoolean("auto_start_confirmed", true)
                                    .putLong("auto_start_confirmed_at", System.currentTimeMillis())
                                    .apply()
                            } else {
                                prefs.edit()
                                    .remove("auto_start_confirmed")
                                    .remove("auto_start_confirmed_at")
                                    .apply()
                            }
                        },
                        onAction = {
                            SettingsNavigator.openAutostartSettings(it)
                        }
                    ),
                    PermItem.Manual(
                        title = "多任务卡片加锁（防误清理）",
                        desc = "防止系统一键清理后台任务时误杀决定守护",
                        actionLabel = "查看指引",
                        checkboxText = "已加锁",
                        isConfirmed = {
                            val prefs = it.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
                            prefs.getBoolean("recents_lock_confirmed", false)
                        },
                        onConfirmChanged = { c, checked ->
                            val prefs = c.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
                            if (checked) {
                                prefs.edit()
                                    .putBoolean("recents_lock_confirmed", true)
                                    .putLong("recents_lock_confirmed_at", System.currentTimeMillis())
                                    .apply()
                            } else {
                                prefs.edit()
                                    .remove("recents_lock_confirmed")
                                    .remove("recents_lock_confirmed_at")
                                    .apply()
                            }
                        },
                        onAction = {
                            showRecentsLockGuideDialog(it)
                        }
                    )
                )
            ),
            PermSection(
                sectionTitle = "二、挑战闹钟（防漏响与起床核验）",
                items = buildList {
                    add(
                        PermItem.Manual(
                            title = "睡眠待机优化（系统设置）",
                            desc = "关闭夜间深睡省电冻结，避免闹钟漏响或延迟",
                            actionLabel = "去关闭",
                            checkboxText = "已关闭",
                            isConfirmed = {
                                val prefs = it.getSharedPreferences("decikeeper_oem_guide", Context.MODE_PRIVATE)
                                prefs.getBoolean("sleep_standby_confirmed", false) || prefs.getBoolean("vivo_sleep_standby_confirmed", false)
                            },
                            onConfirmChanged = { c, checked ->
                                val prefs = c.getSharedPreferences("decikeeper_oem_guide", Context.MODE_PRIVATE)
                                prefs.edit()
                                    .putBoolean("sleep_standby_confirmed", checked)
                                    .putBoolean("vivo_sleep_standby_confirmed", checked)
                                    .apply()
                            },
                            onAction = {
                                SettingsNavigator.openSleepOptimizationSettings(it)
                            }
                        )
                    )
                    add(
                        PermItem.Auto(
                            title = "相机权限",
                            desc = "仅用于扫码挑战核验二维码，设备本地瞬时比对",
                            actionLabel = "去开启",
                            isGranted = {
                                ContextCompat.checkSelfPermission(it, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                            },
                            onAction = {
                                if (ContextCompat.checkSelfPermission(it, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                    cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                                } else {
                                    openAppDetailsSettings()
                                }
                            }
                        )
                    )
                }
            ),
            PermSection(
                sectionTitle = "三、时间规则（应用使用识别）",
                items = listOf(
                    PermItem.Auto(
                        title = "使用情况访问权限",
                        desc = "在手机本地实时识别前台应用包名，用于时间规则拦截",
                        actionLabel = "去开启",
                        isGranted = { PermissionManager.hasUsageStatsPermission(it) },
                        onAction = {
                            safeLaunch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                    ),
                    PermItem.Auto(
                        title = "读取应用列表",
                        desc = "配置时间规则时展示应用列表以供勾选，仅保存在本地",
                        actionLabel = "去开启",
                        isGranted = { PermissionManager.hasInstalledAppsPermission(it) },
                        onAction = {
                            installedAppsRequestedAt = System.currentTimeMillis()
                            installedAppsPermissionLauncher.launch(PermissionManager.PERMISSION_GET_INSTALLED_APPS)
                        }
                    )
                )
            ),
            PermSection(
                sectionTitle = "四、增强能力（高级防绕过，按需开启）",
                items = buildList {
                    add(
                        PermItem.Auto(
                            title = "无障碍服务",
                            desc = "提供防卸载、防关闭关键权限等高阶防护能力",
                            actionLabel = "去开启",
                            isGranted = {
                                AlarmPowerOffProtectionManager.isAccessibilityServiceEnabled(it)
                            },
                            onAction = {
                                safeLaunch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        )
                    )
                    if (isChineseRom) {
                        add(
                            PermItem.Manual(
                                title = "关联启动（系统设置）",
                                desc = "无障碍配套设置，防止系统内存清理后服务失活",
                                actionLabel = "去设置",
                                checkboxText = "已开启",
                                isConfirmed = {
                                    val prefs = it.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
                                    prefs.getBoolean("assoc_start_confirmed", false) ||
                                            RomPermissionDetector.detectManualPermission(it, RomPermissionDetector.ManualPermission.ASSOC_START) == true
                                },
                                onConfirmChanged = { c, checked ->
                                    val prefs = c.getSharedPreferences(CreditManager.PREFS_NAME, Context.MODE_PRIVATE)
                                    if (checked) {
                                        prefs.edit()
                                            .putBoolean("assoc_start_confirmed", true)
                                            .putLong("assoc_start_confirmed_at", System.currentTimeMillis())
                                            .apply()
                                    } else {
                                        prefs.edit()
                                            .remove("assoc_start_confirmed")
                                            .remove("assoc_start_confirmed_at")
                                            .apply()
                                    }
                                },
                                onAction = {
                                    SettingsNavigator.openAssociateStartSettings(it)
                                }
                            )
                        )
                    }
                    add(
                        PermItem.Auto(
                            title = "锁屏显示",
                            desc = "保证挑战闹钟与防关机界面在锁屏状态下正常覆盖",
                            actionLabel = "去设置",
                            isGranted = {
                                AlarmPowerOffProtectionManager.isLockScreenDisplayReady(it)
                            },
                            onAction = {
                                openAppDetailsSettings()
                            }
                        )
                    )
                    // 仅在明确适配推荐该特性的机型（如 OPPO / ColorOS）展示设备管理器；vivo 等机型默认不展示
                    if (isDeviceAdminRecommended) {
                        add(
                            PermItem.Auto(
                                title = "设备管理器（防卸载锁）",
                                desc = "系统底层防卸载增强锁，防止系统设置或桌面直接卸载",
                                actionLabel = "去激活",
                                isGranted = {
                                    AntiUninstallManager.isDeviceAdminActive(it)
                                },
                                onAction = {
                                    safeLaunch(AntiUninstallManager.getDeviceAdminIntent(it))
                                }
                            )
                        )
                    }
                }
            )
        )

        for (section in sections) {
            val card = CardView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (10 * density).toInt()
                }
                radius = 14 * density
                cardElevation = 0f
                setCardBackgroundColor(ThemeManager.getColorAttr(ctx, R.attr.zzSurface))
            }

            val cardInner = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            }

            val tvSecTitle = TextView(ctx).apply {
                text = section.sectionTitle
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
                setPadding(0, 0, 0, (6 * density).toInt())
            }
            cardInner.addView(tvSecTitle)

            for ((index, item) in section.items.withIndex()) {
                if (index > 0) {
                    val divider = View(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            (0.8f * density).toInt().coerceAtLeast(1)
                        ).apply {
                            topMargin = (5 * density).toInt()
                            bottomMargin = (5 * density).toInt()
                        }
                        setBackgroundColor(ThemeManager.getColorAttr(ctx, R.attr.zzLine))
                    }
                    cardInner.addView(divider)
                }

                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
                }

                val textCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        rightMargin = (8 * density).toInt()
                    }
                }

                val tvItemTitle = TextView(ctx).apply {
                    text = item.title
                    textSize = 13.5f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
                }

                val tvItemDesc = TextView(ctx).apply {
                    text = item.desc
                    textSize = 11f
                    setPadding(0, (2 * density).toInt(), 0, 0)
                    setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzMuted))
                }

                textCol.addView(tvItemTitle)
                textCol.addView(tvItemDesc)
                row.addView(textCol)

                when (item) {
                    is PermItem.Auto -> {
                        val tvBadge = TextView(ctx).apply {
                            text = "已开启 ✓"
                            textSize = 12f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                            setTextColor(android.graphics.Color.parseColor("#388E3C"))
                            setPadding((6 * density).toInt(), (3 * density).toInt(), (6 * density).toInt(), (3 * density).toInt())
                            visibility = View.GONE
                        }

                        val btnAction = MaterialButton(ctx).apply {
                            text = item.actionLabel
                            isAllCaps = false
                            textSize = 11.5f
                            setPadding((10 * density).toInt(), 0, (10 * density).toInt(), 0)
                            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
                            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzSurface2))
                            cornerRadius = (8 * density).toInt()
                            minHeight = (32 * density).toInt()
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                (32 * density).toInt()
                            )
                            setOnClickListener {
                                item.onAction(ctx)
                            }
                        }

                        row.addView(tvBadge)
                        row.addView(btnAction)

                        val refreshItem = {
                            val ready = item.isGranted(ctx)
                            if (ready) {
                                tvBadge.visibility = View.VISIBLE
                                btnAction.visibility = View.GONE
                            } else {
                                tvBadge.visibility = View.GONE
                                btnAction.visibility = View.VISIBLE
                            }
                        }
                        refreshCallbacks.add(refreshItem)
                    }

                    is PermItem.Manual -> {
                        val actionsContainer = LinearLayout(ctx).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                        }

                        val btnAction = MaterialButton(ctx).apply {
                            text = item.actionLabel
                            isAllCaps = false
                            textSize = 11.5f
                            setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
                            backgroundTintList = android.content.res.ColorStateList.valueOf(ThemeManager.getColorAttr(ctx, R.attr.zzSurface2))
                            cornerRadius = (8 * density).toInt()
                            minHeight = (32 * density).toInt()
                            layoutParams = LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.WRAP_CONTENT,
                                (32 * density).toInt()
                            ).apply {
                                rightMargin = (4 * density).toInt()
                            }
                            setOnClickListener {
                                item.onAction(ctx)
                            }
                        }

                        var isInternalUpdating = false
                        val checkBox = CheckBox(ctx).apply {
                            text = item.checkboxText
                            textSize = 12f
                            setTextColor(ThemeManager.getColorAttr(ctx, R.attr.zzText))
                            setPadding(0, 0, 0, 0)
                            setOnCheckedChangeListener { _, isChecked ->
                                if (isInternalUpdating) return@setOnCheckedChangeListener
                                item.onConfirmChanged(ctx, isChecked)
                                btnAction.visibility = if (isChecked) View.GONE else View.VISIBLE
                            }
                        }

                        actionsContainer.addView(btnAction)
                        actionsContainer.addView(checkBox)
                        row.addView(actionsContainer)

                        val refreshItem = {
                            val confirmed = item.isConfirmed(ctx)
                            isInternalUpdating = true
                            checkBox.isChecked = confirmed
                            btnAction.visibility = if (confirmed) View.GONE else View.VISIBLE
                            isInternalUpdating = false
                        }
                        refreshCallbacks.add(refreshItem)
                    }
                }

                cardInner.addView(row)
            }

            card.addView(cardInner)
            container.addView(card)
        }

        refreshAll()
    }
}
