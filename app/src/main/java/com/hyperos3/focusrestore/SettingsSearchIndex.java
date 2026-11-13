/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani.
 * Project: FocusRestore
 * Source: https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 搜索仅描述设置入口；不读取应用名单、通知正文或任何设置值。 */
final class SettingsSearchIndex {
    static final class Entry {
        final String id;
        final String title;
        final int page;
        final String section;
        final boolean debugOnly;
        private final String normalizedTitle;
        private final String searchable;

        Entry(String id, String title, int page, String section, String aliases, boolean debugOnly) {
            this.id = id;
            this.title = title;
            this.page = page;
            this.section = section;
            this.debugOnly = debugOnly;
            normalizedTitle = normalize(title);
            searchable = normalize(title + " " + pageName(page) + " " + section + " " + aliases);
        }

        String location() {
            return pageName(page) + " · " + section;
        }

        private int rank(String query) {
            if (normalizedTitle.equals(query)) return 0;
            if (normalizedTitle.startsWith(query)) return 1;
            return normalizedTitle.contains(query) ? 2 : 3;
        }
    }

    private static final List<Entry> ENTRIES;
    static {
        List<Entry> entries = new ArrayList<>();
        add(entries, "mode", "系统界面版本", 0, "系统界面版本", "OS3 OS4 HyperOS3 HyperOS4 澎湃3 澎湃4 模式");
        add(entries, "island", "转换超级岛内容为焦点通知", 1, "超级岛转换", "灵动岛 内容转换");
        add(entries, "forcePackages", "强制转换超级岛应用", 1, "超级岛转换", "应用名单 白名单 强制转换");
        add(entries, "limitWidth", "限制焦点通知宽度", 1, "显示宽度与时长", "显示长度 限宽");
        add(entries, "width", "最大宽度", 1, "显示宽度与时长", "竖屏显示宽度 长度");
        add(entries, "landscapeWidth", "横屏最大宽度", 1, "显示宽度与时长", "横屏显示宽度 长度");
        add(entries, "maxDisplay", "最大显示时间", 1, "显示宽度与时长", "秒 时长 超时 到期");
        add(entries, "timeoutExempt", "最大显示时间豁免应用", 1, "显示宽度与时长", "名单 白名单 超时 不限制");
        add(entries, "iconOrder", "焦点图标优先级", 1, "图标与分隔线", "图标排序 图标顺序 图标来源排序 拖动 应用图标 焦点专用图标 通知小图标 smallIcon ticker");
        add(entries, "islandIcon", "优先使用超级岛解析图标", 1, "图标与分隔线", "图标来源 岛图标");
        add(entries, "iconTint", "超级岛解析图标与通知小图标跟随状态栏反色", 1, "图标与分隔线", "颜色 反色 着色 smallIcon");
        add(entries, "deviceTint", "设备通知图标跟随状态栏反色", 1, "图标与分隔线", "颜色 着色 设备图标");
        add(entries, "iconSize", "焦点图标尺寸", 1, "图标与分隔线", "图标大小 宽高 缩放");
        add(entries, "iconGap", "图标与文字间距", 1, "图标与分隔线", "图文距离 空隙 左右间距");
        add(entries, "divider", "显示焦点分隔线", 1, "图标与分隔线", "竖线 装饰");
        add(entries, "dividerStyle", "分隔线样式", 1, "图标与分隔线", "圆点 斜线 短横线 竖线");
        add(entries, "dividerStart", "分隔线前间距（时间侧）", 1, "图标与分隔线", "左间距 时间距离 负间距");
        add(entries, "dividerEnd", "分隔线后间距（焦点侧）", 1, "图标与分隔线", "右间距 焦点距离 负间距");
        add(entries, "dividerWidth", "分隔线粗细", 1, "图标与分隔线", "线宽 厚度");
        add(entries, "contentMode", "焦点内容模式", 1, "内容解析与拼接", "解析 全文本 药丸 左右拼接");
        add(entries, "richText", "渲染彩色字体（<font color>）", 1, "内容解析与拼接", "颜色 富文本 HTML");
        add(entries, "generalSeparator", "普通内容连接符", 1, "内容解析与拼接", "分隔符 分隔字 符号");
        add(entries, "sideSeparator", "左右区域连接符", 1, "内容解析与拼接", "分隔符 分隔字 符号");
        add(entries, "banner", "点击焦点通知展开横幅", 1, "点击与手势", "短按 实验性 弹窗 卡片");
        add(entries, "bannerBackground", "通知横幅背景", 1, "点击与手势", "纯色 普通通知 背景颜色");
        add(entries, "advancedMaterial", "高级材质接入", 1, "点击与手势", "玻璃 毛玻璃 模糊 混色 GLASS BLUR 默认关闭");
        add(entries, "longPress", "长按焦点通知模拟通知列表点击", 1, "点击与手势", "长按打开 实验性");
        add(entries, "longPressTime", "长按时间", 1, "点击与手势", "长按延迟 秒");
        add(entries, "swipe", "左右滑动切换和收起焦点通知", 1, "点击与手势", "手势 滑动恢复 实验性");
        add(entries, "adaptiveWidth", "空间不足时仅显示焦点图标", 1, "点击与手势", "自动收窄 OS4 大字体 横屏 图标");
        add(entries, "legacyClick", "旧版：打开通知内容", 1, "点击与手势", "直接打开 点击兼容");
        add(entries, "rowClick", "直接打开失败时模拟通知列表点击", 1, "点击与手势", "兜底 点击兼容");
        add(entries, "media", "启用媒体焦点通知", 1, "媒体焦点（实验性）", "音乐 播放 实验性");
        add(entries, "mediaBrief", "媒体焦点简短显示", 1, "媒体焦点（实验性）", "音乐 精简 正文");
        add(entries, "mediaFields", "媒体显示字段与排序", 1, "媒体焦点（实验性）", "标题 副标题 应用名 播放状态 字段选择 拖动顺序");
        add(entries, "mediaClick", "点击媒体焦点通知", 1, "媒体焦点（实验性）", "媒体横幅 直接流转");
        add(entries, "mediaCast", "无缝流转入口", 1, "媒体焦点（实验性）", "小米妙播 安卓原生 音频输出");
        add(entries, "hideIcons", "隐藏其他通知图标（HyperOS 4）", 1, "状态栏显示", "OS4 HyperOS4 通知栏 图标隐藏");
        add(entries, "hideTimeNormal", "普通焦点隐藏时间", 1, "状态栏显示", "状态栏 时钟 隐藏时间");
        add(entries, "hideTimeMedia", "媒体焦点隐藏时间", 1, "状态栏显示", "状态栏 时钟 音乐 隐藏时间");
        add(entries, "hideTimeDevice", "设备通知隐藏时间", 1, "状态栏显示", "状态栏 时钟 隐藏时间");
        add(entries, "touch", "自定义焦点触控范围", 1, "触控范围", "点击区域 触摸宽度 手势范围");
        add(entries, "touchWidth", "竖屏触控宽度", 1, "触控范围", "点击区域 触摸范围");
        add(entries, "touchLandscape", "横屏触控宽度", 1, "触控范围", "点击区域 触摸范围");
        add(entries, "bounce", "启用往返滚动", 1, "滚动与兼容", "文字 跑马灯 循环");
        add(entries, "delay", "滚动启动延迟", 1, "滚动与兼容", "文字 跑马灯 等待 秒");
        add(entries, "retry", "兼容重试模式", 1, "滚动与兼容", "滚动刷新异常");
        add(entries, "elementAnimation", "拦截应用退出/点击时的元素动画（OS3）", 1, "滚动与兼容", "OS3 HyperOS3 转场 图标残留");
        add(entries, "export", "导出配置文件", 2, "配置文件", "备份 保存 JSON");
        add(entries, "import", "导入配置文件", 2, "配置文件", "备份 恢复 JSON");
        entries.add(new Entry("debugProperty", "覆盖 feature.island.debug", 1, "内部兼容开关", "Debug 超级岛 属性", true));
        entries.add(new Entry("debugFeature", "禁用 FEATURE_DYNAMIC_ISLAND", 1, "内部兼容开关", "Debug 超级岛 特性缓存", true));
        ENTRIES = Collections.unmodifiableList(entries);
    }

    private static void add(List<Entry> entries, String id, String title, int page, String section, String aliases) {
        entries.add(new Entry(id, title, page, section, aliases, false));
    }

    static List<Entry> search(String query, boolean includeDebug) {
        String normalized = normalize(query);
        String[] tokens = normalized.isEmpty() ? new String[0] : normalized.split(" ");
        List<Entry> matches = new ArrayList<>();
        for (Entry entry : ENTRIES) {
            if (entry.debugOnly && !includeDebug) continue;
            boolean matchesAll = true;
            for (String token : tokens) {
                if (!entry.searchable.contains(token)) { matchesAll = false; break; }
            }
            if (matchesAll) matches.add(entry);
        }
        // 稳定排序保留同分项的原页面顺序；精确标题优先于别名命中。
        if (!normalized.isEmpty()) matches.sort(Comparator.comparingInt(entry -> entry.rank(normalized)));
        return Collections.unmodifiableList(matches);
    }

    private static String normalize(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Z}]+", " ").trim();
    }

    private static String pageName(int page) {
        return page == 0 ? "主页" : page == 1 ? "高级" : "关于";
    }

    private SettingsSearchIndex() { }
}
