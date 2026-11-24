package com.camview.monitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 摄像头体检：分批（每批 6 路）真实连接，能连上（RTSP 握手成功）算正常，
 * 连不上/报错判为连接失败，检测结束后可勾选删除。
 */
public class CheckActivity extends Activity {

    private static final int PER_BATCH = 6;
    /** 每批最长等待（连接判定） */
    private static final long BATCH_WAIT_MS = 12000L;
    /** 轮询间隔：一路一旦连上/失败就继续等其它路，全部有结果就立刻下一批 */
    private static final long POLL_MS = 1200L;

    private AppConfig cfg;
    private TextView progressLabel, resultLabel, headLabel;
    private ProgressBar bar;
    private LinearLayout gridBox, resultBox;
    private ScrollView resultScroll;

    private final List<String> queue = new ArrayList<>();
    private final List<String> bad = new ArrayList<>();
    private final List<String> okList = new ArrayList<>();
    private final java.util.Map<String, String> badReasons = new java.util.LinkedHashMap<>();
    private final List<CameraStream> live = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int cursor = 0;
    private boolean stopped = false;
    private boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = App.cfg();
        boolean all = getIntent().getBooleanExtra("all", false);
        List<String> ips = all ? cfg.allIps() : cfg.groups.get(cfg.currentGroup);
        if (ips != null) {
            for (String ip : ips) if (!queue.contains(ip)) queue.add(ip);
        }
        Collections.sort(queue, (a, b) -> Long.compare(AppConfig.ipValue(a), AppConfig.ipValue(b)));
        setContentView(buildUi());
        if (queue.isEmpty()) {
            Ui.toast(this, "没有可检测的摄像头");
            finish();
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0D1117);
        root.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));

        headLabel = new TextView(this);
        headLabel.setText("🩺 摄像头体检 · 共 " + queue.size() + " 路");
        headLabel.setTextColor(0xFFC9D1D9);
        headLabel.setTextSize(17);
        headLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(headLabel);

        TextView tip = new TextView(this);
        tip.setText("每批 " + PER_BATCH + " 路同时连接，能连上（RTSP 握手成功）就算正常；"
                + "连不上或报错的判为连接失败。全部有结果会立刻进入下一批，最长等 "
                + (BATCH_WAIT_MS / 1000) + " 秒。");
        tip.setTextColor(0xFF8B949E);
        tip.setTextSize(11);
        tip.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 6));
        root.addView(tip);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(queue.size());
        bar.setProgress(0);
        bar.setProgressTintList(ColorStateList.valueOf(0xFF58A6FF));
        root.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 8)));

        progressLabel = new TextView(this);
        progressLabel.setTextColor(0xFF8B949E);
        progressLabel.setTextSize(12);
        progressLabel.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4));
        progressLabel.setText("准备开始");
        root.addView(progressLabel);

        // 6 格实时预览（2 行 3 列）
        gridBox = new LinearLayout(this);
        gridBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(gridBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 150)));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        root.addView(btnRow);

        TextView startBtn = actionButton("▶ 开始检测", 0xFF3FB950);
        startBtn.setOnClickListener(v -> {
            if (running) return;
            startBtn.setAlpha(0.4f);
            startCheck();
        });
        btnRow.addView(startBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f));

        TextView stopBtn = actionButton("■ 停止", 0xFFC9D1D9);
        stopBtn.setOnClickListener(v -> {
            stopped = true;
            progressLabel.setText("正在停止…");
        });
        btnRow.addView(stopBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 40), 1f));

        resultLabel = new TextView(this);
        resultLabel.setTextColor(0xFF58A6FF);
        resultLabel.setTextSize(13);
        resultLabel.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        resultLabel.setText("检测结果");
        root.addView(resultLabel);

        resultScroll = new ScrollView(this);
        resultScroll.setBackgroundResource(R.drawable.bg_panel);
        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        resultBox.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        resultScroll.addView(resultBox, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(resultScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(0, Ui.dp(this, 8), 0, 0);
        root.addView(bottom);

        TextView delBtn = actionButton("🗑 删除选中的失败设备", 0xFFF85149);
        delBtn.setOnClickListener(v -> deleteSelected());
        bottom.addView(delBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f));

        TextView closeBtn = actionButton("关闭", 0xFFC9D1D9);
        closeBtn.setOnClickListener(v -> finish());
        bottom.addView(closeBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f));

        return root;
    }

    private TextView actionButton(String text, int color) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(R.drawable.bg_toolbar_button);
        return t;
    }

    // ==================== 检测流程 ====================

    private void startCheck() {
        running = true;
        stopped = false;
        bad.clear();
        okList.clear();
        badReasons.clear();
        cursor = 0;
        resultBox.removeAllViews();
        buildGrid();
        checkBatch();
    }

    private void buildGrid() {
        gridBox.removeAllViews();
        for (int r = 0; r < 2; r++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            gridBox.addView(line, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            for (int c = 0; c < 3; c++) {
                FrameLayout cell = new FrameLayout(this);
                cell.setBackgroundColor(0xFF010409);
                android.view.TextureView tv = new android.view.TextureView(this);
                cell.addView(tv, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                TextView tag = new TextView(this);
                tag.setText("");
                tag.setTextColor(0xFFC9D1D9);
                tag.setTextSize(10);
                tag.setPadding(Ui.dp(this, 3), 1, Ui.dp(this, 3), 1);
                tag.setBackgroundColor(0x88000000);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.TOP | Gravity.LEFT;
                cell.addView(tag, lp);
                cell.setTag(tv);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.MATCH_PARENT, 1f);
                clp.leftMargin = 2;
                clp.rightMargin = 2;
                line.addView(cell, clp);
            }
        }
    }

    private List<FrameLayout> cells() {
        List<FrameLayout> out = new ArrayList<>();
        for (int r = 0; r < gridBox.getChildCount(); r++) {
            LinearLayout line = (LinearLayout) gridBox.getChildAt(r);
            for (int c = 0; c < line.getChildCount(); c++) {
                out.add((FrameLayout) line.getChildAt(c));
            }
        }
        return out;
    }

    private void checkBatch() {
        if (stopped) {
            finishCheck("已停止");
            return;
        }
        if (cursor >= queue.size()) {
            finishCheck("检测完成");
            return;
        }
        List<CameraStream> prev = new ArrayList<>(live);
        live.clear();
        for (CameraStream s : prev) {
            try { s.release(); } catch (Throwable ignored) { }
        }

        int shown = Math.min(PER_BATCH, queue.size() - cursor);
        final List<String> batchIps = new ArrayList<>();
        List<FrameLayout> cells = cells();
        for (int i = 0; i < cells.size(); i++) {
            FrameLayout cell = cells.get(i);
            TextView tag = (TextView) cell.getChildAt(1);
            android.view.TextureView tv = (android.view.TextureView) cell.getTag();
            if (i >= shown) {
                tag.setText("");
                continue;
            }
            String ip = queue.get(cursor + i);
            batchIps.add(ip);
            tag.setText(ip);
            CameraInfo info = cfg.findCamera(ip);
            if (info == null) info = cfg.getOrCreateCamera(ip);
            CameraStream s = new CameraStream(this, cfg, info);
            s.rotation = info.rotation;
            s.flipH = info.flipH;
            s.flipV = info.flipV;
            live.add(s);
            final CameraStream fs = s;
            final android.view.TextureView ftv = tv;
            final FrameLayout fcell = cell;
            cell.post(new Runnable() {
                @Override
                public void run() {
                    FrameLayout holder = new FrameLayout(CheckActivity.this);
                    holder.setLayoutParams(new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
                    fcell.addView(holder, 0);
                    fs.attach(ftv, holder);
                    fs.setActive(true);
                }
            });
        }

        progressLabel.setText("连接中 " + (cursor + shown) + " / " + queue.size()
                + " · 已失败 " + bad.size());

        final long deadline = System.currentTimeMillis() + BATCH_WAIT_MS;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                boolean allDecided = true;
                for (CameraStream s : live) {
                    if (s.state == CameraStream.ST_CONNECTING) {
                        allDecided = false;
                        break;
                    }
                }
                if (!allDecided && System.currentTimeMillis() < deadline && !stopped) {
                    ui.postDelayed(this, POLL_MS);
                    return;
                }
                // 结算：连上（ST_LIVE）算正常，其余算连接失败
                for (int i = 0; i < batchIps.size(); i++) {
                    String ip = batchIps.get(i);
                    CameraStream s = live.get(i);
                    if (s.state == CameraStream.ST_LIVE) {
                        okList.add(ip);
                    } else {
                        bad.add(ip);
                        if (s.lastError != null && !s.lastError.isEmpty()) badReasons.put(ip, s.lastError);
                    }
                }
                cursor += batchIps.size();
                bar.setProgress(Math.min(cursor, queue.size()));
                progressLabel.setText("已检测 " + Math.min(cursor, queue.size()) + " / " + queue.size()
                        + " · 连接失败 " + bad.size());
                if (!stopped) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            checkBatch();
                        }
                    });
                }
            }
        }, POLL_MS);
    }

    private void finishCheck(String reason) {
        running = false;
        for (CameraStream s : live) {
            try { s.release(); } catch (Throwable ignored) { }
        }
        live.clear();
        progressLabel.setText(reason + " · 连接正常 " + okList.size() + " 路 · 连接失败 " + bad.size() + " 路");
        headLabel.setText("🩺 检测结果：连接失败 " + bad.size() + " / " + queue.size());
        renderResult();
        stopped = true;
    }

    private void renderResult() {
        resultBox.removeAllViews();
        resultLabel.setText("连接失败设备 " + bad.size() + " 个（勾选后可删除）");
        if (bad.isEmpty()) {
            TextView ok = new TextView(this);
            ok.setText("✔ 全部 " + queue.size() + " 路都能连上");
            ok.setTextColor(0xFF3FB950);
            ok.setTextSize(13);
            ok.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10));
            resultBox.addView(ok);
            return;
        }
        for (String ip : bad) {
            CheckBox cb = new CheckBox(this);
            String reason = badReasons.get(ip);
            cb.setText(ip + (reason == null || reason.isEmpty() ? "" : "   " + reason));
            cb.setTag(ip);
            cb.setChecked(true);
            cb.setTextColor(0xFFF85149);
            cb.setTextSize(13);
            cb.setButtonTintList(ColorStateList.valueOf(0xFFF85149));
            cb.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
            resultBox.addView(cb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void deleteSelected() {
        List<String> picked = new ArrayList<>();
        for (int i = 0; i < resultBox.getChildCount(); i++) {
            View v = resultBox.getChildAt(i);
            if (v instanceof CheckBox && ((CheckBox) v).isChecked()) {
                picked.add((String) v.getTag());
            }
        }
        if (picked.isEmpty()) {
            Ui.toast(this, "没有勾选任何设备");
            return;
        }
        AlertDialog d = Ui.dialog(this).setTitle("删除 " + picked.size() + " 个连接失败的摄像头")
                .setMessage("将从所有分组里移除以下设备：\n"
                        + picked.subList(0, Math.min(8, picked.size())).toString()
                        .replace("[", "").replace("]", "")
                        + (picked.size() > 8 ? " 等 " + picked.size() + " 个" : ""))
                .setPositiveButton("删除", (dd, w) -> {
                    for (String ip : picked) cfg.removeCamera(ip);
                    for (String ip : picked) {
                        bad.remove(ip);
                        okList.remove(ip);
                    }
                    Ui.toast(this, "已删除 " + picked.size() + " 个");
                    renderResult();
                    progressLabel.setText("已删除 " + picked.size() + " 个 · 剩余连接失败 " + bad.size() + " 路");
                })
                .setNegativeButton("取消", null)
                .create();
        Ui.styleDialog(d);
        d.show();
    }

    @Override
    protected void onDestroy() {
        stopped = true;
        ui.removeCallbacksAndMessages(null);
        for (CameraStream s : live) {
            try { s.release(); } catch (Throwable ignored) { }
        }
        live.clear();
        super.onDestroy();
    }
}
