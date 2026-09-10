package com.decikeeper.app.ui

/**
 * The only user-visible exits allowed to remove an already displayed blacklist lock.
 * Detection changes, Presence cleanup, OEM/system windows, screen-off and rule transitions
 * are deliberately absent: they may update detection state or trigger an internal rebuild,
 * but they are never unlock authorities.
 */
enum class LockDismissAuthority {
    REST,
    TEMPORARY_ACCESS,
    WEEKLY_PRIVILEGE,
    RETURN_HOME
}
