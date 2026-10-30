# Changelog

本项目在 **v2.0.1** 时首次纳入版本控制。下表是从 v1.0.0 到 v2.0.1 的完整变更记录，
来自真实服务端联调（Forge 47.4.23 / MC 1.20.1 / MTR 4.0.5）。

## 2.0.1

- 修复：自动/手动车厂重建后 `pathRecoveryNeeded` 残留一个巡检周期的问题。
- 新增恢复窗口：重建期间该车厂从 advisory 移出，单独出现在 `/health.recovering`，路径就绪即自动解除。
- 恢复窗口内巡检改为逐 tick，advisory 与真实状态同帧收敛；超时仍未修复则重新出现在 advisory 中。

## 2.0.0

- `configError` 写入服务端日志（此前只在 `/health` 可见）。
- 全量重建分支的统计改为在重建前采样（`lostSidings` / `depotVehicles`）。
- `/mtbridge audit` 输出改为 JSON，便于机器解析。

## 1.9.0

- 配置解析支持 UTF-8 BOM；解析失败不再静默回落，`configError` 暴露到 `/health.settings`。
- `pathRecoveryNeeded` 改为按全维度合并发布（此前被最后一个维度的空结果覆盖）。
- `/mtbridge audit|recover` 遍历全部维度（此前只认 `simulator(0)`）。
- 恢复动作可观测：`/health.lastRecovery` 与 `recover_depot` 事件；事件增加 `seq`、`bootId`、`sessionStartedAt`。

## 1.8.0

- 车厂级路径体检：`auditDepots`（按车厂分组，列出丢路径侧线与在场车辆）。
- 车厂级恢复：`/mtbridge recover`、自动恢复开关 `allowDepotRegeneration`。
- `pathLost` 遥测字段；`events` 环形缓冲（最近 20 条）。

## 1.7.0

- 发车延迟约 1.5 s（避开“重建同 tick 发车被宿主丢弃”）。
- 发车后置校验：车不存在 / 未上线 → failed；上线 → applied，杜绝假成功。
- `lastPersist` / `persistFailures`：落盘结果可见。

## 1.6.0

- 意图循环改用线性查找（修复侧线意图被静默丢弃）。
- 路径就绪后直接完成排队发车；失败时立即收尾。

## 1.5.0

- 所有意图/尝试的移除点补齐埋点（不再有静默移除）。
- 路径快照与回滚：重建失败时写回旧路径，避免“探测一次毁一条侧线”。
- `routeDiag` 诊断与 `lastTickError`。

## 1.4.0

- 三级路径生成策略（含 `Depot.generateDepots`），按超时逐级升级。
- 策略 2 默认关闭，需 `allowDepotRegeneration` 显式开启。

## 1.3.0

- **P0**：`dwell_control` 写 long 字段用 double 导致服务器 tick 崩溃 → 类型自适应读写。
- 三层异常防线（字段层 / tick 层 / 宿主 tick 层），`tickFailures` 计数。
- 发车改用真实时刻表索引（不再伪造 `departureIndex`）。
- `state` 版本改为内容版本，`?since` 与 ETag 真正可用。
- 新增 `pathReady` / `plannedFirstPlatformId`。

## 1.2.0

- 修复侧线占用判定把目标车自己算作“占用”，导致 `set_platform` 在最需要的场景必然被拒。
- 重建后的发车改为按侧线排队（车辆 id 会变）。
- `?since`、`minPollIntervalMs`、常量时间鉴权、HTTP 线程数可配。

## 1.1.0

- 修复启动竞态：宿主初始化晚于本模组时，桥会永远不可用 → 事件优先级 + 每 5 s 重试（上限 12 次）。
- tick 覆盖全部维度（此前只 tick 维度 0）。
- 意图 TTL 可配（默认 120 s），`/health.intents` 可见。
- 遥测补齐 `serviceStart`、`passengers`、`departure`、`semantics: 2`。
- 压站上限 `maxHoldSeconds`，到期自动释放。

## 1.0.0

- 首个可用版本：`/dispatch/v1/{capabilities,state,command,health}`、四条指令、
  `/mtbridge` 命令、Java API（`DispatchBridgeAPI`）、幂等与前置条件、令牌鉴权。
