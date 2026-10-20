# 实现说明

## 工作原理

FML 在 mod 扫描之前就会创建启动窗口，它的位置由 `config/fml.toml` 的 `earlyWindowProvider` 决定：

- FML 用 `ServiceLoader` 在 SERVICE 层找 `net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider`；
- `mods/` 里带 `META-INF/services/...ImmediateWindowProvider` 的 jar 会被提前加载到 SERVICE 层，
  并从普通模组发现中排除——所以本 jar 不需要 `neoforge.mods.toml`，也不会被当成模组。

实现主体是 `EarlyWindow extends DisplayWindow implements ImmediateWindowProvider`：

- `name()` 返回 `earlywindow`，与 `fml.toml` 的值匹配；
- `initialize()` 解析 `--fml.mcVersion` / `--fml.neoForgeVersion` 后调 `super.initialize()`，
  再往渲染线程调度 `installTerminalScreen()`（等 `elements` / `font` 就绪后替换 FML 默认元素）；
- `addMojangTexture()` 覆盖为空，不显示 Mojang 过渡图标；
- `updateModuleReads()` 自己实现（`self.addReads(neoforge)` + 反射取 `NeoForgeLoadingOverlay.newInstance`）。
  不能调 `super`：父类用 `getClass().getModule()`，被继承后拿到的是本 jar 的模块，会抛
  `IllegalCallerException`。

绘制元素由 `TerminalScreen` 提供（`chrome` / `boot` / `watch` / `footer`）。
自定义元素需要 `RenderElement` 的包私有构造参数类型（`Initializer` / `Renderer`），因此用 `Proxy` +
`privateLookupIn` 反射构造，集中在 `EarlyWindowAccess`；任何反射失败都记录日志并退回默认界面，不中断启动。

`TerminalLayout` 是纯函数（坐标、列宽、面板划分），不碰 GL/FML，因此可以脱离显卡断言。

## 断言

`./gradlew checkLayout`（`build` 也会执行）：

- `LayoutCheck`：854x480 画布、scale=1/1.5/2 下，面板不越界、进度条不压页脚、标签列不重叠等 55 条；
- `LogicCheck`：ASCII 过滤、日志级别标签、ABGR 颜色打包/alpha 乘法、`clamp` 边界等 24 条。

断言源码在 `src/layoutcheck`，不进入发布 jar。

## 版本兼容

`gradle.properties` 的 `fml_version` 是唯一需要跟随升级的版本号：

| NeoForge | FancyModLoader / earlydisplay | 状态 |
| --- | --- | --- |
| 21.1.233 | 4.0.42 | 已验证 |
| 21.1.x 其它小版本 | 一般为 4.0.x | 升级后跑 `./gradlew check` 并冒烟 |
| 21.2+ | earlydisplay 内部成员会变 | 反射相关代码需要重新核对 |

## 调试

在游戏目录（开发环境为 `run/`）放一个空文件 `early-window-dump` 再启动，会在第 30 / 55 帧把 854x480
离屏帧缓冲导出到 `run/tmp/earlywindow-fb-<frame>.ppm`。

注意 FML 的离屏 FBO 是上下颠倒存储的：`glReadPixels` 第 0 行就是画面顶部，写 PPM 时不要再翻转。
