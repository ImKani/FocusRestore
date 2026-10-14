/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.app.Notification;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.text.TextUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small, side-effect-free adapter for platform MediaStyle notifications. */
final class MediaFocusContent {
    private static final String TAG = "HyperOS3FocusRestore";
    /** 会话数很少；上限只用于防止异常情况下无界增长。 */
    private static final int MAX_CONTROLLERS = 16;
    private static final Map<String, MediaController> CONTROLLERS =
            new LinkedHashMap<String, MediaController>(8, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, MediaController> eldest) {
                    return size() > MAX_CONTROLLERS;
                }
            };

    final String text;
    final MediaSession.Token token;
    final boolean playing;

    private MediaFocusContent(String text, MediaSession.Token token, boolean playing) {
        this.text = text;
        this.token = token;
        this.playing = playing;
    }

    static MediaFocusContent from(Notification notification) {
        if (notification == null || notification.extras == null) return null;
        Bundle extras = notification.extras;
        Object rawToken = extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
        MediaSession.Token token = rawToken instanceof MediaSession.Token
                ? (MediaSession.Token) rawToken : null;
        boolean transport = Notification.CATEGORY_TRANSPORT.equals(notification.category);
        int[] compact = extras.getIntArray(Notification.EXTRA_COMPACT_ACTIONS);
        boolean actions = notification.actions != null && notification.actions.length > 0;
        if (token == null && !(transport && (actions || compact != null))) return null;

        String title = charSequence(extras.getCharSequence(Notification.EXTRA_TITLE));
        String text = charSequence(extras.getCharSequence(Notification.EXTRA_TEXT));
        String subText = charSequence(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String summary = join(title, subText);
        summary = join(summary, text);
        if (TextUtils.isEmpty(summary)) summary = charSequence(notification.tickerText);
        if (TextUtils.isEmpty(summary)) return null;
        return new MediaFocusContent(summary, token, false);
    }

    MediaFocusContent withPlaybackState(MediaController controller) {
        if (controller == null) return this;
        PlaybackState state = controller.getPlaybackState();
        if (state == null) return this;
        boolean nowPlaying = state.getState() == PlaybackState.STATE_PLAYING;
        return new MediaFocusContent(text + (nowPlaying ? "  ▶" : "  ❚❚"), token, nowPlaying);
    }

    /**
     * 每个媒体会话复用一个控制器。
     *
     * <p>{@code MediaController} 的构造会向会话注册自己的回调，而
     * {@code MediaController#release()} 在 Android 15 上是 @hide，反射也会被隐藏 API 策略
     * 拒绝（真机 0.37.1 日志：{@code NoSuchMethodException: ...MediaController.release []}），
     * 无法在本模块内释放。因此不再每次判读新建：同一 token 复用同一实例，缓存按会话数封顶，
     * 既避免每次焦点判读累积一个对象，也不依赖任何私有接口。
     */
    static MediaController controller(android.content.Context context, MediaSession.Token token) {
        if (context == null || token == null) return null;
        String tokenKey = String.valueOf(System.identityHashCode(token));
        synchronized (CONTROLLERS) {
            MediaController cached = CONTROLLERS.get(tokenKey);
            if (cached != null) return cached;
            MediaController created;
            try {
                created = new MediaController(context, token);
            } catch (Throwable failure) {
                android.util.Log.w(TAG, "media controller unavailable token=" + tokenKey, failure);
                return null;
            }
            CONTROLLERS.put(tokenKey, created);
            return created;
        }
    }

    private static String charSequence(CharSequence value) {
        return value == null ? null : value.toString().trim();
    }

    private static String join(String left, String right) {
        if (TextUtils.isEmpty(left)) return right;
        if (TextUtils.isEmpty(right) || left.equals(right)) return left;
        return left + " · " + right;
    }
}