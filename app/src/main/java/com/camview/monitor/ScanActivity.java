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
    private final Map<String, CheckBox> checkboxes = new LinkedHashMap<>();
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
        resultLabel.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 6));
        root.addView(resultLabel);

        ScrollView resultScroll = new ScrollView(this);
        resultScroll.setBackgroundResource(R.drawable.bg_panel);
        resultContainer = new LinearLayout(this);
        resultContainer.setOrientation(LinearLayout.VERTICAL);
        resultContainer.setPadding(Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        resultScroll.addView(resultContainer);
        LinearLayout.LayoutParams rslp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 260));
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
        checkboxes.clear();
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
        resultLabel.setText("发现 " + snapshot.size() + " 个可用摄像头（已全选，可取消勾选）");
        resultContainer.removeAllViews();
        checkboxes.clear();
        for (String ip : snapshot) {
            TextView row = new TextView(this);
            row.setText("●  " + ip + "   :554");
            row.setTextColor(0xFFC9D1D9);
            row.setTextSize(13);
            row.setPadding(Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10), Ui.dp(this, 10));
            row.setBackgroundResource(R.drawable.bg_input);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(this, 6);
            resultContainer.addView(row, lp);
        }
        if (snapshot.isEmpty()) {
            TextView none = new TextView(this);
            none.setText("没有发现 554 端口开放的设备，检查网段是否和摄像头在同一局域网。");
            none.setTextColor(0xFF8B949E);
            none.setTextSize(12);
            none.setPadding(Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12));
            resultContainer.addView(none);
        }
        Ui.toast(this, "发现 " + snapshot.size() + " 个摄像头");
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
        List<String> snapshot;
        synchronized (found) {
            snapshot = new ArrayList<>(found);
        }
        if (snapshot.isEmpty()) {
            Ui.toast(this, "没有可添加的摄像头");
            return;
        }
        String user = userInput.getText().toString().trim();
        String pwd = pwdInput.getText().toString().trim();
        int added = cfg.addCameras(snapshot, user, pwd);
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
