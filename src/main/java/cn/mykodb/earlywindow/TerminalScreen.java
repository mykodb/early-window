package cn.mykodb.earlywindow;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import net.neoforged.fml.earlydisplay.ElementShader;
import net.neoforged.fml.earlydisplay.QuadHelper;
import net.neoforged.fml.earlydisplay.RenderElement;
import net.neoforged.fml.earlydisplay.SimpleBufferBuilder;
import net.neoforged.fml.earlydisplay.SimpleFont;
import net.neoforged.fml.loading.progress.ProgressMeter;
import net.neoforged.fml.loading.progress.StartupNotificationManager;
import org.lwjgl.opengl.GL11C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 早期加载屏的绘制内容：TUI / 终端风格。
 *
 * <p>所有元素都画在 FML 固定的 854x480 逻辑画布上（坐标再乘以 {@code context.scale()}），
 * 由 {@link EarlyWindow} 在 FML 初始化完默认元素之后整体替换。</p>
 *
 * <p>构图按字符网格来：面板边框、进度条这类“线/块”用矩形绘制（{@link Batch}），
 * 文字与点线 leader 用 FML 自带的 Monocraft 字体（只覆盖 ASCII 32..126，所以没有中文、
 * 也没有制表符与方块字符）。</p>
 *
 * <p>颜色是 ABGR 打包 int（红在最低字节），与 {@link SimpleBufferBuilder#colour(int)}、
 * {@link SimpleFont.DisplayText} 的约定一致，因此统一用 {@link #rgba(int, int, int, int)} 生成。</p>
 */
final class TerminalScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger("EarlyWindow");

    // ---------------------------------------------------------------- 终端配色

    private static final int GREEN = rgba(60, 255, 122, 255);
    private static final int PINK = rgba(255, 107, 154, 235);
    private static final int CREAM = rgba(240, 245, 240, 235);
    private static final int DIM = rgba(150, 168, 155, 205);
    private static final int FAINT = rgba(150, 168, 155, 105);
    private static final int WARN = rgba(255, 94, 74, 235);
    private static final int TRACK = rgba(255, 255, 255, 34);
    private static final int BORDER = rgba(120, 255, 170, 66);
    private static final int CURSOR = rgba(60, 255, 122, 215);

    // ---------------------------------------------------------------- 运行时数据

    /** 每个绘制元素的失败次数，用于限制异常日志的刷屏。 */
    private static final Map<String, Integer> FAILURE_COUNTS = new ConcurrentHashMap<>();
    /** 同一元素最多记录多少次完整堆栈。 */
    private static final int MAX_LOGGED_FAILURES = 3;

    /** ThreadMXBean 查一次就够，别每帧走一遍 ManagementFactory。 */
    private static final ThreadMXBean THREAD_BEAN = loadThreadBean();

    private static final long START_NANOS = System.nanoTime();
    private static final int HEAP_SAMPLES = 64;
    private static final int[] HEAP_HISTORY = new int[HEAP_SAMPLES];
    private static int heapCursor;
    private static int heapSamples;
    private static long lastHeapSampleNanos = System.nanoTime();

    private TerminalScreen() {}

    /** 构造替换 NeoForge 默认界面的全部元素；顺序即绘制顺序。 */
    static List<RenderElement> create(SimpleFont font, String minecraftVersion, String neoForgeVersion,
                                     String glVersion) {
        Random random = new Random();
        String uid = String.format(Locale.ROOT, "0x%08X", random.nextInt());
        String sig = String.format(Locale.ROOT, "0x%04X", random.nextInt(0x10000));
        List<RenderElement> elements = new ArrayList<>(4);
        elements.add(element("chrome", (bb, ctx, frame) -> drawChrome(bb, ctx, font, frame, uid)));
        elements.add(element("boot", (bb, ctx, frame) -> drawBootPanel(bb, ctx, font, frame)));
        elements.add(element("watch", (bb, ctx, frame) -> drawWatchPanel(bb, ctx, font, frame, sig, glVersion)));
        elements.add(element("footer", (bb, ctx, frame) -> drawFooter(bb, ctx, font, minecraftVersion, neoForgeVersion)));
        if (Dump.enabled()) {
            elements.add(element("dump", (bb, ctx, frame) -> Dump.capture(ctx, frame)));
        }
        return elements;
    }

    private static RenderElement element(String name, RenderCallback callback) {
        return EarlyWindowAccess.newElement((bb, ctx, frame) -> {
            try {
                callback.render(bb, ctx, frame);
            } catch (Exception | LinkageError failure) {
                // FML 渲染线程会吞异常并让窗口卡住，这里自己兜住，保证加载界面继续工作。
                // 但绘制是每帧执行的：持续失败时不能每帧打一条堆栈（50ms/帧 = 每秒 20 条），
                // 只在前几次记录完整堆栈，之后降为一次性提示。
                int seen = FAILURE_COUNTS.merge(name, 1, Integer::sum);
                if (seen <= MAX_LOGGED_FAILURES) {
                    LOGGER.error("早期加载屏绘制失败（{}，第 {} 次），本帧跳过", name, seen, failure);
                } else if (seen == MAX_LOGGED_FAILURES + 1) {
                    LOGGER.error("早期加载屏绘制失败（{}）已重复超过 {} 次，后续不再记录堆栈",
                            name, MAX_LOGGED_FAILURES);
                }
            }
        });
    }

    // ---------------------------------------------------------------- 版面计算

    private static TerminalLayout.Metrics layout(RenderElement.DisplayContext ctx, SimpleFont font) {
        return TerminalLayout.compute(ctx.scaledWidth(), ctx.scaledHeight(), ctx.scale(),
                font.stringWidth("M"), lineHeight(font));
    }

    // ---------------------------------------------------------------- 外壳：外框 / 标题行 / 面板 / 状态灯

    private static void drawChrome(SimpleBufferBuilder bb, RenderElement.DisplayContext ctx, SimpleFont font,
                                   int frame, String uid) {
        TerminalLayout.Metrics l = layout(ctx, font);
        float reveal = clamp(frame / 18f, 0f, 1f);
        Batch chrome = new Batch();

        // 窗口外框：极细、低透明，只用来“收边”
        chrome.add(0, 0, l.width(), 1f * l.s(), withAlpha(BORDER, 0.45f * reveal));
        chrome.add(0, l.height() - 1f * l.s(), l.width(), l.height(), withAlpha(BORDER, 0.45f * reveal));
        chrome.add(0, 0, 1f * l.s(), l.height(), withAlpha(BORDER, 0.3f * reveal));
        chrome.add(l.width() - 1f * l.s(), 0, l.width(), l.height(), withAlpha(BORDER, 0.3f * reveal));

        // 标题行下的横线
        chrome.add(l.margin(), l.ruleY(), l.width() - l.margin(), l.ruleY() + 1f * l.s(),
                withAlpha(BORDER, 0.55f * reveal));

        // 两个面板的边框（直接绘制矩形，不用字符）
        float panelH = l.panelBottom() - l.panelTop();
        float t = 1f * l.s();
        outline(chrome, l.leftX(), l.panelTop(), l.leftW(), panelH, t, withAlpha(BORDER, 0.85f * reveal));
        outline(chrome, l.rightX(), l.panelTop(), l.rightW(), panelH, t, withAlpha(BORDER, 0.85f * reveal));

        // 状态灯（方块 = 灯，右边跟标签）
        float lampY = l.panelBottom() + 10f * l.s();
        float lamp = 6f * l.s();
        boolean blink = (frame / 15) % 2 == 0;
        chrome.add(l.leftX(), lampY, l.leftX() + lamp, lampY + lamp, withAlpha(blink ? GREEN : FAINT, reveal));
        chrome.add(l.leftX() + 120f * l.s(), lampY, l.leftX() + 120f * l.s() + lamp, lampY + lamp,
                withAlpha(FAINT, reveal));
        chrome.add(l.leftX() + 240f * l.s(), lampY, l.leftX() + 240f * l.s() + lamp, lampY + lamp,
                withAlpha(blink ? PINK : FAINT, reveal));
        chrome.flush(bb, ctx);

        // 标题行：左边品牌 + 闪烁光标，右边 uid / tty
        String brand = "early://boot";
        text(bb, ctx, font, l.margin(), 10f * l.s(), brand, withAlpha(GREEN, reveal));
        if ((frame / 10) % 2 == 0) {
            float cursorX = l.margin() + font.stringWidth(brand) + 6f * l.s();
            Batch cursor = new Batch();
            cursor.add(cursorX, 13f * l.s(), cursorX + l.cw() * 0.62f, 13f * l.s() + l.lh() - 9f * l.s(),
                    withAlpha(CURSOR, reveal));
            cursor.flush(bb, ctx);
        }
        String uidText = "uid " + uid + "   tty0 /early-net";
        text(bb, ctx, font, l.width() - l.margin() - font.stringWidth(uidText), 10f * l.s(), uidText,
                withAlpha(PINK, 0.75f * reveal));

        // 面板标题
        text(bb, ctx, font, l.leftX() + 10f * l.s(), l.titleY(), "BOOT", withAlpha(GREEN, reveal));
        text(bb, ctx, font, l.leftX() + 10f * l.s() + font.stringWidth("BOOT") + 8f * l.s(), l.titleY(),
                "// startup", withAlpha(FAINT, reveal));
        text(bb, ctx, font, l.rightX() + 10f * l.s(), l.titleY(), "WATCH", withAlpha(GREEN, reveal));
        text(bb, ctx, font, l.rightX() + 10f * l.s() + font.stringWidth("WATCH") + 8f * l.s(), l.titleY(),
                "// runtime", withAlpha(FAINT, reveal));

        // 状态灯标签
        float labelY = lampY - 7f * l.s();
        text(bb, ctx, font, l.leftX() + lamp + 6f * l.s(), labelY, "BOOT", withAlpha(blink ? GREEN : DIM, reveal));
        text(bb, ctx, font, l.leftX() + 120f * l.s() + lamp + 6f * l.s(), labelY, "LINK", withAlpha(FAINT, reveal));
        text(bb, ctx, font, l.leftX() + 240f * l.s() + lamp + 6f * l.s(), labelY, "SYNC",
                withAlpha(blink ? PINK : FAINT, reveal));
    }

    // ---------------------------------------------------------------- BOOT 面板：阶段 / 日志 / 进度条

    private static void drawBootPanel(SimpleBufferBuilder bb, RenderElement.DisplayContext ctx, SimpleFont font,
                                      int frame) {
        TerminalLayout.Metrics l = layout(ctx, font);
        float reveal = clamp(frame / 18f, 0f, 1f);
        float innerX = l.leftX() + 12f * l.s();
        float innerW = l.leftW() - 24f * l.s();

        List<ProgressMeter> meters = StartupNotificationManager.getCurrentProgress();
        String stage = "boot";
        String progress = "...";
        boolean indeterminate = true;
        float fraction = 0f;
        if (!meters.isEmpty()) {
            ProgressMeter meter = meters.get(0);
            stage = truncate(meter.label().getText(), font, innerW - 12f * l.cw());
            if (meter.steps() > 0) {
                fraction = clamp(meter.progress(), 0f, 1f);
                progress = Math.round(fraction * 100f) + "%";
                indeterminate = false;
            }
        }

        float rowY = l.contentTop();
        keyRow(bb, ctx, l, font, rowY, innerX, innerW, "stage", stage, withAlpha(CREAM, reveal));
        rowY += l.lh() + 3f * l.s();
        keyRow(bb, ctx, l, font, rowY, innerX, innerW, "progress", progress, withAlpha(GREEN, reveal));
        rowY += l.lh() + 3f * l.s();
        keyRow(bb, ctx, l, font, rowY, innerX, innerW, "handoff", "wait", withAlpha(DIM, reveal));
        rowY += l.lh() + 3f * l.s();

        // 进度条放在面板底部，先算出它的位置（日志不能压到它）
        float barH = 9f * l.s();
        float barY = l.panelBottom() - 24f * l.s();
        float labelY = barY - l.lh() - 2f * l.s();

        rowY = rule(bb, ctx, l, rowY, innerX, innerW, reveal) + l.lh() * 0.4f;

        // 活动进度表：所有还在跑的 FML 进度条（真实数据）
        text(bb, ctx, font, innerX, rowY, "tasks", withAlpha(FAINT, reveal));
        rowY += l.lh();
        int tasks = Math.min(4, meters.size());
        for (int i = 0; i < tasks; i++) {
            ProgressMeter meter = meters.get(i);
            String label = truncate(meter.label().getText(), font, innerW - 10f * l.cw());
            String value = meter.steps() > 0
                    ? Math.round(clamp(meter.progress(), 0f, 1f) * 100f) + "%"
                    : "...";
            text(bb, ctx, font, innerX + l.cw(), rowY, label, withAlpha(DIM, reveal * 0.9f));
            text(bb, ctx, font, innerX + innerW - font.stringWidth(value), rowY, value,
                    withAlpha(i == 0 ? GREEN : FAINT, reveal));
            rowY += l.lh();
        }
        if (tasks == 0) {
            text(bb, ctx, font, innerX + l.cw(), rowY, "waiting for loader", withAlpha(FAINT, reveal));
            rowY += l.lh();
        }

        rowY = rule(bb, ctx, l, rowY, innerX, innerW, reveal) + l.lh() * 0.4f;

        // 启动日志：最多 5 行，越旧越淡
        List<StartupNotificationManager.AgeMessage> messages = StartupNotificationManager.getMessages();
        text(bb, ctx, font, innerX, rowY, "log", withAlpha(FAINT, reveal));
        rowY += l.lh();
        float logY = rowY;
        int lines = 0;
        for (int i = 0; i < messages.size() && lines < 5 && logY + l.lh() <= labelY - 4f * l.s(); i++, lines++) {
            StartupNotificationManager.AgeMessage message = messages.get(i);
            String raw = message.message().getText();
            String tag = logTag(raw);
            int tagColor = "warn:".equals(tag) || "error:".equals(tag) ? WARN : GREEN;
            float alpha = clamp(1f - message.age() / 12000f, 0.2f, 1f) * reveal;
            text(bb, ctx, font, innerX, logY, tag, withAlpha(tagColor, alpha));
            float textX = innerX + font.stringWidth(tag) + l.cw() * 0.5f;
            String body = truncate(raw, font, innerX + innerW - textX);
            text(bb, ctx, font, textX, logY, body, withAlpha(DIM, alpha));
            logY += l.lh();
        }

        // 进度条本体：标签 + 百分比 + 轨道 + 填充 / 不定长扫动
        String barValue = indeterminate ? "..." : progress;
        text(bb, ctx, font, innerX, labelY, stage, withAlpha(DIM, reveal));
        text(bb, ctx, font, innerX + innerW - font.stringWidth(barValue), labelY, barValue,
                withAlpha(GREEN, reveal));

        Batch bar = new Batch();
        bar.add(innerX, barY, innerX + innerW, barY + barH, withAlpha(TRACK, reveal));
        bar.flush(bb, ctx);

        float fillLeft;
        float fillRight;
        if (indeterminate) {
            float band = 0.22f;
            float phase = (frame % 90) / 90f;
            fillLeft = innerX + (phase * (1f + band) - band) * innerW;
            fillRight = fillLeft + band * innerW;
            fillLeft = Math.max(innerX, fillLeft);
            fillRight = Math.min(innerX + innerW, fillRight);
        } else {
            fillLeft = innerX;
            fillRight = innerX + fraction * innerW;
        }
        if (fillRight > fillLeft) {
            Batch fill = new Batch();
            fill.add(fillLeft, barY + 1f * l.s(), fillRight, barY + barH - 1f * l.s(), withAlpha(GREEN, reveal));
            // 高光带循环扫过
            float band = 70f * l.s();
            float travel = fillRight - fillLeft + band;
            float bandLeft = fillLeft - band + travel * ((frame % 60) / 60f);
            float clipLeft = Math.max(fillLeft, bandLeft);
            float clipRight = Math.min(fillRight, bandLeft + band);
            if (clipRight > clipLeft) {
                fill.add(clipLeft, barY + 1f * l.s(), clipRight, barY + barH - 1f * l.s(),
                        withAlpha(rgba(255, 255, 255, 60), reveal));
            }
            fill.flush(bb, ctx);
        }
    }

    // ---------------------------------------------------------------- WATCH 面板：内存 / 线程 / 运行时间 / 迷你图

    private static void drawWatchPanel(SimpleBufferBuilder bb, RenderElement.DisplayContext ctx, SimpleFont font,
                                       int frame, String sig, String glVersion) {
        TerminalLayout.Metrics l = layout(ctx, font);
        float reveal = clamp(frame / 18f, 0f, 1f);
        float innerX = l.rightX() + 12f * l.s();
        float innerW = l.rightW() - 24f * l.s();

        Runtime runtime = Runtime.getRuntime();
        long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long maxMb = runtime.maxMemory() / (1024L * 1024L);
        float fraction = maxMb <= 0L ? 0f : clamp(usedMb / (float) maxMb, 0f, 1f);
        sampleHeap(fraction);

        float rowY = l.contentTop();
        keyMeter(bb, ctx, l, font, rowY, innerX, innerW, "mem", fraction, reveal);
        rowY += l.lh() + 3f * l.s();
        keyRow(bb, ctx, l, font, rowY, innerX, innerW, "used", usedMb + " / " + maxMb + " MB",
                withAlpha(CREAM, reveal));
        rowY += l.lh() + 3f * l.s();
        keyRow(bb, ctx, l, font, rowY, innerX, innerW, "threads", String.valueOf(threads()),
                withAlpha(CREAM, reveal));
        rowY += l.lh() + 3f * l.s();
        keyRow(bb, ctx, l, font, rowY, innerX, innerW, "uptime",
                String.format(Locale.ROOT, "%.1fs", uptime() / 1_000_000_000.0), withAlpha(CREAM, reveal));
        rowY += l.lh() + 3f * l.s();
        keyRow(bb, ctx, l, font, rowY, innerX, innerW, "sig", sig, withAlpha(PINK, reveal));
        rowY += l.lh() + 3f * l.s();
        String os = System.getProperty("os.name", "?") + "/" + System.getProperty("os.arch", "?");
        keyRow(bb, ctx, l, font, rowY, innerX, innerW, "os", value(os, font, l, innerW),
                withAlpha(CREAM, reveal));
        rowY += l.lh() + 3f * l.s();
        keyRow(bb, ctx, l, font, rowY, innerX, innerW, "java", value(Runtime.version().toString(), font, l, innerW),
                withAlpha(CREAM, reveal));
        rowY += l.lh() + 3f * l.s();
        keyRow(bb, ctx, l, font, rowY, innerX, innerW, "gl", value(glVersion, font, l, innerW),
                withAlpha(CREAM, reveal));
        rowY += l.lh() + 3f * l.s();

        // 内存曲线：64 个样本的条形迷你图
        float sparkTop = rowY + 8f * l.s();
        float sparkBottom = l.panelBottom() - 42f * l.s();
        if (sparkBottom > sparkTop) {
            float step = innerW / HEAP_SAMPLES;
            float barW = Math.max(1f, step - 1f * l.s());
            Batch spark = new Batch();
            int valid = Math.max(1, heapSamples);
            int peak = 80;
            for (int i = 0; i < valid; i++) {
                peak = Math.max(peak, HEAP_HISTORY[(heapCursor - valid + i + HEAP_SAMPLES) % HEAP_SAMPLES]);
            }
            for (int i = 0; i < valid; i++) {
                int sample = HEAP_HISTORY[(heapCursor - valid + i + HEAP_SAMPLES) % HEAP_SAMPLES];
                float barTop = sparkBottom - (sparkBottom - sparkTop) * Math.min(1f, sample / (float) peak);
                float x = innerX + i * step;
                boolean newest = i == valid - 1;
                spark.add(x, barTop, x + barW, sparkBottom,
                        withAlpha(newest ? GREEN : rgba(60, 255, 122, 150), reveal));
            }
            spark.flush(bb, ctx);
        }

        // 面板底部：分隔线与 watchdog（收进面板内部，留出下边距，不与面板边框重叠）
        float footY = l.panelBottom() - l.lh() - 6f * l.s();
        Batch rule = new Batch();
        rule.add(innerX, footY - 6f * l.s(), innerX + innerW, footY - 6f * l.s() + 1f * l.s(),
                withAlpha(BORDER, 0.45f * reveal));
        rule.flush(bb, ctx);
        boolean blink = (frame / 15) % 2 == 0;
        text(bb, ctx, font, innerX, footY, "watchdog armed", withAlpha(blink ? DIM : FAINT, reveal));
    }

    /** 把 value 截断到 label 右侧可用宽度内。 */
    private static String value(String value, SimpleFont font, TerminalLayout.Metrics l, float innerW) {
        if (value == null || value.isBlank()) return "?";
        return truncate(value, font, innerW - l.labelW() - 3f * l.cw());
    }

    // ---------------------------------------------------------------- 页脚

    private static void drawFooter(SimpleBufferBuilder bb, RenderElement.DisplayContext ctx, SimpleFont font,
                                   String minecraftVersion, String neoForgeVersion) {
        TerminalLayout.Metrics l = layout(ctx, font);
        float top = l.height() - 30f * l.s();

        String right = "Minecraft " + minecraftVersion + "  |  NeoForge " + neoForgeVersion;
        text(bb, ctx, font, l.width() - l.margin() - font.stringWidth(right), top, right, withAlpha(DIM, 0.6f));
    }

    // ---------------------------------------------------------------- 行绘制工具

    /** 一行 key/value：粉色 label + 点线 leader + 右对齐 value。 */
    private static void keyRow(SimpleBufferBuilder bb, RenderElement.DisplayContext ctx, TerminalLayout.Metrics l, SimpleFont font,
                               float y, float x, float width, String label, String value, int valueColor) {
        text(bb, ctx, font, x, y, label, withAlpha(PINK, 0.85f));
        float valueWidth = font.stringWidth(value);
        float valueX = x + width - valueWidth;
        float dotsStart = x + font.stringWidth(label) + l.cw();
        int dots = (int) Math.max(0f, Math.floor((valueX - dotsStart - l.cw()) / l.cw()));
        if (dots > 0) {
            text(bb, ctx, font, dotsStart, y, ".".repeat(dots), withAlpha(FAINT, 0.9f));
        }
        text(bb, ctx, font, valueX, y, value, valueColor);
    }

    /** 一行 key/meter：粉色 label + 12 格字符条（绿填充 / 暗空槽）+ 右对齐百分比。 */
    private static void keyMeter(SimpleBufferBuilder bb, RenderElement.DisplayContext ctx, TerminalLayout.Metrics l, SimpleFont font,
                                 float y, float x, float width, String label, float fraction, float reveal) {
        text(bb, ctx, font, x, y, label, withAlpha(PINK, 0.85f));
        String percent = Math.round(clamp(fraction, 0f, 1f) * 100f) + "%";
        float percentWidth = font.stringWidth(percent);
        float meterX = x + l.labelW() + l.cw();
        int cells = 12;
        float clamped = clamp(fraction, 0f, 1f);
        int filled = clamped <= 0f ? 0 : Math.max(1, Math.round(clamped * cells));
        filled = Math.min(cells, filled);
        String full = "#".repeat(filled);
        String empty = ":".repeat(cells - filled);
        text(bb, ctx, font, meterX, y, full, withAlpha(GREEN, reveal));
        if (!empty.isEmpty()) {
            text(bb, ctx, font, meterX + font.stringWidth(full), y, empty, withAlpha(FAINT, reveal));
        }
        text(bb, ctx, font, x + width - percentWidth, y, percent, withAlpha(CREAM, reveal));
    }

    /** 画一条分隔线，返回这条线之后的行起点。 */
    private static float rule(SimpleBufferBuilder bb, RenderElement.DisplayContext ctx, TerminalLayout.Metrics l,
                              float y, float x, float width, float reveal) {
        float lineY = y + l.lh() * 0.35f;
        Batch batch = new Batch();
        batch.add(x, lineY, x + width, lineY + 1f * l.s(), withAlpha(BORDER, 0.45f * reveal));
        batch.flush(bb, ctx);
        return y + l.lh() * 0.7f;
    }

    /** 在 {@link Batch} 里画一个 1px 粗的矩形边框。 */
    private static void outline(Batch batch, float x, float y, float width, float height, float thickness, int colour) {
        batch.add(x, y, x + width, y + thickness, colour);
        batch.add(x, y + height - thickness, x + width, y + height, colour);
        batch.add(x, y, x + thickness, y + height, colour);
        batch.add(x + width - thickness, y, x + width, y + height, colour);
    }

    // ---------------------------------------------------------------- 运行时数据

    private static long uptime() {
        return System.nanoTime() - START_NANOS;
    }

    private static int threads() {
        ThreadMXBean bean = THREAD_BEAN;
        if (bean == null) return -1;
        try {
            return bean.getThreadCount();
        } catch (RuntimeException failure) {
            return -1;
        }
    }

    /** 每 250ms 采样一次堆占用（千分比），供迷你图使用。 */
    private static void sampleHeap(float fraction) {
        long now = System.nanoTime();
        if (now - lastHeapSampleNanos < 250_000_000L) return;
        lastHeapSampleNanos = now;
        HEAP_HISTORY[heapCursor] = Math.round(clamp(fraction, 0f, 1f) * 1000f);
        heapCursor = (heapCursor + 1) % HEAP_SAMPLES;
        if (heapSamples < HEAP_SAMPLES) heapSamples++;
    }

    static String logTag(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("error") || lower.contains("exception") || lower.contains("failed")) return "error:";
        if (lower.contains("warn")) return "warn:";
        if (lower.contains("debug")) return "debug:";
        return "info:";
    }

    // ---------------------------------------------------------------- 绘制工具

    /**
     * 画一行 FML 内置字体文字。字体只带 ASCII 32..126，版本号与模组名都是 ASCII，够用。
     *
     * @param y 文字顶边（屏幕坐标，逻辑像素；FML 字体的 y 是这一行文字的顶部，不是基线）
     */
    private static void text(SimpleBufferBuilder bb, RenderElement.DisplayContext ctx, SimpleFont font,
                             float x, float y, String text, int argb) {
        text = ascii(text);
        if (text.isEmpty()) return;
        int alpha = ((argb >>> 24) & 0xFF) * EarlyWindowAccess.globalAlpha() / 255;
        if (alpha <= 0) return;
        int colour = (alpha << 24) | (argb & 0x00FFFFFF);
        ElementShader shader = ctx.elementShader();
        shader.updateTextureUniform(EarlyWindowAccess.textureNumber(font));
        shader.updateRenderTypeUniform(ElementShader.RenderType.FONT);
        bb.begin(SimpleBufferBuilder.Format.POS_TEX_COLOR, SimpleBufferBuilder.Mode.QUADS);
        font.generateVerticesForTexts(Math.round(x), Math.round(y), bb, new SimpleFont.DisplayText(text, colour));
        bb.draw();
    }

    private static String truncate(String text, SimpleFont font, float maxWidth) {
        if (text == null) return "";
        text = ascii(text);
        if (font.stringWidth(text) <= maxWidth) return text;
        String candidate = text;
        while (candidate.length() > 3 && font.stringWidth(candidate + "...") > maxWidth) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }
        return candidate + "...";
    }

    /** FML 字体的行高，同时用作单行文字高度。 */
    private static float lineHeight(SimpleFont font) {
        return EarlyWindowAccess.lineSpacing(font);
    }

    static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    static int withAlpha(int argb, float factor) {
        int alpha = Math.round(((argb >>> 24) & 0xFF) * clamp(factor, 0f, 1f));
        return (alpha << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * FML 内置字体只覆盖 ASCII 32..126，非 ASCII 字符宽度为 0、也不会画出字形。
     * 统一替换成 {@code ?}，避免 FML 日志里的 Unicode 把排版算错或留下一片空白。
     */
    static String ascii(String text) {
        if (text == null || text.isEmpty()) return "";
        boolean clean = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 32 || c > 126) {
                clean = false;
                break;
            }
        }
        if (clean) return text;
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            builder.append(c < 32 || c > 126 ? '?' : c);
        }
        return builder.toString();
    }

    private static ThreadMXBean loadThreadBean() {
        try {
            return ManagementFactory.getThreadMXBean();
        } catch (RuntimeException failure) {
            return null;
        }
    }

    static int rgba(int red, int green, int blue, int alpha) {
        return ((alpha & 0xFF) << 24) | ((blue & 0xFF) << 16) | ((green & 0xFF) << 8) | (red & 0xFF);
    }

    /**
     * 调试用：在开发环境没有可见窗口（或窗口被窗口管理器拉伸）时，
     * 直接把 854x480 的离屏帧缓冲导出成 PPM，用来核对排版。
     * 触发方式：游戏目录下存在 {@code early-window-dump} 文件；输出到 {@code tmp/earlywindow-fb-<frame>.ppm}。
     */
    static final class Dump {
        private static final Path TRIGGER = Paths.get("early-window-dump");
        private static final int[] FRAMES = {30, 55};
        private static boolean reported;

        private Dump() {}

        static boolean enabled() {
            if (!reported) {
                reported = true;
                LOGGER.info("早期加载屏：帧导出{}", Files.isRegularFile(TRIGGER) ? "已启用" : "未启用");
            }
            return Files.isRegularFile(TRIGGER);
        }

        static void capture(RenderElement.DisplayContext ctx, int frame) {
            boolean wanted = false;
            for (int candidate : FRAMES) {
                if (candidate == frame) wanted = true;
            }
            if (!wanted) return;
            int width = ctx.scaledWidth();
            int height = ctx.scaledHeight();
            ByteBuffer buffer = ByteBuffer.allocateDirect(width * height * 4);
            GL11C.glReadPixels(0, 0, width, height, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE, buffer);
            Path out = Paths.get("tmp", "earlywindow-fb-" + frame + ".ppm");
            try {
                if (out.getParent() != null) Files.createDirectories(out.getParent());
                try (OutputStream stream = Files.newOutputStream(out)) {
                    stream.write(("P6\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII));
                    byte[] row = new byte[width * 3];
                    // FML 的离屏 FBO 是“上下颠倒”存储的（见 EarlyFramebuffer.draw 的注释），
                    // glReadPixels 第 0 行就是画面顶部，直接按行序写 PPM 即可。
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            int index = (y * width + x) * 4;
                            row[x * 3] = buffer.get(index);
                            row[x * 3 + 1] = buffer.get(index + 1);
                            row[x * 3 + 2] = buffer.get(index + 2);
                        }
                        stream.write(row);
                    }
                }
                LOGGER.info("早期加载屏：已导出帧 {} 到 {}", frame, out.toAbsolutePath());
            } catch (IOException e) {
                LOGGER.error("早期加载屏：导出帧 {} 失败", frame, e);
            }
        }
    }

    /** 一批同色/同 shader 状态的实心矩形，flush 时一次性提交，减少 GL 状态切换。 */
    private static final class Batch {
        /** 顶点坐标（x0,y0,x1,y1 交错）。 */
        private float[] coords = new float[4 * 16];
        /** 颜色必须单独用 int 存：大于 2^24 的 int 转 float 会丢精度，把颜色打包值改坏。 */
        private int[] colours = new int[16];
        private int count;

        void add(float x0, float y0, float x1, float y1, int argb) {
            int index = count * 4;
            if (index + 4 > coords.length) {
                int newCount = Math.max(Math.max(count + 1, colours.length * 2), 16);
                coords = Arrays.copyOf(coords, newCount * 4);
                colours = Arrays.copyOf(colours, newCount);
            }
            coords[index] = x0;
            coords[index + 1] = y0;
            coords[index + 2] = x1;
            coords[index + 3] = y1;
            colours[count] = argb;
            count++;
        }

        void flush(SimpleBufferBuilder bb, RenderElement.DisplayContext ctx) {
            if (count == 0) return;
            int globalAlpha = EarlyWindowAccess.globalAlpha();
            ElementShader shader = ctx.elementShader();
            shader.updateTextureUniform(0);
            shader.updateRenderTypeUniform(ElementShader.RenderType.BAR);
            bb.begin(SimpleBufferBuilder.Format.POS_TEX_COLOR, SimpleBufferBuilder.Mode.QUADS);
            for (int i = 0; i < count; i++) {
                int index = i * 4;
                int argb = colours[i];
                int alpha = ((argb >>> 24) & 0xFF) * globalAlpha / 255;
                if (alpha <= 0) continue;
                int colour = (alpha << 24) | (argb & 0x00FFFFFF);
                QuadHelper.loadQuad(bb, coords[index], coords[index + 2], coords[index + 1], coords[index + 3],
                        0f, 0f, 0f, 0f, colour);
            }
            bb.draw();
            count = 0;
        }
    }
}
