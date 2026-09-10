package com.decikeeper.app.manager

/**
 * 时间规则页面的进入门禁。
 * 使用情况访问是规则执行所需权限，未授权时不应让用户进入规则配置页。
 */
object TimeRulesAccessGate {
    internal fun shouldEnter(hasUsageAccess: Boolean): Boolean = hasUsageAccess
}
