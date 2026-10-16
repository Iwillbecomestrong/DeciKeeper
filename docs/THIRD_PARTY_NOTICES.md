# 第三方开源组件声明 (Third-Party Notices)

本项目（决定守护 / DeciKeeper）使用以下开源组件。版本以 `gradle/libs.versions.toml`、`app/build.gradle.kts` 和最终 Release 依赖解析为准；本清单是工程登记，不替代逐项许可证核验。

## 运行时依赖

| 组件 | 版本 | 许可证 | 说明 |
|------|------|--------|------|
| AndroidX Core KTX | 1.10.1 | Apache License 2.0 | [官方仓库](https://developer.android.com/jetpack/androidx) |
| AndroidX AppCompat / Activity / ConstraintLayout | 1.6.1 / 1.8.0 / 2.1.4 | Apache License 2.0 | [官方仓库](https://developer.android.com/jetpack/androidx) |
| AndroidX GridLayout / WorkManager / ViewPager2 | 1.0.0 / 2.9.0 / 1.1.0 | Apache License 2.0 | [官方仓库](https://developer.android.com/jetpack/androidx) |
| Google Material Components for Android | 1.10.0 | Apache License 2.0 | [官方仓库](https://github.com/material-components/material-components-android) |
| MPAndroidChart | 3.1.0 | Apache License 2.0 | 统计图表（周/月/年使用统计面板） |
| AndroidX CameraX | 1.3.4 | Apache License 2.0 | 相机驱动与生命周期绑定（闹钟扫码挑战） |
| Google ML Kit Barcode Scanning | 17.3.0 | Apache License 2.0 | 使用 bundled 模型；[官方 Android 文档](https://developers.google.com/ml-kit/vision/barcode-scanning/android)确认扫码在设备本地处理；依赖通用说明另见[官方条款](https://developers.google.com/ml-kit/terms) |
| Markwon Core / Tables | 4.6.2 | Apache License 2.0 | [官方仓库](https://github.com/noties/Markwon) |
| JLatexMath Android（ru.noties:jlatexmath-android） | 0.2.0 | GPL-2.0-or-later，含链接例外（与本项目 GPL-3.0 兼容） | 离线公式排版（自定义题库中 `$...$` 公式）；纯 Java，无网络与权限需求；[官方仓库](https://github.com/noties/jlatexmath-android) |

## 测试依赖（仅构建测试用，不随 APK 分发）

| 组件 | 许可证 |
|------|--------|
| JUnit 4 | Eclipse Public License 1.0 |
| AndroidX Test (JUnit Ext / Espresso Core) | Apache License 2.0 |
| Robolectric | MIT License |
| org.json 20240303（测试用） | JSON License |

## 数据资产提示

`app/src/main/assets/` 下的考研词汇、考研真题及项目生成翻译/释义的范围与来源说明，
见 `docs/DATA_SOURCES.md`。真题随包公开时保留来源说明，不收录商业解析；考研词汇、AI 生成对联题型以及翻译/释义按“项目生成/AI 辅助生成”标注。若你发现任何数据存在来源问题，请通过 Issue 告知，我们会处理。
