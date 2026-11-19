/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.XC_MethodHook;

/** 仅接入已核对的 OS3 活动通知事件。接口依据见 docs/capture-export-spec.md。 */
final class IslandCaptureOs3Source {
    private static final String BASE = "com.android.systemui.statusbar.notification.collection.";
    private static final String VERSION = "16.03.251211.r";
    private static final long VERSION_CODE = 202501210;

    static IslandCaptureOs3Source install(Context context, ClassLoader loader, HookInstaller hooks, IslandCaptureSystemUi capture) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo("com.android.systemui", 0);
            if (VERSION.equals(info.versionName) && info.getLongVersionCode() == VERSION_CODE) {
                IslandCaptureOs3Source source = new IslandCaptureOs3Source(loader, capture);
                source.hook(hooks);
                return source;
            }
        } catch (ReflectiveOperationException | android.content.pm.PackageManager.NameNotFoundException
                | RuntimeException | LinkageError failure) {
            // 版本或反射合同无法核实时，不发布来源就绪状态。
        }
        capture.sourceUnsupported();
        return null;
    }

    private final IslandCaptureSystemUi capture;
    private final Class<?> listenerType;
    private final Field attached;
    private final Field listeners;
    private final Field sbn;
    private final Method dispatch;
    private final Method addListener;
    private final Method getEntry;
    private final Method canceled;
    private final Method added;
    private final Method updated;
    // 只在 SystemUI 主线程访问。出现第二个已挂接的集合时，本进程保持关闭。
    private Object owner;
    private boolean disabled;

    private IslandCaptureOs3Source(ClassLoader loader, IslandCaptureSystemUi capture)
            throws ReflectiveOperationException {
        this.capture = capture;
        Class<?> collection = Class.forName(BASE + "NotifCollection", false, loader);
        Class<?> entry = Class.forName(BASE + "NotificationEntry", false, loader);
        Class<?> listenerSet = Class.forName("com.android.systemui.util.NamedListenerSet", false, loader);
        listenerType = Class.forName(BASE + "notifcollection.NotifCollectionListener", false, loader);
        Class<?> updateSource = Class.forName(BASE + "notifcollection.UpdateSource", false, loader);
        attached = field(collection, "mAttached", boolean.class);
        listeners = field(collection, "mNotifCollectionListeners", listenerSet);
        sbn = entry.getDeclaredField("mSbn");
        if (!StatusBarNotification.class.isAssignableFrom(sbn.getType())) throw new NoSuchFieldException("mSbn");
        sbn.setAccessible(true);
        dispatch = method(collection, "dispatchEvents", void.class);
        addListener = method(listenerSet, "addIfAbsent", boolean.class, Object.class);
        getEntry = method(collection, "getEntry", entry, String.class);
        canceled = method(entry, "isCanceled", boolean.class);
        added = method(listenerType, "onEntryAdded", void.class, entry);
        updated = method(listenerType, "onEntryUpdated", void.class, entry, updateSource);
    }

    private void hook(HookInstaller hooks) {
        hooks.method(dispatch, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                register(param.thisObject);
            }
        });
    }

    private void register(Object collection) {
        if (Looper.myLooper() != Looper.getMainLooper() || disabled || owner == collection) return;
        try {
            if (!attached.getBoolean(collection)) return;
            if (owner != null) {
                disable();
                return;
            }
            Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType},
                    (proxy, event, args) -> {
                        if (event.getDeclaringClass() == Object.class) {
                            switch (event.getName()) {
                                case "hashCode": return System.identityHashCode(proxy);
                                case "equals": return proxy == args[0];
                                case "toString": return "FocusRestoreCapture";
                                default: return null;
                            }
                        }
                        if (!disabled && owner == collection && (added.equals(event) || updated.equals(event))) {
                            observePost(args[0]);
                        }
                        return null;
                    });
            if (!Boolean.TRUE.equals(addListener.invoke(listeners.get(collection), listener))) {
                disable();
                return;
            }
            owner = collection;
            capture.pipelineRegistered(true);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            disable();
        }
    }

    // 两个 ROM 回调的先后不固定。只在同一代次按对象引用配对，不强持有通知或字段值。
    private final IslandCaptureAdmission<Object, StatusBarNotification> admission = new IslandCaptureAdmission<>();

    private void observePost(Object entry) {
        IslandCaptureRuntime.Grant grant = capture.activeGrant();
        if (grant == null || disabled || Looper.myLooper() != Looper.getMainLooper()) return;
        try {
            StatusBarNotification source = (StatusBarNotification) readField(sbn, entry);
            if (source != null && admission.post(grant.generation, source, entry)) {
                submit(grant, source, entry, null);
            }
        } catch (RuntimeException | LinkageError failure) {
            // 现场来源无法确认，跳过本次事件。
        }
    }

    void onFocusDecision(String key, String pkg, android.app.Notification notification,
            boolean accepted, String displayText) {
        IslandCaptureRuntime.Grant grant = capture.activeGrant();
        if (grant == null || disabled || owner == null || Looper.myLooper() != Looper.getMainLooper()
                || key == null || pkg == null || notification == null) return;
        try {
            // FocusUtils 接受 ExpandedNotification。用已核对的 getNotification() 对象确认来源，
            // 再以活动集合里的真实 SBN 配对，不依赖包装类型与 SBN 的继承或私有解包字段。
            Object entry = invoke(getEntry, owner, key);
            if (entry == null) return;
            StatusBarNotification source = (StatusBarNotification) readField(sbn, entry);
            if (source == null || !key.equals(source.getKey()) || !pkg.equals(source.getPackageName())
                    || source.getNotification() != notification) return;
            if (admission.decide(grant.generation, source, accepted) == entry) {
                submit(grant, source, entry, displayText);
            }
        } catch (RuntimeException | LinkageError failure) {
            // 活动集合或通知身份无法证明，放弃本次焦点判定。
        }
    }

    private void submit(IslandCaptureRuntime.Grant grant, StatusBarNotification source, Object entry, String displayText) {
        Object collection = owner;
        capture.onOs3Entry(grant, entry, () -> {
            Object current = readField(sbn, entry);
            return current == source ? source : null;
        }, key -> invoke(getEntry, collection, key),
                () -> attachedTo(collection) && Boolean.FALSE.equals(invoke(canceled, entry)), displayText);
    }

    private boolean attachedTo(Object collection) {
        return !disabled && owner == collection && Boolean.TRUE.equals(readField(attached, collection));
    }

    void disable() {
        disabled = true;
        owner = null;
        capture.sourceUnsupported();
    }

    private static Object readField(Field field, Object receiver) {
        try {
            return field.get(receiver);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Object invoke(Method method, Object receiver, Object... args) {
        try {
            return method.invoke(receiver, args);
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Field field(Class<?> owner, String name, Class<?> type) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != type) throw new NoSuchFieldException(name);
        field.setAccessible(true);
        return field;
    }

    private static Method method(Class<?> owner, String name, Class<?> returnType, Class<?>... args)
            throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, args);
        if (method.getReturnType() != returnType) throw new NoSuchMethodException(name);
        method.setAccessible(true);
        return method;
    }
}
