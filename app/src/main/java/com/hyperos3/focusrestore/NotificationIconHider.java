package com.hyperos3.focusrestore;

import android.view.View;
import android.view.ViewGroup;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Keeps SystemUI notification icons hidden without hiding module-owned children. */
final class NotificationIconHider {
    interface Logger { void log(String message); void error(String stage, Throwable throwable); }
    static final int MODE_CONTAINER = 0;
    static final int MODE_SYSTEM_ONLY = 1;
    private final Logger logger;
    private final Map<View, Integer> originalVisibility = Collections.synchronizedMap(new IdentityHashMap<View, Integer>());
    private final Set<View> targets = Collections.newSetFromMap(new IdentityHashMap<View, Boolean>());
    private ViewGroup root;
    private int rootId;
    private boolean requested;
    private int mode = MODE_CONTAINER;
    private boolean internalWrite;
    private boolean installed;
    private View.OnLayoutChangeListener layoutListener;

    NotificationIconHider(Logger logger) { this.logger = logger; }

    void install() {
        if (installed) return;
        installed = true;
        try {
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (internalWrite || !requested || !(param.thisObject instanceof View)
                                    || param.args == null || param.args.length == 0
                                    || !(param.args[0] instanceof Integer)) return;
                            View view = (View) param.thisObject;
                            if (!owns(view)) return;
                            int requestedVisibility = (Integer) param.args[0];
                            remember(view);
                            if (mode == MODE_CONTAINER && view == root) {
                                param.args[0] = View.GONE;
                            } else if (mode == MODE_SYSTEM_ONLY && isSystemIcon(view)) {
                                param.args[0] = View.GONE;
                            } else {
                                logger.log("notificationIcons preserve class=" + view.getClass().getName()
                                        + " id=" + view.getId() + " parent=" + parentName(view));
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (requested && param.thisObject instanceof View && owns((View) param.thisObject)) apply();
                        }
                    });
            logger.log("notificationIcons hider hooks=hooked");
        } catch (Throwable throwable) { logger.error("notificationIcons hider install", throwable); }
    }

    /** Binds the actual notificationIcons root. Call again after SystemUI reinflates it. */
    void bindRoot(ViewGroup newRoot, int id) {
        if (root == newRoot && rootId == id) return;
        clearRootListener();
        restore();
        root = newRoot;
        rootId = id;
        if (root != null) {
            layoutListener = (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (requested) apply();
            };
            root.addOnLayoutChangeListener(layoutListener);
        }
        if (requested) apply();
        logger.log("notificationIcons root=" + (root == null ? "missing" : root.getClass().getName()) + " id=" + id);
    }

    /** Applies the latest request; mode 0 hides the root, mode 1 hides only system icons. */
    void request(boolean hide, int requestedMode) {
        requested = hide;
        mode = requestedMode == MODE_SYSTEM_ONLY ? MODE_SYSTEM_ONLY : MODE_CONTAINER;
        if (!hide) restore(); else apply();
    }

    /** OS3 parent-agent API: locate notificationIcons below the visible prompt's root and apply. */
    void applyForVisiblePrompt(View prompt, boolean hide, int requestedMode) {
        ViewGroup resolved = findNotificationIcons(prompt);
        if (resolved != null) bindRoot(resolved, resolved.getId());
        request(hide, requestedMode);
        logger.log("notificationIcons promptApply hide=" + hide + " mode=" + mode
                + " root=" + (resolved == null ? "missing" : resolved.getClass().getName()));
    }

    void clear() {
        requested = false;
        restore();
        clearRootListener();
        root = null;
        rootId = 0;
    }

    private void apply() {
        ViewGroup container = root;
        if (container == null) return;
        if (mode == MODE_CONTAINER) {
            remember(container);
            write(container, View.GONE);
            logger.log("notificationIcons hide container class=" + container.getClass().getName());
            return;
        }
        scan(container);
        logger.log("notificationIcons selective hidden=" + targets.size()
                + " preservedThirdParty=" + Math.max(0, countChildren(container) - targets.size()));
    }

    private void scan(View view) {
        if (isSystemIcon(view)) {
            targets.add(view);
            remember(view);
            write(view, View.GONE);
            logger.log("notificationIcons hide system class=" + view.getClass().getName()
                    + " id=" + view.getId() + " parent=" + parentName(view));
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scan(group.getChildAt(i));
        }
    }

    private void restore() {
        synchronized (originalVisibility) {
            for (Map.Entry<View, Integer> entry : originalVisibility.entrySet()) {
                if (entry.getKey() != null) write(entry.getKey(), entry.getValue());
            }
            originalVisibility.clear();
        }
        targets.clear();
    }

    private void remember(View view) {
        synchronized (originalVisibility) {
            if (!originalVisibility.containsKey(view)) originalVisibility.put(view, view.getVisibility());
        }
    }

    private void write(View view, int visibility) {
        internalWrite = true;
        try { view.setVisibility(visibility); } finally { internalWrite = false; }
    }

    private boolean owns(View view) { return view == root || isDescendant(view, root); }

    private static boolean isSystemIcon(View view) {
        Class<?> type = view.getClass();
        String name = type.getName();
        return name.startsWith("com.android.systemui.")
                && ("StatusBarIconView".equals(type.getSimpleName()) || name.endsWith(".StatusBarIconView"));
    }

    private static ViewGroup findNotificationIcons(View prompt) {
        if (prompt == null) return null;
        View root = prompt.getRootView();
        if (!(root instanceof ViewGroup)) root = prompt;
        int id = root.getResources().getIdentifier("notificationIcons", "id", "com.android.systemui");
        View found = id == 0 ? null : root.findViewById(id);
        return found instanceof ViewGroup ? (ViewGroup) found : null;
    }

    private static boolean isDescendant(View view, ViewGroup ancestor) {
        View current = view;
        while (current != null) {
            if (current == ancestor) return true;
            Object parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private static String parentName(View view) {
        Object parent = view == null ? null : view.getParent();
        return parent == null ? "null" : parent.getClass().getName();
    }

    private static int countChildren(ViewGroup group) {
        int count = 0;
        for (int i = 0; i < group.getChildCount(); i++) if (!isSystemIcon(group.getChildAt(i))) count++;
        return count;
    }

    private void clearRootListener() {
        if (root != null && layoutListener != null) root.removeOnLayoutChangeListener(layoutListener);
        layoutListener = null;
    }
}
