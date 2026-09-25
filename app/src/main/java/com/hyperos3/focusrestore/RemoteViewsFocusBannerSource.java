package com.hyperos3.focusrestore;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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
    /**
     * The resource the ROM uses as a focus banner's background. Verified in the HyperOS 4 plugin
     * (18.2.2.2.0): {@code focus_notification_template_base} and
     * {@code focus_notification_module_background} both set {@code android:background} to this, with
     * {@code outlineProvider="background"} and {@code clipToOutline="true"}. The drawable itself is a
     * rectangle whose solid colour is {@code @color/transparent} and whose corners are
     * {@code @dimen/notification_item_bg_radius} (24dp): the ROM fills it with a blurred backdrop and
     * uses the shape only to clip. The guesses after it are kept for older plugins.
     */
    private static final String[] FOCUS_BACKGROUND_NAMES = {
        "focus_notify_bg_img_bg",
        "miui_focus_notification_bg",
        "miui_focus_bg",
        "focus_notification_bg",
    };
    /** The ROM's own corner radius for this shape, used when the blurred fill is unavailable. */
    private static final String FOCUS_RADIUS_NAME = "notification_item_bg_radius";
    private static final int FOCUS_RADIUS_FALLBACK_DP = 24;

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
        String background = applyContainerBackground(sysuiContext, host, content);
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
    private static String applyContainerBackground(Context sysuiContext, View host, View content) {
        if (content.getBackground() != null) return "content";
        Drawable romShape = focusBackgroundFrom(sysuiContext, PLUGIN_PACKAGE);
        if (romShape == null) romShape = focusBackgroundFrom(sysuiContext, "com.android.systemui");
        if (romShape != null) {
            // The ROM's shape is transparent by design and is filled by a blurred backdrop there.
            // The platform's cross-window blur was tried for this window and did not take effect on
            // the tested ROM even while isCrossWindowBlurEnabled() reported true, which left the
            // banner invisible, so the shape is always paired with a real fill instead.
            host.setClipToOutline(true);
            host.setBackground(new LayerDrawable(new Drawable[]{
                    roundedSurface(host.getContext(), focusRadiusPx(sysuiContext, host.getContext())),
                    romShape}));
            return "rom-shape+platform-color";
        }
        Drawable platform = roundedSurface(host.getContext(),
                focusRadiusPx(sysuiContext, host.getContext()));
        if (platform == null) return "none";
        host.setBackground(platform);
        host.setClipToOutline(true);
        return "platform";
    }

    /** The first candidate focus-banner resource that exists in {@code packageName}'s resources. */
    private static Drawable focusBackgroundFrom(Context sysuiContext, String packageName) {
        Resources resources = resourcesFor(sysuiContext, packageName);
        if (resources == null) return null;
        for (String name : FOCUS_BACKGROUND_NAMES) {
            for (String type : new String[]{"drawable", "color"}) {
                try {
                    int id = resources.getIdentifier(name, type, packageName);
                    if (id == 0) continue;
                    Drawable drawable = resources.getDrawable(id, sysuiContext.getTheme());
                    if (drawable != null) return drawable;
                } catch (Throwable ignored) {
                    // A candidate that cannot be resolved is simply not the resource we are after.
                }
            }
        }
        return null;
    }

    /** The ROM's own corner radius, so a substituted fill keeps the ROM's geometry. */
    private static int focusRadiusPx(Context sysuiContext, Context fallbackContext) {
        for (String packageName : new String[]{PLUGIN_PACKAGE, "com.android.systemui"}) {
            Resources resources = resourcesFor(sysuiContext, packageName);
            if (resources == null) continue;
            try {
                int id = resources.getIdentifier(FOCUS_RADIUS_NAME, "dimen", packageName);
                if (id != 0) return resources.getDimensionPixelSize(id);
            } catch (Throwable ignored) {
                // Fall through to the next package.
            }
        }
        float density = fallbackContext.getResources().getDisplayMetrics().density;
        return Math.round(FOCUS_RADIUS_FALLBACK_DP * density);
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
    private static Drawable roundedSurface(Context context, int radiusPx) {
        int color = themedColor(context, android.R.attr.colorBackgroundFloating);
        if (!hasAlpha(color)) color = themedColor(context, android.R.attr.colorBackground);
        if (!hasAlpha(color)) return null;
        GradientDrawable surface = new GradientDrawable();
        surface.setColor(color);
        if (radiusPx > 0) surface.setCornerRadius(radiusPx);
        return surface;
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
