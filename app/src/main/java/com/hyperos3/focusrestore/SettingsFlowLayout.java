/* SPDX-License-Identifier: GPL-3.0-only
 * Copyright (C) ImKani. FocusRestore — https://github.com/ImKani/FocusRestore
 */
package com.hyperos3.focusrestore;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** 设置弹窗内按内容宽度换行的原生布局。间距由容器统一管理。 */
final class SettingsFlowLayout extends ViewGroup {
    private final int horizontalGap;
    private final int verticalGap;
    private int lineWidth;

    SettingsFlowLayout(Context context, int horizontalGap, int verticalGap) {
        super(context);
        this.horizontalGap = horizontalGap;
        this.verticalGap = verticalGap;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int horizontalPadding = getPaddingLeft() + getPaddingRight();
        int verticalPadding = getPaddingTop() + getPaddingBottom();
        int limit = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED ? Integer.MAX_VALUE
                : Math.max(0, MeasureSpec.getSize(widthSpec) - horizontalPadding);
        int state = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            measureChild(child, widthSpec, heightSpec);
            state = combineMeasuredStates(state, child.getMeasuredState());
        }
        int height = arrange(limit, false);
        setMeasuredDimension(resolveSizeAndState(Math.max(getSuggestedMinimumWidth(), lineWidth + horizontalPadding),
                        widthSpec, state),
                resolveSizeAndState(Math.max(getSuggestedMinimumHeight(), height + verticalPadding),
                        heightSpec, state << MEASURED_HEIGHT_STATE_SHIFT));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        arrange(Math.max(0, right - left - getPaddingLeft() - getPaddingRight()), true);
    }

    // 测量和摆放共用断行判定。空白边距不计入一行宽度，长标签由父宽度约束。
    private int arrange(int limit, boolean place) {
        boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int origin = rtl ? getWidth() - getPaddingRight() : getPaddingLeft();
        int x = 0;
        int y = 0;
        int rowHeight = 0;
        boolean inRow = false;
        lineWidth = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            int width = child.getMeasuredWidth();
            int height = child.getMeasuredHeight();
            if (inRow && width > limit - x - horizontalGap) {
                x = 0;
                y += rowHeight + verticalGap;
                rowHeight = 0;
                inRow = false;
            }
            if (inRow) x += horizontalGap;
            if (place) {
                int childLeft = rtl ? origin - x - width : origin + x;
                int childTop = getPaddingTop() + y;
                child.layout(childLeft, childTop, childLeft + width, childTop + height);
            }
            x += width;
            lineWidth = Math.max(lineWidth, x);
            rowHeight = Math.max(rowHeight, height);
            inRow = true;
        }
        return y + rowHeight;
    }

    @Override protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }
}
