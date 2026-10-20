# 故障排除

## 日志出现 `Failed to find ImmediateWindowProvider earlywindow, disabling`

provider 没被选中，退回 NeoForge 默认界面。依次检查：

1. `config/fml.toml` 里 `earlyWindowProvider = "earlywindow"` 拼写是否正确；
2. jar 是否在 `mods/` 顶层（不要放进子目录）；
3. jar 内是否有 `META-INF/services/net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider`，
   内容为 `cn.mykodb.earlywindow.EarlyWindow`；
4. `earlyWindowControl` 没有被改成 `false`；
5. 开发环境确认 `run/mods/` 里有本 jar（可执行 `./gradlew installToGraftDev`）。

## `Module kfflang.neoforge reads more than one module named fml_earlydisplay`

同一份 `earlydisplay-*.jar` 以两个不同绝对路径进了 module path。最常见原因是混用过
`GRADLE_USER_HOME`（ModDevGradle 会把绝对路径写进 `build/moddev/*.txt`）。删掉 `build/moddev`
后用当前 Gradle 用户目录重新生成即可。

## 画面还是原版红底加载页

说明 provider 没有被使用，先看第一条；如果配置正确，检查是否把 jar 合并进了模组本体 jar
（合并后整个 jar 会被提前加载，provider 反而可能不生效，模组本体也会加载失败）。

## 画面文字被截断 / 面板错位

启动界面是固定 854x480 逻辑画布、再被 FML 等比 letterbox 到窗口，窗口比例不影响布局。
如果怀疑布局被改坏，跑 `./gradlew checkLayout` 并查看 `docs/design.md` 的调试导出方法。
