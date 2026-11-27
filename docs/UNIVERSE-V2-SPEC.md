# Universe V2：独立代表页面验收

## 范围与结构

保留简约、空间两种风格。只在共享 Compose 守卫首页及其外观页重做玻璃与流体；旧原生业务页面不扩展。HomeScreen、HomeViewModel、Repository、搜索、筛选及导航状态保持一份。

core/style-api：只负责纹理加载、材质绘制接口、坐标输入、生命周期。style/liquidglass：背景纹理采样、曲面折射与镜片形变。style/fluidgradient：三维距离场、表面光照、独立粒子力场。app：统一装配及导航；两种风格可以单独替换。

## Liquid Glass Universe

暗梅色环境，透明玻璃雕塑与液态金属形成远景。共享同一张背景图：背景与前景镜片都调用 environment()，按前景实际坐标采样，避免用另一套无关渐变冒充折射。曲面边缘偏移采样，RGB 分通道色散，手指位置驱动高光。主面板、次级按钮分别使用厚度与磨砂参数，文字区增加透射衰减以保证阅读。

按压：局部折射场凹陷、镜片压缩。拖动：折射场跟随与透视倾斜。释放：弹簧回弹及衰减波纹。页面变化：镜头拉近与空间位移，背景采样同步缩放。该方案是屏幕空间图像折射，不是路径追踪，也不折射文字与其他前景组件。

## Fluid Motion Universe

电蓝、紫罗兰、玫红和青绿液体主体。三维有符号距离场通过平滑并集形成融合颈部，独立液滴与布尔挖洞形成分离、聚合及凹腔；多频位移与旋转驱动形变。射线步进求表面交点，差分求法线，按法线计算漫反射、镜面高光和轮廓光。生成的液体雕塑图作为环境材质及远景纹理采样，图像也随位移场改变。

粒子围绕主体形成云层；切向力、局部旋涡力、速度积分与指数阻尼驱动运动。不同深度的辉光和运动拖痕形成层次。点击产生爆发脉冲，长按增强旋涡，拖动留下有数量上限的轨迹，快速滑动释放有方向的速度脉冲。该方案是程序化三维隐式曲面与粒子力场，不是 Navier–Stokes 流体求解器。

## 资产与生成方式

使用内置图片生成工具，两个独立 prompt，原图保留，项目副本为：

- style/fluidgradient/src/main/assets/visual/fluid-universe/sculpture-v2.png
- style/liquidglass/src/main/assets/visual/liquid-universe/sculpture-v2.png

流体 prompt：production portrait texture, thick translucent liquid sculpture with merging lobes, folds, cavities and droplets, cobalt/violet/magenta/teal internal scattering, physical specular reflections, dark navy negative space, no UI/text/lines/torus.

玻璃 prompt：production portrait environment texture, intricate thick optical glass sculpture with convex walls and concave cutouts, polished liquid chrome behind, amber/pearl/ice-blue caustics, dark plum studio, realistic reflection/refraction, no UI/text/simple spheres/graphic lines.

## 加载和降级

图片在选择风格后异步加载，不在启动时预加载两套图像。窗口暂停时取消时钟，关闭 Shader 与纹理引用；退出释放全部租约。静态图片租约不等于动画租约。HIGH：48 步光线步进和 96 个粒子；MEDIUM：28 步、40 粒子和较低更新率；LOW/减少动态：显示静态 3D 素材，停止持续 Shader 与粒子更新。低档仅是明确的兼容降级，不作为动态视觉验收结果。

AGSL 依赖 Android 13+，原理与 API 已按 Android 官方 RuntimeShader / Using AGSL 文档核对。

## Demo 与验收规则

AFS_UNIVERSE_DEMO=1 构建独立 com.afs.guard.universe 包，专属 provider、权限命名空间与启动入口，和日常守卫并存。Demo 复用同一份业务源码，但使用独立数据目录。未通过本页截图、手势录屏和真机性能验收前，不扩展其他页面、不更新生产安装。

验收必须核对真实 Shader 实例，而非仅确认截图成功；同时覆盖暂停/恢复、降档时钟停止、切换资源清零、同一 ViewModel 及搜索状态保留。截图与录屏路径、实际结果在运行后记录。任何未执行项保持未验收。
