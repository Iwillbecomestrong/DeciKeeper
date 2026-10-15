# 回桌面豁免：3 秒 + 30 秒冷却

## 已确认目标
- 回到桌面（锁屏页按钮或系统 Home 手势）豁免 3 秒，豁免开始后 30 秒内不再豁免；不计数、不续期、不扣额度/借用/冷却。
- 不加手动出口、不加规则参数；临时解锁保持不变。
- 统计明细移除“频繁返回扣减”筛选，历史记录只读并仍计入出流。
- 服务/进程重启保留剩余豁免与冷却。

## 范围与验证
- `ReturnHomeBufferPolicy` / `ReturnHomeBufferManager`、`LockWindowManager`、`RestLedger`、focus 规格、用户指南、HISTORY、CHANGELOG。
- 单测全量通过后出 Release 包，真机观察：系统 Home 手势是否提前消耗豁免、30 秒冷却内能否关小窗。
- 作为 v0.4.3 发布；v0.4.2 已发布内容不改动。
