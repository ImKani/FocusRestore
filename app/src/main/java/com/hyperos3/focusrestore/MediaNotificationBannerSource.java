/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */

package com.hyperos3.focusrestore;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Independently renders the media notification banner.
 *
 * <p>Source: Android platform {@code Notification} RemoteViews contract and static analysis of
 * HyperOS 3 SystemUI APK {@code os3系统界面_16.03.251211.r.apk}, source revision
 * {@code go/retraceme 517ab4bb9ddba253d1b43e57286e9c4432b2e131392282f9817862fc72ce5979}
 * (SystemUI media notification row/heads-up path; private protocol details are not confirmed).
 * No external implementation is copied: FocusRestore independently creates an unattached copy,
 * selects heads-up/big/normal fallbacks, and owns its lifecycle.
 *
 * <p>HyperOS media notifications such as {@code com.miui.player} carry
 * {@code miui.focus.param.media} and keep their {@code Notification} RemoteViews unset; the ROM
 * renders them from the media-session {@code MediaData} instead. For those notifications this
 * source falls back to a banner built from the captured SystemUI {@code MediaData}: artwork,
 * song, artist and the ROM's own transport action Runnables, so the controls behave exactly like
 * the native media notification controls.
 */
final class MediaNotificationBannerSource implements FocusBannerSource {
    private static final int MAX_ACTIONS = 5;

    /** key -> SystemUI MediaData, published by the SystemUI media pipeline hook. */
    private static final ConcurrentHashMap<String, WeakReference<Object>> mediaDataByKey =
            new ConcurrentHashMap<>();

    private final View root;
    private final Context context;
    private final StatusBarNotification sbn;
    private final Object mediaData;
    private final int widthPx;
    private final String source;
    private MediaController mediaController;
    private SeekBar seekBar;
    private TextView currentTime;
    private TextView totalTime;
    private LinearLayout actionRow;
    private final List<ImageButton> actionButtons = new ArrayList<>();
    private TextView titleView;
    private TextView artistView;
    private ImageView artView;
    private ImageView badgeView;
    private ImageView backdropView;
    private Handler handler;
    private Context systemContext;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (closed || seekBar == null || handler == null) {
                tickerRunning = false;
                return;
            }
            updateProgress();
            handler.postDelayed(this, PROGRESS_TICK_MS);
        }
    };
    private boolean tickerRunning;
    private boolean dragging;
    /** Pending seek target in ms; kept while the player has not reached it yet. */
    private long pendingSeekTargetMs = -1L;
    private long pendingSeekSince;
    private Drawable normalThumb;
    private LoadingThumbDrawable loadingThumb;
    private boolean closed;

    private static final long PROGRESS_TICK_MS = 500L;
    /** Button icon box for the standard transport actions (previous / play-pause / next). */
    private static final int ACTION_ICON_BOX_DP = 22;
    /**
     * Fraction of the icon box used by app-provided action icons. MIUI's own transport icons are
     * 40dp and carry their own padding, while app icons are compact full-bleed glyphs (24dp in the
     * observed ROMs), so the app glyphs are drawn into an inset rect to match the transport look.
     */
    private static final float CUSTOM_ICON_VISIBLE_RATIO = 0.58f;
    /** MIUI's own media action icons are 40dp; anything smaller comes from the app itself. */
    private static final int STANDARD_TRANSPORT_ICON_DP = 40;
    /** How close the real position must get to the seek target before clearing the loading state. */
    private static final long SEEK_SETTLE_MS = 1_500L;
    /** Safety bound so a stalled player cannot keep the loading thumb forever. */
    private static final long SEEK_LOADING_TIMEOUT_MS = 20_000L;

    private MediaNotificationBannerSource(View root, Context context, StatusBarNotification sbn,
                                          Object mediaData, int widthPx, String source) {
        this.root = root;
        this.context = context;
        this.sbn = sbn;
        this.mediaData = mediaData;
        this.widthPx = widthPx;
        this.source = source;
    }

    /** Opens the SystemUI media output (cast) picker for a media notification. */
    interface SeamlessOpener {
        boolean open(String key, Object mediaData, View anchor);
    }

    private static volatile SeamlessOpener seamlessOpener;

    /** Installs the cast-picker opener owned by the hook. */
    static void setSeamlessOpener(SeamlessOpener opener) {
        seamlessOpener = opener;
    }

    /** The latest ROM MediaData captured for one key, or null when none is available. */
    static Object mediaDataFor(String key) {
        if (TextUtils.isEmpty(key)) return null;
        WeakReference<Object> reference = mediaDataByKey.get(key);
        return reference == null ? null : reference.get();
    }

    /** Publishes the ROM MediaData object for one media notification key. */
    static void attachMediaData(String key, Object mediaData) {
        if (TextUtils.isEmpty(key) || mediaData == null) return;
        mediaDataByKey.put(key, new WeakReference<>(mediaData));
    }

    /** Drops the ROM MediaData object for one media notification key. */
    static void detachMediaData(String key) {
        if (TextUtils.isEmpty(key)) return;
        mediaDataByKey.remove(key);
    }

    /** The media session click intent for one key, used when the notification row cannot click. */
    static PendingIntent clickIntentFor(String key) {
        if (TextUtils.isEmpty(key)) return null;
        WeakReference<Object> reference = mediaDataByKey.get(key);
        Object mediaData = reference == null ? null : reference.get();
        Object intent = field(mediaData, "clickIntent");
        return intent instanceof PendingIntent ? (PendingIntent) intent : null;
    }

    static boolean isAvailable(StatusBarNotification sbn) {
        if (sbn == null) return false;
        Notification notification = sbn.getNotification();
        boolean remoteViews = notification != null && (notification.headsUpContentView != null
                || notification.bigContentView != null || notification.contentView != null);
        if (remoteViews) return true;
        WeakReference<Object> reference = mediaDataByKey.get(sbn.getKey());
        return reference != null && reference.get() != null;
    }

    static FocusBannerSource create(Context systemUiContext, StatusBarNotification sbn)
            throws Exception {
        if (systemUiContext == null || sbn == null || sbn.getNotification() == null) {
            throw new IllegalStateException("no media notification to inflate");
        }
        Notification notification = sbn.getNotification();
        RemoteViews views = notification.headsUpContentView;
        String source = "notification.headsUpContentView";
        if (views == null) {
            views = notification.bigContentView;
            source = "notification.bigContentView";
        }
        if (views == null) {
            views = notification.contentView;
            source = "notification.contentView";
        }
        if (views == null) return createFromMediaData(systemUiContext, sbn);

        Context packageContext = packageContextFor(systemUiContext, sbn);
        View content = views.apply(packageContext, null);
        if (content == null || content.getParent() != null) {
            throw new IllegalStateException("media RemoteViews returned an attached or empty view");
        }
        FrameLayout host = new FrameLayout(packageContext);
        host.setClipChildren(false);
        host.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int width = Math.max(1, systemUiContext.getResources().getDisplayMetrics().widthPixels);
        return new MediaNotificationBannerSource(host, packageContext, sbn, null, width, source);
    }

    /**
     * Builds the banner from the ROM MediaData when the notification carries no RemoteViews.
     * Mirrors the shade media notification layout {@code miui_media_session.xml} and its
     * {@code miui_media_session_normal} ConstraintSet with the ROM's real resource values:
     * blurred artwork backdrop, 52.5dp artwork with a 24dp app badge, 18sp bold title,
     * 12sp bold artist, up to five 60x50dp action buttons, a 38dp seek bar and 12sp time labels.
     * The transport Runnables are the same objects the native media controls run.
     */
    private static FocusBannerSource createFromMediaData(Context systemUiContext,
                                                         StatusBarNotification sbn)
            throws Exception {
        Object mediaData = null;
        WeakReference<Object> reference = mediaDataByKey.get(sbn.getKey());
        if (reference != null) mediaData = reference.get();
        if (mediaData == null) throw new IllegalStateException("media session data unavailable");

        Context packageContext = packageContextFor(systemUiContext, sbn);
        boolean night = (packageContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int margin = dp(packageContext, 16);

        Icon artworkIcon = (Icon) field(mediaData, "artwork");
        if (artworkIcon == null) artworkIcon = (Icon) field(mediaData, "appIcon");
        Drawable artwork = null;
        if (artworkIcon != null) {
            try {
                artwork = artworkIcon.loadDrawable(packageContext);
            } catch (Throwable ignored) {
                artwork = null;
            }
        }

        MediaBackdropFrame root = new MediaBackdropFrame(packageContext);
        root.setClipToOutline(true);
        root.setOutlineProvider(roundedOutline(packageContext, 28));
        Bitmap artworkBitmap = toBitmap(artwork, systemUiContext);
        ImageView backdrop = null;
        if (artworkBitmap != null) {
            // Native media header background: the album artwork, blurred full-bleed.
            backdrop = new ImageView(packageContext);
            backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
            backdrop.setImageBitmap(artworkBitmap);
            if (Build.VERSION.SDK_INT >= 31) {
                backdrop.setRenderEffect(android.graphics.RenderEffect.createBlurEffect(
                        60f, 60f, android.graphics.Shader.TileMode.CLAMP));
            }
            root.addView(backdrop, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            View scrim = new View(packageContext);
            scrim.setBackgroundColor(night ? 0xB3000000 : 0x80FFFFFF);
            root.addView(scrim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            GradientDrawable background = new GradientDrawable();
            background.setColor(systemColor(systemUiContext, "media_notification_bg_color",
                    night ? 0xF21C1C1E : 0xF2FFFFFF));
            background.setCornerRadius(dp(packageContext, 28));
            root.setBackground(background);
        }

        LinearLayout column = new LinearLayout(packageContext);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(margin, margin, margin, margin);
        root.addView(column, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.setContentChild(column);

        // Top row: artwork with app badge + (title/artist column) + seamless icon.
        LinearLayout topRow = new LinearLayout(packageContext);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(topRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int artworkSize = Math.round(52.5f * packageContext.getResources()
                .getDisplayMetrics().density);
        FrameLayout coverHost = new FrameLayout(packageContext);
        ImageView art = new ImageView(packageContext);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (artwork != null) {
            art.setImageDrawable(artwork);
            art.setClipToOutline(true);
            art.setOutlineProvider(roundedOutline(packageContext, 14));
        }
        coverHost.addView(art, new FrameLayout.LayoutParams(artworkSize, artworkSize));
        Icon appIcon = (Icon) field(mediaData, "appIcon");
        Drawable appDrawable = null;
        if (appIcon != null) {
            try {
                appDrawable = appIcon.loadDrawable(packageContext);
            } catch (Throwable ignored) {
                appDrawable = null;
            }
        }
        ImageView badge = null;
        if (appDrawable != null) {
            badge = new ImageView(packageContext);
            badge.setImageDrawable(appDrawable);
            int badgeSize = dp(packageContext, 16);
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    badgeSize, badgeSize, Gravity.BOTTOM | Gravity.END);
            int badgeMargin = dp(packageContext, 2);
            badgeParams.setMargins(0, 0, badgeMargin, badgeMargin);
            coverHost.addView(badge, badgeParams);
        }
        topRow.addView(coverHost, new LinearLayout.LayoutParams(artworkSize, artworkSize));

        int primary = systemColor(systemUiContext, "media_primary_text",
                night ? Color.WHITE : Color.BLACK);
        int secondary = systemColor(systemUiContext, "media_secondary_text",
                night ? 0xB3FFFFFF : 0x80000000);
        LinearLayout textColumn = new LinearLayout(packageContext);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);
        CharSequence song = (CharSequence) field(mediaData, "song");
        CharSequence artist = (CharSequence) field(mediaData, "artist");
        TextView songView = null;
        TextView artistView = null;
        if (song != null || artist != null) {
            songView = new TextView(packageContext);
            songView.setText(song);
            songView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            songView.setTextColor(primary);
            songView.setTypeface(miuiFont(packageContext, "mipro-demibold"), Typeface.BOLD);
            songView.setSingleLine(true);
            songView.setEllipsize(TextUtils.TruncateAt.END);
            textColumn.addView(songView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            artistView = new TextView(packageContext);
            artistView.setText(artist);
            artistView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            artistView.setTextColor(secondary);
            artistView.setTypeface(miuiFont(packageContext, "MiSans"), Typeface.BOLD);
            artistView.setSingleLine(true);
            artistView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams artistParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            artistParams.topMargin = dp(packageContext, 4);
            textColumn.addView(artistView, artistParams);
        }
        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        columnParams.leftMargin = dp(packageContext, 12);
        topRow.addView(textColumn, columnParams);

        Drawable seamless = systemDrawable(systemUiContext, "ic_media_seamless");
        final Object bannerMediaData = mediaData;
        final String bannerKey = sbn.getKey();
        if (seamless != null) {
            ImageView seamlessIcon = new ImageView(packageContext);
            seamlessIcon.setImageDrawable(seamless);
            TypedValue tintValue = new TypedValue();
            int tint = night ? 0xFFFFFFFF : 0xFF000000;
            try {
                if (systemUiContext.getTheme().resolveAttribute(
                        android.R.attr.colorControlNormal, tintValue, true)) {
                    tint = tintValue.data;
                }
            } catch (Throwable ignored) {
                // keep fallback tint
            }
            seamlessIcon.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
            int iconSize = dp(packageContext, 28);
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(
                    iconSize, iconSize);
            iconParams.leftMargin = dp(packageContext, 6);
            seamlessIcon.setContentDescription("media output");
            seamlessIcon.setClickable(true);
            seamlessIcon.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    SeamlessOpener opener = seamlessOpener;
                    if (opener == null) return;
                    try {
                        Object latest = mediaDataFor(bannerKey);
                        opener.open(bannerKey, latest != null ? latest : bannerMediaData, view);
                    } catch (Throwable ignored) {
                        // the cast picker is best-effort
                    }
                }
            });
            topRow.addView(seamlessIcon, iconParams);
        }

        // Action row: the native media header binds its buttons in slot order
        // custom0 / previous / play-pause / next / custom1 (MediaButton.getActionById).
        Object semantic = field(mediaData, "semanticActions");
        List<?> actions = (List<?>) field(mediaData, "actions");
        List<?> compact = (List<?>) field(mediaData, "actionsToShowInCompact");
        List<Object> ordered = semanticActionList(mediaData);
        String firstActionClass = null;
        boolean firstHasIcon = false;
        boolean firstHasRunnable = false;
        if (!ordered.isEmpty() && ordered.get(0) != null) {
            Object first = ordered.get(0);
            firstActionClass = first.getClass().getName();
            firstHasIcon = field(first, "icon") != null;
            firstHasRunnable = field(first, "action") != null;
        }
        LinearLayout actionRowRef = null;
        List<ImageButton> actionButtonsRef = null;
        if (!ordered.isEmpty()) {
            LinearLayout actionRow = new LinearLayout(packageContext);
            actionRow.setOrientation(LinearLayout.HORIZONTAL);
            actionRow.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(packageContext, 50));
            rowParams.topMargin = dp(packageContext, 15);
            column.addView(actionRow, rowParams);
            int count = 0;
            int actionWidth = dp(packageContext, 60);
            int paddingHorizontal = dp(packageContext, 10);
            int paddingVertical = dp(packageContext, 5);
            List<ImageButton> buttons = new ArrayList<>();
            for (Object action : ordered) {
                if (action == null || count >= MAX_ACTIONS) continue;
                Drawable icon = (Drawable) field(action, "icon");
                final Runnable runnable = (Runnable) field(action, "action");
                if (runnable == null) continue;
                if (count > 0) {
                    // spread_inside: equal flexible gap between fixed-width buttons.
                    View spacer = new View(packageContext);
                    actionRow.addView(spacer, new LinearLayout.LayoutParams(
                            0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
                }
                ImageButton button = new ImageButton(packageContext);
                int boxPx = dp(packageContext, ACTION_ICON_BOX_DP);
                boolean appProvided = isAppProvidedIcon(icon, packageContext);
                Drawable normalized = normalizeIcon(icon, boxPx, appProvided);
                button.setImageDrawable(normalized);
                android.util.Log.i("HyperOS3FocusRestore",
                        "media action icon index=" + count
                                + " appProvided=" + appProvided
                                + " intrinsic=" + (icon == null ? "null"
                                        : icon.getIntrinsicWidth() + "x" + icon.getIntrinsicHeight())
                                + " normalized=" + (normalized == null ? "null"
                                        : normalized.getIntrinsicWidth() + "x"
                                                + normalized.getIntrinsicHeight())
                                + " boxPx=" + boxPx);
                button.setBackgroundColor(Color.TRANSPARENT);
                button.setScaleType(ImageView.ScaleType.FIT_CENTER);
                button.setPadding(paddingHorizontal, paddingVertical,
                        paddingHorizontal, paddingVertical);
                button.setContentDescription((CharSequence) field(action, "contentDescription"));
                button.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        try { runnable.run(); }
                        catch (Throwable throwable) { /* native transport runnable failure */ }
                    }
                });
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        actionWidth, ViewGroup.LayoutParams.MATCH_PARENT);
                actionRow.addView(button, params);
                buttons.add(button);
                count++;
            }
            actionRowRef = actionRow;
            actionButtonsRef = buttons;
        }

        // Progress row: current time + seek bar + total time, like the native media header.
        int timeColor = systemColor(systemUiContext,
                night ? "media_duration_time_font_dark_color" : "media_duration_time_font_color",
                night ? 0x99FFFFFF : 0x66000000);
        LinearLayout progressRow = new LinearLayout(packageContext);
        progressRow.setOrientation(LinearLayout.HORIZONTAL);
        progressRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams progressRowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressRowParams.topMargin = dp(packageContext, 6);
        column.addView(progressRow, progressRowParams);

        int timeWidth = dp(packageContext, 52);
        TextView current = new TextView(packageContext);
        current.setText(formatTime(0L));
        current.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        current.setTextColor(timeColor);
        current.setMinEms(3);
        current.setSingleLine(true);
        // The native media header centers both time labels inside their fixed-width boxes, which
        // keeps the gap to the seek bar symmetric on both sides.
        current.setGravity(Gravity.CENTER);
        progressRow.addView(current, new LinearLayout.LayoutParams(
                timeWidth, ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar seek = new SeekBar(packageContext);
        seek.setPadding(0, dp(packageContext, 16), 0, dp(packageContext, 16));
        Drawable progressDrawable = systemDrawable(systemUiContext, "media_notification_progress");
        if (progressDrawable != null) seek.setProgressDrawable(progressDrawable);
        Drawable thumb = systemDrawable(systemUiContext, "media_notification_seek_thumb");
        if (thumb != null) seek.setThumb(thumb);
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                0, dp(packageContext, 38), 1f);
        seekParams.leftMargin = dp(packageContext, 8);
        seekParams.rightMargin = dp(packageContext, 8);
        progressRow.addView(seek, seekParams);

        TextView total = new TextView(packageContext);
        total.setText(formatTime(0L));
        total.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        total.setTextColor(timeColor);
        total.setMinEms(3);
        total.setSingleLine(true);
        total.setGravity(Gravity.CENTER);
        progressRow.addView(total, new LinearLayout.LayoutParams(
                timeWidth, ViewGroup.LayoutParams.WRAP_CONTENT));

        MediaController controller = null;
        Object token = field(mediaData, "token");
        if (token instanceof android.media.session.MediaSession.Token) {
            try {
                controller = new MediaController(systemUiContext,
                        (android.media.session.MediaSession.Token) token);
            } catch (Throwable ignored) {
                controller = null;
            }
        }
        final MediaController mediaController = controller;

        int width = Math.max(1, systemUiContext.getResources().getDisplayMetrics().widthPixels);
        String diagnostics = ";actions=" + (actions == null ? -1 : actions.size())
                + ";compact=" + (compact == null ? -1 : compact.size())
                + ";semantic=" + (semantic == null ? "none" : "present")
                + ";ordered=" + ordered.size()
                + ";firstClass=" + (firstActionClass == null ? "none" : firstActionClass)
                + ";firstIcon=" + firstHasIcon
                + ";firstRunnable=" + firstHasRunnable
                + ";token=" + (controller != null);
        try {
            android.util.Log.i("HyperOS3FocusRestore",
                    "media banner data key=" + sbn.getKey() + diagnostics);
        } catch (Throwable ignored) {
            // logging is best-effort
        }
        final MediaNotificationBannerSource source = new MediaNotificationBannerSource(
                root, packageContext, sbn, mediaData, width, "media-session-data" + diagnostics);
        source.seekBar = seek;
        source.currentTime = current;
        source.totalTime = total;
        source.mediaController = mediaController;
        source.handler = new Handler(Looper.getMainLooper());
        if (actionRowRef != null) source.actionRow = actionRowRef;
        if (actionButtonsRef != null) source.actionButtons.addAll(actionButtonsRef);
        source.titleView = songView;
        source.artistView = artistView;
        source.artView = art;
        source.badgeView = badge;
        source.backdropView = backdrop;
        source.systemContext = systemUiContext;
        source.normalThumb = seek.getThumb();
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && source.currentTime != null) {
                    source.currentTime.setText(formatTime(progress));
                }
            }

            @Override public void onStartTrackingTouch(SeekBar bar) {
                source.dragging = true;
                source.stopTicker();
                android.util.Log.i("HyperOS3FocusRestore",
                        "media seek drag start progress=" + bar.getProgress());
            }

            @Override public void onStopTrackingTouch(SeekBar bar) {
                source.dragging = false;
                int progress = bar.getProgress();
                boolean sought = false;
                try {
                    if (mediaController != null) {
                        mediaController.getTransportControls().seekTo(progress);
                        sought = true;
                    }
                } catch (Throwable error) {
                    // 保留回弹行为，但 Debug 必须能区分未找到控制器与实际发送失败。
                    if (BuildConfig.DEBUG) android.util.Log.e("HyperOS3FocusRestore",
                            "DIAG media seek request failed key=" + sbn.getKey()
                                    + " target=" + progress, error);
                }
                android.util.Log.i("HyperOS3FocusRestore",
                        "media seek drag stop progress=" + progress + " controller="
                                + (mediaController != null) + " sought=" + sought);
                if (sought) {
                    // Hold the thumb here and show the loading state until the player's real
                    // position reaches the target (buffering video, slow decoder).
                    source.pendingSeekTargetMs = progress;
                    source.pendingSeekSince = SystemClock.uptimeMillis();
                }
                source.startTicker();
                source.updateProgress();
            }
        });
        return source;
    }

    private static Typeface miuiFont(Context context, String family) {
        try {
            Typeface face = Typeface.create(family, Typeface.NORMAL);
            if (face != null && !face.equals(Typeface.DEFAULT)) return face;
        } catch (Throwable ignored) {
            // font unavailable; fall back to the default typeface
        }
        return Typeface.DEFAULT;
    }

    /**
     * FrameLayout whose decorative backdrop children (blurred artwork, scrim) must not drive the
     * banner height. The content column is measured first against the incoming constraints and
     * determines the final height; backdrop children are then measured exactly to that height so
     * the banner stays compact instead of expanding to the full window.
     */
    private static final class MediaBackdropFrame extends FrameLayout {
        private View contentChild;

        MediaBackdropFrame(Context context) {
            super(context);
        }

        void setContentChild(View content) {
            contentChild = content;
        }

        @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            View content = contentChild;
            int desiredHeight = 0;
            int desiredWidth = 0;
            if (content != null && content.getVisibility() != GONE) {
                measureChildWithMargins(content, widthMeasureSpec, 0, heightMeasureSpec, 0);
                desiredHeight = content.getMeasuredHeight();
                desiredWidth = content.getMeasuredWidth();
            }
            int heightMode = MeasureSpec.getMode(heightMeasureSpec);
            int heightSize = MeasureSpec.getSize(heightMeasureSpec);
            if (heightMode == MeasureSpec.EXACTLY) {
                desiredHeight = heightSize;
            } else if (heightMode == MeasureSpec.AT_MOST) {
                desiredHeight = Math.min(Math.max(desiredHeight, 0), heightSize);
            }
            int exactHeightSpec = MeasureSpec.makeMeasureSpec(
                    Math.max(0, desiredHeight), MeasureSpec.EXACTLY);
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child == content || child.getVisibility() == GONE) continue;
                measureChildWithMargins(child, widthMeasureSpec, 0, exactHeightSpec, 0);
            }
            setMeasuredDimension(resolveSize(Math.max(desiredWidth, 0), widthMeasureSpec),
                    desiredHeight);
        }
    }

    private static void addAction(List<Object> target, Object action) {
        if (action != null) target.add(action);
    }

    /**
     * Wraps an action icon in a fixed-size box. ImageView derives drawable bounds from the
     * intrinsic size, so an app-provided action icon with a large intrinsic size fills the whole
     * button. Transport icons are drawn at the full box; app-provided compact glyphs are drawn into
     * an inset rect, leaving transparent margins so their visual weight matches.
     */
    private static Drawable normalizeIcon(Drawable icon, int sizePx, boolean appProvided) {
        if (icon == null || sizePx <= 0) return icon;
        try {
            Drawable copy = icon.getConstantState() == null
                    ? icon.mutate()
                    : icon.getConstantState().newDrawable().mutate();
            return new FixedSizeDrawable(copy, sizePx,
                    appProvided ? CUSTOM_ICON_VISIBLE_RATIO : 1f);
        } catch (Throwable throwable) {
            android.util.Log.i("HyperOS3FocusRestore",
                    "media action icon normalize failed: " + throwable);
            return icon;
        }
    }

    /** True when the icon is smaller than MIUI's own 40dp transport icons, i.e. app-provided. */
    private static boolean isAppProvidedIcon(Drawable icon, Context context) {
        if (icon == null || context == null) return false;
        int intrinsic = Math.max(icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
        return intrinsic > 0 && intrinsic < dp(context, STANDARD_TRANSPORT_ICON_DP);
    }

    /**
     * Drawable that reports a fixed intrinsic size and draws its icon inside an inset rect, so the
     * visible glyph stays smaller than the reported size regardless of the source drawable.
     */
    private static final class FixedSizeDrawable extends Drawable {
        private final Drawable inner;
        private final int sizePx;
        private final float visibleRatio;

        FixedSizeDrawable(Drawable inner, int sizePx, float visibleRatio) {
            this.inner = inner;
            this.sizePx = sizePx;
            this.visibleRatio = visibleRatio <= 0f || visibleRatio > 1f ? 1f : visibleRatio;
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            int insetX = Math.round(bounds.width() * (1f - visibleRatio) / 2f);
            int insetY = Math.round(bounds.height() * (1f - visibleRatio) / 2f);
            inner.setBounds(bounds.left + insetX, bounds.top + insetY,
                    bounds.right - insetX, bounds.bottom - insetY);
            inner.draw(canvas);
        }

        @Override public void setAlpha(int alpha) { inner.setAlpha(alpha); }

        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            inner.setColorFilter(colorFilter);
        }

        @Override public int getOpacity() { return inner.getOpacity(); }

        @Override public int getIntrinsicWidth() { return sizePx; }

        @Override public int getIntrinsicHeight() { return sizePx; }
    }

    /**
     * The media action buttons in the ROM's native slot order (custom0 / previous / play-pause /
     * next / custom1) from MediaData.semanticActions.
     */
    static List<Object> semanticActionList(Object mediaData) {
        List<Object> ordered = new ArrayList<>();
        if (mediaData == null) return ordered;
        Object semantic = field(mediaData, "semanticActions");
        if (semantic != null) {
            addAction(ordered, field(semantic, "custom0"));
            addAction(ordered, field(semantic, "prevOrCustom"));
            addAction(ordered, field(semantic, "playOrPause"));
            addAction(ordered, field(semantic, "nextOrCustom"));
            addAction(ordered, field(semantic, "custom1"));
        }
        if (ordered.isEmpty()) {
            List<?> actions = (List<?>) field(mediaData, "actions");
            List<?> compact = (List<?>) field(mediaData, "actionsToShowInCompact");
            List<?> chosen = actions != null && !actions.isEmpty() ? actions : compact;
            if (chosen != null) for (Object action : chosen) addAction(ordered, action);
        }
        return ordered;
    }

    /**
     * Refreshes the banner in place when the ROM's MediaData changes (play/pause, seek, metadata).
     * Replaces the previous rebuild-based flow so ongoing gestures are not cancelled.
     */
    void refreshContent() {
        if (closed) return;
        Object latest = null;
        WeakReference<Object> reference = mediaDataByKey.get(sbn.getKey());
        if (reference != null) latest = reference.get();
        if (latest == null) return;

        CharSequence song = (CharSequence) field(latest, "song");
        CharSequence artist = (CharSequence) field(latest, "artist");
        if (titleView != null) titleView.setText(song);
        if (artistView != null) artistView.setText(artist);

        Icon artworkIcon = (Icon) field(latest, "artwork");
        if (artworkIcon == null) artworkIcon = (Icon) field(latest, "appIcon");
        if (artworkIcon != null) {
            Drawable artwork = null;
            try {
                artwork = artworkIcon.loadDrawable(context);
            } catch (Throwable ignored) {
                artwork = null;
            }
            if (artView != null && artwork != null) artView.setImageDrawable(artwork);
            if (backdropView != null && artwork != null) {
                Bitmap bitmap = toBitmap(artwork, context);
                if (bitmap != null) backdropView.setImageBitmap(bitmap);
            }
        }
        Icon appIcon = (Icon) field(latest, "appIcon");
        if (badgeView != null && appIcon != null) {
            try {
                badgeView.setImageDrawable(appIcon.loadDrawable(context));
            } catch (Throwable ignored) {
                // keep previous badge
            }
        }

        List<Object> ordered = semanticActionList(latest);
        int index = 0;
        int boxPx = dp(context, ACTION_ICON_BOX_DP);
        for (ImageButton button : actionButtons) {
            Object action = index < ordered.size() ? ordered.get(index) : null;
            if (action != null) {
                Drawable icon = (Drawable) field(action, "icon");
                final Runnable runnable = (Runnable) field(action, "action");
                button.setImageDrawable(normalizeIcon(icon, boxPx,
                        isAppProvidedIcon(icon, context)));
                button.setContentDescription((CharSequence) field(action, "contentDescription"));
                button.setOnClickListener(runnable == null ? null : new View.OnClickListener() {
                    @Override public void onClick(View view) {
                        try { runnable.run(); }
                        catch (Throwable throwable) { /* native transport runnable failure */ }
                    }
                });
            }
            index++;
        }
        Object token = field(latest, "token");
        if (token instanceof android.media.session.MediaSession.Token && mediaController == null) {
            try {
                Context controllerContext = systemContext != null ? systemContext : context;
                mediaController = new MediaController(controllerContext,
                        (android.media.session.MediaSession.Token) token);
            } catch (Throwable ignored) {
                mediaController = null;
            }
        }
        updateProgress();
    }

    /** Renders a drawable into a bitmap for the blurred backdrop; null when unavailable. */
    private static Bitmap toBitmap(Drawable drawable, Context context) {
        if (drawable == null) return null;
        try {
            if (drawable instanceof android.graphics.drawable.BitmapDrawable) {
                Bitmap bitmap = ((android.graphics.drawable.BitmapDrawable) drawable).getBitmap();
                if (bitmap != null && !bitmap.isRecycled()) return bitmap;
            }
            int width = Math.max(1, context.getResources().getDisplayMetrics().widthPixels);
            int height = Math.max(1, dp(context, 240));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            return bitmap;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void startTicker() {
        if (tickerRunning || closed || handler == null) return;
        tickerRunning = true;
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    private void stopTicker() {
        tickerRunning = false;
        if (handler != null) handler.removeCallbacks(ticker);
    }

    private void updateProgress() {
        if (seekBar == null || closed || dragging) return;
        long position = -1L;
        long duration = -1L;
        try {
            PlaybackState state = mediaController == null ? null : mediaController.getPlaybackState();
            if (state != null) position = state.getPosition();
        } catch (Throwable ignored) {
            position = -1L;
        }
        try {
            MediaMetadata metadata = mediaController == null ? null : mediaController.getMetadata();
            if (metadata != null) duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        } catch (Throwable ignored) {
            duration = -1L;
        }
        if (duration <= 0 && mediaData != null) {
            Object durationField = field(mediaData, "mediaDuration");
            if (durationField instanceof Long && (Long) durationField > 0) {
                duration = (Long) durationField;
            }
        }
        if (duration > 0 && seekBar.getMax() != duration) seekBar.setMax((int) duration);
        if (pendingSeekTargetMs >= 0) {
            boolean settled = position >= 0
                    && Math.abs(position - pendingSeekTargetMs) <= SEEK_SETTLE_MS;
            boolean timedOut = SystemClock.uptimeMillis() - pendingSeekSince
                    >= SEEK_LOADING_TIMEOUT_MS;
            if (!settled && !timedOut) {
                // Keep the thumb where the user dropped it and show the loading state until the
                // player's real position catches up (buffering video, slow seek).
                seekBar.setProgress((int) Math.min(pendingSeekTargetMs,
                        Math.max(0, seekBar.getMax())));
                currentTime.setText(formatTime(pendingSeekTargetMs));
                totalTime.setText(formatTime(duration > 0 ? duration : 0L));
                showLoadingThumb();
                return;
            }
            // 只在加载态结束时打印一次；sought 只证明请求发出，不证明播放器已跳转。
            if (BuildConfig.DEBUG) android.util.Log.i("HyperOS3FocusRestore",
                    "DIAG media seek complete key=" + sbn.getKey()
                            + " result=" + (settled ? "settled" : "timeout")
                            + " target=" + pendingSeekTargetMs + " position=" + position
                            + " elapsed=" + (SystemClock.uptimeMillis() - pendingSeekSince));
            clearPendingSeek();
        }
        int progress = position >= 0 ? (int) Math.min(position, seekBar.getMax()) : 0;
        seekBar.setProgress(progress);
        currentTime.setText(formatTime(position >= 0 ? position : 0L));
        totalTime.setText(formatTime(duration > 0 ? duration : 0L));
    }

    private void showLoadingThumb() {
        if (seekBar == null || closed) return;
        try {
            if (loadingThumb == null) {
                loadingThumb = new LoadingThumbDrawable(dp(context, 20), nightColor());
            }
            if (seekBar.getThumb() != loadingThumb) {
                if (normalThumb == null) normalThumb = seekBar.getThumb();
                seekBar.setThumb(loadingThumb);
            }
            loadingThumb.start();
        } catch (Throwable ignored) {
            // the loading indicator is best-effort
        }
    }

    private void clearPendingSeek() {
        pendingSeekTargetMs = -1L;
        try {
            if (loadingThumb != null) loadingThumb.stop();
            if (seekBar != null && seekBar.getThumb() == loadingThumb) {
                seekBar.setThumb(normalThumb);
            }
        } catch (Throwable ignored) {
            // restoring the thumb is best-effort
        }
    }

    private int nightColor() {
        boolean night = false;
        try {
            Context reference = systemContext != null ? systemContext : context;
            night = (reference.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ignored) {
            night = false;
        }
        return night ? 0xFFFFFFFF : 0xFF000000;
    }

    /** Small indeterminate rotating arc used as the seek thumb while the player catches up. */
    private static final class LoadingThumbDrawable extends Drawable implements Runnable {
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF arc = new android.graphics.RectF();
        private final int sizePx;
        private final Handler handler;
        private float angle;
        private boolean running;

        LoadingThumbDrawable(int sizePx, int color) {
            this.sizePx = Math.max(1, sizePx);
            this.handler = new Handler(Looper.getMainLooper());
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, sizePx / 8f));
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setColor(color);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            float inset = paint.getStrokeWidth();
            arc.set(bounds.left + inset, bounds.top + inset,
                    bounds.right - inset, bounds.bottom - inset);
            canvas.drawArc(arc, angle, 100f, false, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }

        @Override public void setColorFilter(android.graphics.ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }

        @Override public int getIntrinsicWidth() { return sizePx; }

        @Override public int getIntrinsicHeight() { return sizePx; }

        @Override public void run() {
            if (!running) return;
            angle = (angle + 30f) % 360f;
            invalidateSelf();
            handler.postDelayed(this, 60L);
        }

        void start() {
            if (running) return;
            running = true;
            handler.post(this);
        }

        void stop() {
            running = false;
            handler.removeCallbacks(this);
        }
    }

    private static String formatTime(long millis) {
        long totalSeconds = Math.max(0L, millis / 1000L);
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    /** Resolves a SystemUI drawable resource by name; returns null when the ROM lacks it. */
    private static Drawable systemDrawable(Context context, String name) {
        try {
            int id = context.getResources().getIdentifier(name, "drawable",
                    "com.android.systemui");
            if (id != 0) return context.getResources().getDrawable(id);
        } catch (Throwable ignored) {
            // resource lookup failure; fall back to the default seek bar look
        }
        return null;
    }

    /** Resolves a SystemUI media resource by name; falls back when the ROM lacks it. */
    private static int systemColor(Context context, String name, int fallback) {
        try {
            int id = context.getResources().getIdentifier(name, "color", "com.android.systemui");
            if (id != 0) {
                int resolved = context.getResources().getColor(id);
                if (resolved != 0) return resolved;
            }
        } catch (Throwable ignored) {
            // resource lookup failure; use the fallback color
        }
        return fallback;
    }

    private static ViewOutlineProvider roundedOutline(final Context context, final int radiusDp) {
        final int radius = dp(context, radiusDp);
        return new ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        };
    }

    private static Object field(Object target, String name) {
        if (target == null || name == null) return null;
        try {
            return target.getClass().getField(name).get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int dp(Context context, int value) {
        return Math.max(1, Math.round(value * context.getResources().getDisplayMetrics().density));
    }

    private static Context packageContextFor(Context systemUiContext, StatusBarNotification sbn)
            throws Exception {
        try {
            return systemUiContext.createPackageContext(sbn.getPackageName(),
                    Context.CONTEXT_RESTRICTED);
        } catch (Throwable ignored) {
            // Work-profile/package isolation can reject the user context; SystemUI context still
            // allows RemoteViews inflation for layouts whose resources are already resolved.
            return systemUiContext;
        }
    }

    @Override public View view() { return root; }
    @Override public Context context() { return context; }
    @Override public int widthPx() { return widthPx; }
    @Override public int minHeightPx() { return 0; }
    @Override public String source() { return source; }
    @Override public String layoutSummary() { return "mediaRemoteViews=" + source; }
    @Override public void onAttached() {
        startTicker();
    }
    @Override public boolean isCurrent(StatusBarNotification actual) {
        if (closed || actual == null || actual.getNotification() == null) return false;
        if (!sbn.getKey().equals(actual.getKey())) return false;
        if (mediaData == null) {
            // RemoteViews path: re-posts replace the content, so require the same generation.
            return actual.getPostTime() == sbn.getPostTime();
        }
        // MediaData path: the media notification keeps re-posting while playing. Treating the
        // same key as current keeps gestures alive between source refreshes; content updates
        // are handled by prepareNative rebuilding the source.
        return true;
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        stopTicker();
        if (loadingThumb != null) loadingThumb.stop();
        handler = null;
        mediaController = null;
        if (root instanceof ViewGroup) ((ViewGroup) root).removeAllViews();
    }
}