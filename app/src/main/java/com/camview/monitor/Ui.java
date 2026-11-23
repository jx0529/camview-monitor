package com.camview.monitor;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class Ui {

    public static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                c.getResources().getDisplayMetrics()));
    }

    public static AlertDialog.Builder dialog(Context c) {
        AlertDialog.Builder b = new AlertDialog.Builder(c, R.style.Theme_CamView_Dialog);
        return b;
    }

    public static void styleDialog(AlertDialog d) {
        // 主题里已经设置了圆角深色背景（bg_dialog），这里只保证窗口不透明
        Window w = d.getWindow();
        if (w != null) {
            w.setDimAmount(0.6f);
        }
    }

    public static void toast(Context c, String msg) {
        Toast.makeText(c, msg, Toast.LENGTH_SHORT).show();
    }

    public static TextView title(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(0xFFC9D1D9);
        t.setTextSize(17);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(dp(c, 20), dp(c, 18), dp(c, 20), dp(c, 8));
        return t;
    }

    public static TextView label(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(0xFF8B949E);
        t.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 10);
        lp.leftMargin = dp(c, 20);
        lp.rightMargin = dp(c, 20);
        t.setLayoutParams(lp);
        return t;
    }

    public static EditText input(Context c, String value, String hint, boolean password) {
        EditText e = new EditText(c);
        e.setBackgroundResource(R.drawable.bg_input);
        e.setTextColor(0xFFC9D1D9);
        e.setHintTextColor(0xFF8B949E);
        e.setTextSize(14);
        e.setHint(hint);
        if (value != null) e.setText(value);
        e.setSingleLine(true);
        if (password) e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        else e.setInputType(InputType.TYPE_CLASS_TEXT);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 46));
        lp.leftMargin = dp(c, 20);
        lp.rightMargin = dp(c, 20);
        lp.topMargin = dp(c, 4);
        e.setLayoutParams(lp);
        return e;
    }

    public static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundResource(R.drawable.bg_dialog);
        l.setPadding(0, dp(c, 6), 0, dp(c, 12));
        return l;
    }

    public static LinearLayout buttonRow(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.END);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 12);
        lp.rightMargin = dp(c, 16);
        l.setLayoutParams(lp);
        return l;
    }

    /**
     * 底部/列表式操作面板，替代桌面版的右键菜单
     */
    public static AlertDialog actionSheet(Context c, String title, String[] items,
                                          final OnItem onItem) {
        AlertDialog.Builder b = dialog(c);
        if (title != null) b.setTitle(title);
        b.setItems(items, (d, which) -> {
            if (onItem != null) onItem.onClick(which, items[which]);
        });
        b.setNegativeButton("取消", null);
        AlertDialog d = b.create();
        styleDialog(d);
        d.show();
        return d;
    }

    public interface OnItem {
        void onClick(int index, String text);
    }

    public static void centerToast(Context c, String msg) {
        Toast t = Toast.makeText(c, msg, Toast.LENGTH_SHORT);
        t.setGravity(Gravity.CENTER, 0, 0);
        t.show();
    }

    public static void divider(Context c, LinearLayout parent) {
        View v = new View(c);
        v.setBackgroundColor(0xFF30363D);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.topMargin = dp(c, 8);
        v.setLayoutParams(lp);
        parent.addView(v);
    }
}
