# AwayFromShorts Phone · 第三阶段数据库 Schema

数据库：`afs_data.db`  
实现：`SQLiteOpenHelper`，版本 `1`  
模式：WAL、外键开启、写入使用显式事务。  
状态：已进入真机数据迁移、统计读取、本地备份恢复和诊断验证。

## 表关系

```text
applications 1 ─── n rules 1 ─── n time_windows
      │                │
      │                └── n rule_revisions
      │
      ├── n freeze_slot_members n ─── 1 freeze_slots
      ├── n enforcement_events
      └── n focus_sessions (可选 application 维度)

devices
configuration
daily_statistics
schema_migrations
sync_outbox
```

## 表定义

### `devices`

本地设备非敏感信息。`device_uuid` 为应用生成的随机 UUID，不使用 IMEI、序列号或广告 ID。

### `applications`

- `id`：随机 UUID，稳定实体 ID。
- `package_name`：唯一，系统识别依据。
- `display_name`：仅用于展示。
- `enabled`：是否仍受本应用管理。
- `installed`：最近一次观察结果。
- `last_enforcement_state`：最近一次已验证冻结状态。
- `last_enforced_at`：最近一次状态变化时间。

### `rules`

- `application_id`：外键。
- `rule_uuid`：稳定 UUID，供未来同步使用。
- `rule_type`：`schedule`。
- `freeze_mode`：0 为旧版放行段，1 为冻结段。
- `gate`：是否参与学习闸门。
- `weekday_mask`：周一为 bit 0。
- `enabled`、`priority`、`revision`。
- `added_at`：规则加入宽限期时间。

### `time_windows`

- `start_minute`、`end_minute`：0–1439。
- `start > end` 表示跨午夜。
- `start == end` 表示全天。
- `sort_order` 保留原始顺序。

### `freeze_slots` / `freeze_slot_members`

保存冻结界面的时段分组。`schedule_key` 由现有 `FreezeSlotStore.scheduleKey` 计算，不重新解释规则语义。

### `focus_sessions`

保存主动专注会话。计划结束时间与实际结束时间分开，不把计划时长伪装成真实使用时长。

### `enforcement_events`

- `event_type`：规则变更、冻结、解冻、拦截、执行失败、服务恢复、定时触发。
- `execution_result`：`attempted`、`succeeded`、`failed`、`verified`。
- `enforcement_method`：`device_owner`、`hidden`、`accessibility`、`shizuku`。
- `application_id` 可为空。
- `event_time` 为 wall-clock 毫秒。
- `local_date`、`timezone` 用于本地日聚合。
- `dedupe_key` 唯一索引用于状态变化去重。

### `daily_statistics`

按 `local_date + timezone` 唯一聚合。所有数值都必须能从事件或会话追溯，聚合表可重建。

### `schema_migrations`

记录 Schema 与业务数据迁移。`result` 为 `SUCCESS`、`FAILED` 或 `SKIPPED`，并保存校验摘要。

### `sync_outbox`

本地变更登记，不联网。实体更新、规则版本和 Outbox 写入在同一事务内完成。

### `configuration`

按 `key/value/type` 保存非敏感配置。禁止把整份业务数据库序列化成单个 JSON 字段。

## 索引

- `applications.package_name`
- `rules.application_id`
- `time_windows.rule_id, sort_order`
- `freeze_slot_members.slot_id`
- `enforcement_events.event_time`
- `enforcement_events.local_date`
- `enforcement_events.application_id, event_time`
- `enforcement_events.dedupe_key`（唯一）
- `daily_statistics.local_date, timezone`（唯一）
- `sync_outbox.sync_status, next_attempt_at`

## 迁移原则

1. 迁移在单个事务中写入新表。
2. 旧 SharedPreferences 只读，不删除。
3. 迁移前把旧规则与冻结分组写成独立安全快照。
4. 写入后逐条比对包名、`days`、`freeze`、`gate`、全部时间段和分组键。
5. 校验失败必须回滚，并保留旧规则继续驱动执行引擎。
6. 迁移记录具有幂等键，重复启动不会重复写入。

## 旧数据格式兼容

规则与冻结时段不是同一种 JSON 结构，迁移器不能用同一个宽松解析器猜测：

- 应用规则使用短键：`d`、`w`、`s`、`e`、`g`、`freeze`、`at`。
- 冻结时段使用长键：`days`、`windows`、`freeze`。冻结时段没有 `g`，导入时固定为 `gate=true`。
- 冻结时段的 `windows` 必须是数组；字符串、损坏的窗口或重复分组计划都会让迁移失败，不能静默替换成默认时段。
- 应用规则中的空 `w` 会原样保留为空窗口；只有字段缺失时才使用默认时段。
- `start > end` 仍表示跨午夜，`start == end` 仍表示全天。数据库保存原始分钟和星期位，不重算业务语义。

当前迁移键：

| version | migration_name | 结果含义 |
| --- | --- | --- |
| 100 | `legacy-sharedpreferences-v1` | 首次迁移；保留历史记录 |
| 101 | `legacy-sharedpreferences-v2` | 修正冻结时段长键兼容后强制执行的一次校验迁移 |

`v2` 不删除 `v1` 记录。应用以 `v2` 的 `SUCCESS` / `SKIPPED` 作为当前迁移完成依据。

## Repository 约定

`AfsRepositories` 是数据层入口。公开查询和写入是阻塞调用，UI 线程必须通过 `AfsDatabase.runAsync()` 或自己的工作线程调用。

| Repository | 职责 |
| --- | --- |
| `applications()` | 包名、展示名、启用与已安装状态，以及最近一次已验证冻结状态 |
| `ruleRepository()` | 规则读取、保存、删除、整批替换；自动写版本记录和 Outbox |
| `configuration()` | 非敏感配置键值；不保存凭据 |
| `eventRepository()` | 结构化执行事件、去重、按时间清理 |
| `focusRepository()` | 主动专注会话的启动、当前会话和完成 |
| `statistics()` | 按本地日期与时区聚合统计 |
| `outbox()` | 本地同步变更登记；不联网 |

规则保存与 `replaceAll` 在同一数据库事务内完成实体更新、`rule_revisions` 快照和 `sync_outbox` 写入。出错时三者一起回滚，不留下“规则已改但版本/同步记录没写”的半状态。

## 事件定义

`enforcement_events` 只记录可追溯的真实动作，不记录普通页面浏览。

| event_type | 触发时机 | 结果 |
| --- | --- | --- |
| `APP_SUSPENDED` | 系统冻结/隐藏状态从放行变为冻结且已核验 | `VERIFIED`，成功执行数 +1 |
| `APP_UNSUSPENDED` | 系统状态从冻结变为放行且已核验 | `VERIFIED`，成功执行数 +1 |
| `APP_BLOCKED` | 用户再次尝试进入当前被拦截的应用 | `BLOCKED`，拦截次数 +1 |
| `ENFORCEMENT_FAILED` | 系统执行失败或执行后状态不符 | `FAILED`，失败执行数 +1 |
| `SERVICE_RECOVERED` | 守护服务完成恢复检查 | `VERIFIED` |
| `FOCUS_STARTED` | 工具箱真实开始专注 | `STARTED` |
| `FOCUS_ENDED` | 专注完成、取消或恢复 | `COMPLETED`，按真实结束时间累计专注时长 |

- `event_time` 是 wall-clock 毫秒；`local_date` 与 `timezone` 同时保存，避免跨时区或日界线后归错日期。
- `metadata` 保存原因、触发来源、desired/previous 状态和计划结束时间等诊断信息。
- `dedupe_key` 有唯一索引。周期性巡检重复看到同一状态时不会重复记账。
- 事件清理只删除超过保留期的执行记录；规则、会话和日聚合不受影响。

## 统计定义

`daily_statistics` 是查询缓存，不是独立事实来源。每个值都能从事件或专注会话追溯：

| 字段 | 来源 |
| --- | --- |
| `focus_duration` | `FOCUS_ENDED` 的真实开始/结束时间，单次最多按 24 小时计入 |
| `enforced_duration` | 已核验冻结状态的时间；当前版本保留字段，尚未由状态区间自动累计 |
| `blocked_attempts` | `APP_BLOCKED` 的分钟级去重记录 |
| `successful_enforcements` | `APP_SUSPENDED` / `APP_UNSUSPENDED` 的核验结果 |
| `failed_enforcements` | `ENFORCEMENT_FAILED` |

统计页“今日专注”和“守护成功率”读取这些值；没有真实事件时显示 `0` 或“暂无记录”，不以计划时长或界面演示数值冒充实际使用。

## Outbox 与版本

- `sync_outbox` 是本地变更日志，当前不执行网络上传，也没有后台同步进程。
- 每次规则保存会写入实体类型、实体 ID、操作、实体修订号和 JSON 负载。
- `payload_version` 为后续同步协议预留；当前负载版本为 `1`。
- 迁移 35 条规则会生成 35 条初始 Outbox 记录，用于后续首次同步对账。
- 待同步数量和迁移结果可在系统健康诊断页查看。

## 备份与恢复

- 整合备份格式为 `afs-local-backup` v2；读取端继续兼容 v1。
- 备份包含 `preferences.json` 和一致的 `database/afs_data.db` 快照。
- 数据库快照优先使用 SQLite `VACUUM INTO`；旧 SQLite 不支持时先做 WAL checkpoint，再按大小上限复制。
- 导出后读取备份中的数据库字节，执行 `PRAGMA quick_check`，通过后才接受文件。
- 恢复先校验整个归档，再替换数据库、清理旧的 `-wal` / `-shm`；失败时使用原回滚包恢复。
- 设备管理身份、权限、首次宽限、云端凭据和本地锁文件不进入数据库备份。

