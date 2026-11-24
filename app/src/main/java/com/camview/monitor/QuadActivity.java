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

/**
 * 横屏四路同屏（2×2），支持左右翻页一次换 4 路。
 * 进入方式：① 更多菜单手动选 4 路；② 主界面转到横屏时自动进入。
 */
public class QuadActivity extends Activity {

    private final List<CameraStream> streams = new ArrayList<>();
    private final List<TextView> statusViews = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private String groupName;
    private int base = 0;
    private FrameLayout root;
    private LinearLayout grid;
    private TextView prevBtn, nextBtn, pageLabel;
    private boolean bound = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        groupName = getIntent().getStringExtra("group");
        if (groupName == null) groupName = App.cfg().currentGroup;
        base = getIntent().getIntExtra("start", App.cfg().currentPage * App.cfg().itemsPerPage);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        buildChrome();
        setContentView(root);
        loadPage();

        ui.post(new Runnable() {
            @Override
            public void run() {
                updateStatus();
                ui.postDelayed(this, 800);
            }
        });
    }

    private void buildChrome() {
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

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setGravity(Gravity.CENTER);
        nav.setBackgroundColor(0x66000000);
        nav.setPadding(Ui.dp(this, 6), Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 2));

        prevBtn = makeNavButton("◀ 上 4 个");
        prevBtn.setOnClickListener(v -> turnPage(-1));
        pageLabel = new TextView(this);
        pageLabel.setTextColor(0xFFC9D1D9);
        pageLabel.setTextSize(12);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        nextBtn = makeNavButton("下 4 个 ▶");
        nextBtn.setOnClickListener(v -> turnPage(1));

        nav.addView(prevBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 34)));
        nav.addView(pageLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 34)));
        nav.addView(nextBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 34)));

        FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        nlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        nlp.bottomMargin = Ui.dp(this, 14);

        root.addView(nav, nlp);
        root.addView(close, clp);
    }

    private TextView makeNavButton(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(0xFF58A6FF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        return t;
    }

    private List<String> groupIps() {
        List<String> list = App.cfg().groups.get(groupName);
        return list == null ? new ArrayList<String>() : list;
    }

    private void turnPage(int dir) {
        List<String> ips = groupIps();
        int total = ips.size();
        int target = base + dir * 4;
        if (target < 0) return;
        if (target >= total) return;
        base = target;
        loadPage();
    }

    /** 按 base 取最多 4 路，重建界面并挂上播放器 */
    private void loadPage() {
        List<String> ips = groupIps();
        int shown = Math.max(0, Math.min(4, ips.size() - base));

        for (CameraStream s : streams) {
            try { s.release(); } catch (Throwable ignored) { }
        }
        streams.clear();
        statusViews.clear();
        bound = false;
        if (grid != null) root.removeView(grid);

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        root.addView(grid, 0, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(line, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
            for (int c = 0; c < 2; c++) {
                int idx = row * 2 + c;
                String ip = idx < shown ? ips.get(base + idx) : null;
                line.addView(buildCell(ip), new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            }
        }

        for (int i = 0; i < shown; i++) {
            CameraInfo info = App.cfg().findCamera(ips.get(base + i));
            if (info == null) continue;
            CameraStream s = new CameraStream(this, App.cfg(), info);
            s.rotation = info.rotation;
            s.flipH = info.flipH;
            s.flipV = info.flipV;
            streams.add(s);
        }

        int pageNo = base / 4 + 1;
        int pageTotal = Math.max(1, (int) Math.ceil(ips.size() / 4.0));
        pageLabel.setText("第 " + pageNo + " / " + pageTotal + " 页 · "
                + (ips.isEmpty() ? 0 : base + 1) + "–" + Math.min(base + 4, ips.size())
                + " / " + ips.size());
        prevBtn.setAlpha(base > 0 ? 1f : 0.35f);
        nextBtn.setAlpha(base + 4 < ips.size() ? 1f : 0.35f);

        root.post(new Runnable() {
            @Override
            public void run() {
                bindStreams();
            }
        });
    }

    private View buildCell(final String ip) {
        FrameLayout cell = new FrameLayout(this);
        cell.setBackgroundColor(0xFF010409);

        FrameLayout holder = new FrameLayout(this);
        cell.addView(holder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView label = new TextView(this);
        label.setText(ip == null ? "" : ip);
        label.setTextColor(0xFFC9D1D9);
        label.setTextSize(12);
        label.setPadding(Ui.dp(this, 6), Ui.dp(this, 3), Ui.dp(this, 6), Ui.dp(this, 3));
        label.setBackgroundColor(0x88000000);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        cell.addView(label, lp);

        TextView status = new TextView(this);
        status.setText("连接中…");
        status.setTextColor(0xFF8B949E);
        status.setTextSize(12);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.CENTER;
        cell.addView(status, sp);
        statusViews.add(status);

        if (ip != null) {
            android.view.TextureView tv = new android.view.TextureView(this);
            holder.addView(tv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            cell.setTag(tv);
            final String target = ip;
            cell.setOnClickListener(v -> {
                Intent i = new Intent(QuadActivity.this, FullscreenActivity.class);
                i.putExtra("ip", target);
                startActivity(i);
            });
        }
        return cell;
    }

    private void bindStreams() {
        if (bound || root == null || grid == null) return;
        List<FrameLayout> cells = new ArrayList<>();
        for (int r = 0; r < grid.getChildCount(); r++) {
            LinearLayout line = (LinearLayout) grid.getChildAt(r);
            for (int c = 0; c < line.getChildCount(); c++) {
                if (line.getChildAt(c) instanceof FrameLayout) {
                    cells.add((FrameLayout) line.getChildAt(c));
                }
            }
        }
        int k = 0;
        for (int i = 0; i < cells.size() && k < streams.size(); i++) {
            FrameLayout cell = cells.get(i);
            android.view.TextureView tv = (android.view.TextureView) cell.getTag();
            if (tv == null) continue;
            FrameLayout holder = (FrameLayout) cell.getChildAt(0);
            streams.get(k).attach(tv, holder);
            k++;
        }
        bound = true;
    }

    private void updateStatus() {
        int n = Math.min(streams.size(), statusViews.size());
        for (int i = 0; i < n; i++) {
            CameraStream s = streams.get(i);
            TextView t = statusViews.get(i);
            boolean live = s.state == CameraStream.ST_LIVE
                    && (s.isVlcMode() || s.isSnapMode() || s.fps > 0.01f);
            if (live) {
                t.setVisibility(View.GONE);   // 正常出画面就什么都不显示
                continue;
            }
            t.setVisibility(View.VISIBLE);
            if (s.state == CameraStream.ST_FAILED) {
                t.setText(s.lastError == null || s.lastError.isEmpty() ? "连接失败" : s.lastError);
                t.setTextColor(0xFFF85149);
            } else {
                t.setText("连接中…");
                t.setTextColor(0xFFD29922);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (CameraStream s : streams) s.setActive(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        for (CameraStream s : streams) s.setActive(true);
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
