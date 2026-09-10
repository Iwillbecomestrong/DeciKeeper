# 第三方开源组件声明 (Third-Party Notices)

本项目（决定守护 / DeciKeeper）使用以下开源组件。按照各组件许可证要求，在此列出其名称、版本与许可证。

## 运行时依赖

| 组件 | 版本 | 许可证 | 说明 |
|------|------|--------|------|
| AndroidX Core / AppCompat / Activity / ConstraintLayout / GridLayout / WorkManager / ViewPager2 | 见 gradle/libs.versions.toml | Apache License 2.0 | Google AndroidX 库 |
| Google Material Components for Android | 见 gradle/libs.versions.toml | Apache License 2.0 | UI 组件 |
| MPAndroidChart | 3.1.0 | Apache License 2.0 | 统计图表（周/月/年使用统计面板） |
| zxing-android-embedded | 4.3.0 | Apache License 2.0 | 二维码扫描（闹钟扫码挑战）；内嵌 ZXing Core（Apache License 2.0） |
| Markwon Core / Tables | 4.6.2 | Apache License 2.0 | App 内用户手册 Markdown 与表格渲染 |

## 测试依赖（仅构建测试用，不随 APK 分发）

| 组件 | 许可证 |
|------|--------|
| JUnit 4 | Eclipse Public License 1.0 |
| Robolectric | MIT License |
| org.json（测试用） | JSON License（保留版权声明即可） |

## 数据资产提示

app/src/main/assets/ 下的词库、选词填空、对联等数据文件的来源与授权状态，
见 docs/DATA_SOURCES.md（随开源发布时补充）。若你发现任何数据存在版权问题，
请通过 Issue 告知，我们会立即处理。
