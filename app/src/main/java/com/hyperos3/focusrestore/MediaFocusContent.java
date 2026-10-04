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

/** Small, side-effect-free adapter for platform MediaStyle notifications. */
final class MediaFocusContent {
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

    static MediaController controller(android.content.Context context, MediaSession.Token token) {
        if (context == null || token == null) return null;
        try {
            return new MediaController(context, token);
        } catch (Throwable ignored) {
            return null;
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