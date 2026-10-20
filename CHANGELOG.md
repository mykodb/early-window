# Changelog

## 1.0.0 — 2026-10-20

首个版本。

- TUI 风格 FML early window provider（provider 名 `earlywindow`，包 `cn.mykodb.earlywindow`）：
  标题行 + `BOOT // startup` / `WATCH // runtime` 双面板 + 状态灯 + 页脚；
- BOOT 面板：stage / progress / handoff、活动进度表、启动日志流、底部进度条（含不定长与高光动画）；
- WATCH 面板：内存字符条、used / threads / uptime / sig / os / java / gl、64 样本堆 sparkline、watchdog 指示；
- 不显示 Mojang 过渡图标（覆盖 `addMojangTexture`）；
- 深色配色（运行时把 `ColourScheme` 切到 BLACK）；
- 55 条排版断言 + 24 条纯函数断言（无 GPU 可跑）；
- `dist` 发布 zip（jar + `fml.toml` 示例 + README）；
- 帧缓冲 dump 调试手段（触发文件 `early-window-dump`）。
