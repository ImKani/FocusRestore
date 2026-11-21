/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 规则页面共用设置页配色，内容区滚动，底栏不随内容滚动。 */
final class IslandRuleEditorUi {
    static final String REDACTED = "示例已脱敏";
    static final String NO_SAMPLE = "未保存示例";
    private final SettingsActivity activity;

    IslandRuleEditorUi(SettingsActivity activity) { this.activity = activity; }

    final class Page {
        final Dialog dialog = new Dialog(activity) {
            @Override public void onBackPressed() {
                if (busy) return;
                if (decision.getVisibility() == View.VISIBLE) closeDecision();
                else if (backAction != null) backAction.run();
                else super.onBackPressed();
            }
        };
        final LinearLayout body = column();
        private final LinearLayout footer = new LinearLayout(activity);
        private final LinearLayout decision = column();
        private Runnable backAction;
        private Runnable releaseAction;
        private boolean busy;
        private final TextView heading;
        private final TextView notice = secondary("", 13);
        private ViewTreeObserver.OnWindowFocusChangeListener focusListener;

        Page(String title) {
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout root = column();
            root.setBackground(activity.roundedBg(activity.COLOR_SURFACE_HIGH, 12));
            root.setForceDarkAllowed(false);
            root.setPadding(dp(16), dp(16), dp(16), dp(12));
            root.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
            root.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
            heading = heading(title, 18);
            root.addView(heading, spaced(10));
            notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            notice.setVisibility(View.GONE);
            root.addView(notice, spaced(8));
            root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1f));
            root.addView(decision, new LinearLayout.LayoutParams(-1, 0, 1f));
            decision.setVisibility(View.GONE);
            footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            footer.setPadding(0, dp(10), 0, 0);
            root.addView(footer);
            dialog.setContentView(root);
            Window window = dialog.getWindow();
            if (window == null) throw new IllegalStateException("RULE_EDITOR_WINDOW_MISSING");
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        void title(String title) { heading.setText(title); }

        void notice(String text) {
            notice.setText(text);
            notice.setVisibility(text == null ? View.GONE : View.VISIBLE);
        }

        void show() {
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
                window.setLayout(Math.min(dp(560), (int) (metrics.widthPixels * 0.94f)),
                        (int) (metrics.heightPixels * 0.88f));
            }
        }

        void watchFocus(Runnable revoke) {
            focusListener = focused -> { if (!focused) revoke.run(); };
            dialog.getWindow().getDecorView().getViewTreeObserver().addOnWindowFocusChangeListener(focusListener);
        }

        void exitEdits(java.util.function.BooleanSupplier dirty, Runnable exit) {
            dialog.setCanceledOnTouchOutside(false);
            backAction = () -> {
                if (!dirty.getAsBoolean()) exit.run();
                else ask("放弃未保存的更改？", "继续编辑", "放弃更改", exit);
            };
        }

        void cancelEdits() {
            if (backAction != null) backAction.run();
        }

        void ask(String message, String cancel, String confirm, Runnable accept) {
            closeDecision();
            body.setVisibility(View.GONE);
            footer.setVisibility(View.GONE);
            decision.addView(heading(message, 16), spaced(12));
            decision.addView(button(cancel, false, this::closeDecision), spaced(8));
            decision.addView(button(confirm, true, () -> {
                closeDecision();
                accept.run();
            }), spaced(0));
            decision.setVisibility(View.VISIBLE);
            decision.announceForAccessibility(message);
        }

        private void closeDecision() {
            if (decision.getVisibility() != View.VISIBLE) return;
            scrub(decision);
            decision.removeAllViews();
            decision.setVisibility(View.GONE);
            body.setVisibility(View.VISIBLE);
            footer.setVisibility(View.VISIBLE);
        }

        boolean isBusy() { return busy; }

        void setBusy(boolean busy) {
            closeDecision();
            this.busy = busy;
            if (busy) {
                body.setVisibility(View.GONE);
                footer.setVisibility(View.GONE);
                decision.addView(secondary("正在保存更改…", 14));
                decision.setVisibility(View.VISIBLE);
            }
        }

        void onRelease(Runnable callback) { releaseAction = callback; }

        void release() {
            if (focusListener != null) {
                ViewTreeObserver observer = dialog.getWindow().getDecorView().getViewTreeObserver();
                if (observer.isAlive()) observer.removeOnWindowFocusChangeListener(focusListener);
                focusListener = null;
            }
            clear();
            scrub(heading);
        }

        LinearLayout scrolling() {
            ScrollView scroll = new ScrollView(activity);
            scroll.setSaveEnabled(false);
            scroll.setFillViewport(true);
            LinearLayout content = column();
            scroll.addView(content);
            body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
            return content;
        }

        Button action(String title, boolean primary, Runnable click) {
            Button button = button(title, primary, click);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
            if (footer.getChildCount() > 0) params.setMarginStart(dp(8));
            footer.addView(button, params);
            return button;
        }

        void clear() {
            if (backAction != null) dialog.setCanceledOnTouchOutside(true);
            backAction = null;
            if (releaseAction != null) {
                releaseAction.run();
                releaseAction = null;
            }
            setBusy(false);
            notice(null);
            scrub(body);
            body.removeAllViews();
            scrub(footer);
            footer.removeAllViews();
        }
    }

    LinearLayout column() {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setSaveFromParentEnabled(false);
        return layout;
    }

    LinearLayout.LayoutParams spaced(int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(bottom);
        return params;
    }

    TextView primary(String label, int size) { return label(label, size, activity.COLOR_TEXT_PRIMARY); }
    TextView secondary(String label, int size) { return label(label, size, activity.COLOR_TEXT_SECONDARY); }

    TextView heading(String label, int size) {
        TextView title = primary(label, size);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setAccessibilityHeading(true);
        return title;
    }

    private TextView label(String label, int size, int color) {
        TextView text = activity.text(label, size, color);
        text.setSaveEnabled(false);
        return text;
    }

    Button button(String title, boolean primary, Runnable click) {
        int background = primary ? activity.COLOR_PRIMARY : activity.COLOR_SURFACE;
        int foreground = primary ? Color.WHITE : activity.COLOR_PRIMARY;
        Button button = activity.actionButton(title, background, foreground);
        int horizontal = dp(12);
        button.setPadding(horizontal, dp(6), horizontal, dp(6));
        button.setSaveEnabled(false);
        button.setFilterTouchesWhenObscured(true);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    // 规则相关页面共用同一套操作按钮尺寸；图标、文字和圆角由这里统一，避免各页各写一套。
    static final int ACTION_HEIGHT = 40;
    private static final int ACTION_RADIUS = 10;
    private static final int ACTION_ICON = 16;
    private static final float ACTION_TEXT_SIZE = 12.5f;

    static int dangerColor() { return Color.rgb(214, 84, 74); }

    Button tonal(String label, Runnable click) {
        return tone(0, label, activity.COLOR_SURFACE_HIGH, activity.COLOR_TEXT_PRIMARY, click);
    }

    Button tonal(int iconRes, String label, Runnable click) {
        return tone(iconRes, label, activity.COLOR_SURFACE_HIGH, activity.COLOR_TEXT_PRIMARY, click);
    }

    Button danger(int iconRes, String label, Runnable click) {
        return tone(iconRes, label, dangerSurface(), dangerColor(), click);
    }

    private int dangerSurface() {
        int base = activity.COLOR_SURFACE_HIGH;
        int tint = dangerColor();
        return Color.rgb(
                Math.round(Color.red(base) * 0.86f + Color.red(tint) * 0.14f),
                Math.round(Color.green(base) * 0.86f + Color.green(tint) * 0.14f),
                Math.round(Color.blue(base) * 0.86f + Color.blue(tint) * 0.14f));
    }

    private Button tone(int iconRes, String label, int background, int foreground, Runnable click) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(ACTION_TEXT_SIZE);
        button.setTextColor(foreground);
        button.setBackground(activity.roundedBg(background, ACTION_RADIUS));
        button.setMinHeight(dp(ACTION_HEIGHT));
        button.setMinimumHeight(dp(ACTION_HEIGHT));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setSaveEnabled(false);
        button.setFilterTouchesWhenObscured(true);
        // 去掉主题自带的抬升动画，与设置页操作按钮保持一致。
        button.setElevation(0f);
        button.setTranslationZ(0f);
        button.setStateListAnimator(null);
        if (iconRes != 0) setLeadingIcon(button, iconRes, foreground);
        button.setOnClickListener(view -> click.run());
        return button;
    }

    private void setLeadingIcon(Button button, int iconRes, int tint) {
        Drawable icon = activity.getDrawable(iconRes);
        if (icon == null) return;
        icon.setBounds(0, 0, dp(ACTION_ICON), dp(ACTION_ICON));
        button.setCompoundDrawablesRelative(icon, null, null, null);
        button.setCompoundDrawablePadding(dp(6));
        button.setCompoundDrawableTintList(ColorStateList.valueOf(tint));
    }

    /** 纯图形按钮仍保留 40dp 触控区，图标本身较小。 */
    ImageButton glyphButton(int iconRes, String description, Runnable click) {
        ImageButton button = new ImageButton(activity);
        button.setImageResource(iconRes);
        button.setImageTintList(ColorStateList.valueOf(activity.COLOR_TEXT_SECONDARY));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        button.setPadding(dp(11), dp(11), dp(11), dp(11));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x26000000 | (activity.COLOR_PRIMARY & 0x00FFFFFF)),
                activity.roundedBg(activity.COLOR_SURFACE, ACTION_RADIUS),
                activity.roundedBg(Color.WHITE, ACTION_RADIUS)));
        button.setMinimumWidth(dp(ACTION_HEIGHT));
        button.setMinimumHeight(dp(ACTION_HEIGHT));
        button.setContentDescription(description);
        button.setSaveEnabled(false);
        button.setFilterTouchesWhenObscured(true);
        button.setStateListAnimator(null);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(ACTION_HEIGHT), dp(ACTION_HEIGHT)));
        button.setOnClickListener(view -> click.run());
        return button;
    }

    /** 静态图标：boxDp 是占位尺寸，glyphDp 是实际图形尺寸。 */
    ImageView glyph(int iconRes, int boxDp, int glyphDp, int color) {
        ImageView view = new ImageView(activity);
        view.setImageResource(iconRes);
        view.setImageTintList(ColorStateList.valueOf(color));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int box = dp(boxDp);
        int inset = Math.max(0, dp(boxDp - glyphDp) / 2);
        view.setPadding(inset, inset, inset, inset);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        view.setLayoutParams(new LinearLayout.LayoutParams(box, box));
        return view;
    }

    CheckBox choice(String example, IslandRulePaths.Entry path) {
        CheckBox choice = new CheckBox(activity);
        styleChoice(choice);
        choice.setText(field(example, path.path));
        return choice;
    }

    CheckBox batchFieldCheck(String field) {
        CheckBox choice = new CheckBox(activity);
        styleChoice(choice);
        choice.setBackground(null);
        choice.setPadding(0, 0, 0, 0);
        choice.setContentDescription("批量操作，" + field);
        return choice;
    }

    CheckBox fieldChip(String example, IslandRulePaths.Entry path) {
        String name = fieldName(path);
        boolean hasExample = !NO_SAMPLE.equals(example) && !REDACTED.equals(example);
        CheckBox chip = new CheckBox(activity);
        styleChoice(chip);
        // 保留原生复选语义，图形用描边和底色表示选用状态。
        chip.setButtonDrawable((android.graphics.drawable.Drawable) null);
        android.graphics.drawable.StateListDrawable fill = new android.graphics.drawable.StateListDrawable();
        fill.addState(new int[] {-android.R.attr.state_enabled}, fieldSurface(false, 22));
        fill.addState(new int[] {android.R.attr.state_checked}, fieldSurface(true, 22));
        fill.addState(new int[0], fieldSurface(false, 22));
        chip.setBackground(new android.graphics.drawable.RippleDrawable(
                ColorStateList.valueOf(0x26000000 | (activity.COLOR_PRIMARY & 0x00FFFFFF)), fill, null));
        chip.setTextColor(new ColorStateList(new int[][] {new int[] {-android.R.attr.state_enabled}, new int[0]},
                new int[] {activity.COLOR_TEXT_SECONDARY, activity.COLOR_TEXT_PRIMARY}));
        chip.setGravity(android.view.Gravity.CENTER_VERTICAL);
        chip.setPaddingRelative(dp(14), dp(8), dp(14), dp(8));
        chip.setTextSize(14);
        chip.setMaxLines(2);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        chip.setText(hasExample ? name + " · " + example : name);
        chip.setTooltipText(path.path);
        chip.setContentDescription("用于正文，" + name + "，" + path.path + (hasExample ? "，示例 " + example : ""));
        return chip;
    }

    android.graphics.drawable.GradientDrawable fieldSurface(boolean used, int radius) {
        android.graphics.drawable.GradientDrawable fill = (android.graphics.drawable.GradientDrawable)
                activity.roundedBg(used ? activity.COLOR_SURFACE_HIGH : activity.COLOR_SURFACE, radius);
        fill.setStroke(dp(1), used ? activity.COLOR_PRIMARY : activity.COLOR_DIVIDER);
        return fill;
    }

    static String fieldName(IslandRulePaths.Entry entry) {
        String path = entry.path;
        if ("/aodTitle".equals(path)) return "息屏标题";
        if ("/ticker".equals(path)) return "滚动提示";
        if ("/title".equals(path)) return "旧版标题";
        if ("/desc1".equals(path)) return "旧版正文";
        if ("/desc2".equals(path)) return "旧版附加正文";
        String group;
        switch (entry.templateGroup) {
            case "baseInfo": group = "基础信息"; break;
            case "highlightInfo":
            case "highlightInfoV3": group = "重点信息"; break;
            case "chatInfo": group = "聊天"; break;
            case "iconTextInfo": group = "图文"; break;
            case "animTextInfo": group = "动效"; break;
            case "coverInfo": group = "封面"; break;
            case "hintInfo": group = "提示"; break;
            case "stepInfo": group = "步骤"; break;
            case "progressInfo": group = "进度"; break;
            case "multiProgressInfo": group = "多段进度"; break;
            default:
                group = path.contains("/imageTextInfoLeft/") ? "岛左侧"
                        : path.contains("/imageTextInfoRight/") ? "岛右侧" : "岛内容";
                break;
        }
        String leaf = path.substring(path.lastIndexOf('/') + 1);
        switch (leaf) {
            case "title": leaf = "标题"; break;
            case "subTitle": leaf = "副标题"; break;
            case "specialTitle": leaf = "特别标题"; break;
            case "extraTitle": leaf = "附加标题"; break;
            case "frontTitle": leaf = "前置标题"; break;
            case "content": leaf = "内容"; break;
            case "subContent": leaf = "附加内容"; break;
            case "primaryText": leaf = "主文字"; break;
            case "secondaryText": leaf = "次要文字"; break;
            case "highLightText": leaf = "高亮文字"; break;
            case "label": leaf = "标签"; break;
            case "progress": leaf = "进度值"; break;
            case "step": leaf = "步骤值"; break;
            default: break;
        }
        return group + " · " + leaf;
    }

    RadioButton radio(String title, String paths) {
        RadioButton option = new RadioButton(activity);
        styleChoice(option);
        option.setId(View.generateViewId());
        option.setText(paths == null ? title : field(title, paths));
        return option;
    }

    private void styleChoice(CompoundButton choice) {
        choice.setSaveEnabled(false);
        choice.setFilterTouchesWhenObscured(true);
        choice.setTextColor(activity.COLOR_TEXT_PRIMARY);
        choice.setTextSize(16);
        choice.setMinHeight(dp(48));
        choice.setPadding(dp(8), dp(6), dp(8), dp(6));
        choice.setBackground(activity.roundedBg(activity.COLOR_SURFACE, 10));
        choice.setButtonTintList(new ColorStateList(new int[][] {
                new int[] {android.R.attr.state_checked}, new int[0]},
                new int[] {activity.COLOR_PRIMARY, activity.COLOR_TEXT_SECONDARY}));
    }

    TextView fieldLabel(String example, String path) {
        TextView text = primary("", 14);
        text.setText(field(example, path));
        text.setLineSpacing(dp(2), 1f);
        text.setPadding(dp(12), dp(8), dp(12), dp(8));
        GradientDrawable shape = (GradientDrawable) activity.roundedBg(activity.COLOR_SURFACE, 10);
        shape.setStroke(dp(1), activity.COLOR_DIVIDER);
        text.setBackground(shape);
        return text;
    }

    private CharSequence field(String example, String path) {
        SpannableStringBuilder caption = new SpannableStringBuilder(readableExample(example)).append('\n');
        int pathStart = caption.length();
        caption.append(path);
        caption.setSpan(new RelativeSizeSpan(0.75f), pathStart, caption.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        caption.setSpan(new ForegroundColorSpan(activity.COLOR_TEXT_SECONDARY),
                pathStart, caption.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return caption;
    }

    static String readableExample(String example) {
        return example == null || example.trim().isEmpty() ? "空文本" : example;
    }

    static String typeLabel(IslandNotificationType type) {
        if (type == null) return "按应用匹配";
        List<String> names = new ArrayList<>(3);
        if (type.business != null) names.add(type.business);
        if (type.scene != null) names.add(type.scene);
        if (type.protocol != null) names.add("协议 " + type.protocol);
        return names.isEmpty() ? "按字段结构匹配" : String.join(" · ", names);
    }

    static void setActionEnabled(Button button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.38f);
    }

    static void setActionEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.38f);
    }

    static void scrub(View view) {
        if (view.getTag() instanceof SettingsSortRows) {
            ((SettingsSortRows) view.getTag()).unbind();
            view.setTag(null);
        }
        // AdapterView 拒绝 setOnClickListener，包括传入 null。列表的专用回调由其持有者解绑。
        if (!(view instanceof AdapterView)) view.setOnClickListener(null);
        view.setAccessibilityDelegate(null);
        view.setContentDescription(null);
        view.setStateDescription(null);
        view.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        if (view instanceof RadioGroup) ((RadioGroup) view).setOnCheckedChangeListener(null);
        if (view instanceof TextView) ((TextView) view).setText("");
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) scrub(group.getChildAt(i));
        }
    }

    private int dp(int value) { return activity.dp(value); }
}
