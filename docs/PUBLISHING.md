# 决定守护 (DeciKeeper) 分发与双推指南 (PUBLISHING)

> **定位**：本文档是 Gitee / GitHub 双仓库初始化、构建产物分发与 Release 发布的
> **唯一可执行清单**。
> **不上架任何应用商店**，只走自建发行渠道。
> 最后核验：2026-09-04（下表状态与远端地址需要重新核对后再依赖；仓库名与产品名目前并不一致）

---

## 1. 当前状态（已实测，非推测）

| 项目 | 状态 | 证据 |
|:---|:---|:---|
| GitHub 仓库 | ✅ 已建立并已推送 | `git@github.com:Iwillbecomestrong/ZhiZhi.git`（仓库名尚未随产品更名调整） |
| Gitee 账号 | ✅ SSH 已验证可用 | `Hi 陈永争(@my_room_welcome_you)! You've successfully authenticated` |
| Gitee 仓库 | ❌ **尚未创建** | `git ls-remote` 返回 `404 not found` |
| 本地 `main` | ✅ 已与远端同步 | 原落后 9 个提交，已快进至 `79ee151` |
| debug APK | ✅ 构建成功且校验通过 | `app/build/outputs/apk/debug/app-debug.apk`，9.3 MB，997 条目 |
| 单元测试 | ✅ 全通过 | `38 tests OK (skipped=6)`，6 个跳过为无真机日志样本的预期跳过 |
| GitHub Actions | ✅ 已配置 | `.github/workflows/android-build.yml`（自建 Windows runner） |

---

## 2. 双推配置：分步执行

### 第 0 步 · 在 Gitee 网页创建**空**仓库

访问 <https://gitee.com/projects/new>，按以下填写：

- 仓库名称：`DeciKeeper`（GitHub 侧当前仍为 `ZhiZhi`，改名需要同步更新远端地址与 README 徽章）
- 归属：`my_room_welcome_you`
- 是否开源：**公开**
- 开发语言：`Kotlin`
- 开源许可证：`GPL-3.0`
- ⚠️ **不要**勾选「初始化 README」「添加 .gitignore」「选择许可证模板」
  —— 空仓库才能直接 push，否则会触发非快进冲突，需要额外 rebase。

创建后**先不要**急着 push，等开源审核通过（见第 5 节风险）。

### 第 1 步 · 添加 Gitee remote

```bash
cd /g/IronWIl0329/IronWill-OpenSource

# 用独立 remote，不要合并进 origin（原因见下方说明）
git remote add gitee git@gitee.com:my_room_welcome_you/zhizhi.git
```

**为什么不用 `git remote set-url --add origin <gitee>`？**
合并后每一次 `git push origin` 都会同时推两边。Gitee 需要实名 + 开源审核，
审核期间推送会一直失败，导致**连 GitHub 那一次推送也被感知为失败**。
拆成两个 remote，可以精确控制：GitHub 随时可推，Gitee 就绪后再推。

### 第 2 步 · 首次推送

```bash
# 先推 GitHub（随时可做）
git push github main
git push github dev

# Gitee 仓库创建并审核通过后再推
git push gitee main
git push gitee dev
```

### 第 3 步 · 验证

```bash
git ls-remote gitee          # 应列出 main / dev 等分支
git remote -v                # 应看到 origin=github, github, gitee 三组
```

### 第 4 步 · 上传 APK 到 Releases（两端各传一份）

> **选定方案 A**：Gitee 既镜像全量源码，也在 Releases 挂 APK。
> 这样国内用户既能 `git clone`/读代码（信任链完整），又能直接下包。

```bash
# 同一个 debug APK，两端都传
APK=app/build/outputs/apk/debug/app-debug.apk

# GitHub：在仓库 Releases 页点「Draft a new release」，tag 如 v0.1.0，附上 APK
# Gitee ：仓库「发行版」页点「创建发行版」，tag 保持一致，附上同一份 APK
```

Release 文案至少写明三件事：

1. 无障碍权限的用途（应用检测 / 拦截限时 / 防卸载）；
2. **卸载退出机制**：今天申请关闭 → 明天生效 → 之后可正常卸载；
3. 安装前提：手动开启 AccessibilityService，Android 13+ 需先解除「受限设置」。

---

## 3. 分发渠道与分工

| 渠道 | 角色 | 源码 | 安装包 |
|:---|:---|:---:|:---:|
| **GitHub** | 协作主站 | ✅ | ✅（Releases） |
| **Gitee** | 国内镜像 | ✅ **必须**（方案 A：全量镜像） | ✅（发行版） |
| **酷安** | 国内社区曝光 | ➖ 挂链接 | ✅ |
| **F-Droid** | 国外开源社区 | ✅ | ✅ |

> 开源解决**信任**，分发解决**触达**，是两件独立的事。
> 手动装包 + 手动开无障碍这两道门槛会筛掉大多数人，但留下的是高动机核心用户
> ——这与产品理念自洽：**约束是自己主动戴上的，不是被推送的。**
>
> ⚠️ 关于「Gitee 推源码还是只发 APK」：已确认走**方案 A（源码 + APK 双推）**，
> 理由见下方 **3.1 节**——全源码镜像才能兑现「不用相信我，可以直接看代码」的信任承诺，
> 且单独挂 APK 不满足 GPL-3.0 第 6 条对源码随附的要求。
> 除非万不得已（如开源审核长期卡住），不建议降级为「只发 APK」。

---

## 3.1 Gitee 必须推源码，不是可选项

**结论：Gitee 上源码和 APK 都要有，同一仓库内两样都放，不是二选一。**

| | 是什么 | 怎么放 | 服务谁 |
|---|---|---|---|
| **Gitee 仓库** | 完整源码 | `git push gitee main` | 想看代码、想改代码的人 |
| **Gitee 发行版** | APK + Release Notes | 仓库页 → 发行版 → 上传 | 只想装来用的人 |

### 四个理由

1. **GPL-3.0 的硬性要求。** 第 6 条规定以目标码形式分发时必须同时提供对应源码。
   只挂 APK 不给源码，严格讲不满足许可证。
   且"源码在 GitHub"这条抗辩对 Gitee 用户不成立——
   **Gitee 的存在理由恰恰是这些用户打不开 GitHub**，
   对他们来说那个源码等于不存在。
2. **视频 1 的核心承诺会塌。** 全片最重的一句是「不用相信我，可以直接看代码」。
   国内观众打开 Gitee 只看到下载页，这句话就是空的——
   而它要 preempt 的质疑（无障碍 + 防卸载）恰恰最需要源码支撑。
3. **视频 3 需要它才成立。** 视频 3 讲"用 AI 修改开源 App"，
   国内观众拿不到源码就跟着做不了，漏斗最后一层在国内直接断掉。
4. **Gitee 的开源身份绑定源码仓库。** 开源审核审的是仓库；
   只有 APK 没有源码仓库，在 Gitee 上就不是开源项目，拿不到开源项目的曝光位。

### 节奏提醒

**首发不要等 Gitee 审核。** 开源审核卡的是仓库可见性，不是发布节奏。
先发 GitHub Release，Gitee 审核通过后再补推源码与 APK，
**不要把 Gitee 审核时间当作发布的前置条件**（另见 5.1）。

### 维护成本

双推后两边要保持同步，否则代码会漂。固定用一条命令：

```bash
git push github main && git push gitee main
```

可以接受 Gitee 日常落后几天，但**大版本发布时两边必须对齐**。

---

## 4. 构建与签名

```bash
# 日常验证：debug APK 免签名，可直接安装
./gradlew assembleDebug

# 装真机（红线：严禁 adb uninstall，会抹掉本地规则与词库）
adb install -r -d app/build/outputs/apk/debug/app-debug.apk
```

正式分发再动 `assembleRelease` + 签名：

1. 生成 keystore 后，**离线备份到至少两个物理位置**。
2. ⚠️ **签名密钥丢失 = 无法再更新**，用户必须卸载重装并丢失全部本地数据。
3. keystore 与密码**禁止**进仓库；如需 CI 签名，用 GitHub Secrets 注入。

---

## 5. 风险与已发现问题

### 5.1 Gitee 审核时间不确定（已知，需预案）

Gitee 需实名认证 + 开源审核，**无障碍服务属敏感权限**，审核可能更严、放行时间不确定。
**不要把发布计划押在 Gitee 上。** 首发以 GitHub Release 为准，Gitee 作为补充镜像。

### 5.2 ⚠️ 公开历史中存在 3 个不同作者邮箱（建议处理）

```
60  cyz200303@163.com                                  ← 个人邮箱
46  14566253+my_room_welcome_you@user.noreply.gitee.com ← Gitee noreply
16  likara460@gmail.com                                ← 个人邮箱
```

两个**个人邮箱**会随开源永久公开，且 GitHub 无法把 Gitee noreply 邮箱的提交
关联到 GitHub 账号（贡献图谱会断裂）。

处理方案（**需你确认后再执行，改历史不可逆**）：

- **方案 A（推荐，无损）**：只统一**未来**提交，历史保持不变。
  ```bash
  git config user.name "Iwillbecomestrong"
  git config user.email "<你的 GitHub noreply 邮箱>"
  ```
  GitHub noreply 邮箱在 Settings → Emails → Keep my email addresses private 处查看。
- **方案 B（彻底，破坏性）**：用 `git filter-repo` 重写全部历史统一邮箱。
  会改变所有 commit SHA，**所有协作者必须重新 clone**。开源前做代价最小。

### 5.3 CI 当前不产出 Release

`.github/workflows/android-build.yml` 触发条件为：

- `pull_request` → `dev`
- `workflow_dispatch`（手动）

即：**push 到 main 不会构建，也不创建 Release**。
若希望每次发版自动出包，需补充 `release` 触发与 `softprops/action-gh-release` 步骤。
当前手动发版流程：本地 `assembleDebug` → 在 GitHub Releases 页面手动上传 APK。

### 5.4 SSH config 未声明 Gitee 专用密钥（低优先级）

`~/.ssh/id_ed25519_gitee` 存在，但 `~/.ssh/config` 里没有对应 Host 条目。
目前能连上，是因为默认密钥 `id_ed25519` 也已注册到同一 Gitee 账号。
若日后轮换默认密钥，Gitee 推送会静默失效。建议补一段：

```
Host gitee.com
    HostName gitee.com
    User git
    IdentityFile C:\Users\23248\.ssh\id_ed25519_gitee
    PreferredAuthentications publickey
```

---

## 6. 发版检查清单

- [ ] Gitee 空仓库已创建并通过开源审核
- [ ] `git remote add gitee` 已配置，双端均可 `git ls-remote`
- [ ] 提交邮箱已统一（方案 A 或 B）
- [ ] `./gradlew assembleDebug` 通过，`python -m unittest discover -s scripts/tests` 通过
- [ ] 真机 `adb install -r -d` 安装验证，无障碍服务开启后核心三项功能实测
- [ ] Release Notes 写明：无障碍权限用途、卸载退出机制（今天关→明天生效）
- [ ] README 提供普通用户安装指引（**必须包含 Android 13+ 受限设置解除步骤**）
- [ ] 题库版权已清洗（古诗词安全；考研真题与商业词表仅开放格式，不分发内容）
