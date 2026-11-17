/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import java.util.Arrays;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * 独立横幅外壳的可选材质适配，不接管通知栏 View 或更改系统材质策略。
 * 接口依据：Xiaomi HyperOS SystemUI OS3 16.03.251211.r / OS4 17.03.260226.r，
 * BlurUtilsExt、BlurUtilsImpl.applyModalContainerBlur、MiBlurCompat；OS4 另读
 * NotificationMaterialStateInteractor.materialTypeState，并为自有背景层接入MiGlass/type/SDF。
 * 仅独立实现调用与回退，未复制 ROM 实现或玻璃数组；商业ROM实现许可证未确认。
 * 作者工作区 Knowledge/rom/banner-material-and-geometry.md 与
 * Knowledge/rom/os4-notification-material-chain.md 保留只读 APK 证据。
 */
final class NativeBannerMaterial {
    private static final String TAG = "HyperOS3FocusRestore";
    // 保留约20%的主题底色，降低透视感；这里是Drawable alpha，不是最终合成透明度。
    private static final int MATERIAL_BACKGROUND_ALPHA = 51;
    private static final HookInstaller HOOKS = new HookInstaller();
    private static volatile WeakReference<Object> state = new WeakReference<>(null);
    private static volatile Class<?> compat;

    static void install(ClassLoader loader) {
        try {
            compat = XposedHelpers.findClass("com.miui.systemui.util.MiBlurCompat", loader);
            Class<?> owner = XposedHelpers.findClass("com.miui.systemui.statusbar.policy.BlurUtilsExt", loader);
            HOOKS.constructors(owner, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!param.hasThrowable()) state = new WeakReference<>(param.thisObject);
                }
            });
        } catch (Throwable error) {
            Log.w(TAG, "banner material observation unavailable; keep themed background", error);
        }
    }

    /** 模板创建前读取本次有效材质，不能借用上一条横幅的应用结果。 */
    static boolean isGlassActive() {
        if (!NativeFocusAppearance.useNativeMaterial()) return false;
        Object current = state.get();
        if (current == null || compat == null) return false;
        try {
            boolean glass = "GLASS".equals(readMaterialType(current));
            materialObservationFailed = false;
            return glass;
        } catch (Throwable error) {
            if (!materialObservationFailed) {
                Log.w(TAG, "banner material theme observation failed; use system theme", error);
                materialObservationFailed = true;
            }
            return false;
        }
    }

    static boolean hasHunGlassParameters(Context context) {
        if (NativeFocusAppearance.isOs3() || !NativeFocusAppearance.useNativeMaterial()) return false;
        return readHunGlassParameters(context) != null;
    }

    private static float[] readHunGlassParameters(Context context) {
        Context app = context.getApplicationContext();
        Context live = app != null ? app : context;
        boolean dark = (live.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        float[] values = NativeHunGlassParameterCapture.read(live, dark);
        return values != null && hunResourcesReady(live) ? values : null;
    }

    private static Resources readyResources;
    private static Configuration readyConfiguration;
    private static boolean readyHunResources;

    private static synchronized boolean hunResourcesReady(Context context) {
        Resources resources = context.getResources();
        Configuration configuration = resources.getConfiguration();
        if (resources == readyResources && readyConfiguration != null
                && readyConfiguration.equals(configuration)) return readyHunResources;
        boolean ready = false;
        try {
            int drawable = resources.getIdentifier("notification_heads_up_transparent_bg", "drawable", "com.android.systemui");
            if (drawable == 0 || resources.getDrawable(drawable, context.getTheme()) == null)
                throw new IllegalStateException("transparent HUN drawable missing");
            for (String suffix : new String[]{"color_1", "mode_1", "color_2", "mode_2"}) {
                boolean color = suffix.startsWith("color");
                String name = "notification_element_blend_headsUp_" + suffix;
                int id = resources.getIdentifier(name, color ? "color" : "integer", "com.android.systemui");
                if (id == 0) throw new IllegalStateException("HUN blend resource missing: " + name);
                if (color) resources.getColor(id, null); else resources.getInteger(id);
            }
            ready = true;
        } catch (Throwable error) {
            Log.w(TAG, "banner HUN resources not ready; retain legacy content and material", error);
        }
        readyResources = resources;
        readyConfiguration = new Configuration(configuration);
        readyHunResources = ready;
        return ready;
    }

    private static boolean materialObservationFailed;

    /** OS3无materialInteractor；OS4只认通知门控后的Flow，不读取原始material_style。 */
    private static String readMaterialType(Object current) throws Exception {
        if (!Boolean.TRUE.equals(XposedHelpers.callMethod(current, "getUseBlendBlur"))
                || !XposedHelpers.getBooleanField(current, "blurFeatureSupportedOnDevice")
                || XposedHelpers.getBooleanField(current, "blurDisabledBySettings")) return "NONE";
        String name = "BLUR";
        java.lang.reflect.Field interactorField = XposedHelpers.findFieldIfExists(
                current.getClass(), "materialInteractor");
        if (interactorField != null) {
            Object interactor = interactorField.get(current);
            Object flow = XposedHelpers.getObjectField(interactor, "materialTypeState");
            Object type = XposedHelpers.callMethod(flow, "getValue");
            if (!(type instanceof Enum)) throw new IllegalStateException("unknown material type");
            name = ((Enum<?>) type).name();
            if (!"NONE".equals(name) && !"BLUR".equals(name) && !"GLASS".equals(name)) {
                throw new IllegalStateException("unsupported material type: " + name);
            }
        }
        if ("NONE".equals(name)) return name;
        int radius = XposedHelpers.getIntField(current,
                "GLASS".equals(name) ? "modalMaxGlassBlurRadius" : "miBlurMaxRadius");
        return radius > 0 ? name : "NONE";
    }

    static final class Session {
        private final View target;
        private Method pass, mode, radius, windowException, glass, readMode, readRadius;
        private Method viewMode, clearBlend, addBlend, glassOutline;
        private Method glassParameters, materialType, glassSize;
        private BannerGlassState glassState;
        private Resources glassResources;
        private Configuration glassConfiguration;
        private float[] nativeGlassParameters;
        private Resources blendResources;
        private Configuration blendConfiguration;
        private int[] notificationBlendColors;
        private boolean blendHun, appliedHun;
        private boolean blendFocusHun;
        private final boolean hunEligible;
        private int appliedRecipe = BannerMaterialRecipe.LEGACY;
        private Drawable hunBackground;
        private Resources hunResources;
        private Configuration hunConfiguration;
        private String fallbackReason;
        private boolean glassReadyLogged;
        private boolean glassOutlineFailed, glassOutlineTouched;
        private boolean resolved, failed, touched;
        private int appliedRadius = -1;
        private boolean appliedGlass;
        private int[] appliedColors;
        private View blendLayer;
        private Drawable originalBackground;
        private ViewOutlineProvider originalOutline;
        private int originalAlpha;
        private boolean appearanceOwned;
        private final BannerMaterialStart firstFrame = new BannerMaterialStart();
        private boolean materialReady, waitingForState;
        private int preparationRevision;

        Session(View target) { this(target, false); }
        Session(View target, boolean hunEligible) {
            this.target = target;
            this.hunEligible = hunEligible;
        }

        boolean hasFailed() { return failed; }

        /** 安装到宿主前预建0×0装饰层，避免在draw遍历中改变子树。 */
        void prepare() {
            if (target == null || failed || !NativeFocusAppearance.useNativeMaterial()) return;
            try {
                touched = true; // 仅预建也拥有child/outline，未调用native前同样必须可回收。
                ensureBlendLayer();
            } catch (Throwable error) {
                failed = true;
                clear();
                Log.w(TAG, "banner material preparation failed; keep themed background", error);
            }
        }

        /** OnPreDraw阶段允许取消本轮提交，材质写入和布局不能推迟到draw。 */
        boolean prepareForDraw() {
            if (target == null) return true;
            refresh();
            boolean requested = !failed && NativeFocusAppearance.useNativeMaterial()
                    && (waitingForState || appliedRadius >= 0);
            boolean draw = firstFrame.allowDraw(requested, materialReady && !target.isLayoutRequested(), preparationRevision,
                    target.getWidth(), target.getHeight(), android.os.SystemClock.uptimeMillis());
            if (!draw && BuildConfig.DEBUG) Log.d(TAG, "DIAG banner material first-frame deferred ready="
                    + materialReady + " revision=" + preparationRevision + " size="
                    + target.getWidth() + "x" + target.getHeight() + "; visual unverified");
            return draw;
        }


        // 复用横幅既有生命周期检查，不注册新的后台任务或原生 Flow collector。
        void refresh() {
            if (target == null) return;
            waitingForState = false;
            try {
                // 用户关闭先释放已持有效果；即使此前清理失败，也保留这次回收机会。
                if (!NativeFocusAppearance.useNativeMaterial()) { clear(); return; }
                if (failed) { clear(); return; }
                Object current = state.get();
                if (!target.isAttachedToWindow() || current == null || compat == null) {
                    clear();
                    waitingForState = true;
                    return;
                }
                String type = readMaterialType(current);
                if ("NONE".equals(type)) { clear(); return; }
                boolean useGlass = "GLASS".equals(type);
                int value = XposedHelpers.getIntField(current,
                        useGlass ? "modalMaxGlassBlurRadius" : "miBlurMaxRadius");
                if (value <= 0) { clear(); return; }
                boolean useHun = NativeFocusAppearance.useOs4GlassHunBlend(useGlass);
                Context liveContext = liveContext(current);
                float[] captured = useGlass && hunEligible ? readHunGlassParameters(liveContext) : null;
                int recipe = BannerMaterialRecipe.choose(hunEligible, NativeFocusAppearance.isOs3(),
                        useGlass, captured != null);
                int[] nativeColors;
                if (recipe != BannerMaterialRecipe.LEGACY) {
                    try {
                        loadHunBackground(liveContext);
                        nativeColors = loadNotificationBlendColors(current, useHun,
                                recipe == BannerMaterialRecipe.OS3_FOCUS_HUN);
                    } catch (Throwable error) {
                        reportFallback("HUN resources unavailable: " + error.getClass().getSimpleName() + ": " + error.getMessage());
                        recipe = BannerMaterialRecipe.LEGACY;
                        nativeColors = loadNotificationBlendColors(current, useHun, false);
                    }
                } else {
                    reportFallback(!hunEligible ? "render pinned to legacy (eligibility/sample checked at creation)"
                            : useGlass ? "no current-configuration native HUN glass sample" : "material uses legacy BLUR");
                    nativeColors = loadNotificationBlendColors(current, useHun, false);
                }
                if (useGlass) {
                    if (recipe == BannerMaterialRecipe.OS4_CAPTURED_HUN) {
                        if (!Arrays.equals(captured, nativeGlassParameters)) preparationRevision++;
                        nativeGlassParameters = captured;
                        glassResources = null;
                        glassConfiguration = null;
                    } else loadGlassParameters(current);
                }
                if (touched && recipe == appliedRecipe && value == appliedRadius && useGlass == appliedGlass && useHun == appliedHun
                        && Arrays.equals(nativeColors, appliedColors)) {
                    syncBlendBounds();
                    materialReady = updateGlassMaterial(useGlass);
                    // GLASS→BLUR 时清理失败仍保留持有标记，复用既有检查重试，不重复打印。
                    if (!useGlass || glassOutlineFailed) clearGlassOutline();
                    return;
                }
                resolve();
                if (useGlass && glass == null) {
                    glass = View.class.getDeclaredMethod("setMiGlassBlurRadius", int.class, int.class);
                    glass.setAccessible(true);
                }
                // 原生 Modal 将根窗口采样与背景混色分层；对正文根设置混色会连带影响文字和按钮。
                touched = true;
                ensureBlendLayer();
                syncBlendBounds();
                if (!useGlass) updateGlassMaterial(false);
                invoke(pass, true);
                invoke(mode, 1);
                invoke(windowException, "NotificationShade");
                invoke(radius, value);
                if (glass != null) invoke(glass, useGlass ? value : 0, useGlass ? value : 0);
                if (!Integer.valueOf(1).equals(invoke(readMode))
                        || !Integer.valueOf(value).equals(invoke(readRadius))) {
                    throw new IllegalStateException("native blur readback mismatch");
                }
                invokeOn(blendLayer, viewMode, 1);
                invokeOn(blendLayer, clearBlend);
                for (int i = 0; i < nativeColors.length; i += 2) {
                    invokeOn(blendLayer, addBlend, nativeColors[i], nativeColors[i + 1]);
                }
                materialReady = updateGlassMaterial(useGlass);
                // HUN 使用 ROM 透明形状，不叠加旧 alpha51 底色；旧配方保留原行为。
                // 仅替换模块自有外壳；保留 padding 和显式圆角，清理时恢复原 Drawable。
                int backgroundAlpha = recipe == BannerMaterialRecipe.LEGACY
                        ? Math.min(originalAlpha, MATERIAL_BACKGROUND_ALPHA) : originalAlpha;
                originalBackground.setAlpha(backgroundAlpha);
                setOwnedBackground(recipe == BannerMaterialRecipe.LEGACY ? originalBackground : hunBackground);
                target.invalidateOutline();
                target.invalidate();
                appliedRadius = value;
                appliedGlass = useGlass;
                appliedHun = useHun;
                appliedRecipe = recipe;
                appliedColors = nativeColors.clone();
                preparationRevision++;
                if (BuildConfig.DEBUG) Log.d(TAG, "DIAG banner material pipeline applied type="
                        + (useGlass ? "glass" : "blur") + " radius=" + value
                        + " blendPairs=" + nativeColors.length / 2
                        + " recipe=" + (recipe == BannerMaterialRecipe.OS3_FOCUS_HUN ? "os3-focus-hun"
                                : recipe == BannerMaterialRecipe.OS4_CAPTURED_HUN ? "os4-captured-hun" : "legacy")
                        + " blendSource=" + (recipe == BannerMaterialRecipe.OS3_FOCUS_HUN ? "focus-headsup-three"
                                : useHun ? "notification-headsup" : "notification-shade")
                        + " systemNight=" + (blendConfiguration.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                        + " blendValues=" + Arrays.toString(nativeColors)
                        + " backgroundAlpha=" + originalAlpha + "->" + backgroundAlpha
                        + " glassOutlineApplied=" + (glassOutlineTouched && !glassOutlineFailed)
                        + "; visual unverified");
            } catch (Throwable error) {
                failed = true;
                clear();
                Log.w(TAG, "banner material apply failed; keep themed background", error);
            }
        }

        /** 混色仍读取系统日夜资源；GLASS文字独立选Dark Modal，不强制背景Context。 */
        private int[] loadNotificationBlendColors(Object current, boolean hun, boolean focusHun) {
            Object owner = XposedHelpers.getObjectField(current, "context");
            if (!(owner instanceof Context)) throw new IllegalStateException("SystemUI blend context unavailable");
            Context context = (Context) owner;
            Context app = context.getApplicationContext();
            final Resources resources = (app != null ? app : context).getResources();
            Configuration configuration = resources.getConfiguration();
            if (resources == blendResources && blendConfiguration != null
                    && blendConfiguration.equals(configuration) && notificationBlendColors != null
                    && blendHun == hun && blendFocusHun == focusHun) {
                return notificationBlendColors;
            }
            // 四项资源必须属于同一配方；HUN缺项直接走主题回退，不静默冒充已应用HUN。
            BannerBlendColors.Resources reader = new BannerBlendColors.Resources() {
                private int id(String name, String type) {
                    int id = resources.getIdentifier(name, type, "com.android.systemui");
                    if (id == 0) throw new IllegalStateException("SystemUI blend resource unavailable: " + name);
                    return id;
                }
                @Override public int color(String name) { return resources.getColor(id(name, "color"), null); }
                @Override public int integer(String name) { return resources.getInteger(id(name, "integer")); }
            };
            int[] colors = focusHun ? BannerBlendColors.loadFocusHun(reader) : BannerBlendColors.load(reader, hun);
            // 四项全部成功后才提交新缓存，避免日夜切换失败时混用两代资源。
            blendResources = resources;
            blendConfiguration = new Configuration(configuration);
            blendHun = hun;
            blendFocusHun = focusHun;
            notificationBlendColors = colors;
            return colors;
        }

        private Context liveContext(Object current) {
            Object owner = XposedHelpers.getObjectField(current, "context");
            if (!(owner instanceof Context)) throw new IllegalStateException("SystemUI material context unavailable");
            Context context = (Context) owner;
            Context app = context.getApplicationContext();
            return app != null ? app : context;
        }

        private void reportFallback(String reason) {
            if (reason.equals(fallbackReason)) return;
            fallbackReason = reason;
            Log.i(TAG, "banner HUN recipe fallback: " + reason + "; keep legacy material");
        }

        private void loadHunBackground(Context context) {
            Resources resources = context.getResources();
            Configuration configuration = resources.getConfiguration();
            if (resources == hunResources && hunConfiguration != null
                    && hunConfiguration.equals(configuration) && hunBackground != null) return;
            int id = resources.getIdentifier("notification_heads_up_transparent_bg", "drawable", "com.android.systemui");
            if (id == 0) throw new IllegalStateException("native transparent HUN drawable missing");
            Drawable drawable = resources.getDrawable(id, context.getTheme()).mutate();
            hunBackground = drawable;
            hunResources = resources;
            hunConfiguration = new Configuration(configuration);
        }

        private void setOwnedBackground(Drawable background) {
            if (target.getBackground() == background) return;
            int left = target.getPaddingLeft(), top = target.getPaddingTop();
            int right = target.getPaddingRight(), bottom = target.getPaddingBottom();
            target.setBackground(background);
            target.setPadding(left, top, right, bottom);
        }

        /** 只使用BlurUtilsExt持有的SystemUI Context，RemoteViews的target可能属于通知应用。 */
        private void loadGlassParameters(Object current) {
            Object ownerContext = XposedHelpers.getObjectField(current, "context");
            if (!(ownerContext instanceof Context)) {
                throw new IllegalStateException("SystemUI material context unavailable");
            }
            Resources resources = ((Context) ownerContext).getResources();
            Configuration configuration = resources.getConfiguration();
            if (resources == glassResources && glassConfiguration != null
                    && glassConfiguration.equals(configuration) && nativeGlassParameters != null) return;
            int id = resources.getIdentifier("notification_glass_params_normal", "array", "com.android.systemui");
            if (id == 0) throw new IllegalStateException("SystemUI notification glass resource unavailable");
            float[] values = BannerGlassState.parseParameters(resources.getStringArray(id));
            glassResources = resources;
            glassConfiguration = new Configuration(configuration);
            if (!Arrays.equals(values, nativeGlassParameters)) preparationRevision++;
            nativeGlassParameters = values;
        }

        private void syncBlendBounds() {
            if (blendLayer == null) return;
            if (blendLayer.getParent() != target) {
                throw new IllegalStateException("material background layer detached");
            }
            int width = target.getWidth();
            int height = target.getHeight();
            if (blendLayer.getWidth() != width || blendLayer.getHeight() != height
                    || blendLayer.getLeft() != 0 || blendLayer.getTop() != 0) {
                blendLayer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                blendLayer.layout(0, 0, width, height);
            }
            // 原外壳轮廓也可能随背景更新而改变，不能只在尺寸变化时刷新。
            blendLayer.invalidateOutline();
        }

        private Method glassMethod(String name, Class<?>... types) throws Exception {
            Method method = View.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method;
        }

        private boolean updateGlassMaterial(boolean useGlass) throws Exception {
            if (!useGlass) {
                // 取消待尺寸的初始化；清理失败则走整段主题回退，不复用失效装饰层。
                if (!clearGlassOutline()) throw new IllegalStateException("glass outline release incomplete");
                if (glassState != null) glassState.clear();
                glassReadyLogged = false;
                return true;
            }
            if (glassState == null) {
                glassParameters = glassMethod("setMiGlass", float[].class);
                materialType = glassMethod("setMiViewMaterialType", int.class);
                glassSize = glassMethod("setMiGlassSdfMaxSize", float.class, float.class);
                glassState = new BannerGlassState(new BannerGlassState.Calls() {
                    @Override public void setParameters(float[] values) throws Exception {
                        invokeOn(blendLayer, glassParameters, (Object) values);
                    }
                    @Override public void setMaterialType(int type) throws Exception {
                        invokeOn(blendLayer, materialType, type);
                    }
                    @Override public void setSize(float width, float height) throws Exception {
                        invokeOn(blendLayer, glassSize, width, height);
                    }
                });
            }
            // 同一背景View承担混色、玻璃参数、type1、SDF与8192；正文不消费玻璃材质。
            boolean ready = glassState.apply(nativeGlassParameters, blendLayer.getWidth(), blendLayer.getHeight());
            if (!ready) return false;
            updateGlassOutline(true);
            if (!glassReadyLogged && BuildConfig.DEBUG) {
                glassReadyLogged = true;
                Log.d(TAG, "DIAG banner glass ready receiver=blendLayer resource="
                        + (glassResources == null ? "observed-native-hun" : "notification_glass_params_normal")
                        + " params=" + nativeGlassParameters.length + " materialType=1 sdf="
                        + blendLayer.getWidth() + "x" + blendLayer.getHeight()
                        + " outline=" + (glassOutlineTouched && !glassOutlineFailed) + "; visual unverified");
            }
            return true;
        }

        /**
         * OS4 17.03.260226.r NotificationBackgroundViewInjectorImpl.ensureOutlineProvider
         * 在 GLASS 分支调用 MiBlurCompat.setGlassOutlineEnable；该包装使用 8192 位。
         * 来源：作者只读 MCP / Knowledge/sources/decompiled/glass-outline-20261113。
         * OS3 未确认相同语义，必须先存在该包装合同，不能仅凭同名 enhance delegate 启用。
         */
        private void updateGlassOutline(boolean useGlass) {
            if (!useGlass) { clearGlassOutline(); return; }
            if (glassOutlineFailed || glassOutlineTouched) return;
            try {
                compat.getDeclaredMethod("setGlassOutlineEnable", View.class, boolean.class);
                glassOutline = delegate("setMiBackgroundBlurEnhanceFlag$delegate");
                // 即使底层先写入后抛错，也在收起/回退时重试清除此位。
                glassOutlineTouched = true;
                invokeOn(blendLayer, glassOutline, 8192, 8192);
            } catch (Throwable error) {
                glassOutlineFailed = true;
                Log.w(TAG, "banner glass outline unavailable; keep blur and blend", error);
                clearGlassOutline();
            }
        }

        private boolean clearGlassOutline() {
            if (!glassOutlineTouched) return true;
            try {
                // 仅清模块自有背景层的玻璃位，不清空其他增强标志。
                invokeOn(blendLayer, glassOutline, 0, 8192);
                glassOutlineTouched = false;
                return true;
            } catch (Throwable error) {
                if (!glassOutlineFailed) Log.w(TAG, "banner glass outline cleanup failed", error);
                glassOutlineFailed = true;
                return false;
            }
        }

        private void resolve() throws Exception {
            if (resolved) return;
            // ROM Compat 会吞掉反射失败，直接使用它解析的 Method，才能核验实际调用并可靠回退。
            pass = delegate("setPassWindowBlurEnabledMethod$delegate");
            mode = delegate("setMiBackgroundBlurModeMethod$delegate");
            windowException = delegate("setMiBlurWinExcMethod$delegate");
            radius = delegate("setMiBackgroundBlurRadiusMethod$delegate");
            readMode = delegate("getMiBackgroundBlurModeMethod$delegate");
            readRadius = delegate("getMiBackgroundBlurRadiusMethod$delegate");
            viewMode = delegate("setMiViewBlurModeMethod$delegate");
            clearBlend = delegate("clearMiBackgroundBlendColorMethod$delegate");
            addBlend = delegate("addMiBackgroundBlendColorMethod$delegate");
            resolved = true;
        }

        private Method delegate(String name) throws Exception {
            Object lazy = XposedHelpers.getStaticObjectField(compat, name);
            Object method = XposedHelpers.callMethod(lazy, "getValue");
            if (!(method instanceof Method)) throw new NoSuchMethodException(name);
            return (Method) method;
        }

        private void ensureBlendLayer() {
            if (!(target instanceof ViewGroup)) throw new IllegalStateException("banner target is not a container");
            if (blendLayer != null) {
                if (blendLayer.getParent() != target) throw new IllegalStateException("stale material background layer");
                return;
            }
            originalBackground = target.getBackground();
            if (originalBackground == null) throw new IllegalStateException("banner background missing");
            originalAlpha = originalBackground.getAlpha();
            originalOutline = target.getOutlineProvider();
            if (originalOutline == null) throw new IllegalStateException("banner outline missing");
            ViewGroup parent = (ViewGroup) target;
            blendLayer = new View(target.getContext());
            blendLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            blendLayer.setClickable(false);
            blendLayer.setFocusable(false);
            // MATCH_PARENT 背景子项在 AT_MOST 首轮测量会抢占整个可用高度。
            // 固定0×0只退出尺寸计算；布局和pre-draw阶段按外壳实际尺寸同步。
            parent.addView(blendLayer, 0, new ViewGroup.LayoutParams(0, 0));
            appearanceOwned = true;
            ViewOutlineProvider materialOutline = new ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    // BACKGROUND provider必须读有主题背景的target，不能读空背景的装饰View。
                    originalOutline.getOutline(target, outline);
                    outline.setAlpha(1f);
                }
            };
            target.setOutlineProvider(materialOutline);
            blendLayer.setOutlineProvider(materialOutline);
            blendLayer.setClipToOutline(false);
        }

        private Object invoke(Method method, Object... args) throws Exception {
            if (method == null) throw new NoSuchMethodException("material method");
            Object result = method.invoke(target, args);
            if (Boolean.FALSE.equals(result)) {
                throw new IllegalStateException("native call returned false: " + method.getName());
            }
            return result;
        }

        private void invokeOn(View view, Method method, Object... args) throws Exception {
            if (view == null || method == null) throw new NoSuchMethodException("material blend method");
            Object result = method.invoke(view, args);
            if (Boolean.FALSE.equals(result)) {
                throw new IllegalStateException("native call returned false: " + method.getName());
            }
        }

        void clear() {
            // 先撤销首帧待尺寸意图，再逐项回收；之后的pre-draw不能重新启用玻璃。
            materialReady = false;
            waitingForState = false;
            appliedGlass = false;
            appliedHun = false;
            appliedRecipe = BannerMaterialRecipe.LEGACY;
            appliedRadius = -1;
            appliedColors = null;
            glassReadyLogged = false;
            if (!touched) return;
            boolean success = clearGlassOutline();
            if (glassState != null) {
                try { glassState.clear(); }
                catch (Throwable error) {
                    success = false;
                    if (!failed) Log.w(TAG, "banner glass cleanup incomplete", error);
                }
            }
            success &= resetOn(blendLayer, clearBlend);
            success &= resetOn(blendLayer, viewMode, 0);
            success &= reset(glass, 0, 0);
            success &= reset(radius, 0);
            success &= reset(mode, 0);
            success &= reset(pass, false);
            if (appearanceOwned && originalBackground != null) {
                try {
                    originalBackground.setAlpha(originalAlpha);
                    setOwnedBackground(originalBackground);
                    target.setOutlineProvider(originalOutline);
                    target.invalidateOutline();
                    target.invalidate();
                    appearanceOwned = false;
                } catch (Throwable error) {
                    success = false;
                    if (!failed) Log.w(TAG, "banner material appearance restore failed", error);
                }
            }
            if (blendLayer != null && blendLayer.getParent() instanceof ViewGroup) {
                try { ((ViewGroup) blendLayer.getParent()).removeView(blendLayer); }
                catch (Throwable error) {
                    success = false;
                    if (!failed) Log.w(TAG, "banner material blend layer removal failed", error);
                }
            }
            if (success) {
                touched = false;
                blendLayer = null;
                glassState = null;
            } else {
                // 保留旧对象供clear重试，但禁止重新apply到已移除的背景层。
                failed = true;
            }
        }

        private boolean resetOn(View view, Method method, Object... args) {
            if (view == null || method == null) return true;
            try { invokeOn(view, method, args); return true; }
            catch (Throwable error) {
                if (!failed) Log.w(TAG, "banner material cleanup failed: " + method.getName(), error);
                failed = true;
                return false;
            }
        }

        private boolean reset(Method method, Object... args) {
            if (method == null) return true;
            try { invoke(method, args); return true; }
            catch (Throwable error) {
                // 清理失败只记一次，本 Session 后续仍可在释放时重试。
                if (!failed) Log.w(TAG, "banner material cleanup failed: " + method.getName(), error);
                failed = true;
                return false;
            }
        }
    }
    private NativeBannerMaterial() { }
}
