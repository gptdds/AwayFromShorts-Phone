# 第一阶段现状与实施计划

> 后续实施与最终验证已记录于 [PHASE1-REPORT.md](PHASE1-REPORT.md)（2026-11-16）。本文保留初查时的基线和风险说明。

> 范围：AwayFromShorts-Phone 第一阶段升级（统一规则引擎、两个执行器、首页真实状态、应用规则管理、系统诊断、备份恢复、可靠性检查）。
> 约束：先检查不大规模重构；不引入 Room / Gradle / Kotlin；每完成一个模块立即构建与测试。
> 记录时间：2026-11-15。仓库 HEAD `87788bd`，工作区有 255 项未提交改动（属于既有开发线，未清理、未覆盖）。

## 1. 结论摘要

本轮完成两件事，都有构建与离线回归证据：

1. **构建脚本不再吞掉失败**。无障碍版的 `javac` 报错曾被管道 `grep ... || true` 吃掉并继续打包；现在失败即停，且不再产出 APK。
2. **判定核心已统一**。`com.afs.core.rules.WeeklyRule` 成为 DO 守卫与无障碍版共用的唯一判定实现，两侧各自保留执行器；逐分钟对拍证明行为与历史一致。

首页真实状态与倒计时、应用规则管理、诊断页、备份恢复尚未开始。设备端（真机）尚未验证本轮改动。

## 2. 基线证据

| 项目 | 命令 | 结果 |
| --- | --- | --- |
| DO 守卫构建 | `dpc/build.sh` | 退出 0，`afs-guard.apk` 31,987,680 字节 |
| 无障碍版构建 | `blocker/build.sh` | 退出 0，`afs-blocker.apk` 53,801 字节 |
| DO 离线回归 | `dpc/test/run.sh` | 退出 0，`207 PASS / 0 FAIL` |
| 无障碍版离线回归 | `blocker/test/run.sh` | 退出 0，`27 PASS / 0 FAIL` |
| DO 规则对拍 | `RuleParityTest` | `80955 checks passed` |
| 无障碍版规则对拍 | `GuardParityTest` | `8940 checks passed` |

构建前环境：`AFS_SDK=D:/afs-android/sdk`，Git Bash `D:\learn\Git\bin\bash.exe`。

### 2.1 已修掉的构建缺陷

- `blocker/build.sh`：`javac ... 2>&1 | grep -v '^warning:' || true` 会忽略编译失败，随后 d8/打包照常进行，装上的是没有新代码的旧 dex。现改为重定向到 `build/javac.log`，失败打印日志、清空 `classes`、`exit 1`。
- `blocker/test/run.sh`：同样的吞错逻辑，且 SDK 路径写死 `C:/Users/nj/.afs-android/sdk`。现改为失败即停 + 多路径探测（`AFS_SDK` / `ANDROID_HOME` / `ANDROID_SDK_ROOT` / `D:/afs-android/sdk` 等）。
- `blocker/build.sh` 的打包回退：Git Bash 没有 `zip` 时用 python 追加 `classes.dex`，原逻辑优先取 `python3`，而本机 `python3` 是 Microsoft Store 占位程序，执行即退出码 49，导致 `[5/6]` 静默失败。现改为逐个候选探测，跳过 `*WindowsApps/python*`，找不到可用解释器时明确报错。
- 日志读取加 `grep -a`，避免 `Binary file (standard input) matches` 把真正的报错藏起来。

失败路径验证：在隔离副本里插入非法 Java 后，构建退出码 1、打印编译错误、不再生成 `build/afs-blocker.apk`。

## 3. 规则模型与语义

共享核心 `core/rules/src/main/java/com/afs/core/rules/WeeklyRule.java`，纯 Java，无 Android 依赖，两个 `build.sh` 与两套离线回归都直接编译。

语义（迁移时不可改）：

- 时段左闭右开 `[start, end)`：22:00 生效、22:59 仍在、23:00 已结束。
- `start > end` 表示跨午夜，星期位看**起始日**：周五 22:00–01:00 覆盖周五 22:00–24:00 与周六 00:00–01:00。
- `start == end` 当成全天，不是空窗口。
- 两种模式互为补集：`ALLOW` 时段内放行（无障碍版的放行时段）；`FREEZE` 时段内拦截（DO 守卫的冻结时段）。**同一个起止时间，两种模式结论相反**，迁移只能按模式搬，不能只搬时间。
- `days == 0` 或没有任何时段：`ALLOW` 全天拦截、`FREEZE` 全天放行，与两个 APK 的历史行为一致。

### 3.1 接入方式

- DO：`Rule` 内部持有 `WeeklyRule`，`inWindow` = `!core.blocked(...)`，`nextFlip` 直接转交核心；`autoGate`、`normalize` 也复用核心。DO 特有语义仍留在 `Rule`：`gate`（学习闸门）、`freeze`、`groupKey`。
- 无障碍版：`Guard.inWindow` 把“一条全局放行时段”表达为 `WeeklyRule.allow(ALL_DAYS, {{start,end}})`；`force_until` 仍在执行器侧叠加（档位优先于时段），不属于规则本体。

### 3.2 对拍覆盖

- DO：一周 7×1440 逐分钟对比 `Rule.inWindow` 与 `WeeklyRule.blocked` 的补集；另比 `nextFlip` 在 14 个时间点（含跨午夜边界）、每日总时长与闸门阈值、分组签名、规范化与星期位映射。共 80955 项。
- 无障碍版：6 组起止（含全天、跨午夜、贴边 1 分钟）逐分钟对比；另钉住档位优先级与过期回退。共 8940 项。

## 4. 存储与迁移现状

- DO：`afs_guard/rules` JSON，字段 `d/g/freeze/w/at`；`RuleStore` 已能自动迁移旧版 `targets + days + start_min + end_min`。规则之外还有学习闸门、每日额度、应用额度、禁忌惩罚、奖励券与修改宽限期，这些**不属于** `WeeklyRule`。
- 无障碍版：SharedPreferences `afs_blocker`，键 `targets`(StringSet) / `start_min` / `end_min` / `force_until` / `configured`，只有一条全局时段，没有星期与多段。
- 迁移风险：改存储格式必须保留旧键（幂等、不删旧数据），并且旧数据没有 `mode` 字段时，DO 按 `freeze` 推断、无障碍版按“放行时段”推断，绝不能默认成同一个方向。

## 5. 模块顺序（每步都要构建 + 回归）

1. **构建与回归可靠性**（已完成）：失败路径闭合、解释器探测、SDK 探测。
2. **共享规则引擎与两版接入**（已完成）：`WeeklyRule` + 双端对拍。
3. **旧配置迁移适配**：DO `RuleStore` 旧 JSON、无障碍版全局窗口 → `WeeklyRule`，幂等、旧键保留、迁移前后逐分钟一致。
4. **首页真实状态与倒计时**：从真实规则/额度/闸门计算“当前冻结数、下一次切换时刻”，不写死文案；离屏/回前台重算。
5. **应用规则管理**：无障碍版补齐按应用规则编辑（沿用 DO 的星期 + 多段 + 模式语义），写入前预览。
6. **系统诊断页**：权限、DO、无障碍服务、Shizuku、守护进程、规则解析结果的可复制快照。
7. **备份恢复**：复用 DO `LocalBackup` 的校验/原子写入/回滚边界，补齐版本与预览，恢复受锁定状态约束。
8. **可靠性检查**：开机恢复、后台守护、跨午夜边界、时区/夏令时、低电量与 Doze。

## 6. 未验证与已知风险

- 本轮只做到“构建 + 离线回归”，**未在真机安装验证**；`topResumedActivity`、冻结生效、无障碍拦截都未实测。
- `dpc/keystore/afs.keystore` 被仓库追踪，`blocker/build.sh` 的签名口令为明文硬编码。这是需要决策的安全风险（轮换密钥或改为本地注入），未擅自改动。
- 构建与回归共用 `build/` 目录：`build.sh` 的 `rm -rf build` 会在回归运行中删掉 `build/test`，并发执行时出现 `NoClassDefFoundError`（本轮已实际遇到一次，单独重跑即通过）。尚未加互斥锁，建议后续把离线产物移出 `build/` 或加锁。
- `blocker/test/run.sh` 仍会打印 `注意: ... 过时 API` 的中文乱码（GBK 输出），不影响判定，属显示问题。

## 7. 复现命令

```bash
export AFS_SDK=D:/afs-android/sdk
cd /d/空间/afs-build/dpc     && ./build.sh && ./test/run.sh
cd /d/空间/afs-build/blocker && ./build.sh && ./test/run.sh
```

预期：四条命令退出码均为 0；DO 输出 `207 PASS / 0 FAIL` 与 `Weekly rule parity: 80955 checks passed`；无障碍版输出 `27 PASS / 0 FAIL` 与 `Guard parity: 8940 checks passed`。
