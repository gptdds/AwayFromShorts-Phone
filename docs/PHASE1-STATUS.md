# 第一阶段：现状检查与实施记录

> 2026-11-16 更新：规则迁移、首页/编辑入口、系统诊断、JSON 规则备份与恢复及可靠性修补已完成；两版最终构建和离线回归通过，API 34 模拟器最终包分别通过 15 / 18 项行为检查。PLR110 真机已安装 1.91.91，升级后审计 10 项通过、13 个配置摘要前后一致、诊断页全部正常。当前交付和限制见 [PHASE1-REPORT.md](PHASE1-REPORT.md)。以下保留 2026-11-15 的历史检查记录，其中“尚未做”不代表当前状态。

日期：2026-11-15。范围限定：只在现有原生 Java 技术栈内改动，**不引入 Room / Gradle / Kotlin**；
每个模块改完立刻构建并跑离线回归。本文记录已核对的事实、已完成的模块和仍未动的部分。

## 一、构建链路（先修，否则所有"通过"都不可信）

| 问题 | 证据 | 处理 |
| --- | --- | --- |
| `blocker/build.sh` 把 `javac` 输出管道给 `grep ... \|\| true` | 在隔离副本插入非法 Java，构建退出码仍是 0，并继续产出 `base.apk` | 改为 `2> build/javac.log`，失败即打印日志、清空 `classes`、`exit 1` |
| `blocker/test/run.sh` 同样吞掉编译错误 | 探针目录只报 `ClassNotFoundException: GuardTest`，看不出是编译失败 | 同上；并把写死的 `C:/Users/nj/.afs-android/sdk` 换成多路径探测 |
| 日志里的中文报错被当成二进制 | 输出 `Binary file (standard input) matches`，真实错误被藏起来 | 读取改用 `grep -a` |
| `dpc/build.sh` 正常路径读日志有同样问题 | 构建日志出现 `Binary file build/javac.log matches` | 同样加 `-a`（失败分支原本已正确 `exit 1`） |

修完后用探针复测：非法源码 → `javac 失败，停止构建。` + 退出码 1 + 不再生成 `afs-blocker.apk`。

注意：`dpc/build.sh` 开头 `rm -rf build`，`dpc/test/run.sh` 也用 `build/test`。两个脚本**必须串行跑**，
并行或与上一次构建重叠会出现"类文件只编译了一半"的假故障（本轮实测到一次
`NoClassDefFoundError`，串行重跑即恢复）。

## 二、两个 APK 的规则语义（迁移前必须钉住的事实）

| | DO 守卫（dpc） | 无障碍版（blocker） |
| --- | --- | --- |
| 存储 | `afs_guard/rules` JSON，每应用一条 | `afs_blocker` 单条全局时段 |
| 时段含义 | `freeze=true` → 时段内**冻结** | 时段是**放行**窗口，时段外才拦 |
| 星期 | 7 位位图，跨午夜凌晨算**起始日** | 无星期概念 |
| 叠加层 | 学习闸门、每日额度、应用额度、禁忌罚时、奖励券 | 档位（force_until），不可提前解除 |
| 边界 | `[start, end)`，`start==end` 为全天，`start>end` 跨午夜 | 同上 |

结论：**不能只迁移起止时间**。`freeze` 与"放行窗口"方向相反，迁移必须连模式一起搬；
学习闸门、额度、档位仍留在各自外层，不进共享核心。

## 三、模块 M1：共享判定核心（已完成）

新增 `core/rules/src/main/java/com/afs/core/rules/WeeklyRule.java`（纯 Java，无 Android 依赖）：

- 模式只有 `ALLOW` / `FREEZE` 两种，互为补集，不允许第三种；
- `blocked(星期, 分钟)` 是唯一判定出口，`nextFlip()` 给出下一次状态翻转时刻；
- `totalWindowMinutes()` 保留"每天可用时长 → 是否进学习闸门"的旧算法；
- `signature()` 给分组卡片和去重用的稳定键。

接线（两处都收敛到这一份实现，不再各算一套）：

- `dpc/java/com/afs/guard/Rule.java`：`inWindow` / `nextFlip` / `normalize` / `autoGate` 全部委托给 `WeeklyRule`；
- `blocker/java/com/afs/blocker/Guard.java`：`inWindow` 把单条全局时段表达成 `ALLOW` 模式单段规则。

构建脚本已把 `../core/rules/src/main/java` 加进两个 `build.sh` 和两个 `test/run.sh` 的源码集。

## 四、本轮实际跑过的验证

| 项目 | 命令（环境 `AFS_SDK=D:/afs-android/sdk`，JDK17 在 PATH） | 结果 |
| --- | --- | --- |
| 无障碍版构建 | `blocker/build.sh` | 退出码 0，`afs-blocker.apk` 53,801 字节 |
| 无障碍版回归 | `blocker/test/run.sh` | `27 PASS / 0 FAIL` + `Guard parity: 8940 checks passed` |
| DO 构建 | `dpc/build.sh` | 退出码 0，`afs-guard.apk` 31,987,680 字节，版本 1.91.85 |
| DO 回归 | `dpc/test/run.sh` | 退出码 0，含 `Weekly rule parity: 80955 checks passed` |

对拍覆盖：7 天 × 1440 分钟逐点比对、14 个采样点的 `nextFlip`、每天总时长与闸门阈值两侧、
模式互补、非法行规范化、星期映射。

说明：委托之后 `RuleParityTest` 变成"接线自检"，独立语义仍由各自的黄金用例钉住
（`dpc` 的 `GuardTest` 207 项、`FreezeRuleTest`、`FreezeSlotTest`，`blocker` 的 `GuardTest` 27 项）。

## 五、尚未做（按原计划顺序，每步都要构建 + 回归）

1. **首页真实状态与倒计时**：当前状态、下一段、剩余时间走共享核心，不再各页面自算。
2. **应用规则管理**：无障碍版目前没有规则编辑页，只有一条全局时段；补齐前先确认是否要动作业范围。
3. **系统诊断页**：DO 有无障碍/DO/Shizuku/通知权限的实际状态，不靠推测。
4. **备份导出 / 导入预览 / 回滚**：`LocalBackup` 已有 Zip 校验、允许路径、原子写、恢复前日志和回滚，
   但它受 `mayRestore()` 锁定限制，导出的版本号、预览和锁定绕过边界仍需逐项核对。
5. **可靠性检查**：DO 冻结、无障碍拦截、Shizuku force-stop、开机恢复、后台守护的实机验证清单。

## 六、需要你决定的安全问题（我没有擅自改）

- `dpc/keystore/afs.keystore` 被公开仓库跟踪；
- `blocker/build.sh` 里签名口令是硬编码明文。

两条都只能改成本地注入或轮换密钥，涉及已发布 APK 的升级连续性，必须先由你确认方向。

## 七、明确未验证

- 没有装到真机验证外观/交互，本轮只验证构建与离线判定；
- `steamservice.exe` 这类不带 `steam*` 前缀的进程是否该放行，未在实机观测；
- 无障碍版的规则编辑、诊断、备份功能尚不存在，谈不上验证。
