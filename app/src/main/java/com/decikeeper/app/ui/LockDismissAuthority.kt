package com.decikeeper.app.ui

/**
 * The authorized exits allowed to remove an already displayed blacklist lock.
 * Spec v1.0 establishes: entering allowed time window (Gap or AppWindow allowed range)
 * is a genuine rule release; the lock screen unblocks automatically when restrictions clear.
 */
enum class LockDismissAuthority {
    REST,
    TEMPORARY_ACCESS,
    WEEKLY_PRIVILEGE,
    RETURN_HOME,
    NATURAL_RULE_RELEASE
}
