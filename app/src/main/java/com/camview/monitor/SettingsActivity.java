package com.camview.monitor;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.app.Activity;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 系统设置：与桌面版设置项一一对应，另加移动端解码/更新选项 */
public class SettingsActivity extends Activity {

    private AppConfig cfg;
    private EditText userIn, pwdIn, timeoutIn, templatesIn, repoIn;
    private Switch fpsSwitch, hwSwitch, tcpSwitch, updateSwitch, lowLatencySwitch;
    private TextView perPageValue, decodeValue;
    private int perPage;
    private int cacheMs;
    private int maxDecoders;
    private int quadGrid;
    private TextView cacheValue, maxDecValue, quadValue;
    private String decodeMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = App.cfg();
        perPage = cfg.itemsPerPage;
        cacheMs = cfg.cacheMs;
        maxDecoders = cfg.maxDecoders;
        quadGrid = cfg.quadGrid;
        decodeMode = cfg.gpuMode == null ? "auto" : cfg.gpuMode;
        setContentView(buildUi());
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0D1117);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 28));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("⚙ 系统设置");
        title.setTextColor(0xFFC9D1D9);
        title.setTextSize(19);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, Ui.dp(this, 8));
        root.addView(title);

        groupTitle(root, "账号");

        root.addView(label("默认用户名"));
        userIn = Ui.input(this, cfg.defaultUser, cfg.defaultUser, false);
        userIn.setLayoutParams(fieldParams());
        root.addView(userIn);

        root.addView(label("默认密码"));
        pwdIn = Ui.input(this, cfg.defaultPassword, cfg.defaultPassword, true);
        pwdIn.setLayoutParams(fieldParams());
        root.addView(pwdIn);

        groupTitle(root, "连接");

        root.addView(label("连接超时（毫秒）"));
        timeoutIn = Ui.input(this, String.valueOf(cfg.timeoutMs), "3000", false);
        timeoutIn.setInputType(InputType.TYPE_CLASS_NUMBER);
        timeoutIn.setLayoutParams(fieldParams());
        root.addView(timeoutIn);

        tcpSwitch = switchRow(root, "RTSP 走 TCP（跨路由更稳定，推荐开启）", cfg.rtspTcp);
        lowLatencySwitch = switchRow(root, "强制 UDP 传输（延迟最低，个别设备会无画面）", cfg.forceUdp);
        cacheValue = valueRow(root, "播放缓存", cfg.cacheMs + " ms");
        maxDecValue = valueRow(root, "同时解码路数", maxDecText(maxDecoders));
        quadValue = valueRow(root, "横屏同屏路数", (quadGrid == 9 ? "9 路（3×3）" : "4 路（2×2）"));
        quadValue.setOnClickListener(v -> {
            String[] items = {"4 路（2×2）", "9 路（3×3）"};
            Ui.actionSheet(this, "横屏同屏路数", items, (index, text) -> {
                quadGrid = index == 1 ? 9 : 4;
                quadValue.setText(quadGrid == 9 ? "9 路（3×3）" : "4 路（2×2）");
            });
        });
        maxDecValue.setOnClickListener(v -> {
            String[] items = {"2 路（最省，最流畅）", "4 路", "6 路（推荐）", "8 路", "不限（路多会卡）"};
            Ui.actionSheet(this, "同时解码路数", items, (index, text) -> {
                int[] values = {2, 4, 6, 8, 0};
                maxDecoders = values[index];
                maxDecValue.setText(maxDecText(maxDecoders));
            });
        });
        cacheValue.setOnClickListener(v -> {
            String[] items = {"0 ms（最实时，可能花屏）", "150 ms（推荐）", "300 ms", "800 ms（最稳，延迟大）"};
            Ui.actionSheet(this, "播放缓存", items, (index, text) -> {
                int[] values = {0, 150, 300, 800};
                cacheMs = values[index];
                cacheValue.setText(cacheMs + " ms");
            });
        });
        hwSwitch = switchRow(root, "硬件解码（MediaCodec / GPU）", cfg.hwDecode);

        decodeValue = valueRow(root, "解码模式", decodeText());
        decodeValue.setOnClickListener(v -> chooseDecodeMode());

        groupTitle(root, "显示");

        fpsSwitch = switchRow(root, "显示帧率 FPS", cfg.showFps);

        perPageValue = valueRow(root, "每页显示数量", perPage + " 个");
        perPageValue.setOnClickListener(v -> {
            String[] items = {"4 个", "6 个", "9 个", "12 个"};
            Ui.actionSheet(this, "每页显示数量", items, (index, text) -> {
                int[] values = {4, 6, 9, 12};
                perPage = values[index];
                perPageValue.setText(perPage + " 个");
            });
        });

        groupTitle(root, "RTSP 地址模板（每行一条，支持 {ip} {user} {password}）");
        templatesIn = new EditText(this);
        templatesIn.setBackgroundResource(R.drawable.bg_input);
        templatesIn.setTextColor(0xFFC9D1D9);
        templatesIn.setHintTextColor(0xFF8B949E);
        templatesIn.setTextSize(12);
        templatesIn.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        templatesIn.setMinLines(6);
        templatesIn.setText(join(cfg.rtspTemplates));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = Ui.dp(this, 6);
        root.addView(templatesIn, tlp);

        groupTitle(root, "在线更新");

        updateSwitch = switchRow(root, "启动时自动检查更新", cfg.autoUpdate);
        root.addView(label("GitHub 仓库（owner/repo，用于检查新版本）"));
        repoIn = Ui.input(this, cfg.githubRepo, "例如 yourname/camview-monitor", false);
        repoIn.setLayoutParams(fieldParams());
        root.addView(repoIn);

        Button checkBtn = new Button(this);
        checkBtn.setText("立即检查更新");
        checkBtn.setBackgroundResource(R.drawable.bg_toolbar_button);
        checkBtn.setTextColor(0xFFC9D1D9);
        checkBtn.setTextSize(13);
        checkBtn.setStateListAnimator(null);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 40));
        clp.topMargin = Ui.dp(this, 10);
        root.addView(checkBtn, clp);
        checkBtn.setOnClickListener(v -> {
            // 先保存仓库设置再检查
            cfg.githubRepo = repoIn.getText().toString().trim();
            cfg.save();
            UpdateChecker.check(this, false);
        });

        groupTitle(root, "关于 GPU 加速");
        TextView note = new TextView(this);
        note.setText("手机上没有 CUDA/OpenCL 这套桌面方案。这里用手机自带的硬件解码器（MediaCodec，H.264/H.265 解码在 GPU 上完成），"
                + "画面用 GPU 纹理上屏，CPU 占用很低。\n\n"
                + "· 自动：硬件解码优先，失败自动回退软解（最稳）\n"
                + "· 强制硬件：只允许硬件解码（最省电）\n"
                + "· 软件兼容：个别老摄像头编码特殊时使用（会占 CPU）");
        note.setTextColor(0xFF8B949E);
        note.setTextSize(12);
        note.setLineSpacing(0, 1.25f);
        root.addView(note);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams brlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        brlp.topMargin = Ui.dp(this, 22);
        root.addView(btnRow, brlp);

        Button save = new Button(this);
        save.setText("💾 保存");
        save.setBackgroundResource(R.drawable.bg_button_green);
        save.setTextColor(Color.WHITE);
        save.setTextSize(15);
        save.setStateListAnimator(null);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f);
        slp.rightMargin = Ui.dp(this, 8);
        btnRow.addView(save, slp);
        save.setOnClickListener(v -> save());

        Button reset = new Button(this);
        reset.setText("恢复默认");
        reset.setBackgroundResource(R.drawable.bg_toolbar_button);
        reset.setTextColor(0xFFC9D1D9);
        reset.setTextSize(14);
        reset.setStateListAnimator(null);
        btnRow.addView(reset, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f));
        reset.setOnClickListener(v -> confirmReset());

        return scroll;
    }

    private LinearLayout.LayoutParams fieldParams() {
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

    private void groupTitle(LinearLayout root, String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(0xFF58A6FF);
        v.setTextSize(13);
        v.setTypeface(null, android.graphics.Typeface.BOLD);
        v.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 2));
        root.addView(v);
    }

    private Switch switchRow(LinearLayout root, String text, boolean value) {
        Switch s = new Switch(this);
        s.setText(text);
        s.setTextColor(0xFFC9D1D9);
        s.setTextSize(13);
        s.setChecked(value);
        s.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        s.setThumbTintList(ColorStateList.valueOf(0xFF58A6FF));
        s.setTrackTintList(ColorStateList.valueOf(0xFF30363D));
        root.addView(s, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private TextView valueRow(LinearLayout root, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_input);
        row.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        rlp.topMargin = Ui.dp(this, 8);
        root.addView(row, rlp);

        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(0xFFC9D1D9);
        l.setTextSize(13);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(l);

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(0xFF58A6FF);
        v.setTextSize(13);
        row.addView(v);
        return v;
    }

    private String decodeText() {
        switch (decodeMode) {
            case "cuda":
            case "hardware":
                return "强制硬件解码";
            case "opencl":
            case "software":
                return "软件兼容";
            default:
                return "自动（硬件优先）";
        }
    }

    private void chooseDecodeMode() {
        String[] items = {"自动（硬件优先）", "强制硬件解码", "软件兼容"};
        String[] values = {"auto", "hardware", "software"};
        Ui.actionSheet(this, "解码模式", items, (index, text) -> {
            decodeMode = values[index];
            decodeValue.setText(decodeText());
        });
    }

    private String maxDecText(int v) {
        return v <= 0 ? "不限" : (v + " 路");
    }

    private void save() {
        int timeout;
        try {
            timeout = Integer.parseInt(timeoutIn.getText().toString().trim());
        } catch (Exception e) {
            Ui.toast(this, "连接超时请填数字");
            return;
        }
        timeout = Math.max(500, Math.min(timeout, 30000));

        List<String> templates = new ArrayList<>();
        for (String line : templatesIn.getText().toString().split("\n")) {
            String s = line.trim();
            if (s.length() > 0) templates.add(s);
        }
        if (templates.isEmpty()) {
            Ui.toast(this, "RTSP 模板至少保留一条");
            return;
        }

        cfg.defaultUser = emptyTo(userIn.getText().toString(), "admin");
        cfg.defaultPassword = pwdIn.getText().toString();
        cfg.timeoutMs = timeout;
        cfg.rtspTcp = tcpSwitch.isChecked();
        cfg.forceUdp = lowLatencySwitch.isChecked();
        cfg.cacheMs = cacheMs;
        cfg.maxDecoders = maxDecoders;
        cfg.quadGrid = quadGrid;
        cfg.hwDecode = hwSwitch.isChecked();
        cfg.showFps = fpsSwitch.isChecked();
        cfg.gpuMode = decodeMode;
        cfg.itemsPerPage = perPage;
        if (cfg.currentPage >= cfg.totalPages()) cfg.currentPage = Math.max(0, cfg.totalPages() - 1);
        cfg.rtspTemplates = templates;
        cfg.autoUpdate = updateSwitch.isChecked();
        cfg.githubRepo = repoIn.getText().toString().trim();
        cfg.ensureCameraEntries();
        cfg.save();

        Ui.toast(this, "设置已保存");
        finish();
    }

    private String emptyTo(String v, String def) {
        v = v == null ? "" : v.trim();
        return v.length() == 0 ? def : v;
    }

    private void confirmReset() {
        new AlertDialog.Builder(this, R.style.Theme_CamView_Dialog)
                .setTitle("恢复默认设置")
                .setMessage("将把用户名、密码、超时、模板、显示等设置恢复默认（摄像头与分组数据不受影响）。")
                .setPositiveButton("恢复", (d, w) -> {
                    cfg.defaultUser = "admin";
                    cfg.defaultPassword = "admin123";
                    cfg.timeoutMs = 3000;
                    cfg.rtspTcp = true;
                    cfg.hwDecode = true;
                    cfg.showFps = true;
                    cfg.gpuMode = "auto";
                    cfg.itemsPerPage = 6;
                    cfg.rtspTemplates = AppConfig.defaultTemplates();
                    cfg.save();
                    Ui.toast(this, "已恢复默认");
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(s);
        }
        return sb.toString();
    }
}
