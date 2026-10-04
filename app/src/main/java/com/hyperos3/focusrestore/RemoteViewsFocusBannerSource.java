/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

/*
 * 原始项目：https://github.com/ImKani/FocusRestore
 * 外部事实来源：HyperOS/SystemUI 的 notification_item_bg 与 miui.focus.rv 资源/协议；具体版本和分析记录见 Notes。
 * 说明：本文件为 FocusRestore 独立实现，不复制或重新授权 SystemUI 代码。
 */

package com.hyperos3.focusrestore;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.Color;
import android.util.Log;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Banner content inflated from the notification's own {@code miui.focus.rv} RemoteViews.
 *
 * <p>A focus notification of type CUSTOM is rendered by {@code
 * FocusNotifPreHandler.buildNoParamsFocusNotification}, which applies the application's RemoteViews
 * through {@code RemoteViews.apply}. It never calls {@code createStandardTemplateView}, so the
 * native-template engine has nothing to observe and can never become ready for these notifications.
 *
 * <p>This source follows the ROM's own approach, verified in the plugin: build a context for the
 * notification package with {@code createPackageContextAsUser(packageName, CONTEXT_RESTRICTED,
 * user)} and inflate a fresh copy of the RemoteViews. Nothing system-owned is reparented, and no
 * {@code OnClickHandler} is supplied, so the application's own {@code PendingIntent} actions on its
 * buttons keep working exactly as the ROM intended.
 */
final class RemoteViewsFocusBannerSource implements FocusBannerSource {
    private static final String LIGHT = "miui.focus.rv";
    private static final String DARK = "miui.focus.rvNight";
    private static final String PLUGIN_PACKAGE = "miui.systemui.plugin";
    private static final int MAX_TEXT_ENTRIES = 4;
    /** The notification card background SystemUI draws in the shade; the banner matches it. */
    private static final String NOTIFICATION_ITEM_BG = "notification_item_bg";
    /** The card's own colour. Never read directly: a theme overlay replaces it at runtime, so the card is
     * inflated from {@link #NOTIFICATION_ITEM_BG} instead of being rebuilt from this. */
    private static final String NOTIFICATION_BG_COLOR = "notification_bg_color";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    /** The card's corner radius, used only for the fallback surface. */
    private static final String FOCUS_RADIUS_NAME = "notification_item_bg_radius";
    private static final int FOCUS_RADIUS_FALLBACK_DP = 24;
    /**
     * The height the ROM gives a focus notification's layout: {@code focus_notification_template_base}
     * sets {@code android:layout_height} to this (75dp in the OS4 plugin). A {@code wrap_content} host
     * collapses a layout that expects that box — a call banner came out at 52dp instead of 75dp — so it
     * is applied as a minimum, which still lets taller content expand.
     */
    private static final String FOCUS_HEIGHT_NAME = "focus_notify_normal_height";
    private static final int FOCUS_HEIGHT_FALLBACK_DP = 75;
    private static volatile boolean forceNormalBackground;

    /**
     * 参数语义是“使用普通通知卡片背景”，而不是“强制纯色”。主 Hook 传入 !specialSetting，
     * 因此默认的统一纯色设置会传 false；普通背景开关开启时传 true。
     */
    static void setForceNormalBackground(boolean enabled) {
        forceNormalBackground = enabled;
        NativeFocusAppearance.setUseNormalNotificationBackground(enabled);
    }

    private final View view;
    private final Context context;
    private final StatusBarNotification sbn;
    private final int widthPx;
    private final String source;
    private final String containerBackground;
    private boolean closed;

    private RemoteViewsFocusBannerSource(View view, Context context, StatusBarNotification sbn,
                                         int widthPx, String source, String containerBackground) {
        this.view = view;
        this.context = context;
        this.sbn = sbn;
        this.widthPx = widthPx;
        this.source = source;
        this.containerBackground = containerBackground;
    }

    /** True when the notification carries a RemoteViews this source can inflate. */
    static boolean isAvailable(StatusBarNotification sbn) {
        return pick(sbn, false) != null || pick(sbn, true) != null;
    }

    /**
     * Inflates the notification's focus RemoteViews. Throws when the notification carries none, so
     * the caller can fall back to reporting the original native failure instead of guessing.
     */
    static FocusBannerSource create(Context sysuiContext, StatusBarNotification sbn, boolean dark)
            throws Exception {
        if (sysuiContext == null || sbn == null || sbn.getNotification() == null) {
            throw new IllegalStateException("no notification to inflate focus content from");
        }
        boolean useDark = dark;
        RemoteViews views = pick(sbn, useDark);
        if (views == null) {
            useDark = !dark;
            views = pick(sbn, useDark);
        }
        if (views == null) {
            throw new IllegalStateException("notification has no miui.focus.rv RemoteViews");
        }
        Context packageContext = packageContextFor(sysuiContext, sbn);
        // A null parent keeps inflation unattached and lets us supply the banner layout params.
        View content = views.apply(packageContext, null);
        if (content == null) {
            throw new IllegalStateException("RemoteViews.apply returned no view");
        }
        if (content.getParent() != null) {
            throw new IllegalStateException("inflated focus content is already attached");
        }
        FrameLayout host = new FrameLayout(packageContext);
        host.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        String background = applyContainerBackground(sysuiContext, host, content, useDark);
        // The ROM measures the application's layout inside a fixed-height box and the layout centres
        // its own rows and buttons in that box. Giving the height to the host alone left the layout at
        // its natural, shorter size pinned to the top, so everything inside drifted upwards; the same
        // minimum has to reach the layout itself for its internal centring to work as it does in the ROM.
        int focusHeight = focusHeightPx(sysuiContext, packageContext);
        host.setMinimumHeight(focusHeight);
        content.setMinimumHeight(focusHeight);
        // Report the full display width; the host clamps it to the safe area for this display.
        DisplayMetrics metrics = sysuiContext.getResources().getDisplayMetrics();
        String label = "remoteviews/" + (useDark ? DARK : LIGHT) + "/" + sbn.getPackageName();
        return new RemoteViewsFocusBannerSource(host, packageContext, sbn,
                Math.max(1, metrics.widthPixels), label, background);
    }

    /**
     * Gives the banner a surface to sit on and reports which one was used.
     *
     * <p>The application's own layout wins when it brings a background. Otherwise the ROM's focus
     * shape is used, because the ROM — not this module — owns what a focus banner looks like. That
     * shape is deliberately transparent and is filled by a blurred backdrop in the ROM, so the window
     * asks for a platform blur behind it; where no blur is available the platform colour is put behind
     * the same ROM shape, so the banner is at least a legible card rather than nothing.
     */
    private static String applyContainerBackground(Context sysuiContext, View host, View content,
                                                    boolean nightVariant) {
        if (forceNormalBackground) {
            int radiusPx = focusRadiusPx(sysuiContext, host.getContext());
            // 普通背景保留电话/通知栏旧卡片的主题和透明度，不强制改成纯色。
            Drawable normal = notificationCardBackground(sysuiContext);
            if (normal == null) normal = roundedSurface(host.getContext(), radiusPx, nightVariant);
            if (normal != null) {
                content.setBackground(null);
                host.setBackground(normal);
                host.setClipToOutline(true);
                return "notification-card-normal-themed radiusPx=" + radiusPx
                        + " alpha=" + normal.getAlpha();
            }
        }
        if (!forceNormalBackground) {
            int radiusPx = focusRadiusPx(sysuiContext, host.getContext());
            // 默认统一纯色，独立窗口没有通知栏模糊层，必须去除透明透底。
            Drawable solid = opaqueNotificationSurface(sysuiContext, radiusPx, nightVariant);
            if (solid == null) solid = roundedSurface(host.getContext(), radiusPx, nightVariant);
            if (solid != null) {
                content.setBackground(null);
                host.setBackground(solid);
                host.setClipToOutline(true);
                return "solid-background radiusPx=" + radiusPx + " alpha=" + solid.getAlpha();
            }
        }
        // The card is the ROM's own notification card drawable, used as-is, with only its alpha forced to
        // fully opaque. The ROM's fill carries real transparency because inside the shade it sits on a blur
        // layer; a standalone window has no such layer, so that transparency goes straight through to the
        // app behind the banner. Rebuilding the shape from notification_bg_color was tried and is wrong:
        // a theme overlay replaces that colour, so re-deriving it produced a white card in dark mode.
        // Inflating the drawable keeps the ROM (and the theme) in charge of everything but the alpha.
        Drawable card = notificationCardBackground(sysuiContext);
        if (card != null) {
            String described = cardDescription(card, sysuiContext);
            host.setBackground(forceOpaque(card));
            host.setClipToOutline(true);
            return "notification-card" + described + " forced=255 hostAlpha=" + host.getAlpha();
        }
        int radiusPx = focusRadiusPx(sysuiContext, host.getContext());
        Drawable platform = roundedSurface(host.getContext(), radiusPx, nightVariant);
        if (platform == null) return "none";
        host.setBackground(platform);
        host.setClipToOutline(true);
        return "platform" + (nightVariant ? "-night" : "-day")
                + " hostAlpha=" + host.getAlpha();
    }

    /**
     * The ROM's own notification card drawable, resolved through SystemUI's live resources: that is the
     * same drawable, in the same configuration, that the shade is drawing at this moment, so the banner
     * cannot disagree with it about day/night. A forced configuration is deliberately not used here, since
     * one was measured returning a day colour in a night session.
     */
    private static Drawable notificationCardBackground(Context sysuiContext) {
        try {
            Resources resources = sysuiContext.getResources();
            int id = resources.getIdentifier(NOTIFICATION_ITEM_BG, "drawable", SYSTEMUI_PACKAGE);
            if (id == 0) return null;
            return resources.getDrawable(id, sysuiContext.getTheme());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The same card with its transparency removed. Both the shape's fill colour and the drawable's own
     * alpha are forced, because which of the two is responsible depends on how the drawable was built and
     * {@code setAlpha} alone can end up merely combined with an already-translucent fill.
     */
    private static Drawable forceOpaque(Drawable card) {
        if (card instanceof GradientDrawable) {
            GradientDrawable shape = (GradientDrawable) card;
            try {
                ColorStateList fill = shape.getColor();
                if (fill != null) shape.setColor((fill.getDefaultColor() & 0x00FFFFFF) | 0xFF000000);
            } catch (Throwable ignored) { }
        }
        card.setAlpha(255);
        return card;
    }

    /**
     * What the card really is at runtime, which the resource table cannot say: a theme overlay replaces
     * {@code notification_bg_color}, so the fill and its alpha are only visible here. {@code fill} carries
     * its own alpha, and is reported before the force so the log shows what the ROM asked for;
     * {@code nightFill} is the same drawable resolved in a forced night configuration, which is how a
     * colour lookup that ignores night mode becomes visible in the log.
     */
    private static String cardDescription(Drawable card, Context sysuiContext) {
        try {
            String name = card.getClass().getSimpleName();
            if (card instanceof GradientDrawable) {
                ColorStateList fill = ((GradientDrawable) card).getColor();
                int color = fill == null ? 0 : fill.getDefaultColor();
                return "(" + name + " fill=#" + Integer.toHexString(color)
                        + " alpha=" + card.getAlpha()
                        + " nightFill=" + nightCardFill(sysuiContext) + ")";
            }
            return "(" + name + " alpha=" + card.getAlpha() + ")";
        } catch (Throwable ignored) {
            return "(?)";
        }
    }

    /** The same card's fill under a forced night configuration, for log comparison only. */
    private static String nightCardFill(Context sysuiContext) {
        try {
            Context themed = nightContext(sysuiContext, true);
            Resources resources = themed.getResources();
            int id = resources.getIdentifier(NOTIFICATION_ITEM_BG, "drawable", SYSTEMUI_PACKAGE);
            if (id == 0) return "?";
            Drawable drawable = resources.getDrawable(id, themed.getTheme());
            if (drawable instanceof GradientDrawable) {
                ColorStateList fill = ((GradientDrawable) drawable).getColor();
                if (fill != null) return "#" + Integer.toHexString(fill.getDefaultColor());
            }
            return "?";
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** The first candidate focus-banner resource that exists in {@code packageName}'s resources. */

    /** The ROM's own corner radius, so a substituted fill keeps the ROM's geometry. */
    private static int focusRadiusPx(Context sysuiContext, Context fallbackContext) {
        return focusDimenPx(sysuiContext, fallbackContext, FOCUS_RADIUS_NAME, FOCUS_RADIUS_FALLBACK_DP);
    }

    /** The ROM's focus layout height, so the application's layout is measured in the ROM's own box. */
    private static int focusHeightPx(Context sysuiContext, Context fallbackContext) {
        return focusDimenPx(sysuiContext, fallbackContext, FOCUS_HEIGHT_NAME, FOCUS_HEIGHT_FALLBACK_DP);
    }

    private static int focusDimenPx(Context sysuiContext, Context fallbackContext, String name,
                                    int fallbackDp) {
        for (String packageName : new String[]{PLUGIN_PACKAGE, "com.android.systemui"}) {
            Resources resources = resourcesFor(sysuiContext, packageName);
            if (resources == null) continue;
            try {
                int id = resources.getIdentifier(name, "dimen", packageName);
                if (id != 0) {
                    int px = resources.getDimensionPixelSize(id);
                    if (px > 0) return px;
                }
            } catch (Throwable ignored) {
                // Fall through to the next package.
            }
        }
        float density = fallbackContext.getResources().getDisplayMetrics().density;
        return Math.round(fallbackDp * density);
    }

    private static Resources resourcesFor(Context sysuiContext, String packageName) {
        try {
            return sysuiContext.getPackageManager().getResourcesForApplication(packageName);
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /**
     * A rounded surface in the platform's floating colour, used only when the ROM's shape cannot be
     * filled the way the ROM fills it. Deliberately not an imitation of the ROM's design: a theme
     * colour and the ROM's own corner radius.
     */
    private static Drawable opaqueNotificationSurface(Context sysuiContext, int radiusPx,
                                                      boolean nightVariant) {
        Drawable card = notificationCardBackground(sysuiContext);
        if (card == null) return null;
        int width = Math.max(32, Math.round(96 * sysuiContext.getResources().getDisplayMetrics().density));
        int height = Math.max(32, Math.round(48 * sysuiContext.getResources().getDisplayMetrics().density));
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            card.setBounds(0, 0, width, height);
            card.draw(new Canvas(bitmap));
            int color = bitmap.getPixel(width / 2, height / 2);
            if (Color.alpha(color) == 0) return null;
            GradientDrawable surface = new GradientDrawable();
            surface.setColor(Color.rgb(Color.red(color), Color.green(color), Color.blue(color)) | 0xFF000000);
            if (radiusPx > 0) surface.setCornerRadius(radiusPx);
            Log.i("HyperOS3FocusRestore", "forced normal background sampled color=#"
                    + Integer.toHexString(color) + " source=" + card.getClass().getSimpleName());
            return surface;
        } finally {
            bitmap.recycle();
        }
    }

    private static Drawable roundedSurface(Context context, int radiusPx, boolean night) {
        // night-qualified, so the surface colour is read from a context forced to the requested night
        // mode instead of trusting the theme it happens to carry; the colour is still the platform's.
        Context themed = nightContext(context, night);
        int color = themedColor(themed, android.R.attr.colorBackgroundFloating);
        if (!hasAlpha(color)) color = themedColor(themed, android.R.attr.colorBackground);
        if (!hasAlpha(color)) return null;
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(color);
        if (radiusPx > 0) surface.setCornerRadius(radiusPx);
        return surface;
    }

    /** A context forced to the requested night mode, so a theme colour matches the inflated variant. */
    private static Context nightContext(Context context, boolean night) {
        try {
            Configuration configuration = new Configuration(context.getResources().getConfiguration());
            configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                    | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
            return context.createConfigurationContext(configuration);
        } catch (Throwable ignored) {
            return context;
        }
    }

    private static int themedColor(Context context, int attribute) {
        TypedArray values = null;
        try {
            values = context.obtainStyledAttributes(new int[]{attribute});
            return values.getColor(0, 0);
        } catch (Throwable ignored) {
            return 0;
        } finally {
            if (values != null) values.recycle();
        }
    }

    private static boolean hasAlpha(int color) {
        return (color >>> 24) != 0;
    }

    /**
     * Resolves a context whose resources belong to the notification package, which is what makes the
     * application's layout and drawables inflate correctly.
     *
     * <p>{@code Context.createPackageContextAsUser} is a hidden API — the SystemUI plugin calls it
     * directly because it is built against the internal SDK, but a module compiled against the public
     * SDK must reach it reflectively. This mirrors the plugin's own call
     * ({@code createPackageContextAsUser(packageName, CONTEXT_RESTRICTED, user)}); the public
     * per-user API is used only if the hidden one is unavailable.
     */
    private static Context packageContextFor(Context sysuiContext, StatusBarNotification sbn)
            throws Exception {
        String packageName = sbn.getPackageName();
        Context context = null;
        try {
            Method method = Context.class.getMethod("createPackageContextAsUser",
                    String.class, int.class, UserHandle.class);
            Object created = method.invoke(sysuiContext, packageName,
                    Context.CONTEXT_RESTRICTED, sbn.getUser());
            if (created instanceof Context) context = (Context) created;
        } catch (NoSuchMethodException hiddenApiUnavailable) {
            context = null;
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof PackageManager.NameNotFoundException) {
                throw new IllegalStateException("focus content package not found: " + packageName, cause);
            }
            throw failure;
        }
        if (context == null) {
            context = sysuiContext.createPackageContext(packageName, Context.CONTEXT_RESTRICTED);
        }
        return context;
    }

    private static RemoteViews pick(StatusBarNotification sbn, boolean dark) {
        Bundle extras = sbn == null || sbn.getNotification() == null
                ? null : sbn.getNotification().extras;
        if (extras == null) return null;
        Object value = extras.getParcelable(dark ? DARK : LIGHT);
        return value instanceof RemoteViews ? (RemoteViews) value : null;
    }

    @Override
    public View view() {
        return view;
    }

    @Override
    public Context context() {
        return context;
    }

    @Override
    public int widthPx() {
        return widthPx;
    }

    @Override
    public int minHeightPx() {
        return 0;
    }

    @Override
    public String source() {
        return source;
    }

    /** Which surface the banner ended up on, for the host's log line. */
    String containerBackground() {
        return containerBackground;
    }

    @Override
    public String layoutSummary() {
        View content = contentRoot();
        return "remoteviews width=" + view.getWidth() + " height=" + view.getHeight()
                + " measuredHeight=" + view.getMeasuredHeight()
                + " contentBackground=" + describeBackground(content)
                + " containerBackground=" + containerBackground
                + " romMinHeight=" + view.getMinimumHeight()
                + " texts=" + textInventory(content == null ? view : content);
    }

    /** The inflated RemoteViews root, which is what carries the application's own styling. */
    private View contentRoot() {
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        return group.getChildCount() > 0 ? group.getChildAt(0) : null;
    }

    /**
     * Reports the inflated root's background and class. Reading the module's own host instead would
     * always say "none" and would say nothing about what the application supplied; the class name is
     * what identifies an application layout that renders through custom views rather than TextViews.
     */
    private static String describeBackground(View content) {
        if (content == null) return "absent";
        return (content.getBackground() != null ? "content/" : "none/")
                + content.getClass().getSimpleName();
    }

    /**
     * Reads the inflated tree back. The banner is a fresh copy of the application's own layout, and
     * {@code measuredHeight} alone cannot distinguish a populated row from an empty one, so when the
     * banner looks blank the log has to say whether the application's text was applied at all.
     */
    private static String textInventory(View root) {
        StringBuilder out = new StringBuilder("[");
        collectTexts(root, out, new int[]{MAX_TEXT_ENTRIES});
        return out.append(']').toString();
    }

    private static void collectTexts(View node, StringBuilder out, int[] budget) {
        if (node == null || budget[0] <= 0) return;
        if (node instanceof TextView) {
            CharSequence text = ((TextView) node).getText();
            if (budget[0] < MAX_TEXT_ENTRIES) out.append(',');
            out.append(DebugText.preview(text == null ? null : text.toString()));
            budget[0]--;
        }
        if (!(node instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) node;
        for (int index = 0; index < group.getChildCount() && budget[0] > 0; index++) {
            collectTexts(group.getChildAt(index), out, budget);
        }
    }

    @Override
    public void onAttached() {
        // No timer session: the ROM's focus timer belongs to the native template. The banner's own
        // soft/hard deadlines in the host still bound how long this content stays visible.
    }

    @Override
    public boolean isCurrent(StatusBarNotification actual) {
        if (closed) return false;
        return NativeFocusTemplateRenderer.sameNotification(sbn, actual);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            if (view instanceof ViewGroup) ((ViewGroup) view).removeAllViews();
        } catch (Throwable ignored) {
            // Cleanup must never mask the original failure that triggered it.
        }
    }
}