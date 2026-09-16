package com.decikeeper.app.ui

import android.content.Context
import com.decikeeper.app.manager.AlarmOverlayRequirement
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = "app/src/main/AndroidManifest.xml")
class AlarmOverlayRequirementTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        ShadowSettings.reset()
    }

    @After
    fun tearDown() {
        ShadowSettings.reset()
    }

    @Test
    fun missingOverlayPermissionBlocksAlarmChallenge() {
        ShadowSettings.setCanDrawOverlays(false)

        assertFalse(AlarmOverlayRequirement.isGranted(context))
    }

    @Test
    fun grantedOverlayPermissionAllowsAlarmChallenge() {
        ShadowSettings.setCanDrawOverlays(true)

        assertTrue(AlarmOverlayRequirement.isGranted(context))
    }

}
