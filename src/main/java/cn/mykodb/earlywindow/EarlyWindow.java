package cn.mykodb.earlywindow;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import net.neoforged.fml.earlydisplay.ColourScheme;
import net.neoforged.fml.earlydisplay.DisplayWindow;
import net.neoforged.fml.earlydisplay.RenderElement;
import net.neoforged.fml.earlydisplay.SimpleFont;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FML 早期加载屏（early window）provider。
 *
 * <p>NeoForge 的加载界面由 FML 的 {@link DisplayWindow} 提供，它把界面元素放在 private 的
 * {@code elements} 列表里，官方没有公开的替换入口。这里的做法是：继承 {@link DisplayWindow}
 * 并实现 SPI {@code net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider}，
 * 让 FML 用我们的类作为 provider，然后在 FML 初始化完默认元素后（同一个渲染线程上），
 * 用 {@link EarlyWindowAccess} 把 {@code elements} 整体替换成 {@link TerminalScreen} 的内容。</p>
 *
 * <p>如何生效：把本模块打出的 {@code early-window jar} 放进 {@code mods/}，
 * 并在 {@code config/fml.toml} 里设置 {@code earlyWindowProvider = "earlywindow"}。
 * 该 jar 里只有 {@code META-INF/services/...ImmediateWindowProvider}，没有
 * {@code neoforge.mods.toml}，FML 会把它当作 SERVICE 层插件而不是普通模组。</p>
 */
public class EarlyWindow extends DisplayWindow {
    /** 必须在 {@code config/fml.toml} 的 earlyWindowProvider 中填写的名字。 */
    public static final String PROVIDER_NAME = "earlywindow";

    private static final Logger LOGGER = LoggerFactory.getLogger("EarlyWindow");
    private static final long INSTALL_DELAY_MILLIS = 80L;
    private static final long RETRY_DELAY_MILLIS = 100L;
    private static final int MAX_INSTALL_ATTEMPTS = 20;

    private final AtomicInteger installAttempts = new AtomicInteger();

    private String minecraftVersion = "1.21.1";
    private String neoForgeVersion = "21.1.233";

    @Override
    public String name() {
        return PROVIDER_NAME;
    }

    @Override
    public Runnable initialize(String[] arguments) {
        parseVersions(arguments);

        Runnable tick = super.initialize(arguments);

        if (!EarlyWindowAccess.available()) {
            LOGGER.warn("早期加载屏不可用（无法访问 FML early display 内部 API），继续使用 NeoForge 默认界面");
            return tick;
        }

        // 用黑色配色：背景/清屏色变成深色，白色/绿色描边才有对比度。
        // （initialize 跑在启动主线程，此时 initRender 通常还没执行；即使已执行，
        //   之后的每帧 glClear 也会用新配色。）
        try {
            EarlyWindowAccess.setColourScheme(this, ColourScheme.BLACK);
        } catch (Exception | LinkageError failure) {
            LOGGER.warn("早期加载屏：切换深色配色失败，沿用 NeoForge 默认配色", failure);
        }

        // 与 FML 的 initRender 用同一个单线程调度器：任务排在它之后执行，
        // 因此这里改 elements 与渲染线程之间没有并发问题。
        ScheduledExecutorService scheduler = EarlyWindowAccess.renderScheduler(this);
        scheduler.schedule(this::installTerminalScreen, INSTALL_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        return tick;
    }

    @Override
    public void updateModuleReads(ModuleLayer layer) {
        // 不能直接调用 super.updateModuleReads：它的实现是
        //   getClass().getModule().addReads(neoforgeModule);
        // 这里 getClass() 是子类（本类），而调用者却是 fml_earlydisplay 模块，
        // Module.addReads 会抛 IllegalCallerException("module fml_earlydisplay != module ...")。
        // 所以由本类自己加读边，再用反射把 loadingOverlay 字段绑到 NeoForge 的加载遮罩工厂。
        var neoforgeModule = layer.findModule("neoforge");
        if (neoforgeModule.isEmpty()) {
            LOGGER.warn("早期加载屏：找不到 neoforge 模块，跳过加载遮罩绑定");
            return;
        }
        Module fm = neoforgeModule.get();
        Module self = getClass().getModule();
        self.addReads(fm);
        try {
            Class<?> overlay = Class.forName(fm, "net.neoforged.neoforge.client.loading.NeoForgeLoadingOverlay");
            Method newInstance = null;
            for (Method method : overlay.getMethods()) {
                if (Modifier.isStatic(method.getModifiers()) && method.getName().equals("newInstance")) {
                    newInstance = method;
                    break;
                }
            }
            if (newInstance == null) {
                LOGGER.warn("早期加载屏：NeoForgeLoadingOverlay 里没有 newInstance");
                return;
            }
            newInstance.setAccessible(true);
            EarlyWindowAccess.setLoadingOverlay(this, newInstance);
        } catch (Exception | LinkageError failure) {
            // 绑定失败只影响“Minecraft 接管窗口后的渐变遮罩”，不该让游戏起不来。
            LOGGER.error("早期加载屏：绑定 NeoForge 加载遮罩失败", failure);
        }
    }

    @Override
    public void addMojangTexture(int textureId) {
        // 默认实现是 elements.add(0, RenderElement.mojang(textureId, framecount))，
        // 把 Minecraft 传进来的 Mojang 图标插到元素列表最底层，在“加载完成→标题画面”
        // 的过渡阶段淡入。我们自己的界面已经占满屏幕，不要这个图标：
        // 忽略回调即可（纹理由 MC 上传，我们只是不画它）。
        // 想恢复默认图标就删掉这个覆盖，或改成调用 super.addMojangTexture(textureId)。
        LOGGER.info("早期加载屏：忽略 Mojang 过渡图标（textureId={}）", textureId);
    }

    private void installTerminalScreen() {
        try {
            List<RenderElement> current = EarlyWindowAccess.elements(this);
            SimpleFont font = EarlyWindowAccess.font(this);
            if (current == null || font == null) {
                // initRender 在慢机器上可能还没跑完，等一会儿再试。
                if (installAttempts.incrementAndGet() <= MAX_INSTALL_ATTEMPTS) {
                    EarlyWindowAccess.renderScheduler(this).schedule(this::installTerminalScreen, RETRY_DELAY_MILLIS, TimeUnit.MILLISECONDS);
                } else {
                    LOGGER.warn("早期加载屏：等待 FML 渲染初始化超时，保留默认界面");
                }
                return;
            }
            RenderElement.DisplayContext ctx = EarlyWindowAccess.context(this);
            if (ctx != null) {
                LOGGER.info("早期加载屏画布: {}x{} 逻辑像素, scale={}", ctx.width(), ctx.height(), ctx.scale());
            }
            List<RenderElement> elements = TerminalScreen.create(font, minecraftVersion, neoForgeVersion, getGLVersion());
            current.clear();
            current.addAll(elements);
            LOGGER.info("早期加载屏已接管（替换 {} 个自定义元素）", elements.size());
        } catch (Exception | LinkageError failure) {
            LOGGER.error("早期加载屏安装失败，保留 NeoForge 默认界面", failure);
        }
    }

    private void parseVersions(String[] arguments) {
        if (arguments == null) return;
        for (int i = 0; i < arguments.length; i++) {
            String argument = arguments[i];
            if (i + 1 >= arguments.length) continue;
            if (isOption(argument, "fml.mcVersion")) {
                minecraftVersion = arguments[i + 1];
            } else if (isOption(argument, "fml.neoForgeVersion")) {
                neoForgeVersion = arguments[i + 1];
            }
        }
    }

    private static boolean isOption(String argument, String name) {
        return argument.equals("--" + name) || argument.equals(name);
    }
}
