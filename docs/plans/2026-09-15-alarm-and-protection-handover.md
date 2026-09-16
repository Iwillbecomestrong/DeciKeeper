# 交接文档：闹钟体验重构、全局圆角倒角统一与保护功能治理

> **交接对象**：接手开发者 / 后续迭代 AI
> **分支**：`codex/oppo-recents-experiment` → 目标合并分支 `dev`
> **状态**：全部功能与测试已闭环，全量单元测试通过，Release APK 编译签名通过，具备合并至 `dev` 的完整条件。

---

## 1. 任务背景与交付全景

本次迭代在 `codex/oppo-recents-experiment` 分支上完成了以下五大核心维度的功能迭代与缺陷修复：

1. **OPPO / vivo ROM 权限与保活深层适配**：
   - 完善了 OPPO (ColorOS) 与 vivo 系统的多任务、自启动、关联启动以及后台高耗电跳转路由表与指引。
   - 修复了后台耗电跳转至应用详情页并实现优雅降级。
2. **保护功能待关闭状态文案与每日生效时间统一**：
   - 移除了防卸载、浮窗保护、无障碍等处于 PENDING 关闭状态时各模块各自为政的具体时间戳（如 `07:30`）。
   - 统一为规范的 `状态: 待关闭（明日生效）`。
   - 在设置页（`RulesFragment`）增加了「每日生效时间」控制卡片，支持 04:00~12:00 自定义调整与「自动跟随最早结束的勿扰时段」自动同步开关。
   - 在首页（`HomeFragment`）增加了只读状态胶囊标牌（`🕒 每日生效时刻: 08:00`）。
3. **闹钟提示音左右分栏选择器**：
   - 全新设计并实现了 `AlarmRingtonePickerActivity`，采用与黑名单一致的双栏分栏架构。
   - **左栏（系统自带）**：首项为「系统默认闹钟」，后面跟随系统所有铃声，支持单选与试听播放。
   - **右栏（自己导入）**：通过 SAF 框架安全导入本地音频并拷贝到应用沙盒内部受控存储目录（`filesDir/custom_ringtones/`），避免权限过期失效；支持试听与二次确认删除。
   - 打通了默认闹钟配置和单个闹钟添加/编辑对话框中的铃声选择与标题解析（`AlarmRingtoneManager`）。
4. **默认闹钟配置弹窗三按钮重构与全局圆角倒角统一**：
   - 底部操作栏严格固定为从左到右三按钮：**【退出】**、**【保存】**、**【保存并测试】**。
   - 「保存并测试」按钮实现先自动保存全局默认设置，再立即组装配置拉起测试闹钟。
   - 弹窗外框去除 Android 原生直角边框，统一应用 `bg_zz_card` 20dp 圆角卡片背景。
   - 响铃界面的两个「应急应用」按钮、扫码挑战的「打开扫码」和「返回挑战」按钮全部统一应用 `@drawable/bg_zz_pill` 胶囊倒角。
   - 锁屏端确认弹窗（二级应急确认、借用额度确认等）在 `applyEscapeDisciplineColors()` 中统一注入 `bg_zz_card` 圆角背景。
5. **音量键硬件稍后再响拦截修复**：
   - 针对按音量键无法触发稍后再响（仅熄屏键生效）的根因，新建了 `AlarmOverlayRootView`。
   - 在 Window 顶层的 `dispatchKeyEvent` 阶段直接拦截 `KEYCODE_VOLUME_UP` / `KEYCODE_VOLUME_DOWN` / `KEYCODE_VOLUME_MUTE`，抢在系统音量条和子 View 消费前触发稍后再响。
   - 在 `AlarmActivity` 中同样覆写 `dispatchKeyEvent` 提供多层兜底保障。

---

## 2. 关键设计决策与技术细节

### 2.1 闹钟提示音存储与 URI 兼容性
- **本地存储安全性**：SAF 返回的 Content URI 可能在重启或应用重装后丢失临时读取权限。因此在 `AlarmRingtoneManager.importCustomRingtone` 中将文件内容同步拷贝到 `filesDir/custom_ringtones/`，生成应用内可永久访问的 `file://` URI。
- **跨平台与单元测试路径解析**：Robolectric 测试在 Windows 环境下运行时，Android Uri 解析可能会产生 `/C:/...` 或保留反斜杠等差异。`AlarmRingtoneManager` 采用基于自定义目录文件名的相对解析（`getCustomAudioFile`），不强依赖 URI 字符串的绝对路径切片，保证了在 Windows 与 Android 真机上的一致性。

### 2.2 默认闹钟弹窗布局契约保全
- `DefaultAlarmSettingsLayoutContractTest` 严格规定 `updateTestRingtoneVisibility()` 中隐藏模式必须使用 `View.INVISIBLE` 而非 `View.GONE`，以保留布局槽位。
- 在本次三按钮与卡片重构中，完整保留了这一行为，并将三按钮放置在 `ScrollView` 外部底部的固定横向栏，使内容滚动时操作按钮始终吸底常驻。

### 2.3 音量键事件分发拦截原理
- Android 系统中，当应用正在播放音频（MediaPlayer / Ringtone）时，按音量键通常直接被 AudioManager 拦截调节媒体音量，无法下发给 `onKeyDown`。
- 此外，响铃浮窗内部包含 EditText、单选框等可获取焦点的 View，当它们持有焦点时，父容器的 `setOnKeyListener` 不会被调用。
- 通过在自定义顶层 ViewGroup `AlarmOverlayRootView` 中覆写 `dispatchKeyEvent`，所有流经该窗口的按键事件最优先到达该方法，判断为音量加/减/静音键后直接调用 `LockAlarmOverlayController.requestSnoozeFromHardware("VOLUME_KEY")` 并 `return true`，彻底屏蔽了系统音量条弹出与事件丢失。

---

## 3. 分支状态与合并可行性

### 3.1 分支比对分析
- 当前分支：`codex/oppo-recents-experiment`
- 目标主开发分支：`dev`
- **分叉检查**：
  ```powershell
  git log HEAD..dev --oneline
  ```
  结果为空，说明 `dev` 分支没有产生任何领先于当前分支的额外提交，当前分支包含了 `dev` 的全部历史。
- **合并模式**：可以进行干净的 **Fast-Forward 合并**，不存在任何代码冲突。

### 3.2 验证证据
1. **契约测试与专项测试**：
   - `AlarmHardwareSnoozeContractTest`: PASSED
   - `DefaultAlarmSettingsLayoutContractTest`: PASSED
   - `AlarmRingtoneManagerTest`: PASSED
   - `AlarmUiAndHardwareSnoozeTest`: PASSED
2. **全量单元测试**：
   ```powershell
   .\gradlew.bat testDebugUnitTest
   ```
   **结果**：1346+ 个测试全部通过（`BUILD SUCCESSFUL`）。
3. **Release 签名编译构建**：
   ```powershell
   .\gradlew.bat assembleRelease
   ```
   **结果**：编译通过，生成正式签名的 Release APK：
   `app/build/outputs/apk/release/app-release.apk`（约 13.8 MB，时间戳 2026-09-15 19:57:42）。

---

## 4. 合并到 `dev` 分支的推荐操作步骤

建议按如下标准步骤进行提交与合并：

### 步骤 1：在当前分支提交全部工作区改动
```bash
git add app/ docs/
git commit -m "feat(alarm): 重构提示音分栏、默认配置三按钮、全局倒角与音量键稍后再响"
```

### 步骤 2：切换到 `dev` 分支并合并
```bash
git checkout dev
git merge codex/oppo-recents-experiment --ff-only
```

### 步骤 3：验证合并后的 `dev` 分支
```bash
./gradlew.bat testDebugUnitTest
```

---

## 5. 文件变更索引

| 文件 | 变更说明 |
| --- | --- |
| `app/src/main/java/com/decikeeper/app/ui/AlarmOverlayRootView.kt` | [新增] 响铃覆盖层根容器，覆写 `dispatchKeyEvent` 拦截音量键稍后再响 |
| `app/src/main/java/com/decikeeper/app/manager/AlarmRingtoneManager.kt` | [新增] 铃声管理类，处理系统铃声枚举、本地 SAF 音频安全拷贝与持久化 |
| `app/src/main/java/com/decikeeper/app/ui/AlarmRingtonePickerActivity.kt` | [新增] 提示音双栏选择界面（系统自带 vs 自己导入） |
| `app/src/main/res/layout/activity_alarm_ringtone_picker.xml` | [新增] 提示音左右分栏选择器布局 |
| `app/src/main/res/layout/item_ringtone_picker.xml` | [新增] 提示音列表单项布局（单选框、标题、试听胶囊、删除按钮） |
| `app/src/main/res/layout/dialog_test_alarm.xml` | [修改] 增加圆角卡片背景，底部新增固定三按钮（退出、保存、保存并测试） |
| `app/src/main/res/layout/window_alarm_overlay.xml` | [修改] 根标签改为 `AlarmOverlayRootView`，扫码与取消按钮加 `@drawable/bg_zz_pill` |
| `app/src/main/java/com/decikeeper/app/ui/AlarmFragment.kt` | [修改] 绑定提示音选择器、重构默认闹钟配置弹窗三按钮逻辑与圆角窗口 |
| `app/src/main/java/com/decikeeper/app/ui/AlarmActivity.kt` | [修改] 覆写 `dispatchKeyEvent` 拦截音量加/减/静音键 |
| `app/src/main/java/com/decikeeper/app/service/AlarmChallengeEmergencyGuardService.kt` | [修改] 2 个应急应用按钮增加 `@drawable/bg_zz_pill` 胶囊倒角 |
| `app/src/main/java/com/decikeeper/app/ui/LockWindowManager.kt` | [修改] `applyEscapeDisciplineColors` 统一注入 `bg_zz_card` 圆角卡片背景 |
| `app/src/test/java/com/decikeeper/app/AlarmUiAndHardwareSnoozeTest.kt` | [新增] 验证按键拦截与 UI 倒角布局契约的单元测试 |
| `app/src/test/java/com/decikeeper/app/AlarmRingtoneManagerTest.kt` | [新增] 验证铃声管理器生命周期与文件解析的单元测试 |
