# OPPO 后台卡片隐藏实验

状态：用户批准小范围实验，尚未通过 PKP110 真机验收，不是正式保护策略。

## 证据与目标

用户日志 2026-09-14 20:56:26：Athena with swipe up → SIGKILL → OplusAppStartupManager prevent restart service → CoreService 仍在 enabled 集合，同时进入 crashed 集合。用户验证锁定最近任务卡片后划掉可以恢复无障碍与防卸载。菜单和下滑均能解除锁定。

验证隐藏自身卡片是否保留后台锁定待遇。不修改保护规则、不新增进程、不拦截其他应用或最近任务界面。

## 实现与回退

- `app/build.gradle.kts`：仅 `-PrecentsExperiment=true` 构建开启实验入口，普通构建默认关闭。
- `MainActivity.kt`、`RecentsExperiment.kt`：同意隐私后，每次回到主页提供隐藏/恢复选择，默认显示；使用 AppTask.setExcludeFromRecents，只处理当前任务，不 finishAndRemoveTask。
- 选择保存在单独实验偏好中，主页重建时重新应用。没有声称获取或设置 OPPO 后台锁定状态。
- 实验期间可随时恢复卡片，因此实验包本身不是防主动绕过的交付。失败时恢复卡片、重新手工锁定，随后保留数据覆盖普通签名包；不得卸载。

## 验收步骤

1. 安装签名实验 Release，选择恢复卡片，在最近任务锁定应用。
2. 回主页选择隐藏；进入最近任务确认无卡片。
3. 一键清理，记录进程、无障碍 enabled/bound/crashed、服务事件与系统 kill/restart 日志；实际验证防卸载拦截，不完成卸载。
4. 从桌面重开、切换应用、重启后分别重复，确认卡片状态和保护能力。桌面 launcher 存在清栈逻辑，必须实测新任务是否保留 OEM 锁定待遇。
5. 恢复卡片验证可回退。隐藏导致待遇丢失则停止采用，不写入正式规格。

## 验证记录

- ADB 后续仅连接 V2425A（vivo），不是目标 OPPO PKP110，未安装；划卡、清理和重启验收仍待目标机。
- AOCI 工具未提供，未声称完成索引认知或维护。
- `:app:testDebugUnitTest :app:assembleRelease -PrecentsExperiment=true --max-workers=4`：最终 BUILD SUCCESSFUL。新增 Robolectric 用例验证隐藏/恢复仅影响当前任务且不结束 Activity；弹窗视觉、偏好跨重启及 OEM 待遇仍需真机。
- 实验 APK：`app/build/outputs/recents-experiment/decikeeper-recents-experiment-release.apk`，apksigner verify 通过，SHA-256 `13ACE3CEA49AE6162D65273A40210B0965C15DFD2E500381C2FDB7330E70083C`。
