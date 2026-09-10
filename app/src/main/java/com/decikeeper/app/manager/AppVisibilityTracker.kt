/**
 * =================================================================================
 * [Context Header]
 * * @file: AppVisibilityTracker.kt
 * * @role: Domain Skill
 * * @description: Tracks whether the Home screen is currently visible.
 * * @responsibilities:
 * 1. Store HomeFragment visibility state.
 * 2. Provide a lightweight read API for background services.
 * * @context:
 * - 上游: HomeFragment
 * - 下游: A11yGuardService
 * * @constraints (必读):
 * 1. Thread-safe reads/writes.
 * 2. No Android UI dependencies.
 * 3. Keep state minimal.
 * =================================================================================
 */
package com.decikeeper.app.manager

import java.util.concurrent.atomic.AtomicBoolean

object AppVisibilityTracker {
    private val homeVisible = AtomicBoolean(false)

    fun setHomeVisible(visible: Boolean) {
        homeVisible.set(visible)
    }

    fun isHomeVisible(): Boolean = homeVisible.get()
}
