package com.decikeeper.app.ui

import android.content.Context
import android.os.UserManager
import com.decikeeper.app.R
import com.decikeeper.app.testutil.CeLockedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Direct Boot 红线：未解锁时 ThemeManager 不得触碰 CE SharedPreferences。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ThemeManagerDirectBootTest {

    @Test
    fun `locked user gets default theme without touching CE storage`() {
        val app = RuntimeEnvironment.getApplication()
        val userManager = app.getSystemService(UserManager::class.java)
        Shadows.shadowOf(userManager).setUserUnlocked(false)
        try {
            val ctx = CeLockedContext(app)
            assertEquals(R.style.Theme_DeciKeeper, ThemeManager.themeRes(ctx))
            assertEquals("summer", ThemeManager.currentKey(ctx))
            assertTrue(ThemeManager.getColorAttr(ctx, R.attr.zzPageBg) != 0)
            assertTrue("CE accessed while locked: ${ctx.ceAccess}", ctx.ceAccess.isEmpty())
        } finally {
            Shadows.shadowOf(userManager).setUserUnlocked(true)
        }
    }

    @Test
    fun `unlocked user still reads persisted season`() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("decikeeper_ui_theme", Context.MODE_PRIVATE)
            .edit().putString("theme", "winter").commit()
        assertEquals(R.style.Theme_DeciKeeper_Winter, ThemeManager.themeRes(app))
        app.getSharedPreferences("decikeeper_ui_theme", Context.MODE_PRIVATE).edit().clear().commit()
    }
}
