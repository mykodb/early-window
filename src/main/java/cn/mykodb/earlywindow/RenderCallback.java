package cn.mykodb.earlywindow;

import net.neoforged.fml.earlydisplay.RenderElement;
import net.neoforged.fml.earlydisplay.SimpleBufferBuilder;

/**
 * 自定义渲染元素的绘制回调。
 *
 * <p>FML 的 {@link RenderElement} 内部接口 {@code Renderer} 与 {@code Initializer} 是包私有的，
 * 外部 provider 无法直接实现，因此由 {@link EarlyWindowAccess} 生成动态代理转接到本接口。</p>
 *
 * @param bb    本次绘制可以复用的顶点缓冲
 * @param ctx   渲染上下文（画布尺寸、缩放、shader、配色等）
 * @param frame 当前动画帧数，约 20 FPS 自增
 */
@FunctionalInterface
interface RenderCallback {
    void render(SimpleBufferBuilder bb, RenderElement.DisplayContext ctx, int frame);
}
