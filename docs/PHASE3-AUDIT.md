# AwayFromShorts Phone · 第三阶段仓库审计

日期：2026-11-16  
范围：本地数据库、Repository、旧数据迁移、事件与统计基础  
原则：不改变现有冻结、额度、学习闸门和设备所有者执行语义。

## 1. 当前架构

- 业务与系统执行核心仍是 `dpc/java/com/afs/guard` 原生 Java。
- Device Owner、Accessibility、Shizuku、开机恢复和前台服务继续共用原实现。
- 第二阶段的 Compose 页面位于 `app`、`feature/*`、`core/*` 和 `style/*`。
- 两套构建路径：
  - 原生兼容包：`dpc/build.sh`
  - 正式 Compose 包：`dpc/release/build-release.ps1 -Compose`
- `GuardClock` 是 `Application` 入口，负责启动时恢复、缓存预热和后台迁移。

## 2. 现有数据存储

### SharedPreferences

- `afs_guard`：规则 JSON、冻结时段 JSON、自定义启动规则、已应用名单。
- `afs_time_window`：调整时段。
- `afs_daily_quota`：每日限额配置与账本。
- `afs_usage_quota`：旧版单应用额度。
- `afs_todo`、`afs_checkin`、`afs_taboo`、`afs_learning_link`、`afs_screen_stats` 等：各业务模块配置或运行状态。
- `afs_status`、`afs_health`：诊断与最近执行结果。

### AtomicFile / JSON 文件

- `study.json`：学习记录。
- `memory-v1.json`、`quiz-v1.json`、`cards-v1.json`：学习卡片、题库、热爱卡。
- `rule-backups/*.json`：规则恢复前的安全备份。
- `local-restore-rollback.zip`：完整本地恢复的回滚日志。

### 规则格式

`afs_guard/rules` 是“包名 -> 规则对象”的 JSON：

- `d`：星期位图，bit 0 为周一。
- `w`：二维数组时间段，`[startMinute,endMinute]`。
- `freeze`：true 表示时间段是冻结段，false 表示旧版放行段。
- `g`：是否参与学习闸门。
- `at`：新加入应用的时间戳。

关键语义：

- `start > end` 表示跨午夜。
- `start == end` 表示全天。
- `days == 0` 或时间段为空表示全天冻结。
- 放行与冻结两种格式都真实存在，不能统一假设为其中一种。

`afs_guard/freeze_slots` 保存冻结界面的时段分组。分组成员由规则时段键动态计算，不是独立持久化的成员表。

## 3. 可复用代码

- `Rule`、`WeeklyRule`：规则判定、跨午夜、下次翻转和规范化。
- `RuleStore.parseRule`：兼容 v1 单段和 v2 多段规则。
- `FreezeSlotStore.scheduleKey`、`members`：时段分组与成员关系。
- `RuleBackup`：规则快照、恢复前备份和回滚。
- `LocalBackup`：完整本地备份的原子写入与回滚机制。
- `GuardClock.runAsync`：应用启动阶段的后台迁移入口。

## 4. 第三阶段需要改造的模块

- 新增 SQLite 数据库和迁移记录。
- 新增应用、规则、时间段、冻结分组、专注会话、执行事件、每日统计、Outbox 和配置 Repository。
- 将旧规则、冻结分组、调整时段、日界线和非敏感配置迁移到数据库。
- 在 `GuardClock` 启动时异步执行幂等迁移，迁移失败不影响旧规则继续生效。
- 在系统冻结/解冻成功后按状态变化写入事件，避免周期性重复事件。
- 统计页面只读取 Repository，不在 UI 内计算规则或扫描原始事件。
- 本地备份增加数据库兼容格式和版本校验。

## 5. 迁移风险

1. `RuleStore.parseAll` 和 `raw` 当前会吞掉非法 JSON。迁移器必须自己验证原始 JSON，不能把损坏数据当空规则。
2. `FreezeSlotStore.all` 会根据规则自动补分组。迁移应保留已有分组 ID，同时验证补出的成员关系。
3. `start == end` 是全天语义，不是空窗口。
4. 跨午夜窗口的星期属于起始日，数据库必须保留原始 `days` 和起止分钟。
5. `freeze` 与旧版放行格式不能合并成单一布尔判断。
6. `gate` 影响学习闸门，迁移不能默认全部为 true/false。
7. 设备时区和日界线会影响统计聚合；事件必须同时保存 wall-clock 时间和时区/本地日期。
8. 旧规则写入与 `DailyQuotaStore.beforeScheduleChange` 有联动，迁移只读旧数据，不触发额度重算。
9. 迁移失败不能删除 SharedPreferences、不能把规则集合替换为空、不能调用 `Guard.apply` 放宽限制。
10. 当前设备是 Device Owner，任何回滚或清空操作都必须在首次配置/宽限期规则内执行。

## 6. 实施顺序

1. 建立 SQLiteOpenHelper、Schema 和索引。
2. 建立 Repository 接口与 SQLite 实现。
3. 实现只读旧数据快照、事务迁移、回读校验和迁移记录。
4. 在应用启动时异步执行迁移，失败写入诊断状态并保留旧数据。
5. 接入规则版本与结构化事件。
6. 接入专注会话、每日聚合和统计查询。
7. 接入统计 UI 与本地 Outbox。
8. 完成迁移、Repository、统计、Outbox 和系统回归测试。

