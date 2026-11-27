# 第二阶段：三核心组件架构与边界

日期：2026-11-16。范围按用户要求先做 3D 专注环、3D 应用卡片、交互式时间轴，不整体重写页面。

## 1. 实际技术栈

- 业务与执行核心：`dpc/java` 原生 Java。`Guard`、`RuleStore`、`Rule`、`WeeklyRule`、Device Owner、额度、学习闸门、无障碍与 Shizuku 路径保持原实现。
- Compose 样式线：`app`、`feature/home`、`feature/componentlab`、`core/style-api`、`style/liquidglass`、`style/fluidgradient`、`style/spatial3d`。业务数据通过只读适配器 `ExistingHomeRepository` 进入 UI。
- 构建：两套路径共存。原生兼容包走 `dpc/build.sh`；正式 Compose 包走 `dpc/release/build-release.ps1 -Compose`。不新增第二套业务核心。

## 2. 可改与不可改

可改：

- 新增独立预览 Activity 与 `feature/phase2` 组件模块。
- 只读映射 `RuleStore`、`Rule`、`Guard.blockReasonNow`、`Guard.nextScheduledChange` 与真实应用图标。
- Compose Canvas、手势、触觉、动效与渲染降级。

不可改：

- `Guard` 的冻结/放行判定、Device Owner 调用、无障碍拦截、Shizuku force-stop。
- 额度、学习闸门、禁忌罚时、锁定和调整时段规则。
- `RuleStore` 的存储格式、迁移和写入语义。
- 备份、诊断、开机恢复和后台守护。

## 3. 三个组件

### 3D 专注环

- 数据：`Guard.nextScheduledChange`、实际冻结数量、受管应用数量、当前计划状态。
- 进度：从当前时刻向前逐分钟扫描同一套 `Guard.blocked(Rule, Calendar)`，找到最近一次计划状态变化；用“当前段已过时间 / 当前段总时间”计算环进度，不在渲染层重算规则。
- 材质：多层 Canvas 环体、暗部接触阴影、外缘/内缘高光、扫光、刻度与中心发光。背景遮住时仍由环体自身明暗成立。
- 交互：按压压缩、触摸方向轻微倾斜、松手弹簧、长按展开真实状态详情。动效不参与倒计时计算。

### 3D 应用卡片

- 数据：真实包名、图标、名称、实际冻结原因、规则时段、下一次计划切换时间。
- 材质：接触阴影、第二层背板、表面渐变、边缘高光、图标凹槽。状态同时用文字、图标和颜色表达。
- 交互：点击压缩并展开详情；长按抬升与触觉反馈；触摸只产生倾斜/视差，不制造虚假拖动排序，也不修改业务状态。

### 交互式时间轴

- 数据：选中真实应用的 `Rule.days`、`Rule.freeze` 与 `Rule.windows()`。
- 交互：24 小时刻度、多个时段、起点/终点拖动、5 分钟吸附、实时数值气泡、添加/删除、跨午夜、星期选择、重叠提示、撤销未保存编辑、冻结/放行预览。
- 无障碍：每个时段同时提供开始/结束的 ±15 分钟按钮和朗读描述，不强迫使用拖动。
- 保存边界：编辑仍是内存草稿，只有用户点击保存后才写入。`RuleTimelineRepository` 先校验调整时段窗口和时段重叠，再按 `FreezeSlotStore.scheduleKey` 找到所属冻结时段分组，把新日程同步到该组全部成员；没有分组时复用同日程分组或创建新分组，最后更新 `RuleStore`、`FreezeSlotStore` 并触发 `Guard.applyAsync`。

## 4. 渲染与性能

- HIGH：完整环体高光、扫光、粒子和卡片视差。
- BALANCED：减少发光层和粒子数量，保留按压/拖动反馈。
- LOW / 减少动效：静态材质、无持续粒子、无扫光，保留全部功能。
- 粒子只在专注环页面可见且页面处于前台时运行；其他页面和后台停止。
- 组件不创建业务线程，不调用 `DevicePolicyManager`，不读写规则。

## 5. 风格插件

- `feature/phase2` 只暴露 `Phase2VisualPlugin` 契约：背景、专注、限额、每日总览四块能力，以及风格 id、排序和评审指示器元数据。
- 三套内置风格分别在 `style/liquidglass`、`style/spatial3d`、`style/fluidgradient` 注册自己的插件实现，不复用预览舞台里的枚举选型。
- `ComposePhase2Catalog` 通过现有 `StyleRegistry` 发现所有带 `Phase2VisualPlugin` 能力的风格；新增第四套风格只需注册渲染器、组件能力与 Phase 2 插件，不需要修改 `Phase2ArtReviewStage`。
- 预览入口只保存插件 id，切换时按 `order` 轮转；旧的 `LIQUID_GLASS`、`SPATIAL`、`FLUID_GRADIENT` 参数继续兼容。
- `Phase2ArtDirection` 仍可留在功能模块内部作为具体画笔实现细节，但不再是预览入口和风格模块之间的公开选型接口。

## 6. 验收

- 真机安装正式签名 APK，用 `android.permission.DUMP` 保护的预览入口验证，不暴露给普通第三方应用。
- 截图覆盖专注环、应用卡片、时间轴和拖动/撤销后的状态。
- `gfxinfo`、CPU、内存与页面静止采样记录真实数据；模拟器结果单独标注。
- 回归：原有规则、迁移、备份、额度、学习联动与设备所有者检查继续通过。
