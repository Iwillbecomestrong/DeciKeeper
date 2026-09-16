# Release 构建、验证与分发计划

状态：校验脚本、SHA-256/manifest 产物和 CI workflow 已实施；本机 Release 产物与签名结果待本轮构建，双渠道上传未执行。日期：2026-09-16。

## 目标与现状

为每个正式 APK 提供可核对的源码提交、版本、签名指纹、哈希与测试结果。当前 CI 面向 main 的 push/PR 运行 JVM 与 Debug 构建；未形成完整 Release 验证链。现有 v0.3.0 tag 不能单独证明公开 APK 与其一致；远端资产尚未核验。版本仍为 0.3.0 不自动构成缺陷，发布时才必须保证身份一致。

不要求立即将签名私钥移入云端，不承诺二进制逐字节可复现，不自动推送、打 tag 或公开发布。

## 范围

app/build.gradle.kts、gradle/wrapper/gradle-wrapper.properties、.github/workflows/android-build.yml、新的 Release 验证 workflow、scripts/ 下现有或最小新增的产物校验脚本、docs/PUBLISHING.md、CHANGELOG.md。进入 scripts 前先读其 README。

## 实施步骤

1. 清点真实发布流程与本地签名方式。仅检查配置键是否存在，禁止输出密码、私钥或完整 local.properties。
2. 定义发布输入：源码 commit/tag、versionName/versionCode、目标 ABI、SDK/JDK/Gradle/AGP 版本、预期发布证书 SHA-256。
3. 从干净 checkout 验证构建；含未提交修改的工作区不得作为正式来源而不记录。版本号选择在实际发版时决定，不在计划中虚构下一个版本。
4. 普通构建继续支持 unsigned Release；独立正式发布校验任务对缺签名、debuggable、错误包名/版本/证书明确失败。
5. 运行全量 JVM、Release 构建、最终 APK 验签及 Manifest 权限检查。采用实际 APK 而非仅检查源码 Manifest。
6. 生成 release-manifest.json 和 SHA-256 文件：记录 commit、tag、构建工具版本、APK 文件名/大小/哈希、包名、版本、ABI、证书指纹、测试结果引用及权限清单。记录依赖清单，不能仅用它冒充标准 SBOM。
7. 本地签名流程先落地；CI 可构建 unsigned Release 并提供验证报告。只有另行确定私钥管理方式后才接入 CI 签名，外部 PR 不接触发布 secrets。
8. 双渠道上传使用同一个已验证 APK，发布后分别核对下载哈希及源码引用；发布动作由实际发布任务授权。
9. 更新 PUBLISHING 唯一可执行清单，去除 Debug 正式分发、旧 remote、旧 CI 触发条件等矛盾。

## 验收与测试

- 干净环境能够构建；无官方私钥的贡献者仍能构建 unsigned Release。
- 校验器分别拒绝未签名、debuggable、版本/tag 不一致、错误证书和网络权限异常的测试输入。
- 检查哈希、元数据和 APK 结果一致；缺失产物或测试失败阻止发布准备完成。
- 自动化失败时保留报告但不上传正式 Release，不静默用 Debug key 替代。
- 签名指纹是公开信息；签名秘密不进入日志、报告、Git 或普通 CI artifact。
- 未进行实际双渠道发布时，明确远端哈希检查未执行。

## 顺序与回退

依赖质量门禁计划的稳定测试、权限计划提供的许可列表。先交付只读校验脚本及测试，再接 workflow，最后修改发版文档。失败版本不替换已发布包；升级签名不能随意轮换。普通 SHA-256 能校验文件一致性，但不证明来源可信或实现逐字节可复现。本轮不创建 tag、不提交、不推送、不上传；未执行双渠道发布时必须保留该未完成项。
