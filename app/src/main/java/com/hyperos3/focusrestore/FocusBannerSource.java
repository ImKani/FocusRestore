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
}
