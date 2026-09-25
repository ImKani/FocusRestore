package com.hyperos3.focustestnotifier;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Manual test harness for FocusRestore. Each button posts one captured focus payload so the module's
 * parsing, pill text and banner behaviour can be re-checked in seconds instead of waiting for a
 * genuine travel notification.
 */
public class MainActivity extends Activity {
    private static final int REQUEST_POST_NOTIFICATIONS = 1001;
    /** Long enough for a cancel to reach SystemUI before the replacement notification is posted. */
    private static final long CLEAR_SETTLE_MS = 400L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        FocusTestNotifier.ensureChannel(this);
        requestNotificationPermissionIfNeeded();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        root.addView(text("FocusRestore 焦点通知测试", 20, true));
        root.addView(text("包名：" + getPackageName()
                + "\n建议先在模块设置里把该包名加入焦点白名单，再逐条测试。", 13, false));
        root.addView(text("提示：「焦点内容模式」决定完整/紧凑解析，切换该设置可直接对比同一载荷的输出。",
                13, false));

        // Several focus notifications from one package coexist, and the ROM keeps showing the first
        // one it picked, so posting a second payload without clearing the first does not switch the
        // pill. Clearing by default makes each button a clean single-notification test.
        final CheckBox clearFirst = new CheckBox(this);
        clearFirst.setText("发送前先清除其它测试通知（推荐）");
        clearFirst.setChecked(true);
        root.addView(clearFirst, matchWrap());

        for (final FocusTestNotifier.Spec spec : FocusTestNotifier.specs()) {
            Button button = new Button(this);
            button.setText(spec.label);
            button.setAllCaps(false);
            button.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    if (clearFirst.isChecked()) {
                        // The removal has to reach SystemUI before the new post, otherwise the pill
                        // may still be bound to the previous notification when this one arrives.
                        FocusTestNotifier.cancelAll(MainActivity.this);
                        view.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                FocusTestNotifier.post(MainActivity.this, spec);
                            }
                        }, CLEAR_SETTLE_MS);
                    } else {
                        FocusTestNotifier.post(MainActivity.this, spec);
                    }
                    Toast.makeText(MainActivity.this, "已发送：" + spec.label, Toast.LENGTH_SHORT).show();
                }
            });
            root.addView(button, matchWrap());

            TextView expected = text("预期：" + spec.expected, 12, false);
            expected.setPadding(dp(8), 0, 0, dp(12));
            root.addView(expected, matchWrap());
        }

        Button cancel = new Button(this);
        cancel.setText("取消全部测试通知");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                FocusTestNotifier.cancelAll(MainActivity.this);
            }
        });
        root.addView(cancel, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},
                REQUEST_POST_NOTIFICATIONS);
    }

    private TextView text(String value, int sizeSp, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (bold) view.setTypeface(view.getTypeface(), android.graphics.Typeface.BOLD);
        view.setTextIsSelectable(true);
        if (!TextUtils.isEmpty(value) && !bold) view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private static ViewGroup.LayoutParams matchWrap() {
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
