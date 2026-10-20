package cn.mykodb.earlywindow.check;

import cn.mykodb.earlywindow.LogicCheck;
import cn.mykodb.earlywindow.TerminalLayout;

/**
 * 不依赖 OpenGL 的排版断言：把 {@link TerminalLayout} 的计算结果套上绘制代码里的关键约束，
 * 一旦有人改版面常量导致面板越界、进度条压页脚、标签列错位等问题，构建就会失败。
 *
 * <p>运行方式（无需显卡/显示）：{@code ./gradlew checkLayout}。排版之���还会调用
 * {@link LogicCheck} 跑纯函数断言（ASCII 过滤、日志标签、颜色打包）。</p>
 */
public final class LayoutCheck {

    /** 与 FML early display 一致：逻辑画布 854x480；字符宽 11、行高 20 来自 Monocraft 实测。 */
    private static final float[] SCALES = {1f, 1.5f, 2f};
    private static final float CHAR_WIDTH = 11f;
    private static final float LINE_HEIGHT = 20f;
    private static final float CANVAS_WIDTH = 854f;
    private static final float CANVAS_HEIGHT = 480f;

    /** 绘制代码里进度条/状态灯/页脚相对面板底部的偏移。 */
    private static final float BAR_BOTTOM_INSET = 24f;
    private static final float BAR_HEIGHT = 9f;
    private static final float LAMP_OFFSET = 10f;
    private static final float FOOTER_TOP_INSET = 30f;
    private static final float WATCHDOG_TOP_INSET = 6f;
    private static final float SPARK_BOTTOM_INSET = 42f;

    private static int checks;

    private LayoutCheck() {}

    public static void main(String[] args) {
        for (float scale : SCALES) {
            checkScale(scale);
        }
        System.out.println("LayoutCheck OK: " + checks + " 条断言通过（scale=" + describe(SCALES) + "）");
        // 排版之外，再把绘制用的纯函数钉住（不需要 GL/字体）。
        LogicCheck.run();
    }

    private static void checkScale(float scale) {
        TerminalLayout.Metrics m = TerminalLayout.compute(CANVAS_WIDTH, CANVAS_HEIGHT, scale, CHAR_WIDTH, LINE_HEIGHT);
        float s = m.s();
        String where = "scale=" + scale + " ";

        // 画布与面板
        check(where + "面板顶边在标题行之下", m.panelTop() > m.ruleY());
        check(where + "面板底边在顶边之下", m.panelBottom() > m.panelTop());
        check(where + "面板底边留有状态灯/页脚空间",
                m.height() - m.panelBottom() >= (FOOTER_TOP_INSET - PANEL_GAP) * s);
        check(where + "内容区在面板内部", m.contentTop() > m.panelTop() && m.contentTop() < m.panelBottom());
        check(where + "内容区至少能放下 3 行", m.panelBottom() - m.contentTop() >= 3f * m.lh());

        // 左右面板边界
        check(where + "左面板从左边距开始", m.leftX() == m.margin());
        check(where + "左面板不越过中缝", m.leftX() + m.leftW() < m.rightX());
        check(where + "右面板结束于右边距", Math.abs(m.rightX() + m.rightW() - (m.width() - m.margin())) < 0.01f);
        check(where + "右面板宽度为正", m.rightW() > 4f * m.cw());

        // 标签列：等宽字体下应等于最长标签的宽度
        check(where + "标签列宽等于最长标签宽度", Math.abs(m.labelW() - 7f * m.cw()) < 0.01f);

        // BOOT 面板：进度条、标签、状态灯、页脚
        float barBottom = m.panelBottom() - BAR_BOTTOM_INSET * s;
        check(where + "进度条底边在面板内", barBottom + BAR_HEIGHT * s <= m.panelBottom());
        float barLabelTop = barBottom - m.lh() - 2f * s;
        check(where + "进度条标签在内容区之下", barLabelTop > m.contentTop() + 2f * m.lh());
        check(where + "BOOT 内容区能放下 stage/progress/handoff 三行",
                m.contentTop() + 3f * (m.lh() + 3f * s) < barLabelTop);

        // 状态灯行、页脚、面板底边互不重叠
        float lampTop = m.panelBottom() + LAMP_OFFSET * s;
        check(where + "状态灯行在面板之下且在画布之内", lampTop > m.panelBottom() && lampTop < m.height() - 20f * s);
        float footerTop = m.height() - FOOTER_TOP_INSET * s;
        check(where + "页脚在状态灯行之下", footerTop >= lampTop + 6f * s);
        check(where + "页脚文字不越出画布", footerTop + m.lh() <= m.height());

        // WATCH 面板：watchdog 与迷你图
        float watchdogTop = m.panelBottom() - m.lh() - WATCHDOG_TOP_INSET * s;
        check(where + "watchdog 在面板内部", watchdogTop > m.contentTop());
        check(where + "watchdog 文字不越出画布", watchdogTop + m.lh() <= m.panelBottom());
        float sparkBottom = m.panelBottom() - SPARK_BOTTOM_INSET * s;
        float sparkTop = m.contentTop() + 8f * (m.lh() + 3f * s) + 8f * s;
        if (sparkBottom > sparkTop) {
            check(where + "迷你图不与 watchdog 分隔线重叠", sparkBottom < watchdogTop - 6f * s);
        }
    }

    /** 面板底部到状态灯之间允许的最小间距（用于底边留白断言）。 */
    private static final float PANEL_GAP = 10f;

    private static void check(String what, boolean ok) {
        checks++;
        if (!ok) {
            throw new AssertionError("排版断言失败: " + what);
        }
    }

    private static String describe(float[] values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) builder.append('/');
            builder.append(values[i]);
        }
        return builder.toString();
    }
}
