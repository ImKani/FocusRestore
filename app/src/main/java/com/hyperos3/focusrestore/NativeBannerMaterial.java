/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

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
 * NotificationMaterialStateInteractor.materialTypeState。仅独立实现调用与回退，未复制 ROM 实现。
 * 来源与边界：https://github.com/ImKani/FocusRestore README“横幅材质”；
 * 作者工作区 Knowledge/rom/banner-material-and-geometry.md 保留只读 APK 证据。
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

    static final class Session {
        private final View target;
        private Method pass, mode, radius, windowException, glass, readMode, readRadius;
        private Method viewMode, clearBlend, addBlend, glassOutline;
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
        private boolean firstDrawPrepared;

        Session(View target) { this.target = target; }

        /** 在首帧绘制前决定背景；装饰层只跟随已布局的正文，不参与外壳尺寸计算。 */
        void beforeDraw() {
            if (target == null || failed) return;
            if (!firstDrawPrepared) {
                firstDrawPrepared = true;
                refresh();
            }
            if (blendLayer == null || failed) return;
            try {
                int width = target.getWidth();
                int height = target.getHeight();
                if (blendLayer.getWidth() != width || blendLayer.getHeight() != height
                        || blendLayer.getLeft() != 0 || blendLayer.getTop() != 0) {
                    blendLayer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
                    blendLayer.layout(0, 0, width, height);
                }
            } catch (Throwable error) {
                failed = true;
                clear();
                Log.w(TAG, "banner material bounds failed; keep themed background", error);
            }
        }

        // 复用横幅既有生命周期检查，不注册新的后台任务或原生 Flow collector。
        void refresh() {
            if (target == null) return;
            try {
                // 用户关闭先释放已持有效果；即使此前清理失败，也保留这次回收机会。
                if (!NativeFocusAppearance.useNativeMaterial()) { clear(); return; }
                if (failed) return;
                Object current = state.get();
                if (!target.isAttachedToWindow() || current == null || compat == null) {
                    clear();
                    return;
                }
                boolean allowed = Boolean.TRUE.equals(XposedHelpers.callMethod(current, "getUseBlendBlur"))
                        && XposedHelpers.getBooleanField(current, "blurFeatureSupportedOnDevice")
                        && !XposedHelpers.getBooleanField(current, "blurDisabledBySettings");
                boolean useGlass = false;
                // OS3 没有此字段；按已核对的两个模型分支，不推断操作系统模式。
                java.lang.reflect.Field interactorField = XposedHelpers.findFieldIfExists(
                        current.getClass(), "materialInteractor");
                if (interactorField != null) {
                    Object interactor = interactorField.get(current);
                    Object flow = XposedHelpers.getObjectField(interactor, "materialTypeState");
                    Object type = XposedHelpers.callMethod(flow, "getValue");
                    if (!(type instanceof Enum)) throw new IllegalStateException("unknown material type");
                    String name = ((Enum<?>) type).name();
                    if (!"NONE".equals(name) && !"BLUR".equals(name) && !"GLASS".equals(name)) {
                        throw new IllegalStateException("unsupported material type: " + name);
                    }
                    allowed &= !"NONE".equals(name);
                    useGlass = "GLASS".equals(name);
                }
                if (!allowed) { clear(); return; }
                int value = XposedHelpers.getIntField(current,
                        useGlass ? "modalMaxGlassBlurRadius" : "miBlurMaxRadius");
                if (value <= 0) { clear(); return; }
                Object colors = XposedHelpers.getObjectField(current, "blendColors");
                if (!(colors instanceof int[]) || ((int[]) colors).length == 0
                        || (((int[]) colors).length & 1) != 0) {
                    throw new IllegalStateException("native blend color/mode pairs unavailable");
                }
                int[] nativeColors = (int[]) colors;
                if (touched && value == appliedRadius && useGlass == appliedGlass
                        && Arrays.equals(nativeColors, appliedColors)) {
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
                invoke(pass, true);
                invoke(mode, 1);
                invoke(windowException, "NotificationShade");
                invoke(radius, value);
                if (glass != null) invoke(glass, useGlass ? value : 0, useGlass ? value : 0);
                if (!Integer.valueOf(1).equals(invoke(readMode))
                        || !Integer.valueOf(value).equals(invoke(readRadius))) {
                    throw new IllegalStateException("native blur readback mismatch");
                }
                updateGlassOutline(useGlass);
                invokeOn(blendLayer, viewMode, 1);
                invokeOn(blendLayer, clearBlend);
                for (int i = 0; i < nativeColors.length; i += 2) {
                    invokeOn(blendLayer, addBlend, nativeColors[i], nativeColors[i + 1]);
                }
                // 原背景不再完全透明，保留少量主题底色以改善明暗壁纸上的文字对比。
                // 只更改自有外壳 Drawable，保留 padding；显式轮廓 alpha=1 保留模糊圆角。
                int backgroundAlpha = Math.min(originalAlpha, MATERIAL_BACKGROUND_ALPHA);
                originalBackground.setAlpha(backgroundAlpha);
                target.invalidateOutline();
                target.invalidate();
                appliedRadius = value;
                appliedGlass = useGlass;
                appliedColors = nativeColors.clone();
                if (BuildConfig.DEBUG) Log.d(TAG, "DIAG banner material pipeline applied type="
                        + (useGlass ? "glass" : "blur") + " radius=" + value
                        + " blendPairs=" + nativeColors.length / 2
                        + " backgroundAlpha=" + originalAlpha + "->" + backgroundAlpha
                        + " glassOutlineApplied=" + (glassOutlineTouched && !glassOutlineFailed)
                        + "; visual unverified");
            } catch (Throwable error) {
                failed = true;
                clear();
                Log.w(TAG, "banner material apply failed; keep themed background", error);
            }
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
                invoke(glassOutline, 8192, 8192);
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
                invoke(glassOutline, 0, 8192);
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
            if (blendLayer != null) return;
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
            // 固定 0×0 只退出尺寸计算；实际绘制边界在 beforeDraw 中取已布局的外壳。
            parent.addView(blendLayer, 0, new ViewGroup.LayoutParams(0, 0));
            appearanceOwned = true;
            target.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, android.graphics.Outline outline) {
                    originalOutline.getOutline(view, outline);
                    outline.setAlpha(1f);
                }
            });
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
            if (!touched) return;
            // 每一步独立清理；某个扩展失败也继续移除其余效果，内容释放不受材质异常影响。
            boolean success = clearGlassOutline();
            success &= resetOn(blendLayer, clearBlend);
            success &= resetOn(blendLayer, viewMode, 0);
            success &= reset(glass, 0, 0);
            success &= reset(radius, 0);
            success &= reset(mode, 0);
            success &= reset(pass, false);
            if (appearanceOwned && originalBackground != null) {
                originalBackground.setAlpha(originalAlpha);
                target.setOutlineProvider(originalOutline);
                target.invalidateOutline();
                target.invalidate();
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
                appearanceOwned = false;
            }
            appliedRadius = -1;
            appliedGlass = false;
            appliedColors = null;
        }

        private boolean resetOn(View view, Method method, Object... args) {
            if (view == null || method == null) return true;
            try { method.invoke(view, args); return true; }
            catch (Throwable error) {
                if (!failed) Log.w(TAG, "banner material cleanup failed: " + method.getName(), error);
                failed = true;
                return false;
            }
        }

        private boolean reset(Method method, Object... args) {
            if (method == null) return true;
            try { method.invoke(target, args); return true; }
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
