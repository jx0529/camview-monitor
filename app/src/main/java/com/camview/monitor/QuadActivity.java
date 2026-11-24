package com.camview.monitor;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 横屏四路同屏（2×2）。
 * 只在横屏时使用，正好铺满手机横向空间，每格一个摄像头，点任意一格进单路放大。
 */
public class QuadActivity extends Activity {

    private final List<CameraStream> streams = new ArrayList<>();
    private final List<TextView> dots = new ArrayList<>();
    private final List<TextView> names = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        List<String> ips = getIntent().getStringArrayListExtra("ips");
        if (ips == null) ips = new ArrayList<>();
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        root.addView(col, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            col.addView(line, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            for (int c = 0; c < 2; c++) {
                int idx = row * 2 + c;
                String ip = idx < ips.size() ? ips.get(idx) : null;
                View cell = buildCell(ip);
                line.addView(cell, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            }
        }

        // 顶部关闭按钮（浮层）
        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextSize(18);
        close.setTextColor(Color.WHITE);
        close.setGravity(Gravity.CENTER);
        close.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 36));
        clp.gravity = Gravity.TOP | Gravity.RIGHT;
        clp.topMargin = Ui.dp(this, 6);
        clp.rightMargin = Ui.dp(this, 6);
        close.setOnClickListener(v -> finish());
        root.addView(close, clp);

        setContentView(root);

        for (int i = 0; i < ips.size() && i < 4; i++) {
            CameraInfo info = App.cfg().findCamera(ips.get(i));
            if (info == null) continue;
            CameraStream s = new CameraStream(this, App.cfg(), info);
            s.rotation = info.rotation;
            s.flipH = info.flipH;
            s.flipV = info.flipV;
            streams.add(s);
        }

        ui.post(new Runnable() {
            @Override
            public void run() {
                updateStatus();
                ui.postDelayed(this, 800);
            }
        });
    }

    private View buildCell(final String ip) {
        FrameLayout cell = new FrameLayout(this);
        cell.setBackgroundColor(0xFF010409);
        LinearLayout.LayoutParams outer = null;

        FrameLayout holder = new FrameLayout(this);
        cell.addView(holder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView label = new TextView(this);
        label.setText(ip == null ? "未选择" : ip);
        label.setTextColor(0xFFC9D1D9);
        label.setTextSize(12);
        label.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3));
        label.setBackgroundColor(0x88000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        cell.addView(label, lp);
        names.add(label);

        TextView status = new TextView(this);
        status.setText("等待中");
        status.setTextColor(0xFF8B949E);
        status.setTextSize(12);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.CENTER;
        cell.addView(status, sp);
        dots.add(status);

        if (ip != null) {
            android.view.TextureView tv = new android.view.TextureView(this);
            holder.addView(tv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            cell.setTag(tv);
            cell.setOnClickListener(v -> {
                Intent i = new Intent(QuadActivity.this, FullscreenActivity.class);
                i.putExtra("ip", ip);
                startActivity(i);
            });
        }
        return cell;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 绑定视频视图（此时布局已完成）
        bindStreams();
        for (CameraStream s : streams) s.setActive(true);
    }

    private boolean bound = false;

    private void bindStreams() {
        if (bound || root == null) return;
        LinearLayout col = (LinearLayout) root.getChildAt(0);
        List<android.widget.FrameLayout> cells = new ArrayList<>();
        for (int r = 0; r < col.getChildCount(); r++) {
            LinearLayout line = (LinearLayout) col.getChildAt(r);
            for (int c = 0; c < line.getChildCount(); c++) {
                if (line.getChildAt(c) instanceof android.widget.FrameLayout) {
                    cells.add((android.widget.FrameLayout) line.getChildAt(c));
                }
            }
        }
        for (int k = 0; k < streams.size() && k < cells.size(); k++) {
            android.widget.FrameLayout cell = cells.get(k);
            android.view.TextureView tv = (android.view.TextureView) cell.getTag();
            android.widget.FrameLayout holder = (android.widget.FrameLayout) cell.getChildAt(0);
            if (tv != null) streams.get(k).attach(tv, holder);
        }
        bound = true;
    }

    private void updateStatus() {
        int n = Math.min(streams.size(), dots.size());
        for (int i = 0; i < n; i++) {
            CameraStream s = streams.get(i);
            TextView t = dots.get(i);
            String txt;
            int color;
            if (s.state == CameraStream.ST_LIVE) {
                if (s.isVlcMode()) {
                    txt = s.streamRate > 0.5f ? String.format(Locale.US, "%.0f fps", s.streamRate) : "实时";
                } else {
                    txt = String.format(Locale.US, "%.1f fps", s.fps);
                }
                color = 0xFF3FB950;
            } else if (s.state == CameraStream.ST_FAILED) {
                txt = s.lastError == null || s.lastError.isEmpty() ? "连接失败" : s.lastError;
                color = 0xFFF85149;
            } else {
                txt = "连接中…";
                color = 0xFFD29922;
            }
            t.setText(txt);
            t.setTextColor(color);
            t.setVisibility(s.state == CameraStream.ST_LIVE && s.fps > 0.01f ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (CameraStream s : streams) s.setActive(false);
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        for (CameraStream s : streams) {
            try { s.release(); } catch (Throwable ignored) { }
        }
        streams.clear();
        super.onDestroy();
    }
}
