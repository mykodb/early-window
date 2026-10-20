package cn.mykodb.earlywindow;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import net.neoforged.fml.earlydisplay.ColourScheme;
import net.neoforged.fml.earlydisplay.DisplayWindow;
import net.neoforged.fml.earlydisplay.RenderElement;
import net.neoforged.fml.earlydisplay.SimpleBufferBuilder;
import net.neoforged.fml.earlydisplay.SimpleFont;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过反射访问 FML early display 的内部状态。
 *
 * <p>FML 的 {@link DisplayWindow} 把 {@code elements}/{@code font}/{@code renderScheduler}
 * 声明为 private，并且 {@link RenderElement} 的 {@code Initializer}/{@code Renderer} 是包私有接口，
 * 官方并没有给第三方 provider 提供替换界面的扩展点。这里用 {@link MethodHandles#privateLookupIn}
 * 取得与 FML 相同的包级访问权限，再用 {@link VarHandle}/{@link MethodHandle} 读写这些成员，
 * 避免 setAccessible 在强封装下失败。</p>
 *
 * <p>earlydisplay 在 NeoForge/FML 4.x 里是自动模块（{@code fml_earlydisplay}），包全部 open，
 * 所以 privateLookupIn 可以成功。若未来 FML 改结构导致失败，本类返回 {@code null}，
 * provider 会安静地退化为 NeoForge 默认加载界面，而不是让游戏崩溃。</p>
 */
final class EarlyWindowAccess {
    private static final Logger LOGGER = LoggerFactory.getLogger("EarlyWindow");
    private static final int DEFAULT_GLOBAL_ALPHA = 0xFF;
    private static final int DEFAULT_FONT_TEXTURE = 6;
    private static final int DEFAULT_LINE_SPACING = 20;

    private static final Hook HOOK = createHook();

    private EarlyWindowAccess() {}

    static boolean available() {
        return HOOK != null;
    }

    /** 返回 FML 当前加载界面的元素列表本体；对它 clear/addAll 即可整体替换界面。 */
    @SuppressWarnings("unchecked")
    static List<RenderElement> elements(DisplayWindow window) {
        requireHook();
        return (List<RenderElement>) HOOK.elements.get(window);
    }

    static SimpleFont font(DisplayWindow window) {
        requireHook();
        return (SimpleFont) HOOK.font.get(window);
    }

    /** FML early display 的渲染线程调度器（单线程），用来在 initRender 之后修改界面。 */
    static ScheduledExecutorService renderScheduler(DisplayWindow window) {
        requireHook();
        return (ScheduledExecutorService) HOOK.renderScheduler.get(window);
    }

    /**
     * 写入 {@link DisplayWindow} 的 private 字段 {@code loadingOverlay}（指向
     * {@code NeoForgeLoadingOverlay.newInstance}）。FML 在 {@code updateModuleReads}
     * 里完成同样的绑定，但默认实现用 {@code getClass().getModule()} 取模块，
     * 被子类化时会因“调用者模块 != 接收者模块”抛 IllegalCallerException。
     */
    static void setLoadingOverlay(DisplayWindow window, Method newInstance) {
        requireHook();
        HOOK.loadingOverlay.set(window, newInstance);
    }

    /** 把配色方案改成 BLACK（深色背景），让白色/绿色描边有对比度。 */
    static void setColourScheme(DisplayWindow window, ColourScheme scheme) {
        requireHook();
        HOOK.colourScheme.set(window, scheme);
    }

    /** 当前 854x480 逻辑画布（宽高按 {@code scale} 放大成物理像素）。 */
    static RenderElement.DisplayContext context(DisplayWindow window) {
        requireHook();
        return (RenderElement.DisplayContext) HOOK.context.get(window);
    }

    /** {@link SimpleFont lineSpacing()} 包私有；排版时用“行高”当文字高度。 */
    static int lineSpacing(SimpleFont font) {
        if (HOOK == null) return DEFAULT_LINE_SPACING;
        try {
            return (int) HOOK.fontLineSpacing.invokeExact(font);
        } catch (Throwable failure) {
            // invokeExact 声明 throws Throwable，这里无法只捕获 Exception。
            return DEFAULT_LINE_SPACING;
        }
    }

    /** 与 {@link RenderElement globalAlpha} 一致的整体透明度，FML 在淡入淡出时会改它。 */
    static int globalAlpha() {
        if (HOOK == null) return DEFAULT_GLOBAL_ALPHA;
        try {
            return (int) HOOK.globalAlpha.get();
        } catch (Exception | LinkageError failure) {
            return DEFAULT_GLOBAL_ALPHA;
        }
    }

    /** {@link SimpleFont textureNumber()} 是包私有的，文字渲染需要把纹理绑定到 shader。 */
    static int textureNumber(SimpleFont font) {
        if (HOOK == null) return DEFAULT_FONT_TEXTURE;
        try {
            return (int) HOOK.fontTextureNumber.invokeExact(font);
        } catch (Throwable failure) {
            // invokeExact 声明 throws Throwable，这里无法只捕获 Exception。
            return DEFAULT_FONT_TEXTURE;
        }
    }

    /**
     * 创建由 {@link RenderCallback} 驱动的 {@link RenderElement}。
     *
     * <p>实现方式：用动态代理实现包私有的 {@code RenderElement$Renderer}/{@code $Initializer}，
     * 再用包私有的构造器 {@code RenderElement(Initializer)} 包起来。</p>
     */
    static RenderElement newElement(RenderCallback callback) {
        requireHook();
        ClassLoader cl = DisplayWindow.class.getClassLoader();
        Object renderer = Proxy.newProxyInstance(cl, new Class<?>[]{HOOK.rendererType}, new RendererHandler(callback));
        Object initializer = Proxy.newProxyInstance(cl, new Class<?>[]{HOOK.initializerType}, new InitializerHandler(renderer));
        try {
            return (RenderElement) HOOK.elementConstructor.newInstance(initializer);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法构造 RenderElement", e);
        }
    }

    private static void requireHook() {
        if (HOOK == null) throw new IllegalStateException("FML early display 反射钩子不可用");
    }

    private static Hook createHook() {
        try {
            MethodHandles.Lookup lookup = privateLookup();
            Hook hook = new Hook();
            hook.elements = lookup.findVarHandle(DisplayWindow.class, "elements", List.class);
            hook.font = lookup.findVarHandle(DisplayWindow.class, "font", SimpleFont.class);
            hook.renderScheduler = lookup.findVarHandle(DisplayWindow.class, "renderScheduler", ScheduledExecutorService.class);
            hook.loadingOverlay = lookup.findVarHandle(DisplayWindow.class, "loadingOverlay", Method.class);
            hook.colourScheme = lookup.findVarHandle(DisplayWindow.class, "colourScheme", ColourScheme.class);
            hook.context = lookup.findVarHandle(DisplayWindow.class, "context", RenderElement.DisplayContext.class);
            hook.globalAlpha = lookup.findStaticVarHandle(RenderElement.class, "globalAlpha", int.class);
            hook.fontTextureNumber = lookup.findVirtual(SimpleFont.class, "textureNumber", MethodType.methodType(int.class));
            hook.fontLineSpacing = lookup.findVirtual(SimpleFont.class, "lineSpacing", MethodType.methodType(int.class));
            hook.initializerType = Class.forName("net.neoforged.fml.earlydisplay.RenderElement$Initializer", false, DisplayWindow.class.getClassLoader());
            hook.rendererType = Class.forName("net.neoforged.fml.earlydisplay.RenderElement$Renderer", false, DisplayWindow.class.getClassLoader());
            hook.elementConstructor = RenderElement.class.getConstructor(hook.initializerType);
            hook.elementConstructor.setAccessible(true);
            return hook;
        } catch (Exception | LinkageError failure) {
            LOGGER.error("早期加载屏：无法反射访问 FML early display，将使用 NeoForge 默认界面", failure);
            return null;
        }
    }

    private static MethodHandles.Lookup privateLookup() throws IllegalAccessException {
        Module ours = EarlyWindowAccess.class.getModule();
        Module target = DisplayWindow.class.getModule();
        // earlydisplay 是自动模块，正常会读取所有模块；这里兜底避免分层模块场景下访问被拒。
        if (ours.isNamed() && target.isNamed() && !ours.canRead(target)) {
            ours.addReads(target);
        }
        return MethodHandles.privateLookupIn(DisplayWindow.class, MethodHandles.lookup());
    }

    private static final class Hook {
        VarHandle elements;
        VarHandle font;
        VarHandle renderScheduler;
        VarHandle loadingOverlay;
        VarHandle colourScheme;
        VarHandle context;
        VarHandle globalAlpha;
        MethodHandle fontTextureNumber;
        MethodHandle fontLineSpacing;
        Class<?> initializerType;
        Class<?> rendererType;
        Constructor<?> elementConstructor;
    }

    private record RendererHandler(RenderCallback callback) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "accept" -> {
                    callback.render((SimpleBufferBuilder) args[0],
                            (RenderElement.DisplayContext) args[1], (Integer) args[2]);
                    yield null;
                }
                case "then" -> args[0];
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "EarlyWindowRenderer";
                default -> throw new UnsupportedOperationException("未实现的 Renderer 方法: " + method);
            };
        }
    }

    private record InitializerHandler(Object renderer) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "get" -> renderer;
                case "equals" -> proxy == args[0];
                case "hashCode" -> System.identityHashCode(proxy);
                case "toString" -> "EarlyWindowInitializer";
                default -> throw new UnsupportedOperationException("未实现的 Initializer 方法: " + method);
            };
        }
    }
}
