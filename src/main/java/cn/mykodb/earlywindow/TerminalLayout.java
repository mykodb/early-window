package cn.mykodb.earlywindow;

/**
 * 早期加载屏的纯排版计算：只依赖宽高、缩放、字符宽、行高，不接触 GL / FML 对象。
 *
 * <p>把版面参数抽出来的目的有两个：一是绘制代码里不再散落间距魔法数；
 * 二是可以脱离 OpenGL 上下文直接做断言（见 {@code checkEarlyWindowLayout} Gradle 任务与
 * {@code cn.mykodb.earlywindow.check.LayoutCheck}）。</p>
 *
 * <p>所有数值都是逻辑像素，绘制时再乘以 {@link Metrics#s()}（画布缩放）。</p>
 */
public final class TerminalLayout {

    /** 面板底边距画布底部的距离（容纳状态灯行与页脚）。 */
    public static final float PANEL_BOTTOM_INSET = 76f;

    /** BOOT 面板在可用宽度里的占比（其余给 WATCH）。 */
    public static final float PANEL_SPLIT = 0.575f;

    /** WATCH 面板 key 行的标签，用来计算统一的标签列宽（等宽字体下即最长标签的宽度）。 */
    public static final String[] KEY_LABELS = {
            "mem", "used", "threads", "uptime", "sig", "os", "java", "gl"
    };

    /**
     * 版面参数（逻辑像素）。
     *
     * @param cw     等宽字符宽度，用于点线 leader / 字符条
     * @param lh     行高
     * @param labelW key 行标签列的统一宽度（最长标签）
     */
    public record Metrics(float s, float width, float height, float cw, float lh, float margin,
                          float ruleY, float panelTop, float panelBottom,
                          float leftX, float leftW, float rightX, float rightW,
                          float titleY, float contentTop, float labelW) {}

    private TerminalLayout() {}

    public static Metrics compute(float width, float height, float scale, float charWidth, float lineHeight) {
        float s = scale > 0f ? scale : 1f;
        float cw = Math.max(1f, charWidth);
        float lh = Math.max(1f, lineHeight);
        float margin = 14f * s;
        float ruleY = 10f * s + lh + 6f * s;
        float panelTop = ruleY + 12f * s;
        float panelBottom = height - PANEL_BOTTOM_INSET * s;
        float leftX = margin;
        float leftW = Math.round((width - 2f * margin) * PANEL_SPLIT);
        float rightX = leftX + leftW + 14f * s;
        float rightW = width - margin - rightX;
        float titleY = panelTop + 8f * s;
        float contentTop = titleY + lh + 10f * s;
        float labelW = 0f;
        for (String label : KEY_LABELS) {
            // Monocraft 是等宽字体，ASCII 标签宽度 = 字符数 × 字符宽。
            labelW = Math.max(labelW, label.length() * cw);
        }
        return new Metrics(s, width, height, cw, lh, margin, ruleY, panelTop, panelBottom,
                leftX, leftW, rightX, rightW, titleY, contentTop, labelW);
    }
}
