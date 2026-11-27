# 多风格引擎最终验收跟踪

目标是完成原始多风格任务，而非把已完成的首页验证当作整个任务完成。
按用户最新确认：保留原有简约风格，再增加 Liquid Glass、Spatial 3D、Fluid Gradient，共四种。此前撤下玻璃风格的决定已被这次确认取代。

| 要求 | 当前权威证据 | 状态 |
| --- | --- | --- |
| Kotlin、Compose、Material 3、Coroutines、StateFlow、Navigation 可构建 | Gradle 开发包与 SharedHomeStyleTest | 已接入；迁移并非覆盖所有旧页面 |
| 单一业务核心，无复制 Store/Repository 业务实现 | app 从权威 dpc/java 生成兼容编译源码，ExistingHomeRepository 仅适配读取 | 已验证首页路径 |
| 共享状态/事件/导航，不随风格切换重建 | HomeScreenState/HomeUiAction/HomeViewModel 与 Compose 重建测试 | 首页路径已验证 |
| 业务、交互结构、视觉严格分层 | core/model、core/domain、feature/home | 完整页面与视觉契约仍待补齐 |
| DesignTokens/MotionTokens/StyleRegistry/StyleManager/RendererFactory | core/style-api 复用权威 Java API；StyleEngineDeviceTest 19 项 | View 与 Compose 共用单一目录和生命周期归属 |
| ComponentContracts 与共享语义结构 | Background/Card/Action；HomeScreen 共享语义；三种原生渲染器移入对应风格模块 | 四个风格模块可独立 assembleDebug；应用仅保留原生元数据与转接；API 24 四风格回归 19/19，跨进程持久化 2/2 通过 |
| 简约风格保留、默认与回退 | PaperStyle 与未知/故障工厂回退测试 | 已验证旧 View 路径 |
| Liquid Glass 独立包、半透明面板、高光、折射/模糊、弹性按钮、导航指示器 | style/liquidglass，LiquidGlassComponents；共享背景场采样保持文字清晰，HIGH 9 点/MEDIUM 3 点模糊；GlassNavigationPainter 复用原有导航几何和事件 | Shader 暂停/恢复、档位与画面已验证；原生导航高光/形变、减少动效、失败回退与释放已由 NativeNavigationStyleTest 验证 |
| 四种风格同时可选、简约继续为默认 | BundledStyles + ComposeStyleCatalog；原设置两列选项；SharedHomeStyleTest、VisualEntryTest | 四选项、名称不截断、默认与切换保留状态均已验证 |
| HIGH 下连续四风格切换、实际出帧与旧效果停止 | HighStyleSwitchTest，两轮共 8 次切换，每次等待至少 3 次完成帧 | 同一 ViewModel、搜索、数据读取次数不变；旧资源租约归零已验证；不替代真机性能结论 |
| Fluid Gradient 完整视觉组件与按需 Shader | FluidComponents，FluidLifecycleTest | 真正 AGSL 加载、边框/波纹及暂停释放已验证；视觉复核待完成 |
| Spatial 3D 卡片、透视、手势视差、多层布局、弹簧与转场 | SpatialComponents、SpatialInteractionTest | 轻量空间层已接入；手势不抢滚动/点击已验证；视觉复核待完成 |
| 独立 spatial3d 模块、可选真实场景按需加载与失败回退 | style/spatial3d、LazySceneController、LazySceneTest | 通用懒工厂/预算/取消已验证；当前无真实模型页面，不加载重型引擎；真实引擎未宣称验收 |
| 新风格注册无需修改业务核心 | ComponentRegistryTest 与旧 View Probe | 两条路径均验证延迟工厂、独立扩展与故障回退 |
| 设置随时切换，保留表单/搜索/筛选/导航/ViewModel | SharedHomeStyleTest，StyleEngineDeviceTest，MemoStyleStateTest | 首页和设置路径已验证；真实备忘录编辑器在四风格下保留标题/正文、光标选区、原控件、撤销历史和 Markdown 预览；各风格实际撤销/重做按钮通过；其他旧页面仍需扩大验证 |
| 切换不额外读取业务数据或触发网络 | HomeRepository.readCount 在 UI 测试中保持；适配器无新增网络调用 | 首页已验证；其余页面需扩大验证 |
| DataStore 持久化与旧值迁移 | VisualPreferencesRepository、StylePreferencesBridge；两个独立进程的 StylePersistenceTest | 已验证恢复、仅首次导入及备份键白名单 |
| 平滑切换 | 只有背景装饰 Crossfade；导航使用共享视觉参数 | 已实现并复验，旧 Shader 立即停止，页面树不参与替换 |
| 旧风格动画停止、Shader/场景资源释放 | VisualResources、FluidLifecycleTest、LazySceneTest | 暂停恢复、旧 Shader 清零、失败清理及晚到场景关闭已验证 |
| HIGH/MEDIUM/LOW/REDUCED_MOTION | EffectQualityManager、EffectQualityDeviceTest | 30/15fps、静态低档、减少动效优先均已实现；持续降档作用当前实例已验证 |
| 按真实测量和设备能力降级，不按型号判断 | FrameMetricsWatcher 使用 TOTAL_DURATION 和当前 Display 刷新率；FrameBudget 统一换算；API 能力及 lowRam 检查 | 60/90/120Hz、无效采样和超慢帧共 14 项纯函数检查通过；窗口采样/手动档位及四风格连续切换的 2 项设备检查通过；不能替代真机性能数据 |
| 独立业务/渲染器单元与 Compose UI 测试 | HomeStateTest、SharedHomeStyleTest、StyleEngineDeviceTest | 后续新增风格均需补测 |
| 真机性能测试与资源预算 | 当前仅模拟器 | 待设备连接后验收；不能用模拟器代替真机证据 |
| 独立设置模块与真实入口 | feature/settings、AppearanceViewModel；VisualEntryTest、SharedHomeStyleTest | 已验证原设置入口、菜单、返回同步与两个 ViewModel 保留 |
| 最终可用集成、发行与既有功能回归 | 最新 D 盘主工程保留现有业务修复；离线主套件 207 PASS / 0 FAIL 且所有补充套件通过；API 24 开发包四风格 19/19、持久化 2/2；正式签名 1.91.73 / 2383 在 API 24 通过 8/8 实际入口、空间触点、备忘录、导航、玻璃面板和 HIGH 连续切换 | 已生成并核验生产 APK、2384 维护 APK、源码包和 Windows 包；云端发布、更多旧页面及真机性能仍未验收 |

只有每个仍待完成的条目都获得匹配范围的代码和运行证据后，才允许将整个任务标为完成。
