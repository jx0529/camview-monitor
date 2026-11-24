package com.camview.monitor;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** 网段扫描：并发探测 554（RTSP）端口，批量发现并添加摄像头 */
public class ScanActivity extends Activity {

    private AppConfig cfg;
    private EditText rangeInput, userInput, pwdInput;
    private TextView progressLabel, resultLabel;
    private ProgressBar bar;
    private LinearLayout resultContainer;

    private final List<String> found = new ArrayList<>();
    /** ip -> 是否勾选（已添加的不进这里） */
    private final Map<String, Boolean> selected = new LinkedHashMap<>();
    private final Map<String, TextView> rowViews = new LinkedHashMap<>();
    /** 已在配置里出现过的 IP（任何分组），扫描结果里直接忽略 */
    private final List<String> alreadyAdded = new ArrayList<>();
    /** “勾选到这里”的起点 */
    private String anchorIp = null;
    private TextView hintLabel;
    private TextView addBtnRef;
    private volatile boolean cancel = false;
    private ExecutorService pool;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = App.cfg();
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0D1117);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 24));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView t = new TextView(this);
        t.setText("🔍 扫描网段");
        t.setTextColor(0xFFC9D1D9);
        t.setTextSize(19);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setPadding(0, 0, 0, Ui.dp(this, 10));
        root.addView(t);

        root.addView(label("支持格式：192.168.1.1-254、192.168.1.1-192.168.1.254、10.0.0.0/24，多个用逗号分隔"));
        rangeInput = new EditText(this);
        rangeInput.setBackgroundResource(R.drawable.bg_input);
        rangeInput.setTextColor(0xFFC9D1D9);
        rangeInput.setHintTextColor(0xFF8B949E);
        rangeInput.setTextSize(14);
        rangeInput.setText("192.168.1.1-254");
        rangeInput.setInputType(InputType.TYPE_CLASS_TEXT);
        rangeInput.setMinLines(2);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Ui.dp(this, 6);
        root.addView(rangeInput, rlp);

        Button autoBtn = plainButton("📡 自动扫描本机网段");
        autoBtn.setOnClickListener(v -> {
            long[] subnet = NetUtil.localSubnet(this);
            if (subnet == null) {
                Ui.toast(this, "没检测到本机网段，请手动填写");
            } else {
                rangeInput.setText(NetUtil.subnetText(subnet));
                Ui.toast(this, "已填入本机网段");
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 38));
        blp.topMargin = Ui.dp(this, 8);
        root.addView(autoBtn, blp);

        root.addView(label("用户名"));
        userInput = Ui.input(this, cfg.defaultUser, cfg.defaultUser, false);
        userInput.setLayoutParams(fullWidthParams());
        root.addView(userInput);
        root.addView(label("密码"));
        pwdInput = Ui.input(this, cfg.defaultPassword, cfg.defaultPassword, true);
        pwdInput.setLayoutParams(fullWidthParams());
        root.addView(pwdInput);

        Button startBtn = new Button(this);
        startBtn.setText("开始扫描");
        startBtn.setBackgroundResource(R.drawable.bg_button_green);
        startBtn.setTextColor(Color.WHITE);
        startBtn.setTextSize(15);
        startBtn.setStateListAnimator(null);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        slp.topMargin = Ui.dp(this, 16);
        startBtn.setLayoutParams(slp);
        startBtn.setOnClickListener(v -> startScan());
        root.addView(startBtn);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgressTintList(ColorStateList.valueOf(0xFF58A6FF));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 8));
        plp.topMargin = Ui.dp(this, 16);
        root.addView(bar, plp);

        progressLabel = new TextView(this);
        progressLabel.setTextColor(0xFF8B949E);
        progressLabel.setTextSize(12);
        progressLabel.setPadding(0, Ui.dp(this, 6), 0, 0);
        root.addView(progressLabel);

        resultLabel = new TextView(this);
        resultLabel.setTextColor(0xFF58A6FF);
        resultLabel.setTextSize(13);
        resultLabel.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        root.addView(resultLabel);

        LinearLayout selRow = new LinearLayout(this);
        selRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams selp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 34));
        selp.topMargin = Ui.dp(this, 4);
        root.addView(selRow, selp);
        selRow.addView(selButton("全选", () -> selectAll()), new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1f));
        selRow.addView(selButton("反选", () -> invert()), new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1f));
        selRow.addView(selButton("清空", () -> clearSelection()), new LinearLayout.LayoutParams(0, Ui.dp(this, 34), 1f));

        hintLabel = new TextView(this);
        hintLabel.setTextColor(0xFF8B949E);
        hintLabel.setTextSize(11);
        hintLabel.setPadding(0, Ui.dp(this, 4), 0, 0);
        hintLabel.setText("点一行 = 勾选/取消；长按一行设起点，再长按另一行 = 勾选这一段");
        root.addView(hintLabel);

        final ScrollView resultScroll = new ScrollView(this);
        resultScroll.setBackgroundResource(R.drawable.bg_panel);
        resultContainer = new LinearLayout(this);
        resultContainer.setOrientation(LinearLayout.VERTICAL);
        resultContainer.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        resultScroll.addView(resultContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        resultScroll.setFillViewport(true);
        // 关键：结果列表嵌在外层滚动里，必须抢走触摸事件，否则外层拦截后列表滑不动
        resultScroll.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, android.view.MotionEvent e) {
                switch (e.getActionMasked()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                    case android.view.MotionEvent.ACTION_MOVE:
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                        break;
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        v.getParent().requestDisallowInterceptTouchEvent(false);
                        break;
                }
                return false;
            }
        });
        LinearLayout.LayoutParams rslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 300));
        rslp.topMargin = Ui.dp(this, 4);
        root.addView(resultScroll, rslp);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = Ui.dp(this, 14);
        root.addView(btnRow, brlp);

        Button addBtn = new Button(this);
        addBtn.setText("✅ 添加选中");
        addBtn.setBackgroundResource(R.drawable.bg_button_green);
        addBtn.setTextColor(Color.WHITE);
        addBtn.setTextSize(14);
        addBtn.setStateListAnimator(null);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f);
        alp.rightMargin = Ui.dp(this, 8);
        addBtn.setLayoutParams(alp);
        addBtn.setOnClickListener(v -> addSelected());
        addBtnRef = addBtn;
        btnRow.addView(addBtn);

        Button cancelBtn = new Button(this);
        cancelBtn.setText("停止");
        cancelBtn.setBackgroundResource(R.drawable.bg_toolbar_button);
        cancelBtn.setTextColor(0xFFC9D1D9);
        cancelBtn.setTextSize(14);
        cancelBtn.setStateListAnimator(null);
        cancelBtn.setLayoutParams(new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f));
        cancelBtn.setOnClickListener(v -> {
            cancel = true;
            progressLabel.setText("正在停止…");
        });
        btnRow.addView(cancelBtn);

        return scroll;
    }

    private ViewGroup.LayoutParams fullWidthParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        lp.topMargin = Ui.dp(this, 4);
        return lp;
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(0xFF8B949E);
        v.setTextSize(12);
        v.setPadding(0, Ui.dp(this, 10), 0, 0);
        return v;
    }

    private TextView selButton(String text, Runnable run) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(0xFF58A6FF);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(R.drawable.bg_toolbar_button);
        t.setOnClickListener(v -> run.run());
        return t;
    }

    private Button plainButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setBackgroundResource(R.drawable.bg_toolbar_button);
        b.setTextColor(0xFFC9D1D9);
        b.setTextSize(13);
        b.setStateListAnimator(null);
        return b;
    }

    // ==================== 扫描 ====================

    private void startScan() {
        final List<String> ips = NetUtil.expandInput(rangeInput.getText().toString(), 4096);
        if (ips.isEmpty()) {
            Ui.toast(this, "网段格式不正确");
            return;
        }
        if (ips.size() > 2000) {
            new android.app.AlertDialog.Builder(this, R.style.Theme_CamView_Dialog)
                    .setTitle("范围较大")
                    .setMessage("将扫描 " + ips.size() + " 个 IP，可能需要 1-3 分钟，是否继续？")
                    .setPositiveButton("继续", (d, w) -> runScan(ips))
                    .setNegativeButton("取消", null)
                    .show();
        } else {
            runScan(ips);
        }
    }

    private void runScan(final List<String> ips) {
        cancel = false;
        found.clear();
        selected.clear();
        rowViews.clear();
        anchorIp = null;
        resultContainer.removeAllViews();
        resultLabel.setText("正在扫描…");
        bar.setMax(ips.size());
        bar.setProgress(0);

        final AtomicInteger done = new AtomicInteger();
        final AtomicInteger foundCount = new AtomicInteger();
        final int timeout = Math.max(150, Math.min(cfg.timeoutMs, 1200));
        final String user = userInput.getText().toString().trim();
        final String pwd = pwdInput.getText().toString().trim();

        pool = Executors.newFixedThreadPool(32);
        for (final String ip : ips) {
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    if (cancel) {
                        done.incrementAndGet();
                        return;
                    }
                    boolean open = probe(ip, 554, timeout);
                    if (open) {
                        synchronized (found) {
                            found.add(ip);
                        }
                        foundCount.incrementAndGet();
                    }
                    int n = done.incrementAndGet();
                    if (n % 10 == 0 || n == ips.size()) {
                        final int cmp = n;
                        final int fc = foundCount.get();
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                bar.setProgress(cmp);
                                progressLabel.setText("已扫描 " + cmp + " / " + ips.size()
                                        + "  ·  发现 " + fc + " 个摄像头");
                            }
                        });
                    }
                }
            });
        }
        pool.shutdown();

        ui.post(new Runnable() {
            @Override
            public void run() {
                int doneNow = done.get();
                if (doneNow < ips.size() && !cancel) {
                    progressLabel.setText("已扫描 " + doneNow + " / " + ips.size()
                            + "  ·  发现 " + found.size() + " 个摄像头");
                    ui.postDelayed(this, 300);
                } else {
                    finishScan(user, pwd);
                }
            }
        });
    }

    private void finishScan(String user, String pwd) {
        progressLabel.setText(cancel ? "扫描已停止" : "扫描完成");
        List<String> snapshot;
        synchronized (found) {
            snapshot = new ArrayList<>(found);
        }
        java.util.Collections.sort(snapshot, (a, b) -> Long.compare(AppConfig.ipValue(a), AppConfig.ipValue(b)));

        // 已添加过的（任何分组里出现过的 IP）直接忽略
        alreadyAdded.clear();
        List<String> existing = cfg.allIps();
        for (String ip : existing) {
            if (!alreadyAdded.contains(ip)) alreadyAdded.add(ip);
        }

        resultContainer.removeAllViews();
        rowViews.clear();
        selected.clear();
        anchorIp = null;

        int newCount = 0;
        for (String ip : snapshot) {
            boolean added = alreadyAdded.contains(ip);
            if (!added) {
                newCount++;
                selected.put(ip, Boolean.TRUE);   // 新设备默认勾选
            }
            View row = buildResultRow(ip, added);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 6);
            resultContainer.addView(row, lp);
        }

        resultLabel.setText("发现 " + snapshot.size() + " 个可用摄像头 · 新 " + newCount
                + " 个 / 已添加忽略 " + (snapshot.size() - newCount) + " 个");
        if (snapshot.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("没有发现 554 端口开放的设备，检查网段是否和摄像头在同一局域网。");
            none.setTextColor(0xFF8B949E);
            none.setTextSize(12);
            none.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
            resultContainer.addView(none);
        }
        refreshCounts();
        Ui.toast(this, "发现 " + snapshot.size() + " 个，其中新设备 " + newCount + " 个");
    }

    private View buildResultRow(final String ip, boolean added) {
        TextView row = new TextView(this);
        row.setTextColor(0xFFC9D1D9);
        row.setTextSize(13);
        row.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
        row.setBackgroundResource(R.drawable.bg_input);
        rowViews.put(ip, row);

        if (added) {
            row.setText("✔  " + ip + "   :554   （已添加，已忽略）");
            row.setTextColor(0xFF6E7681);
            row.setOnClickListener(v -> Ui.toast(this, ip + " 已经在列表里了，可到主界面删除后再添加"));
            return row;
        }

        row.setOnClickListener(v -> {
            boolean now = !(selected.get(ip) == Boolean.TRUE);
            selected.put(ip, now);
            paintRow(ip, false);
            refreshCounts();
        });
        row.setOnLongClickListener(v -> {
            if (anchorIp == null) {
                anchorIp = ip;
                paintRow(ip, false);
                hintLabel.setText("起点： " + ip + "  → 再长按另一行，勾选这一段");
                Ui.toast(this, "已设起点 " + ip);
            } else {
                rangeSelect(anchorIp, ip);
                anchorIp = null;
            }
            return true;
        });
        paintRow(ip, false);
        return row;
    }

    private void paintRow(String ip, boolean isAnchor) {
        TextView row = rowViews.get(ip);
        if (row == null) return;
        if (alreadyAdded.contains(ip)) return;
        boolean on = selected.get(ip) == Boolean.TRUE;
        boolean anchor = ip.equals(anchorIp);
        row.setText((on ? "☑  " : "☐  ") + ip + "   :554" + (anchor ? "    ← 起点" : ""));
        row.setTextColor(on ? 0xFF58A6FF : 0xFFC9D1D9);
        row.setBackgroundResource(on ? R.drawable.bg_input_sel : R.drawable.bg_input);
    }

    /** 把 from~to 之间（按当前排序）所有未添加设备勾上 */
    private void rangeSelect(String from, String to) {
        List<String> order = new ArrayList<>();
        synchronized (found) {
            order.addAll(found);
        }
        java.util.Collections.sort(order, (a, b) -> Long.compare(AppConfig.ipValue(a), AppConfig.ipValue(b)));
        int i = order.indexOf(from), j = order.indexOf(to);
        if (i < 0 || j < 0) return;
        if (i > j) { int t = i; i = j; j = t; }
        int n = 0;
        for (int k = i; k <= j; k++) {
            String ip = order.get(k);
            if (alreadyAdded.contains(ip)) continue;
            selected.put(ip, Boolean.TRUE);
            paintRow(ip, false);
            n++;
        }
        paintRow(from, false);
        hintLabel.setText("已勾选 " + order.get(i) + " ~ " + order.get(j) + " 共 " + n + " 个");
        refreshCounts();
    }

    private void selectAll() {
        for (String ip : rowViews.keySet()) {
            if (alreadyAdded.contains(ip)) continue;
            selected.put(ip, Boolean.TRUE);
            paintRow(ip, false);
        }
        refreshCounts();
    }

    private void invert() {
        for (String ip : rowViews.keySet()) {
            if (alreadyAdded.contains(ip)) continue;
            selected.put(ip, !(selected.get(ip) == Boolean.TRUE));
            paintRow(ip, false);
        }
        refreshCounts();
    }

    private void clearSelection() {
        for (String ip : rowViews.keySet()) {
            if (alreadyAdded.contains(ip)) continue;
            selected.put(ip, Boolean.FALSE);
            paintRow(ip, false);
        }
        anchorIp = null;
        refreshCounts();
    }

    private int selectedCount() {
        int n = 0;
        for (Map.Entry<String, Boolean> e : selected.entrySet()) {
            if (e.getValue() == Boolean.TRUE && !alreadyAdded.contains(e.getKey())) n++;
        }
        return n;
    }

    private void refreshCounts() {
        int n = selectedCount();
        if (addBtnRef != null) addBtnRef.setText("✅ 添加选中（" + n + "）");
    }

    private boolean probe(String ip, int port, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), timeoutMs);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void addSelected() {
        List<String> ips = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : selected.entrySet()) {
            if (e.getValue() == Boolean.TRUE && !alreadyAdded.contains(e.getKey())) ips.add(e.getKey());
        }
        if (ips.isEmpty()) {
            Ui.toast(this, "还没有勾选任何新摄像头");
            return;
        }
        java.util.Collections.sort(ips, (a, b) -> Long.compare(AppConfig.ipValue(a), AppConfig.ipValue(b)));
        String user = userInput.getText().toString().trim();
        String pwd = pwdInput.getText().toString().trim();
        int added = cfg.addCameras(ips, user, pwd);
        Ui.toast(this, "已添加 " + added + " 个摄像头到「" + cfg.currentGroup + "」");
        finish();
    }

    @Override
    protected void onDestroy() {
        cancel = true;
        if (pool != null) pool.shutdownNow();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
