## 📋 Pull Request 说明

### 🎯 解决的问题 / 关联 Issue
- Closes #(issue_number)
- 简述本 PR 的背景与动机。

### 🛠️ 改动内容
- 
- 

### ⚖️ 生效策略与业务红线自查
- [ ] 本次改动是否涉及规则放松？若涉及，已严格接入 `iron_will_rules_pending` 次日生效体系。
- [ ] 架构分层：UI 层未包含业务判定，Manager 未反向引入 UI 类。
- [ ] 取证独立性：未引入诊断日志干预业务判定的代码。

### 🧪 验证与测试记录
- [ ] `.\gradlew.bat :app:compileDebugKotlin` 编译通过。
- [ ] `.\gradlew.bat :app:testDebugUnitTest` 单元测试通过，未引入任何新增失败。
- [ ] `python -m unittest discover -s scripts/tests` 脚本单元测试通过。
- [ ] （如涉及锁屏/无障碍/前台判定）已在真机上验证：
  - **测试设备**：
  - **验证步骤与结果**：
