package com.hyperos3.focustestnotifier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.List;

/**
 * Posts focus/island notifications that mirror the real field notifications.
 *
 * <p>Extras are copied from the captured {@code dumpsys notification --noredact} output: the param
 * payload itself, the notification's own {@code miui.focus.rv*} RemoteViews set, {@code
 * miui.focus.pics}, and the scalar flags the travel notification carries.
 */
final class FocusTestNotifier {
    static final String CHANNEL_ID = "focus_test";

    private static final String[] REMOTE_VIEWS_KEYS = {
            "miui.focus.rv", "miui.focus.rvNight", "miui.focus.rvBar", "miui.focus.rvBarNight",
            "miui.focus.rvAod", "miui.focus.rv.fullAod", "miui.focus.rv.tiny",
            "miui.focus.rv.tinyNight", "miui.focus.rv.island.expand"
    };

    /** One test case. Only these inputs change between buttons. */
    static final class Spec {
        final int id;
        final String label;
        final String expected;
        final String paramKey;
        final String payload;
        final String channelType;
        final boolean remoteViews;
        final boolean explicitFocus;
        final boolean islandDispatcher;

        Spec(int id, String label, String expected, String paramKey, String payload,
             String channelType, boolean remoteViews, boolean explicitFocus,
             boolean islandDispatcher) {
            this.id = id;
            this.label = label;
            this.expected = expected;
            this.paramKey = paramKey;
            this.payload = payload;
            this.channelType = channelType;
            this.remoteViews = remoteViews;
            this.explicitFocus = explicitFocus;
            this.islandDispatcher = islandDispatcher;
        }
    }

    private FocusTestNotifier() {
    }

    /**
     * The shipped test matrix.
     *
     * <p>{@code miui.focus.isFocus} is deliberately <em>not</em> set on most cases. The module only
     * converts a notification that it does not already consider an original focus notification, or
     * that is whitelisted; {@code isFocus=true} (and any {@code miui.focus.rv}) makes it look
     * original, so those cases require adding this package to the module's focus whitelist first.
     * The plain cases therefore work with no configuration at all.
     */
    static List<Spec> specs() {
        List<Spec> specs = new ArrayList<>();
        specs.add(new Spec(1, "出行通知（不加白名单即可）",
                "胶囊显示模块解析文本：D8396·检票口",
                "miui.focus.param.custom", FocusPayloads.TRAVEL_PARAM_CUSTOM,
                "travel01", false, false, false));
        specs.add(new Spec(2, "出行通知（去掉 aodTitle）",
                "完整模式应为 D8396·检票口（无重复）",
                "miui.focus.param.custom", FocusPayloads.TRAVEL_WITHOUT_AOD_TITLE,
                "travel01", false, false, false));
        specs.add(new Spec(3, "出行通知（原样，带自带 RemoteViews）【需白名单】",
                "胶囊显示 App 自带布局「检票口 检票口」，解析文本被遮住",
                "miui.focus.param.custom", FocusPayloads.TRAVEL_PARAM_CUSTOM,
                "travel01", true, false, false));
        specs.add(new Spec(4, "出行通知（带 isFocus，模拟真实通知）【需白名单】",
                "复现真实出行通知：必须加白名单才会转换",
                "miui.focus.param.custom", FocusPayloads.TRAVEL_PARAM_CUSTOM,
                "travel01", false, true, false));
        specs.add(new Spec(5, "超级岛（param_v2，PARAMS 路径）",
                "应走原生模板路径；解析文本为 欢迎使用·HyperIsland",
                "miui.focus.param", FocusPayloads.ISLAND_PARAM,
                null, false, false, true));
        specs.add(new Spec(6, "左右分区（对比分隔符）",
                "完整模式：北京南·上海虹桥；紧凑模式：北京南 | 上海虹桥",
                "miui.focus.param", FocusPayloads.ISLAND_SIDES,
                null, false, false, false));
        specs.add(new Spec(7, "仅紧凑区文本",
                "完整与紧凑模式均应得到：进站中",
                "miui.focus.param", FocusPayloads.ISLAND_SMALL_ONLY,
                null, false, false, false));
        return specs;
    }

    static void ensureChannel(Context context) {
        NotificationManager manager = manager(context);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "焦点通知测试",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("FocusRestore 手动测试通知");
        manager.createNotificationChannel(channel);
    }

    static void post(Context context, Spec spec) {
        NotificationManager manager = manager(context);
        if (manager == null) return;
        ensureChannel(context);

        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_focus_test)
                .setContentTitle(spec.label)
                .setContentText("FocusRestore 测试通知")
                .setAutoCancel(true)
                .setWhen(System.currentTimeMillis());

        Bundle extras = builder.getExtras();
        extras.putString(spec.paramKey, spec.payload);
        // Opt-in only: isFocus=true marks the notification as an original focus notification, which
        // the module refuses to convert unless the package is whitelisted.
        if (spec.explicitFocus) {
            extras.putBoolean("miui.focus.isFocus", true);
        }
        extras.putBoolean("miui.focus.enableAlert", true);
        extras.putBoolean("miui.focus.isPromoted", true);
        extras.putString("miui.focus.reopen", "reopen");
        extras.putString("notif_extend_items", "focus_restore_test#" + spec.id);
        if (spec.channelType != null) {
            extras.putString("miui.focus.param.channeltype", spec.channelType);
        }
        if (spec.islandDispatcher) {
            // Mirrors the SystemUI HyperIsland notification's own extras.
            extras.putString("miui.bigIsland.effect.src", "outer_glow");
            extras.putString("hyperisland.owner", "com.hyperos3.focustestnotifier");
        }
        extras.putBundle("miui.focus.pics", pictures(context));
        extras.putParcelable("miui.appIcon", Icon.createWithResource(context, R.drawable.ic_focus_test));
        extras.putString("miui.focus.pic_ticker", "miui.focus.pic_ticker");
        extras.putString("miui.focus.pic_ticker_dark", "miui.focus.pic_ticker");

        if (spec.remoteViews) {
            RemoteViews views = remoteViews(context);
            for (String key : REMOTE_VIEWS_KEYS) {
                extras.putParcelable(key, views);
            }
        }

        manager.notify(spec.id, builder.build());
    }

    static void cancelAll(Context context) {
        NotificationManager manager = manager(context);
        if (manager == null) return;
        for (Spec spec : specs()) {
            manager.cancel(spec.id);
        }
    }

    /**
     * The notification application's own focus layout. Both the literal field names
     * ({@code island_pic}) and the {@code miui.focus.pic_} form the module accepts are published, so
     * whichever reference a payload uses can resolve.
     */
    private static RemoteViews remoteViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.focus_test_rv);
        views.setTextViewText(R.id.rv_title, "检票口 检票口");
        views.setTextViewText(R.id.rv_sub, "D8396");
        return views;
    }

    private static Bundle pictures(Context context) {
        Bundle pictures = new Bundle();
        Icon icon = Icon.createWithResource(context, R.drawable.ic_focus_test);
        String[] keys = {
                "island_pic", "island_pic_small", "miui.focus.travel_aod_pic",
                "miui.focus.pic_island_pic", "miui.focus.pic_island_pic_small",
                "miui.focus.pic_travel_aod_pic", "miui.focus.pic_ticker",
                "miui.focus.pic_key_island_icon", "miui.focus.pic_key_focus_icon",
                "miui.focus.pic_aod"
        };
        for (String key : keys) {
            pictures.putParcelable(key, icon);
        }
        return pictures;
    }

    private static NotificationManager manager(Context context) {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }
}
