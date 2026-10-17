/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.text.TextUtils;

import java.util.LinkedHashMap;
import java.util.List;
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

    /** 完整正文（含播放状态尾巴），保持 0.38.0 之前的语义不变。 */
    final String text;
    final String title;
    final String subText;
    final String textBody;
    final String packageName;
    final MediaSession.Token token;
    final boolean playing;
    final boolean playStateKnown;

    private MediaFocusContent(String text, String title, String subText, String textBody,
                              String packageName, MediaSession.Token token, boolean playing,
                              boolean playStateKnown) {
        this.text = text;
        this.title = title;
        this.subText = subText;
        this.textBody = textBody;
        this.packageName = packageName;
        this.token = token;
        this.playing = playing;
        this.playStateKnown = playStateKnown;
    }

    static MediaFocusContent from(Notification notification, String packageName) {
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
        String textBody = charSequence(extras.getCharSequence(Notification.EXTRA_TEXT));
        String subText = charSequence(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String summary = join(join(title, subText), textBody);
        if (TextUtils.isEmpty(summary)) summary = charSequence(notification.tickerText);
        if (TextUtils.isEmpty(summary)) return null;
        return new MediaFocusContent(summary, title, subText, textBody, packageName, token, false, false);
    }

    MediaFocusContent withPlaybackState(MediaController controller) {
        if (controller == null) return this;
        PlaybackState state = controller.getPlaybackState();
        if (state == null) return this;
        boolean nowPlaying = state.getState() == PlaybackState.STATE_PLAYING;
        return new MediaFocusContent(text + (nowPlaying ? "  ▶" : "  ❚❚"),
                title, subText, textBody, packageName, token, nowPlaying, true);
    }

    /**
     * 简短显示正文：按已选字段顺序拼接，播放状态只有在控制器读到播放态且被选中时才出现；
     * 应用名按包名解析标签，解析失败退回包名。未开启简短显示时直接返回完整正文，与旧行为一致。
     */
    String assembleText(boolean brief, List<Integer> fields, Context context) {
        if (!brief) return text;
        StringBuilder result = new StringBuilder();
        for (int field : MediaBriefFields.normalize(fields)) {
            String value = fieldValue(field, context);
            if (value == null || value.length() == 0) continue;
            if (result.length() > 0) result.append(" · ");
            result.append(value);
        }
        return result.toString();
    }

    private String fieldValue(int field, Context context) {
        switch (field) {
            case MediaBriefFields.TITLE: return title;
            case MediaBriefFields.SUBTEXT: return subText;
            case MediaBriefFields.TEXT: return textBody;
            case MediaBriefFields.APP_NAME: return appLabel(context);
            case MediaBriefFields.PLAY_STATE:
                return playStateKnown ? (playing ? "▶" : "❚❚") : null;
            default: return null;
        }
    }

    private String appLabel(Context context) {
        if (TextUtils.isEmpty(packageName)) return null;
        if (context == null) return packageName;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(packageName, 0);
            CharSequence label = context.getPackageManager().getApplicationLabel(info);
            return label == null ? packageName : label.toString().trim();
        } catch (Throwable failure) {
            return packageName;
        }
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
    static MediaController controller(Context context, MediaSession.Token token) {
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
