/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** 规则迁移仅展示校验后的摘要，通知示例和原规则内容不进入视图。 */
final class IslandRulePackReviewUi {
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,47}");
    private final SettingsActivity activity;
    private final IslandRuleEditorUi ui;
    private final IslandRuleEditorUi.Page page;
    private final ListView cards;
    private final Set<Integer> replacements = new HashSet<>();
    private final Set<Integer> expandedTypes = new HashSet<>();
    private Runnable replacementsChanged;

    static IslandRulePackReviewUi preview(SettingsActivity activity, IslandRuleRepository.PendingBatch prepared,
            Consumer<Set<Integer>> confirm) {
        IslandRulePackReviewUi review = new IslandRulePackReviewUi(activity, "确认导入规则");
        int added = 0;
        int duplicates = 0;
        int failed = 0;
        for (IslandRuleRepository.BatchRow row : prepared.rows) {
            switch (row.status) {
                case ADD: added++; break;
                case DUPLICATE: duplicates++; break;
                case FAILED: failed++; break;
            }
        }
        String summary = "可新增 " + added + " 条 · 重复 " + duplicates + " 条 · 失败 " + failed + " 条";
        String detail = prepared.rows.isEmpty() ? "规则包为空。" : "本地已有 " + prepared.previousCount + " 条规则。";
        if (duplicates > 0) detail += " 重复项默认保留本地，可逐条勾选替换。";
        review.header(summary, detail);
        review.cards.setAdapter(review.new Cards(prepared.rows, null));
        review.page.action("取消", false, review.page.dialog::dismiss);
        boolean hasAdds = added > 0;
        Button apply = review.page.action(hasAdds ? "确认导入" : "查看结果", true,
                () -> confirm.accept(Collections.unmodifiableSet(new HashSet<>(review.replacements))));
        review.replacementsChanged = () ->
                apply.setText(hasAdds || !review.replacements.isEmpty() ? "确认导入" : "查看结果");
        return review;
    }

    static IslandRulePackReviewUi result(SettingsActivity activity, IslandRuleRepository.BatchResult result,
            Consumer<IslandRulePackReviewUi> completed) {
        IslandRulePackReviewUi review = new IslandRulePackReviewUi(activity, "规则导入结果");
        String detail = resultDetail(result);
        if (result.confirmableRevision() > 0) detail += "\n点「完成」后，若设备需要，可确认规则读取权限。";
        review.header(resultHeading(result), detail);
        review.cards.setAdapter(review.new Cards(null, result.rows));
        review.page.action("完成", true, () -> completed.accept(review));
        return review;
    }

    static String resultHeading(IslandRuleRepository.BatchResult result) {
        if (result.warning == IslandRuleRepository.Code.DURABILITY_UNCERTAIN) {
            return "规则集已切换，保存状态待确认";
        }
        return result.changed ? "App 已保存规则" : "本地规则未变更";
    }

    static String resultDetail(IslandRuleRepository.BatchResult result) {
        String summary = resultSummary(result);
        if (result.warning == IslandRuleRepository.Code.DURABILITY_UNCERTAIN) {
            return summary + "\n请重新查看本地规则以确认保存状态。";
        }
        if (result.warning == IslandRuleRepository.Code.COMMITTED_CLEANUP_FAILED) {
            return summary + "\n旧文件清理待完成。";
        }
        return summary;
    }

    private static String resultSummary(IslandRuleRepository.BatchResult result) {
        int added = 0;
        int replaced = 0;
        int skipped = 0;
        int failed = 0;
        for (IslandRuleRepository.BatchResultRow row : result.rows) {
            switch (row.status) {
                case ADDED: added++; break;
                case REPLACED: replaced++; break;
                case SKIPPED: skipped++; break;
                case FAILED: failed++; break;
            }
        }
        return "新增 " + added + " 条 · 替换 " + replaced + " 条 · 跳过 " + skipped + " 条 · 失败 " + failed + " 条";
    }

    private IslandRulePackReviewUi(SettingsActivity activity, String title) {
        this.activity = activity;
        ui = new IslandRuleEditorUi(activity);
        page = ui.new Page(title);
        cards = new ListView(activity);
        cards.setSaveEnabled(false);
        cards.setDivider(new ColorDrawable(Color.TRANSPARENT));
        cards.setDividerHeight(activity.dp(8));
        cards.setSelector(new ColorDrawable(Color.TRANSPARENT));
        cards.setItemsCanFocus(true);
        page.body.addView(cards, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    void show(Runnable dismissed) {
        page.dialog.setOnDismissListener(ignored -> dismissed.run());
        page.show();
        page.watchFocus(page.dialog::dismiss);
    }

    void close() {
        replacementsChanged = null;
        replacements.clear();
        expandedTypes.clear();
        for (int index = 0; index < cards.getChildCount(); index++) {
            View child = cards.getChildAt(index);
            if (child instanceof Card) ((Card) child).replace.setOnCheckedChangeListener(null);
        }
        cards.setAdapter(null);
        page.release();
        page.dialog.setOnDismissListener(null);
        page.dialog.dismiss();
    }

    private void header(String title, String detail) {
        LinearLayout header = ui.column();
        header.addView(ui.heading(title, 15), ui.spaced(6));
        header.addView(ui.secondary(detail, 13), ui.spaced(8));
        cards.addHeaderView(header, null, false);
    }

    private final class Cards extends BaseAdapter {
        private final List<IslandRuleRepository.BatchRow> planned;
        private final List<IslandRuleRepository.BatchResultRow> results;

        Cards(List<IslandRuleRepository.BatchRow> planned, List<IslandRuleRepository.BatchResultRow> results) {
            this.planned = planned;
            this.results = results;
        }

        @Override public int getCount() { return results == null ? planned.size() : results.size(); }
        @Override public IslandRuleRepository.BatchRow getItem(int position) {
            return results == null ? planned.get(position) : results.get(position).preview;
        }
        @Override public long getItemId(int position) { return getItem(position).declarationIndex; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Card card = recycled instanceof Card ? (Card) recycled : new Card();
            IslandRuleRepository.BatchResultRow result = results == null ? null : results.get(position);
            card.bind(getItem(position), result);
            return card;
        }
    }

    private final class Card extends LinearLayout {
        private final TextView title = ui.heading("", 15);
        private final TextView identity = ui.secondary("", 12);
        private final Button typeDetails = ui.tonal("", () -> { });
        private final TextView typePaths = ui.secondary("", 12);
        private final TextView local = ui.primary("", 13);
        private final TextView incoming = ui.primary("", 13);
        private final TextView note = ui.secondary("", 12);
        private final CheckBox replace = new CheckBox(activity);

        Card() {
            super(IslandRulePackReviewUi.this.activity);
            setOrientation(VERTICAL);
            setSaveFromParentEnabled(false);
            setBackground(activity.roundedBg(activity.COLOR_SURFACE, 14));
            int padding = activity.dp(12);
            setPadding(padding, padding, padding, padding);
            replace.setSaveEnabled(false);
            replace.setTextSize(13);
            replace.setMinHeight(activity.dp(44));
            replace.setFilterTouchesWhenObscured(true);
            replace.setTextColor(activity.COLOR_TEXT_PRIMARY);
            replace.setButtonTintList(new ColorStateList(new int[][] {
                    new int[] {android.R.attr.state_checked}, new int[0]},
                    new int[] {activity.COLOR_PRIMARY, activity.COLOR_TEXT_SECONDARY}));
            addView(title, ui.spaced(5));
            addView(identity, ui.spaced(6));
            addView(typeDetails, ui.spaced(5));
            addView(typePaths, ui.spaced(6));
            addView(local, ui.spaced(6));
            addView(incoming, ui.spaced(6));
            addView(note, ui.spaced(4));
            addView(replace, ui.spaced(0));
        }

        void bind(IslandRuleRepository.BatchRow row, IslandRuleRepository.BatchResultRow result) {
            String status = result == null ? previewStatus(row.status) : resultStatus(result.status);
            title.setText("第 " + (row.declarationIndex + 1) + " 条 · " + status);
            IslandRuleRepository.BatchRuleSummary rule = row.incoming != null ? row.incoming : row.local;
            IslandNotificationType type = rule == null ? null : rule.type;
            showText(identity, rule == null ? null : rule.packageName + "\n" + typeText(type));
            bindType(row.declarationIndex, type);
            showText(local, ruleText("本地", row.local));
            showText(incoming, ruleText("包内", row.incoming));
            showText(note, rowNote(row, result));

            // ListView 复用卡片，勾选状态始终按包内索引恢复。
            replace.setOnCheckedChangeListener(null);
            boolean selectable = result == null && row.status == IslandRuleRepository.BatchRowStatus.DUPLICATE;
            replace.setVisibility(selectable ? VISIBLE : GONE);
            replace.setChecked(selectable && replacements.contains(row.declarationIndex));
            if (selectable) {
                replace.setText("用包内规则替换第 " + (row.declarationIndex + 1) + " 条对应的本地规则");
                replace.setOnCheckedChangeListener((ignored, checked) -> {
                    if (checked) replacements.add(row.declarationIndex);
                    else replacements.remove(row.declarationIndex);
                    if (replacementsChanged != null) replacementsChanged.run();
                });
            } else replace.setText("");
        }

        private void bindType(int index, IslandNotificationType type) {
            typeDetails.setVisibility(type == null ? GONE : VISIBLE);
            typeDetails.setOnClickListener(null);
            if (type == null) {
                showText(typePaths, null);
                return;
            }
            renderType(type, expandedTypes.contains(index));
            typeDetails.setOnClickListener(view -> {
                boolean expanded = expandedTypes.add(index);
                if (!expanded) expandedTypes.remove(index);
                renderType(type, expanded);
            });
        }

        private void renderType(IslandNotificationType type, boolean expanded) {
            typeDetails.setText(expanded ? "收起分型字段" : "查看分型字段（" + type.paths.size() + "）");
            showText(typePaths, expanded ? String.join("\n", type.paths) : null);
        }
    }

    private static void showText(TextView view, String text) {
        view.setText(text);
        view.setVisibility(text == null ? View.GONE : View.VISIBLE);
    }

    private static String ruleText(String source, IslandRuleRepository.BatchRuleSummary rule) {
        if (rule == null) return null;
        StringBuilder display = new StringBuilder(source).append(" · ")
                .append(rule.enabled ? "启用" : "停用").append(" · 优先级 ").append(rule.priority).append('\n');
        if (rule.paths.isEmpty()) return display.append("正文未引用登记字段").toString();
        for (int index = 0; index < rule.paths.size(); index++) {
            if (index > 0) display.append('\n');
            display.append(index + 1).append(". ").append(rule.paths.get(index));
        }
        return display.toString();
    }

    private static String typeText(IslandNotificationType type) {
        if (type == null) return "未指定捕获类型";
        StringBuilder label = new StringBuilder("按字段结构匹配");
        if (type.protocol != null) label.append(" · 协议 ").append(type.protocol);
        if (type.business != null) label.append(" · 业务 ").append(type.business);
        if (type.scene != null) label.append(" · 场景 ").append(type.scene);
        return label.toString();
    }

    private static String rowNote(IslandRuleRepository.BatchRow row, IslandRuleRepository.BatchResultRow result) {
        if (result != null && result.status == IslandRuleRepository.BatchResultStatus.FAILED) {
            return failureText(result.failureCode == null ? row.failureCode : result.failureCode);
        }
        if (row.status == IslandRuleRepository.BatchRowStatus.FAILED) return failureText(row.failureCode);
        boolean unverified = false;
        for (IslandRuleImportValidator.Finding finding : row.findings) {
            if ("L2_UNVERIFIABLE".equals(finding.code)) unverified = true;
        }
        String difference = row.otherContentChanged ? "其他规则内容也有变化。" : "";
        if (unverified) return difference + "部分设备兼容信息无法核实。";
        return difference.isEmpty() ? null : difference;
    }

    private static String failureText(String code) {
        if (code == null) return "规则未通过校验。";
        switch (code) {
            case "AMBIGUOUS_LOCAL": return "本地有多条同类型规则。请整理本地规则后重新导入。";
            case "DUPLICATE_IN_PACK": return "规则包内已有同类型的有效条目。";
            case "L2_ENVIRONMENT": return "规则要求的 SystemUI 版本与本机不一致。";
            case "FRPK_ENABLED": return "启停状态格式无效。";
            case "FRPK_ENCODING":
            case "L1_ENCODING": return "规则文字编码无效。";
            case "FRPK_SAMPLE_FORMAT": return "规则示例格式无效。";
            case "L1_JSON":
            case "L1_DUPLICATE_KEY": return "规则 JSON 格式无效或含重复字段。";
            case "L1_VERSION": return "此规则版本不受支持，请使用当前版本重新导出。";
            case "L1_FORBIDDEN_FIELD":
            case "L1_UNKNOWN_FIELD":
            case "L1_REQUIRED":
            case "L1_TYPE": return "规则字段缺失、类型错误，或使用了不受支持的字段。";
            case "L1_PATH_ENUM":
            case "L1_TEMPLATE_PATH":
            case "L3_PATH_SCOPE": return "所选字段路径超出支持范围。";
            case "L1_OPERATOR_ENUM":
            case "L3_OPERATOR_TYPE": return "匹配条件的操作或值类型不受支持。";
            case "L1_SLOT_ENUM":
            case "L3_REFERENCE":
            case "L3_FORBIDDEN_CAPABILITY": return "提取或正文显示设置不受支持。";
            case "L1_FILE_SIZE":
            case "L1_CAPACITY":
            case "L3_QUOTA": return "单条规则超出支持的大小或数量上限。";
            case "ACTIVE_LIMIT": return "本地启用规则已达上限。请停用部分规则后重试。";
            case "METADATA_LIMIT":
            case "MANIFEST_LIMIT": return "本地规则已达仓库上限。请清理部分本地规则后重试。";
            case "REPOSITORY_CAPACITY": return "本地规则仓库空间不足。";
            case "SAMPLE_CAPACITY": return "本地示例存储空间不足。";
            default:
                // 固定符号码用于定位；导入的路径、原文和异常内容不透传。
                return SAFE_CODE.matcher(code).matches()
                        ? "规则未通过校验（" + code + "）。" : "规则未通过校验。";
        }
    }

    private static String previewStatus(IslandRuleRepository.BatchRowStatus status) {
        switch (status) {
            case ADD: return "可新增";
            case DUPLICATE: return "与本地重复";
            case FAILED: return "失败";
            default: throw new AssertionError(status);
        }
    }

    private static String resultStatus(IslandRuleRepository.BatchResultStatus status) {
        switch (status) {
            case ADDED: return "已新增";
            case REPLACED: return "已替换";
            case SKIPPED: return "已跳过 · 保留本地";
            case FAILED: return "导入失败";
            default: throw new AssertionError(status);
        }
    }
}
