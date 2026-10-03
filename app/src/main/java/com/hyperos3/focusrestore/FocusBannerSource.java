package com.hyperos3.focusrestore;

import android.content.Context;
import android.service.notification.StatusBarNotification;
import android.view.View;

/**
 * Content source for the independent focus banner.
 *
 * <p>Two implementations exist, because the ROM renders focus notifications by two unrelated paths:
 *
 * <ul>
 *   <li>{@code NativeFocusTemplateRenderer.Render} mirrors {@code
 *       TemplateFactoryV3.createStandardTemplateView} for notifications that carry {@code
 *       miui.focus.param} (the ROM's {@code focusType=PARAMS}).</li>
 *   <li>{@link RemoteViewsFocusBannerSource} inflates the notification's own {@code miui.focus.rv}
 *       for notifications the ROM renders through {@code
 *       FocusNotifPreHandler.buildNoParamsFocusNotification} ({@code focusType=CUSTOM}), which never
 *       produces a standard template.</li>
 * </ul>
 *
 * <p>The host only depends on this interface, so the banner lifecycle (display, keyguard, rotation,
 * touch-outside, timeouts) stays in one place.
 */
interface FocusBannerSource {
    /** The independent, unattached root view this source owns. */
    View view();

    /** Context the root view was built with; used for validation only. */
    Context context();

    /** Preferred banner width in px; the host clamps it to the safe display area. */
    int widthPx();

    /** Minimum height in px; zero means the content decides. */
    int minHeightPx();

    /** Diagnostic label describing where this content came from. */
    String source();

    /** Diagnostic summary of the current layout state. */
    String layoutSummary();

    /**
     * Called once the root view is attached to the banner window. The native template uses this to
     * bind its timer session; a source without one simply does nothing.
     */
    void onAttached();

    /** Whether this source still describes the given notification and has not been closed. */
    boolean isCurrent(StatusBarNotification actual);

    /** Releases everything this source owns. Must be safe to call more than once. */
    void close();

    /**
     * 正文点击动作，供没有通知条目的内容源实现。
     *
     * <p>焦点横幅的正文点击走通知点击通路（打开通知条目）。设备通知没有条目，改为由内容源自己
     * 执行动作：ROM 的强提示点击也是发送模型里的 {@code PendingIntent}。
     */
    interface BodyAction {
        /** 返回 true 表示点击已处理，宿主随后关闭横幅。 */
        boolean onBodyTap();
    }

    /**
     * 要求贴状态栏行摆放的内容源。
     *
     * <p>默认摆放让开状态栏高度、落在状态栏下方的横幅位，并在安全区内居中；设备通知的内容原本
     * 就是 ROM 状态栏引导内容（{@code StrongToastModel.statusBarGuideModel}），要和状态栏同一行、
     * 并紧跟在状态栏时间右侧显示，因此实现本接口，由宿主改纵向起点与横向起点。
     */
    interface StatusBarRow {
        /** 横幅左边缘的屏幕坐标 px；返回负数表示由宿主在安全区内居中。 */
        int preferredLeftPx();
    }
}
