package com.decikeeper.app.oem

import android.os.Build
import java.util.Locale

/**
 * 厂商适配统一门面管理器。
 * 负责检测当前运行设备的 OEM 厂商并分发至对应具体 [OemAdapter] 实现。
 * 支持单元测试 mock 覆盖与快速恢复。
 */
object OemManager {

    private var mockAdapter: OemAdapter? = null

    /** 获取当前机型适配器，测试优先使用 mockAdapter */
    val current: OemAdapter
        get() = mockAdapter ?: detectedAdapter

    private val detectedAdapter: OemAdapter by lazy {
        resolveAdapter(
            manufacturer = Build.MANUFACTURER,
            brand = Build.BRAND
        )
    }

    /**
     * 纯函数解析适配器，便于单元测试针对不同厂商与品牌字符串验证
     */
    fun resolveAdapter(manufacturer: String?, brand: String?): OemAdapter {
        val m = (manufacturer ?: "").lowercase(Locale.ROOT)
        val b = (brand ?: "").lowercase(Locale.ROOT)

        return when {
            m.contains("vivo") || m.contains("iqoo") || b.contains("vivo") || b.contains("iqoo") ->
                VivoOemAdapter()

            m.contains("oppo") || m.contains("oneplus") || m.contains("realme") ||
                    b.contains("oppo") || b.contains("oneplus") || b.contains("realme") ->
                OppoOemAdapter()

            else ->
                DefaultOemAdapter()
        }
    }

    fun setMockAdapterForTesting(adapter: OemAdapter?) {
        mockAdapter = adapter
    }

    fun resetForTesting() {
        mockAdapter = null
    }
}
