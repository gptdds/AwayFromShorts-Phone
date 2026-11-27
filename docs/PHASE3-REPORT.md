# 第三阶段交付报告

日期：2026-11-16。源码目录：`D:\空间\afs-build`。审计与边界见 [PHASE3-AUDIT.md](PHASE3-AUDIT.md)，数据关系见 [PHASE3-SCHEMA.md](PHASE3-SCHEMA.md)。

第三阶段已完成 SQLite 数据层、Repository、旧数据安全迁移、真实执行事件、专注会话、每日统计、本地 Outbox 和备份数据库快照。数据层使用现有 Java 与 `SQLiteOpenHelper`，没有引入 Room、Kotlin 数据框架或新的持久化服务。冻结、额度、学习闸门和设备所有者执行语义没有改变；迁移期间和迁移失败时，SharedPreferences 继续是执行权威来源。

## 1. 实现范围

| 模块 | 实现 |
| --- | --- |
| 数据库 | `afs_data.db`，WAL、外键、显式事务、版本 `1` |
| Repository | 应用、规则、配置、事件、专注、统计、Outbox，共 7 个入口 |
| 旧数据迁移 | 严格解析、checksum 快照、单事务导入、逐项回读校验、失败回滚 |
| 规则版本 | 每次保存写 `rule_revisions`，包含修订号、前后摘要、来源和快照 |
| Outbox | 规则更新与迁移生成本地变更记录；当前只保存在本机，不上传 |
| 真实事件 | 冻结、解冻、拦截、执行失败、服务恢复、专注开始/结束 |
| 统计页 | 今日专注、拦截次数、成功/失败执行、成功率、近 7 天数据 |
| 诊断页 | 迁移结果、规则数、待同步数、失败原因和 30 天旧事件清理 |
| 本地备份 | 格式 v2，读取兼容 v1；包含一致性数据库快照并通过 `quick_check` |

主要代码：

- `dpc/java/com/afs/guard/data/AfsDatabase.java`
- `dpc/java/com/afs/guard/data/AfsRepositories.java`
- `dpc/java/com/afs/guard/data/DataRecords.java`
- `dpc/java/com/afs/guard/LegacySnapshotParser.java`
- `dpc/java/com/afs/guard/LegacyDatabaseMigration.java`
- `dpc/java/com/afs/guard/Phase3Telemetry.java`
- `dpc/java/com/afs/guard/StatsActivity.java`
- `dpc/java/com/afs/guard/DiagnosticsActivity.java`
- `dpc/java/com/afs/guard/LocalBackup.java`

## 2. 迁移结果

当前迁移记录：

| version | migration_name | result |
| --- | --- | --- |
| 100 | `legacy-sharedpreferences-v1` | `SUCCESS` |
| 101 | `legacy-sharedpreferences-v2` | `SUCCESS` |

真机首次实现发现冻结时段使用长键 `days` / `windows`，而应用规则使用 `d` / `w`。修正严格解析器后，用 v2 迁移重新导入并核验；v1 历史行保留，不删除旧偏好。

正式包实际数据库审计：

```text
migration=SUCCESS
database_bytes=221184
integrity=ok
foreign_key_check=ok
applications=35
rules=35
time_windows=45
freeze_slots=4
freeze_slot_members=26
rule_revisions=35
focus_sessions=0
enforcement_events=1
daily_statistics=0
sync_outbox=35
schema_migrations=2
```

迁移快照：`/data/user/0/com.afs.guard/files/phase3/legacy-sharedpreferences-v2-1794837136755.json`。迁移完成标记为 `true`。

## 3. 事件、统计与本地 Outbox

- 事件只有在系统状态实际变化并核验后才写入；普通巡检看到相同状态不会重复记账。
- 拦截使用分钟级去重键，重复尝试不会无限放大次数。
- 专注时长只来自真实开始和结束，单次最多按 24 小时计入。
- 统计表按 `local_date + timezone` 唯一；页面不把计划时长当作真实使用时长。
- Outbox 与规则修订在同一个事务中写入。当前 35 条规则生成 35 条待同步记录，但尚无网络上传实现。
- `enforced_duration` 字段已保留，当前版本没有根据冻结区间自动累计；这是后续同步/重建统计时需要补齐的字段。

## 4. 备份与恢复

- 整合备份格式升级为 `afs-local-backup` v2，读取端兼容 v1。
- 导出数据库时优先使用 `VACUUM INTO`；旧 SQLite 回退到 WAL checkpoint 加有界复制。
- 导出后对数据库字节执行 `PRAGMA quick_check`，不通过则拒绝备份。
- 恢复顺序为：整体校验 → 关闭 Repository → 原子替换数据库 → 删除旧 `-wal`/`-shm` → 重启读取；失败使用原回滚包。
- 生产设备的设备所有者完整覆盖恢复仍受原 `LocalBackup.mayRestore()` 锁定边界限制；备份数据库快照的导出/恢复行为在隔离设备测试上下文中验证。

## 5. 验证结果

### 离线回归

`dpc/test/run.sh` 退出 0：

- 既有手机回归：`207 PASS / 0 FAIL`
- 严格快照解析：29 项通过
- 规则引擎：67 项
- 规则迁移：10,091 项
- 规则备份：12 项
- 冻结分组：697 项
- 其余既有额度、学习、卡片、笔记、插件和生命周期测试全部通过

### 真机数据测试

设备：PLR110，Android 16 / API 36。正式包上运行 `Phase3DataDeviceTest`：

```text
OK (5 tests)
```

覆盖：严格迁移与幂等、损坏 JSON 不触碰偏好、Repository 修订/回滚/Outbox/事件去重/专注/统计、真实备份导出恢复、Repository 性能。

正式包最新性能记录：

```text
rules=500 write_ms=85 read_ms=8 events=200 recent_ms=13
```

这是 PLR110 上的 Repository 数据操作测量，不等于 UI 帧率或长期耗电。

### 正式包视觉与数据审计

- `Phase3VisualCaptureTest`：`OK (1 test)`，生成统计页和诊断页截图。
- `Phase3ReleaseAuditTest`：`OK (1 test)`，对正式安装的 `afs_data.db` 执行完整性、外键、表计数、迁移行和快照检查。
- 正式包启动后顶部 Activity 为应用打卡页，logcat 无 `FATAL EXCEPTION`。

证据目录：`C:\Users\nj\Desktop\空间\archive\runs\2026-11-16-afs-phase3-phone\`

- `phase3-release-audit.txt`
- `phase3-release-audit-test.txt`
- `phase3-data-device-test-release.txt`
- `phase3-visual-capture-release.txt`
- `captures-release/stats.png`
- `captures-release/diagnostics.png`
- `phase3-release-home.png`

## 6. 正式包与安装

构建：`dpc/release/build-release.ps1 -Compose -Version 1.91.93 -VersionCode 2418 -MaintenanceVersionCode 2419`

| 产物 | 大小 | SHA-256 |
| --- | --- | --- |
| `dist/1.91.93/afs-guard.apk` | 43,812,398 字节 | `63c20e2767af9de3d8e76701216be482cc4efcb1ae8b4d942acfcd37eba606a3` |
| `dist/1.91.93/maintenance/afs-recovery.apk` | 43,812,446 字节 | `2b76f040a79dc2872a8153a5f67c6f83fcd1f4f3225a5ceb3fe49f384b7996d6` |
| `AwayFromShorts-Windows-1.91.93.zip` | 89,009,342 字节 | `72da9510f2ecd2f71ff29eafa9bb046ef9d1c3b5dca03352d62b047555bc3c38` |
| `AwayFromShorts-source-1.91.93.zip` | 以实际文件为准 | 见 `SHA256SUMS.txt`（避免报告内嵌源码包自身的哈希） |

主包与维护包均使用证书 SHA-256 `0c047b4af90f7d980852fa089f5384dea14c39df8f4bcf816adb254e8e74be97`。

真机覆盖安装结果：

- 版本：`1.91.93 / 2418`
- 首次安装时间保持 `2026-10-18 22:52:34`
- 更新时间：`2026-11-16 22:05:43`
- Device Owner 保持：`com.afs.guard/.AfsAdmin`
- 未卸载、未清除应用数据
- 诊断页核对 48 个已安装应用，系统状态与规则一致

## 7. 未完成或未验证

- SharedPreferences 仍是冻结执行权威来源；数据库当前是一致性影子/持久化层，尚未切换执行读取路径。
- Outbox 尚无网络同步、冲突解决或服务端版本协商。
- 既有事件没有历史回填，迁移后的统计数据从新事件开始。
- `enforced_duration` 尚未自动累计冻结区间。
- 未做 24 小时以上运行、长期 Doze、大量事件后的统计查询和数据库增长压力测试。
- 生产设备上未执行完整整合备份覆盖恢复，因为设备所有者锁定边界仍然有效；隔离上下文的数据库导出/恢复已通过。
- 未提交 Git，也未发布云端 Release；本轮只生成并安装了本地签名正式包。
