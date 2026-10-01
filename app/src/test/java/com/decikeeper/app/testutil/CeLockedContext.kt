package com.decikeeper.app.testutil

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences

/**
 * 模拟 Direct Boot：CE SharedPreferences 一律抛出与框架一致的 IllegalStateException，
 * 并记录被访问的文件名；DPS（createDeviceProtectedStorageContext）交给 base，保持可读。
 * 使用时需配合 `Shadows.shadowOf(userManager).setUserUnlocked(false)`。
 */
class CeLockedContext(base: Context) : ContextWrapper(base) {
    val ceAccess = mutableListOf<String>()
    val startedForegroundServices = mutableListOf<Intent>()

    override fun getApplicationContext(): Context = this

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences {
        ceAccess.add(name ?: "<null>")
        throw IllegalStateException(
            "SharedPreferences in credential encrypted storage are not available until after user (id 0) is unlocked"
        )
    }

    override fun startForegroundService(service: Intent): android.content.ComponentName? {
        startedForegroundServices.add(service)
        return null
    }
}
