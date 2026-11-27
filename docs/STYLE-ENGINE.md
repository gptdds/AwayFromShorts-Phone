# 可插拔视觉引擎：增量实施

## 当前范围：四种风格

本地上一个分发版本为 1.91.72 / 2381（维护包 2382）：包含刷新率帧预算、原生玻璃卡片、空间触点与冻结容器接入、独立原生渲染模块。发行与维护包签名、权限隔离和源码打包校验通过；DEX 中已检查 NativeGlassRenderer/NativeFluidRenderer/NativeSpatialRenderer/FrameBudget 实际存在。正式包在 API 24 独立模拟器通过 7 项真实页面状态/入口/四风格连续切换检查，记录 `archive/dumps/afs-1.91.72-release-device.log`。APK SHA-256 为 `736E626D2F0097A61E2E5ACE714B3DE737537DDF5D8FA267C04AA6B898E0C36F`。下方早期“尚未更新分发包”仅属历史记录。

1.91.73 / 2383（维护包 2384）加入装饰根坐标到屏幕坐标的统一换算。`PointerCoordinates` 只处理坐标，不消费事件；Spatial 原生渲染器在根布局移动、系统栏或窗口偏移时换算到正确视图坐标后再做倾斜。API 24 上四风格真实设备回归 19/19 通过，覆盖设置页与冻结页空间触点、菜单、备忘录状态、玻璃材质、导航、流体与玻璃资源释放、HIGH 连续切换和真实入口；跨进程 DataStore 持久化另按两次独立进程通过 2/2。正式发行 APK 在 API 24 通过 8/8 实际入口、空间触点、备忘录、导航、玻璃面板和 HIGH 四风格连续切换，日志见 `archive/dumps/2026-11-14-afs-1.91.73-release-device.log`。开发回归日志为 `archive/dumps/2026-11-14-afs-four-style-pointer-device.log` 与 `2026-11-14-afs-four-style-persistence-device.log`。完整离线回归主套件 207 PASS / 0 FAIL，Pointer 8 项、FrameBudget 14 项及其余所有补充套件通过，日志见 `archive/dumps/2026-11-14-afs-style-offline-final.log`。生产 APK SHA-256 为 `CF8A15E199DF5F61D1EDFC643BBEE153CD8EA7705CDBC20D470EB91EF7E05E16`；签名、版本、维护接收器隔离与源码/Windows 包清单已复核。这些证据仍来自模拟器，不宣称真机性能或完整拖放手势已验收。

1.91.74 / 2385（维护包 2386）补齐旧 View 页面的流体卡片边框。`NativeFluidPanels` 只扫描带统一语义角色的卡片并绘制圆角描边，不替换页面背景，也不接管按钮、输入框或手势；卡片后加载、滚动与布局变化会刷新，所有卡片共用一个 Sweep Shader。HIGH/MEDIUM 按页面帧推进，LOW/减少动效保持静态。Android 7 专项用例验证了后加载卡片、草稿与选区、原背景和重复释放均不受影响，并保存真实备忘录页面截图。发行包设备结果和真机结果以本次交付日志为准。

原生渲染模块边界已进一步收敛：NativeGlassRenderer、NativeFluidRenderer、NativeSpatialRenderer 分别移入现有玻璃、流体、空间模块；应用的 LiquidGlassStyle/FluidGradientStyle/SpatialStyle 只保留元数据与 attach/detach 转接。语义角色 ID 与显示名从应用装配端注入，各实现仅依赖 Android 和 core/style-api，不导入业务 Store、页面类、应用资源或翻译工具。原生兼容构建也直接编译同一模块 Java 源码，不维护副本。四个风格模块单独 assembleDebug 成功，记录 `archive/dumps/afs-independent-styles-build.log`；完整应用构建及离线回归通过。完整设备回归结果另见 `afs-native-modules-device.log`，不以编译通过替代运行验证；该阶段之后已在 1.91.73 更新正式分发包。

冻结页定制容器后续接入：总览与冻结时段外层卡片现在使用标准视觉角色，应用小格不增加嵌套视觉面板。拖入高亮期间临时退出材质装饰，复位后重新按正常背景生成材质；NativeGlassPanels 保留原阴影基线，不会把上一轮视觉阴影记作页面原值。开始拖动前通过共享指针接口取消倾斜，CANCEL 立即恢复几何，UP 继续阻尼回弹。原拖入/拖出和时间窗口判定代码未改动。真实冻结总览与设置页的倾斜/恢复、材质高亮退出/重入和备忘录草稿共 4 项设备用例通过，日志 `archive/dumps/afs-freeze-style-device.log`；完整离线回归通过。该证据尚不包含真机完整拖放手势验收，正式分发包仍未更新。下面早期“冻结页没有语义标记”的记录已被本次接入取代。

旧 View 空间交互新增只读指针观察契约：ThemedActivity 将坐标交给当前根所属渲染器，再原样调用原 dispatchTouchEvent；渲染器不返回事件消费结果，不替换已有触摸监听器。SpatialStyle 对标准语义卡片最多倾斜 2.5 度，松手/取消后按共享弹簧参数阻尼回弹，退出取消动画并恢复旋转、相机距离和阴影。LOW/减少动效不倾斜，没有待机循环。真实 SettingsActivity 的触摸倾斜、恢复及同一内容根已验证，记录 `archive/dumps/afs-native-spatial-settings-device.log`；完整离线回归通过。冻结页使用定制容器，当前没有标准语义卡片标记，初次测试找不到卡片的失败记录保留在 `afs-native-spatial-device.log`，不把该页记为已接入。备忘录状态与原生导航在同次测试中通过。尚未更新正式分发包。

真实旧页面状态验收新增 `MemoStyleStateTest`：启动未保存的新备忘录，在编辑、Markdown 预览和格式化后撤销/重做三个阶段分别遍历四种注册风格；检查原 Activity 内容根、标题/正文控件、历史实例、光标选区和预览状态保持。格式化后实际点击撤销与重做按钮，验证四种风格下均恢复正确文本。关闭测试编辑器后持久化备忘录和冻结规则不变，偏好恢复并等待 DataStore 写入完成。API 24 测试通过，记录工作区 `archive/dumps/afs-memo-style-undo-device.log`；此证据仅覆盖备忘录路径，不代替其他旧页面验收。

旧 View 标准卡片现由 `style/liquidglass/NativeGlassPanels` 提供静态玻璃渐层和高光描边，角色 ID 从装配端注入，不依赖业务或页面类。保留原始圆角、padding、子视图与输入状态，布局后加载的语义卡片自动应用；关闭时恢复仍由材质层持有的背景和阴影，页面自己更新过的背景不会被覆盖。LOW/减少动效不增加持续时钟。这是原生卡片材质接入，不宣称旧页面已获得 Compose 场景采样折射。Android 7 的统一圆角 getter 空数组异常已兼容；构建及离线回归通过。设备结果记录工作区 `archive/dumps/afs-native-glass-final-device.log`，尚未更新正式分发包。

用户最新确认原简约风格保留，再增加液态玻璃、空间 3D、流体渐变，一共四种。下文早期“撤下玻璃、三种风格”的记录是当时阶段历史，现已被这次确认取代。

新增独立 `style/liquidglass`，仅依赖 `core/style-api`，使用相同页面状态、事件、导航与资源租约。背景与玻璃面板采样同一个场景函数，面板边缘做折射偏移和局部多点模糊，不把前景文字放入模糊层。HIGH 9 点采样、MEDIUM 3 点采样，最多 9 个 Shader（背景和可见面板合计）；LOW/减少动效使用静态材质。弹性按压使用共享 MotionTokens。原设置页改为两列，自适应换行，默认简约不变。

四风格切换、Shader 生命周期和实际画面复核已通过；液态导航指示器已接入原有导航。旧页面更完整接入与真机性能仍需完成。

### 主工程迁移后的合并验证（2026-11-14）

主工程实际迁移到 `D:/空间/afs-build`，已与最新 1.91.70 核心合并。仅合入视觉模块、装配、选择页和导航材质，保留新的冻结格子、运行状态与额度修复，没有用恢复副本覆盖业务源码或用户配置。

- 最新完整离线回归通过，记录 `archive/dumps/afs-four-style-offline.log`（工作区 archive）。
- API 34 独立模拟器 14 项设备用例全部通过，记录 `archive/dumps/afs-four-style-settled-device.log`。首次运行被 Android 系统无响应弹窗挡住，失败记录保留，关闭弹窗后完整重跑通过。
- NavigationPainter 只接受绘制参数，独立 GlassNavigationPainter 复用 FloatingNav 的选择几何与弹簧，不接管页面跳转；无待机时钟。风格/质量变化、页面暂停及 detach 释放画笔资源，工厂失败只尝试一次并回退简约材质。测试检查实际底栏、规则/记录不变及减少动效停止。
- 原设置入口的四个选项为两列，名称不省略；实际截图已复核。SharedHomeStyleTest 的偏好恢复在 Activity 销毁后执行，并移除自己注册的故障夹具，避免污染完整测试目录。
- `build-release.ps1 -Compose` 使用同一版本来源和外部发行签名，构建包含四个风格包、共享设置模块及 DataStore 的完整 APK；旧手工构建保留用于兼容验证。发行包与维护包的生产签名及维护接收器隔离由打包工具核验。本地构建不等于云端发布或真机安装。
- API 24 开发包通过 13 项兼容用例，记录 `archive/dumps/afs-four-style-api24-device.log`。正式签名包 1.91.71 / 2379 在独立 API 24 模拟器通过 8 项实际入口/切换/生命周期用例，以及两个独立目标进程的 DataStore 写入、强制结束后恢复用例；记录 `afs-four-style-release-api24-device.log`、`afs-four-style-release-persist-{write,read}.log`。生产包不带调试/测试权限；维护包为 2380 且仅它注册 DUMP 权限保护的恢复接收器。

### 四风格阶段验证结果

- `four-styles-device-build.log`：完整开发包/测试包构建成功；共享页面实际点击四种风格，保留草稿、筛选、导航、两个 ViewModel 与读取次数，UI 用例通过。
- `four-styles-lifecycle.log`：4 个用例通过，验证玻璃背景/面板 Shader 真正创建、HIGH/MEDIUM/LOW 与减少动效、暂停恢复/切回简约清理、原设置两列四个选项无省略及旧流体生命周期。
- `four-style-high-switch-device.log`：HIGH 下两轮共 8 次四风格切换，每次等待完成帧、旧效果租约归零；同一业务状态和读取次数保持，规则导出保持不变。
- `four-style-rendered-capture-device.log`：四个风格和原设置页截图采集用例通过。每个视觉参照在目标风格启动，确认渲染器 ID、业务加载完成及至少 2 次完成帧后截图。实际图已检查，玻璃面板、高光和按钮材质可见，设置四个名称完整。
- 模拟器 UiAutomation 截图曾返回空图/旧帧，首次失败结果保留在 `four-style-capture-device.log`；截图通道改用系统 screencap 回退并等待目标完成帧。未把首次截图失败记为通过，实时切换另由上述 UI/HIGH 用例验证。
- 最新开发 APK SHA-256：`35B51D516ABC0557103E65324216982BCEF50EA00EA810A3059697CEB3EC062B`。仅验证模拟器，未安装到真机或作为正式发布。

截图位于 `build/compose-phase2/preview-{paper,liquidglass,spatial3d,fluidgradient,style-options}.png`，可用 `dpc/test/pull-style-previews.py --serial <emulator serial>` 重新提取。以上工作尚未完成液态导航、旧页面更完整呈现、正式发布与真机性能，不把四个选项已可用当作整个任务完成。

## 2026-11-13 工程检查

### 刷新率自适应补充（2026-11-14）

FrameMetricsWatcher 读取窗口当前 Display 刷新率，以 FrameBudget 把真实完成帧成本换算为等效 60Hz 成本。相同 20ms 在 60/90/120Hz 分别计入 20/30/40ms，因此高刷新率设备不再沿用固定毫秒预算。无效刷新率回退 60Hz，无效耗时忽略，有效超慢帧封顶计入，避免被原异常样本过滤器误丢弃。手动档位与减少动效的优先级保持不变。

FrameBudgetTest 共 14 项检查通过；完整离线回归 207 PASS / 0 FAIL 及附加套件通过；Gradle 开发包构建通过。API 24 独立模拟器的 EffectQualityDeviceTest、HighStyleSwitchTest 共 2 项通过，记录工作区 `archive/dumps/afs-frame-budget-device.log`。此补充尚未重新打入 1.91.71 正式分发包，不将开发验证等同于已发布。

当前为 Java 8 + 原生 View、多 Activity；minSdk 24、target/compile SDK 34。
通过 aapt2/javac/d8 构建，无 Gradle、Kotlin、AndroidX、Compose、DataStore。
已有 DesignTokens、MotionTokens、质量管理、简约与渐变效果；此前注册表复用渲染实例，设置切换 recreate，暂停页面未释放特效。
按用户此前修正保留简约，Liquid Glass 不再注册、不在选择列表出现；Spatial 3D 尚未实现，不展示空入口。

## 具体拆分与依赖

| 目标模块 | 内容 | 依赖 |
| --- | --- | --- |
| core/model | 纯业务模型、ScreenState、UiAction | Kotlin 标准库 |
| core/domain | 冻结、额度、学习等规则与 UseCase，Repository 接口 | model |
| core/data | 现有 Store、同步、数据库适配；未来 DataStore 风格偏好 | domain、model |
| core/navigation | 共享路由、导航栈 | model |
| core/designsystem、core/motion | 设计、动效 token | 无具体风格 |
| core/style-api | RendererFactory、StyleRenderer、ComponentContracts | tokens、motion |
| core/style-manager | 注册、选择、生命周期、质量策略 | style-api、偏好接口 |
| feature/home、feature/settings | 唯一共享状态持有者、状态与事件、页面语义 | domain、navigation、style-api |
| style/paper、style/fluidgradient、style/spatial3d | 纯视觉实现 | style-api，不依赖 data/domain |
| spatial3d/scene | 真正需要模型/纹理场景时才引入的引擎适配 | style-api；不得在 Application 初始化引擎 |
| app | 装配实现与依赖注入 | features、data、styles |
| benchmark | 切换、帧耗时、内存与生命周期验证 | 测试目标 |

当前阶段用现有源码包实现边界，不创建空 Gradle 模块冒充完成拆分。
未来 Gradle 阶段使用依赖配置强制这些单向边界；业务核心不得导入具体风格。
新增风格只需增加工厂并在 app 装配点注册。

## 第一阶段最小实现

- StyleScreenState/StyleUiAction 为实际主题设置入口的不可变状态与事件，非新增业务副本。
- StyleRegistry 保存 RendererFactory 元数据；列出选项不构造渲染器。
- StyleManager 持有可见根视图的资源。换风格先清理旧资源，create/attach 失败回退 paper；失败实例仍会 detach/release。
- released 实例不复用；旧 Activity 的 release(root) 不会清理新页面的资源。
- ThemedActivity 在恢复时附着，暂停/销毁时按根视图释放；只观察 style_id/effect_quality，不改业务状态与导航。
- 设置点击仅更新选择装饰，保留 Activity、控件、输入、焦点与滚动，不保存 appearance revision，也不调用 recreate。
- 简约是默认与兼容回退；已保存的 liquidglass 或未知 id 按简约渲染，保留原偏好以便诊断。
- 修复自动质量降档条件反向和 LOW 渐变覆盖层不透明的问题。

## 后续阶段与验收边界

第一阶段结束时尚未接入 Kotlin/Compose/Material 3、StateFlow ViewModel、DataStore 或 Gradle 多模块；第二阶段进展见下方。
下一阶段先引入可构建的 Kotlin/Compose 宿主及共享首页状态/事件，保留原业务 Store 与设备所有者组件；验证无重复请求、无新业务副本后再迁移页面。
风格偏好随后通过 DataStore 单次迁移，验证重启持久化；当前仍使用既有 SharedPreferences。
之后开发共享组件渲染契约、Fluid Gradient、Spatial 3D 的卡片层；普通卡片使用轻量变换，真实场景需求确定后才评估引擎与懒加载。
平滑切换、持续帧测量自动降档、组件弹簧、复杂场景资源预算仍属后续验收项，不以现有背景覆盖层代替完整视觉引擎。
Compose UI 测试要在 Compose 迁入后执行；当前使用原生 Instrumentation 验证实际页面、状态保留及失败清理。

## 第一阶段验证结果

- `dpc/build.sh` 构建成功；开发验证包 `dpc/build/style-phase1.apk`，不覆盖 `dist/1.91.63` 已发行包。
- `dpc/test/run.sh` 完整回归成功：主套件 207 PASS / 0 FAIL，所有附加套件通过。
- API 34 模拟器 `StyleEngineDeviceTest`：19 assertions passed。实际点击主题页的渐变选项，同一 Activity、预览实例与输入草稿保持；未知风格回退、返回恢复、离开释放、旧根视图隔离、工厂延迟创建与重复 apply 幂等均通过。
- 注入 attach 和 detach 同时失败的测试渲染器，确认 release 仍执行、装饰视图清除、回退 paper。原冻结规则和学习记录未变；测试恢复原风格与质量偏好。
- 使用独立模拟器验证，尚未执行本阶段真机性能测量，未宣称完整三风格或 Compose 迁移完成。

## 第二阶段：共享 Compose 首页

真实模块依赖为 `app → feature/home → core/domain → core/model`，app 负责把旧核心装配到 HomeRepository 接口。

- `core/model`：不可变 HomeScreenState、HomeSnapshot、ManagedApp 与 HomeUiAction；搜索与筛选操作不修改数据快照。
- `core/domain`：HomeRepository 只读接口，无 Android、Compose、风格或数据库依赖。
- `feature/home`：HomeViewModel 使用 Coroutines、StateFlow 和 SavedStateHandle；共享 Compose 页面只接收状态与事件，列表使用稳定业务 ID。
- `app`：ExistingHomeRepository 在 IO 线程调用既有 Guard/RuleStore 判定；源码直接引用 `dpc/java` 与 `plugin-api/java`，没有复制业务 Store、规则或 UseCase。
- SharedHomeActivity 的 ViewModel 在 Activity 作用域创建，NavHost 位于风格选择之外。搜索、筛选由同一 ViewModel 保管；导航和列表位置使用 Compose 的状态恢复机制。风格切换更新原有 StyleManager；不改变 NavHost key、不重新查询应用数据。
- 共享首页显示受管应用快照、搜索、只看冻结与刷新，并能前往现有打卡、冻结管理页面；现有打卡启动入口不变。当前首页先作为阶段验证入口，不替换尚未完成迁移的原页面。
- Manifest 每次从 `dpc/AndroidManifest.xml` 生成，保留原包名、Application、设备所有者及服务声明，再增加非导出的 SharedHomeActivity，避免维护两套业务清单。

构建版本固定为 AGP 8.5.2、Gradle 8.14.3、Kotlin 1.9.24、Compose compiler 1.5.14、Compose BOM 2024.06.00、Material 3 1.2.1、Lifecycle 2.8.4、Navigation 2.7.7。
参考 [官方 Compose/Kotlin 对照表](https://developer.android.com/jetpack/androidx/releases/compose-kotlin)、[AGP 8.5 的 SDK 兼容范围](https://developer.android.com/build/releases/agp-8-5-0-release-notes)、[ViewModel 作用域](https://developer.android.com/topic/libraries/architecture/viewmodel/viewmodel-apis)。选择已缓存、能适配当前 SDK 34 的组合，并非宣称这些是最新版本。

在本机中文 Windows 路径下，原生 AIDL 依赖文件和 JVM 测试 classpath 有编码问题：仅将生成产物放入 `$GRADLE_USER_HOME/afs-builds/<工程hash>`，AIDL 临时输入放入已校验范围的 ASCII 临时目录，源码仍保留原位置。共享 Gradle 缓存被外部清除后，设备验证脚本改用 `~/.afs-android/gradle-home`，避免与其他项目共用易被清理的缓存。提供 Gradle Wrapper；`local.properties` 使用本机 SDK 路径且不入库。开发签名沿用现有开发 keystore，不用于正式发行。

构建：`./gradlew.bat :core:model:test :app:exportComposeArtifacts`。
模拟器验收：`dpc/test/run-compose-home-device.ps1 -Serial emulator-5560`。
验证包、设备结果和首页截图输出到 `build/compose-phase2/`。

本阶段仍未迁移原有全部页面、DataStore、组件渲染契约、Spatial 3D、平滑过渡与真机性能基准；简约风格继续保留。Gradle 入口与原 `dpc/build.sh` 并存，原发行流程未切换到迁移中的构建。

### 第二阶段验收结果

- Gradle 开发包与测试包构建成功；`HomeStateTest` 1 个用例通过，覆盖同一快照的名称/包名搜索与冻结筛选组合。
- API 34 模拟器运行真实 Compose UI 测试：`OK (1 test)`，包含搜索输入、筛选、导航到外观、切换渐变、Activity 重建、切回简约、返回首页的完整路径。
- 断言同一个 HomeViewModel 保留，query 与 blockedOnly 不丢失，Activity 重建后仍在外观路由；原 HomeRepository.readCount 不增加，原冻结规则字符串保持一致。测试结束恢复风格和质量偏好。
- 使用 `dpc/test/run-compose-home-device.ps1` 直接启动 AndroidJUnitRunner，避免本机 Gradle UTP 的结果传输故障。此脚本是已验证的设备验收入口，不把失败的 UTP 会话记为通过。
- 实际首页截图已复核：3 个卸载后保留名单的应用显示“未安装 · 名单保留”，冻结中数量为 0。
- 本阶段开发包 `build/compose-phase2/app-debug.apk`，SHA-256：`E39E0710A4C8869A5BD150A3341695979E60F843DEC2D1034727513D2BF9946D`；截图 `build/compose-phase2/home.png`，设备测试记录 `build/compose-phase2/device-test.txt`。仅安装到模拟器，未覆盖真机正式版。

## DataStore 迁移阶段

`core/style-manager` 新增独立 VisualPreferencesRepository，固定 DataStore 1.1.1；数据保存在 `files/datastore/visual-style-v1.preferences_pb`。
首次初始化在一个原子 edit 中导入旧 style_id/effect_quality 并记录导入标记；之后启动以 DataStore 值恢复，不再用旧值覆盖。
StylePreferencesBridge 是 Application 生命周期的兼容适配：旧 Java 页面继续读取 afs_ui 镜像，所有修改按顺序进入 DataStore；初始化和写入均在后台线程，用户在初始化期间的新选择优先保留。读取/保存失败通过 StateFlow 反馈到外观页，不影响冻结核心。
仅非导出的 ContentProvider 在启动时初始化偏好管道，不构造渲染器或 3D 引擎；Manifest 仍从单一旧清单生成。风格与档位镜像键已加入 LocalBackup 白名单，备份恢复会通过同一适配管道重新持久化。

验证：

- Gradle 开发包/测试包构建成功；迁移后的 SharedHomeStyleTest 完整交互用例通过。
- `run-style-persistence-device.ps1` 在两个独立进程执行测试：保存 fluidgradient/low，确认真实 DataStore 文件和已存值；强制结束进程后读取恢复，传入不同旧值再次初始化也不会覆盖，最后恢复原偏好。两个用例均 `OK (1 test)`。
- 两个新增镜像键的备份白名单断言通过；完整旧回归 `207 PASS / 0 FAIL`，所有附加套件通过。
- 最终开发包 SHA-256：`2CE1B24EB5C0B8A036075F14A9E62A33456F6B172A820F7CFA7349E0D43364AF`。
- 把截图采集从状态/导航验收用例中移除：模拟器 PixelCopy 偶发超时只属于截图通道问题，不作为业务或风格状态失败。此前第二阶段已复核的 home.png 保留为历史截图，不声称它是本阶段新截图。

完整任务剩余项与证据范围见 [最终验收跟踪](STYLE-ACCEPTANCE.md)；尚未完成共享组件渲染、Spatial 3D、完整视觉效果与真机性能验收。

## 共享组件、流体与空间模块（2026-11-14）

实际依赖增加 `app → style/{paper,fluidgradient,spatial3d} → core/style-api`；`feature/home → core/style-api`，不依赖任何具体风格。
StyleRegistry 继续是唯一目录，既有 RendererFactory 描述可用风格，带类型的懒工厂提供 Compose ComponentContracts；枚举和启动偏好管道均不构造视觉实例。新风格只在装配点注册，不修改 HomeViewModel、Repository 或业务规则。

API 的 Java 权威源码仍在 `dpc/java/com/afs/guard/style`，Gradle 用过滤后的 Sync 生成库源码；app 的兼容源码生成步骤排除这些 API，避免同名类出现在两个模块。生成副本只存在构建目录，不维护两份源码。

ComponentContracts 包含 Background、Card、Action 与视觉导航参数。NavHost、搜索、筛选、列表位置和 ViewModel 均在风格容器之外；只有背景装饰参与交叉淡化。旧背景在淡出期间使用静态回退，原持续动画和 GPU 引用立即释放，不保留两套页面。

- 简约：使用原有配色与轻描边，不增加常驻动画。
- 流体：单个共享绘制时钟、API 33+ AGSL 背景、Canvas 兼容回退、流动卡片边框和按压渐变波纹；HIGH 约 30fps、MEDIUM 约 15fps；LOW 和减少动效没有持续时钟或 Shader。
- 空间：独立 `style/spatial3d`，graphicsLayer 透视、手势视差、双层卡片、统一 MotionTokens 弹簧及空间导航；只响应交互，没有待机渲染循环。触摸以 Final 阶段观察，不消费列表或按钮事件。旧 View 页通过 Ui 的视觉角色标签提供轻量阴影，退出恢复原值。
- VisualResources 对时钟、弹簧和 Shader 使用幂等租约；暂停直接取消协程，某一清理失败不阻止其他资源释放。
- 可选真实场景：LazySceneController 的工厂仅在显式 request 时调用，支持兼容检查、加载状态、32MiB 默认预算、加载失败回退、取消与晚到资源回收。当前页面不需要真实模型，未捆绑 Filament、模型或纹理；通用加载器的测试不等于真实 3D 引擎兼容验收，也不意味着动态下载模块已实现。
- 自动档读取可见窗口 FrameMetrics.TOTAL_DURATION，排除首帧，持续慢帧分级降至中、低；不再使用渐变 tick 的间隔。降档通知当前渲染器；手动档保留，系统/应用减少动效始终优先。采样器随窗口暂停而移除。

阶段证据：`build-compose-spatial-test.log` 的共享状态/三风格切换测试通过；`build-compose-spatial-quality-device.log` 共 6 个用例通过，覆盖空间手势/按钮/滚动、场景预算与取消、真实窗口采样到达及自动降档生效、Shader 暂停恢复和失败清理。进一步加入背景淡化与故障风格宿主回退后的最终复验结果需另行记录。

仍需完成正式入口和发行流程集成、扩大既有页面状态回归及真机性能测量；本阶段只安装模拟器开发包，原手机正式版与发行目录未覆盖。

## 工作副本恢复与独立设置模块

2026-11-14 00:17 左右，原 afs-build 目录的源码和工程文件在工作过程中消失；原因与新位置未确认。恢复操作放在独立目录，不覆盖原位置或改变回收站内容：

`C:/Users/nj/Documents/Codex/2026-11-10/new-chat-4/afs-recovery-20261114/working-project`

恢复依据是本次成功源码补丁、Gradle 捕获的实际 Java 编译输入、资源合并记录和最新开发 APK。仅解码应用资源，业务 Java 来自编译输入，Kotlin 来自成功源码补丁。恢复的 6 个 AIDL 接口用 SDK 34 重新生成，除注释和空白外与原生成代码全部一致。资源合并记录确定应用自己的资源清单，避免将 AndroidX 的资源复制成应用源码。原 Git 状态、历史测试源码和发布脚本未被冒充为已恢复。

完整 app 与 androidTest APK 重建成功，恢复后 9 个设备测试通过；开发签名可以覆盖安装到原模拟器，没有卸载或清空应用数据。248 个捕获的 Java 源文件经哈希核对，后续只修改 ThemeActivity 的视觉设置入口与状态同步，其余保持原样。

新增 `feature/settings`，依赖 `core/style-api`，通过 AppearancePreferences 端口获取唯一 StyleScreenState StateFlow 并提交原 StyleUiAction。AppearanceViewModel 不知道 DataStore、具体渲染器或业务 Store；app 的 StylePreferencesBridge 适配持久化和旧 Java 页面。原 StyleScreenState/StyleUiAction 权威 Java 源码也进入共享 API 编译，app 不再编译重复副本。

原主题页增加“查看风格效果”入口；从共享页面返回时原选择器与特效档位原位同步，不重建预览或页面。未包含该宿主的旧构建不会显示无法打开的入口。启动页继续是原打卡入口。

验证：

- `settings-entry-device-build.log`：完整构建及共享 Compose UI 测试通过，包含菜单选择减少动效/低档、三风格切换、故障回退，两个 ViewModel、草稿、筛选、导航及读取次数保持。
- `settings-entry-device.log`：3 个用例通过，覆盖实际原主题入口、返回同步且预览实例保持，以及 Shader 暂停恢复和清理。
- `restored-app-device.log`：恢复后原先 9 个引擎设备用例全部通过。
- 当前只连接 API 34 模拟器；正式发布构建、更多旧页面验证和真机性能验收仍未完成。
