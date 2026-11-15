package ru.reader.eink;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.widget.Button;

final class Ui {
    static final int WHITE = Color.WHITE;
    static final int INK = Color.BLACK;
    static final int SECONDARY = Color.rgb(68, 68, 68);
    static final int HAIR = Color.rgb(138, 138, 138);
    static final int FILL = Color.rgb(230, 230, 230);
    static final int TEXT_TITLE = 30;
    static final int TEXT_BODY = 16;
    static final int TEXT_SMALL = 14;
    static final int LINE = 2;
    static final int LINE_THICK = 3;
    static final int TOUCH = 48;
    static final int RADIUS = 6;

    private Ui() {
    }

    static GradientDrawable box(Context context, int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setStroke(dp(context, LINE), stroke);
        drawable.setCornerRadius(dp(context, RADIUS));
        return drawable;
    }

    static void styleButton(Button button) {
        Context context = button.getContext();
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_pressed},
                box(context, INK, INK));
        background.addState(new int[] {-android.R.attr.state_enabled},
                box(context, WHITE, HAIR));
        background.addState(new int[] {}, box(context, WHITE, INK));
        button.setBackgroundDrawable(background);
        button.setTextColor(new ColorStateList(
                new int[][] {{android.R.attr.state_pressed},
                        {-android.R.attr.state_enabled}, {}},
                new int[] {WHITE, HAIR, INK}));
        button.setAllCaps(false);
        button.setTextSize(TEXT_BODY);
        button.setMinHeight(dp(context, TOUCH));
        button.setMinimumHeight(dp(context, TOUCH));
        button.setPadding(dp(context, 8), 0, dp(context, 8), 0);
        button.setFocusable(true);
    }

    static void styleTab(Button button, boolean selected) {
        Context context = button.getContext();
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_pressed},
            box(context, INK, INK));
        background.addState(new int[] {}, new ColorDrawable(Color.TRANSPARENT));
        button.setBackgroundDrawable(background);
        button.setTextColor(new ColorStateList(
                new int[][] {{android.R.attr.state_pressed}, {}},
                new int[] {WHITE, selected ? INK : SECONDARY}));
        button.setTypeface(null, selected ? android.graphics.Typeface.BOLD
                : android.graphics.Typeface.NORMAL);
        button.setPaintFlags(selected ? button.getPaintFlags()
                | android.graphics.Paint.UNDERLINE_TEXT_FLAG
                : button.getPaintFlags() & ~android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        button.setAllCaps(false);
        button.setTextSize(TEXT_BODY);
        button.setMinHeight(dp(context, TOUCH));
        button.setMinimumHeight(dp(context, TOUCH));
    }

    static View divider(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(HAIR);
        return divider;
    }

    static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}