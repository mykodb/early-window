# Early Window

NeoForge / FancyModLoader 早期加载窗口（启动时那个红色加载页）的替代实现。通过 FML 的
`net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider` SPI 接管窗口，画一套终端（TUI）风格的
启动界面：`BOOT // startup` + `WATCH // runtime` 双面板、进度条、实时内存/线程/堆纹理、启动日志流。

仓库名、Gradle 工程名、jar 名、包名、provider 名现在都是通用的：`early-window` / `cn.mykodb.earlywindow` /
`earlywindow`。它当前由 Graft 项目使用，也可以给任何 NeoForge 21.1.x 实例用。

![终端风格启动界面](docs/screenshot.png)

它是 **独立 jar**，不是模组：没有 `neoforge.mods.toml`、没有 `@Mod`，靠
`META-INF/services/net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider` 被 FML 在 mod 扫描前
的 SERVICE 层发现。因此它必须单独放 `mods/`，不能合并进模组本体 jar（合并后整个 jar 会被提前提到
SERVICE 层，模组本体就不加载了）。

## 版本对应

`gradle.properties` 里的 `fml_version` 是唯一需要跟着升级的版本号。本实现按 FML 4.0.42 编写：

| NeoForge | FancyModLoader / earlydisplay |
| --- | --- |
| 21.1.233（已验证） | 4.0.42 |
| 21.1.x 其它小版本 | 一般同 4.0.x，升级后跑一次 `./gradlew check` 并冒烟 |
| 21.2+ | earlydisplay 内部会变，反射成员要重新核对 |

## 构建

```fish
./gradlew build            # 离线也可：./gradlew --offline build
./gradlew dist             # 在 build/distributions/ 生成发布 zip
```

产物：

- `build/libs/early-window-<version>.jar` —— provider 本体；
- `build/distributions/early-window-<version>.zip` —— 内含 `mods/`（jar）、`config/fml.toml` 示例、`README.md`。

依赖只用于编译（`compileOnly`，且关掉传递依赖）：`fancymodloader:earlydisplay`、`fancymodloader:loader`、
`lwjgl` / `lwjgl-opengl`、`slf4j-api` —— 运行期这些类由 FML 自己提供，jar 里不会带任何第三方 class。

首次构建需要联网解析依赖元数据；之后 `--offline` 也能构建。

## 排版断言（无 GPU）

```fish
./gradlew checkLayout      # 或 ./gradlew check（build 会带上）
```

`src/layoutcheck` 里有两组断言，都不需要显卡，也不会进入 provider jar：

- `LayoutCheck`：把 `TerminalLayout` 的计算结果套上绘制代码的关键约束
  （画布 854x480、scale=1/1.5/2、面板不越界、进度条不压页脚、标签列不重叠……），共 55 条；
- `LogicCheck`：ASCII 过滤、日志级别标签、ABGR 颜色打包/alpha 乘法、`clamp` 边界，共 24 条。

## 安装

1. 把 `early-window-<version>.jar` 放进实例的 `mods/`；
2. 编辑 `config/fml.toml`，把 `earlyWindowProvider` 改成 `"earlywindow"`：

```toml
earlyWindowProvider = "earlywindow"
```

3. 重启客户端，日志里应出现：

```
Loading ImmediateWindowProvider earlywindow
早期加载屏已接管（替换 4 个自定义元素）
```

## 本地开发（与 graft 模组仓库配合）

```fish
./gradlew installToGraftDev     # 把 jar 复制到 ../graft/run/mods
cd ../graft && ./gradlew runClient
```

graft 仓库的 `run/config/fml.toml` 已预设 `earlyWindowProvider = "earlywindow"`；它的 `runClient` 会先执行
`installEarlyWindow`（从 `../early-window/build/libs` 复制），所以通常只需要先在本项目 `build` 一次。

运行时冒烟脚本在 graft 仓库：`scripts/verify-earlywindow.sh`（启动客户端 → 抓到「已接管」→ grim 截图 →
收日志 → 杀进程），需要显卡/显示环境。

## 工作原理

- `EarlyWindow extends DisplayWindow implements ImmediateWindowProvider`
  - `name()` → `earlywindow`（与 `fml.toml` 的值匹配）；
  - `initialize()` 解析 `--fml.mcVersion` / `--fml.neoForgeVersion`，调 `super.initialize()`，再往渲染线程调度
    `installTerminalScreen()`（80ms 后、`elements`/`font` 就绪时替换）；
  - `addMojangTexture()` 覆盖为空 → 不显示 Mojang 过渡图标；
  - `updateModuleReads()` 自己实现（`self.addReads(neoforge)` + 反射拿
    `NeoForgeLoadingOverlay.newInstance`）。不能调 `super`：父类用 `getClass().getModule()`，子类化后会抛
    `IllegalCallerException: module fml_earlydisplay != module cn.mykodb.earlywindow`。
- 绘制元素全部来自 `TerminalScreen`：`chrome`（标题行 `early://boot` + 双面板边框 + 状态灯）、
  `boot`（stage/progress/handoff、tasks、log、底部进度条）、
  `watch`（mem/used/threads/uptime/sig/os/java/gl、堆 sparkline、watchdog）、`footer`（Minecraft / NeoForge 版本）。
- 因为 `RenderElement` 的构造参数类型（`Initializer`/`Renderer`）是包私有的，自定义元素用 `Proxy` +
  `privateLookupIn` 反射构造，集中在 `EarlyWindowAccess`；所有反射失败都会被吞成"退回默认界面"，不崩游戏。
- `TerminalLayout` 是纯函数：只做坐标/列宽计算，不碰 GL/FML，便于断言。

## 发布

打 tag 会触发 `.github/workflows/release.yml`：构建 → 跑断言 → `dist` 打包 → 创建 GitHub Release 并附上
jar 与 zip。本地等价操作：

```fish
./gradlew build dist          # build/distributions/early-window-<version>.zip
```

版本号在 `gradle.properties` 的 `version`；FML 版本在 `fml_version`。

## 许可

MIT，见 [LICENSE](LICENSE)。

## 已知限制

- `SimpleFont` 只覆盖 ASCII 32–126：非 ASCII 会被 `ascii()` 清成空格，所以界面文案、日志、mod 名都按 ASCII 处理；
- 逻辑画布固定 854x480（FML `DisplayContext` 创建时写死），窗口缩放交给 FML 的 letterbox 绘制；
- 早期窗口只有 ~20fps，动画按帧步进；
- 渲染回调里抛出的异常会被 `DisplayWindow` 吞掉，因此每个元素自己 `try/catch` 并节流上报（同名失败最多 3 次）。

## 调试

在游戏目录（开发环境是 `run/`）创建空文件 `early-window-dump` 后启动，会在第 30 / 55 帧把 854x480 的
离屏帧缓冲导出成 `run/tmp/earlywindow-fb-<frame>.ppm`。注意 FML 的离屏 FBO 是上下颠倒存储的，
`glReadPixels` 第 0 行就是画面顶部。

## 故障排除

- **`Module kfflang.neoforge reads more than one module named fml_earlydisplay`**
  同一份 `earlydisplay-*.jar` 以两个不同绝对路径进了 module path。常见原因是混用过
  `GRADLE_USER_HOME`（ModDevGradle 会把绝对路径写进 `build/moddev/*.txt`）。删掉 `build/moddev` 后用当前
  Gradle 用户目录重新生成即可。
- **日志只有 `Failed to find ImmediateWindowProvider earlywindow, disabling`**
  检查 `config/fml.toml` 的 `earlyWindowProvider` 拼写、jar 是否带 services 文件、jar 是否放在 `mods/` 顶层；
  dev 环境要确认 `run/mods/` 里有本 jar。
- **画面还是原版红底**：provider 没被选中（看上一条），或 `earlyWindowControl=false`。
