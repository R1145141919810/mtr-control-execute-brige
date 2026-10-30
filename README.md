# MTR Control Execute Bridge

> Minecraft Transit Railway 动态调度系统的**执行端**：接收外部调度指令，在服务端主线程安全执行，
> 并把列车与世界状态回传给规划系统。

![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-informational)
![Forge](https://img.shields.io/badge/Forge-47.x-informational)
![MTR](https://img.shields.io/badge/Minecraft%20Transit%20Railway-4.0.0%2B-informational)
![License](https://img.shields.io/badge/license-MIT-blue)

---

## ⚠️ 先读这一段：单独安装本模组功能不完整

本仓库**只是三个组件中的一个**。它负责“把指令落到车上”，本身不产生任何调度决策，
也不包含限速/信号相关的游戏逻辑。

| 角色 | 仓库 | 职责 |
|---|---|---|
| **前端 / 车内逻辑** | [**mtr-realrail**](https://github.com/R1145141919810/mtr-realrail) | 限速变更时机（车尾通过后变速）、多信号系统隔离、车厂优化与站台调度界面 |
| **规划系统** | [**mtr-tsc-dispatch-system**](https://github.com/R1145141919810/mtr-tsc-dispatch-system) | 独立进程运行的外部调度服务：轮询状态、动态规划进路、下发选台/避让/回场指令 |
| **执行桥（本仓库）** | mtr-control-execute-brige | HTTP 指令接收与鉴权、主线程执行、遥测回传、/mtbridge 命令、Java API |

- **只装本模组**：服务器可以正常启动，但没有任何指令来源，选台/避让/回场都不会发生；
  限速与信号行为也与原生一致。
- **只装规划系统**：只能读到站台级遥测，所有决策会降级为 advisory（只出建议、不落地）。
- **三者齐备**：才是完整的“动态调度”——前端提供车内逻辑，规划系统做决策，本模组负责执行。

所以：**请把这两个依赖一起安装**，否则即使强行装上也只得到一个“能启动、但少了一半功能”的服务器。

---

## 整体架构

```text
┌──────────────────────────────┐
│ mtr-realrail（前端/车内逻辑） │  限速判定、信号隔离、车厂与站台配置
└──────────────┬───────────────┘
               │ 游戏内行为
┌──────────────▼───────────────┐        HTTP / RCON        ┌──────────────────────────────┐
│   本模组（执行桥，服务端）    │ ◀───────────────────────▶ │ mtr-tsc-dispatch-system      │
│  · /dispatch/v1/*            │   指令 / 遥测 / 事件        │ （规划系统，独立进程）        │
│  · /mtbridge 命令            │                            │ 选台、绕行、回场、压站决策    │
│  · Java API（给其它模组）     │                            └──────────────────────────────┘
└──────────────────────────────┘
```

---

## 功能

### 四条底层指令

| 指令 | 作用 | 适用场景 |
|---|---|---|
| `set_platform` | 改变列车下一次停靠的站台 | 停场车：重建路径；运行中：挂意图，回场后生效 |
| `deploy_train` | 让停场列车发车，可指定目标站台 | 中途投放、避让后补发 |
| `return_to_depot` | 跑完当前班次后收车（可在终点前保持停场） | 定时回场、容量调度 |
| `dwell_control` | 压站 / 释放（0=释放，>0=延长，<0=保持到上限） | 站台避让、等信号 |

### 状态回传

`GET /dispatch/v1/state` 返回完整快照：车站、站台、线路（含站序与区间时长）、车厂、车辆
（`id / nextPlatformId / currentStationId / nextStationId / speed / railProgress / eta / departure / inDepot / pathReady / pathLost / plannedFirstPlatformId`）。

### 运维能力

- `/mtbridge status`：桥状态、执行/失败/挂起计数、定位方式
- `/mtbridge audit`：按车厂体检，输出 JSON，列出丢路径的车厂与侧线
- `/mtbridge recover`：按车厂重建路径（车厂级，因为路径丢失是车厂/侧线级现象）
- 自动恢复：`allowDepotRegeneration=true` 时，开机巡检发现丢路径会自动重建（默认关闭，破坏性操作）

### 给其它模组的 Java API

```java
DispatchBridgeAPI.getInstance().setTrainPlatform("1002", "0000000000000042");
DispatchBridgeAPI.getInstance().addListener(event ->
    LOGGER.info("{} {} -> {}", event.kind(), event.trainId(), event.result().outcome()));
```

门面永不为 null：未安装时返回全 `UNAVAILABLE` 的实现，可选集成不需要判空。

---

## 环境要求

| 项 | 版本 |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.x |
| Minecraft Transit Railway | 4.0.0 及以上（开发与验证基于 4.0.5） |
| Java | 17 |

本模组**不含** MTR 的任何代码，只在编译期依赖其公开/可反射访问的接口。

---

## 安装

1. 安装 Forge 47.x 服务端；
2. 把 **mtr-realrail**、**MTR 本体**、本模组放入 `mods/`；
3. 首次启动会生成 `config/mtbridge.json`；
4. 把 **mtr-tsc-dispatch-system** 部署到任意能访问该服务器 HTTP 端口的机器上，
   把 `bridge.base_url` 指向本模组的 `http://<host>:8889`。

---

## 配置 `config/mtbridge.json`

```json
{
  "enabled": true,
  "port": 8889,
  "bindAddress": "127.0.0.1",
  "token": "",
  "maxHoldSeconds": 600,
  "intentTtlSeconds": 120,
  "dryRun": false,
  "httpThreads": 8,
  "allowDepotRegeneration": false
}
```

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 是否启动 HTTP 控制面 |
| `port` | `8889` | 监听端口 |
| `bindAddress` | `127.0.0.1` | 绑定地址；**非回环且 token 为空时拒绝启动**（fail closed） |
| `token` | 空 | `Authorization: Bearer <token>`；空表示不鉴权（仅限回环） |
| `maxHoldSeconds` | `600` | 无限压站的上限，到期自动释放 |
| `intentTtlSeconds` | `120` | 未生效意图的默认存活时间（命令可自带 `ttlSeconds` 覆盖） |
| `dryRun` | `false` | 只演练不落地：所有指令返回 applied 但不改世界 |
| `httpThreads` | `8` | HTTP 工作线程数 |
| `allowDepotRegeneration` | `false` | 允许车厂级重建（恢复丢路径）。**会重建该车厂全部列车**，请谨慎开启 |

配置文件支持 UTF-8 BOM；解析失败会写入 `/health.settings.configError` 并打 ERROR 日志，不会静默回落。

---

## 命令

```text
/mtbridge status                     桥状态：定位方式、维度数、执行/失败/挂起计数
/mtbridge audit                      按车厂体检，输出 JSON
/mtbridge recover                    重建所有丢路径的车厂
/mtbridge set_platform <train> <platformId>
/mtbridge deploy <train> <platformId>
/mtbridge return_depot <train> [sidingId]
/mtbridge dwell <train> <seconds>    0=释放，>0=延长（秒），-1=保持到上限
```

---

## HTTP 接口（协议 v1）

所有接口在 `/dispatch/v1/` 下，`Content-Type: application/json`，可选 Bearer 鉴权。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/dispatch/v1/capabilities` | 协议版本、能力清单、维度 `[{index,id}]`、`minPollIntervalMs`、`ready/loading` |
| GET | `/dispatch/v1/health` | 计数、`intents`、`holds`、`events`、`lastPersist`、`lastRecovery`、`pathRecoveryNeeded`、`recovering` |
| GET | `/dispatch/v1/state?dimension=N` | 世界快照；支持 `ETag` / `If-None-Match` / `?since=<version>` 返回 304 |
| POST | `/dispatch/v1/command` | 下发指令，支持 `key` 幂等与 `ttlSeconds` |

指令示例：

```json
POST /dispatch/v1/command
{
  "kind": "set_platform",
  "vehicleId": "1002",
  "args": { "platformId": "0000000000000042" },
  "key": "dispatch-42",
  "ttlSeconds": 120
}

→ { "ok": true, "outcome": "deferred", "applied": false, "detail": "..." }
```

语义要点：

- `applied` 只有在校验通过后才为 true；发车会做后置校验（下一 tick 查车是否在线上）；
- `deferred` 表示已受理但未生效（例如运行中列车改台要等回场），会在 `/health.intents` 可见；
- 重复的 `key` 不会重复执行。

---

## Java API（给其它模组）

```java
// 让某列车下一次停 2 号站台
CompletableFuture<BridgeResult> result =
    DispatchBridgeAPI.getInstance().setTrainPlatform("1002", "0000000000000042");

// 让玩家所乘列车改台
DispatchBridgeAPI.getInstance().setTrainPlatform(playerUuid, "0000000000000041");

// 监听所有执行结果（HTTP / 命令 / API 三个来源统一派发）
DispatchBridgeAPI.getInstance().addListener(event -> {
    if (event.result().outcome() == BridgeResult.Outcome.APPLIED) {
        // ...
    }
});
```

接口定义见 `src/main/java/org/mtbridge/api/BridgeApi.java`。

---

## 构建

前置：JDK 17。首次构建需要联网拉取 Forge 依赖。

```bash
# 1) 准备编译期依赖（不随仓库分发宿主模组的代码）
#    从 MTR 发布 jar 中提取 org/mtr 后重新打包：
mkdir -p libs
jar xf minecraft-transit-railway-4.0.5.jar org
jar cf libs/mtr-compile.jar org
rm -rf org

# 2) 构建
./gradlew build
# -> build/libs/mtbridge-<version>+1.20.1.jar
```

`libs/mtr-compile.jar` 已在 `.gitignore` 中，不会被提交。

---

## 兼容性与已知限制

这些限制来自真实服务端联调，不是猜测：

1. **路径在班次内不可变**。运行中的列车无法直接改台，只能重建路径；重建会清空该侧线车辆并重新生成（车辆 id 会变）。
2. **重建是异步的**，通常数秒。期间 `pathLost=true`、`pathReady=false`，属正常过渡，不应据此告警。
3. **同一 tick 内“重建完成 + 立刻发车”会被宿主丢弃**。桥因此延迟约 1.5 s 发车，并在之后做后置校验；校验失败会如实返回 failed，而不是假 applied。
4. **宿主不把路径写入存档**，重启后路径重算。因此“持久化路径”不可行；丢失检测与恢复按车厂进行（`/mtbridge recover`）。
5. **维度顺序每次启动可能不同**（overworld 恒为 0，其余会互换），任何配置都应按 `capabilities.dimensions[].id` 匹配，而不是猜 index。
6. **不使用 Mixin**，受保护字段通过反射访问；宿主大版本升级后若字段改名，会以 `lastTickError` 与 failed 结果暴露，而不是崩溃。
7. **dwell 控制是软覆盖**（每 tick 把停站计数压回 0），上限由 `maxHoldSeconds` 约束。

---

## 版本历史

完整时间线与每版修复点见 [CHANGELOG.md](CHANGELOG.md)。

> 说明：本仓库在 **v2.0.1** 时首次归档。v1.x 的迭代构建当时未纳入版本控制，
> 因此 Git 历史从 2.0.1 开始；CHANGELOG 记录了 v1.0.0 → v2.0.1 的全部变更。

---

## 许可

MIT，见 [LICENSE](LICENSE)。

本仓库不包含、也不再分发 Minecraft Transit Railway（Transport Simulation Core）的任何代码；
构建时仅以 `compileOnly` 引用其发布产物，运行期由玩家自行安装。
MTR 与其核心库的版权归其各自作者所有。
