package cn.mykodb.earlywindow;

/**
 * 纯函数断言：不依赖 GL、FML、字体，只要 JDK 就能跑。
 *
 * <p>由 {@code LayoutCheck} 调用（{@code ./gradlew checkLayout}）。这些函数决定了界面上显示什么文字、
 * 用什么颜色，出错时不会崩溃、只会静默画错，所以值得单独钉住。</p>
 */
public final class LogicCheck {

    private static int failures;

    private LogicCheck() {}

    public static void run() {
        int before = failures;
        // ascii()：FML 字体只有 ASCII 32..126，其余统一换成 '?'
        expect("ascii 纯 ASCII 原样返回", "abc 123".equals(TerminalScreen.ascii("abc 123")));
        expect("ascii null → 空串", "".equals(TerminalScreen.ascii(null)));
        expect("ascii 空串 → 空串", "".equals(TerminalScreen.ascii("")));
        expect("ascii 非 ASCII → ?", "caf?".equals(TerminalScreen.ascii("café")));
        expect("ascii 控制字符 → ?", "a?b".equals(TerminalScreen.ascii("a\nb")));
        expect("ascii 中文逐字符替换", "??".equals(TerminalScreen.ascii("中文")));

        // logTag()：日志行前缀着色
        expect("logTag error", "error:".equals(TerminalScreen.logTag("ERROR: boom")));
        expect("logTag exception", "error:".equals(TerminalScreen.logTag("caught Exception")));
        expect("logTag failed", "error:".equals(TerminalScreen.logTag("failed to load")));
        expect("logTag warn", "warn:".equals(TerminalScreen.logTag("Warning: slow")));
        expect("logTag debug", "debug:".equals(TerminalScreen.logTag("debug info")));
        expect("logTag info 兜底", "info:".equals(TerminalScreen.logTag("Loading bootstrap resources")));

        // clamp()
        expect("clamp 上界", TerminalScreen.clamp(5f, 0f, 3f) == 3f);
        expect("clamp 下界", TerminalScreen.clamp(-1f, 0f, 3f) == 0f);
        expect("clamp 区间内", TerminalScreen.clamp(2f, 0f, 3f) == 2f);
        expect("clamp NaN 保持 NaN", Float.isNaN(TerminalScreen.clamp(Float.NaN, 0f, 1f)));

        // rgba()：ABGR 打包（红在最低字节，与 SimpleBufferBuilder.colour(int) 一致）
        expect("rgba(1,2,3,4) = 0x04030201", TerminalScreen.rgba(1, 2, 3, 4) == 0x04030201);
        expect("rgba 全零", TerminalScreen.rgba(0, 0, 0, 0) == 0);
        expect("rgba 白色不透明", TerminalScreen.rgba(255, 255, 255, 255) == 0xFFFFFFFF);
        expect("rgba 超界截断", TerminalScreen.rgba(300, -1, 1, 2) == TerminalScreen.rgba(300 & 0xFF, -1 & 0xFF, 1, 2));

        // withAlpha()：只改 alpha 字节，RGB 不动，factor 截断到 [0,1]
        expect("withAlpha 半透明", TerminalScreen.withAlpha(0xFF00FF00, 0.5f) == 0x8000FF00);
        expect("withAlpha 0 → 全透明", TerminalScreen.withAlpha(0xFF123456, 0f) == 0x00123456);
        expect("withAlpha 大于 1 视为 1（alpha 不变）", TerminalScreen.withAlpha(0x80123456, 2f) == 0x80123456);
        expect("withAlpha 负值截断为 0", TerminalScreen.withAlpha(0x80123456, -1f) == 0x00123456);

        int assertions = 24;
        if (failures == before) {
            System.out.println("LogicCheck OK: " + assertions + " 条断言通过");
        } else {
            throw new AssertionError("LogicCheck 失败 " + (failures - before) + " 条");
        }
    }

    private static void expect(String what, boolean ok) {
        if (!ok) {
            failures++;
            System.err.println("LogicCheck 失败: " + what);
        }
    }
}
