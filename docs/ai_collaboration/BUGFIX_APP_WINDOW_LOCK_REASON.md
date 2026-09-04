# Bugfix note: App window lock reason display

Issue: App window lock alone may display as blacklist lock. Dual-lock cases can display correctly because they enter the app-window priority path.

Investigation: LockWindowManager already supports `isAppWindowBlocked -> 应用时间窗锁机`, so the remaining issue is likely a missing flag in the single app-window lock trigger path.

Fix target: audit all showLockScreen callers and ensure app-window-only path passes the app-window lock state instead of blacklist state.
