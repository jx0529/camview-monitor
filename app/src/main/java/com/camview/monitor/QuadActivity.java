package com.camview.monitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

    /** 左侧抽屉：分组切换 + 常用功能 */
    private LinearLayout drawer;
    private View scrim;
    private LinearLayout groupBox;
    private TextView menuBtn, drawerGroupLabel;
    private boolean drawerOpen = false;
    private int drawerW = 0;

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

        // 左上角的小菜单按钮（不抢画面，44×36 半透明）
        menuBtn = new TextView(this);
        menuBtn.setText("☰");
        menuBtn.setTextSize(18);
        menuBtn.setTextColor(Color.WHITE);
        menuBtn.setGravity(Gravity.CENTER);
        menuBtn.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 36));
        mlp.gravity = Gravity.TOP | Gravity.LEFT;
        mlp.topMargin = Ui.dp(this, 6);
        mlp.leftMargin = Ui.dp(this, 6);
        menuBtn.setOnClickListener(v -> toggleDrawer());
        root.addView(menuBtn, mlp);

        buildDrawer();
    }

    private void buildDrawer() {
        drawerW = Ui.dp(this, 200);

        // 抽屉外的遮罩，点一下收起
        scrim = new View(this);
        scrim.setBackgroundColor(0x66000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> closeDrawer());
        root.addView(scrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        drawer = new LinearLayout(this);
        drawer.setOrientation(LinearLayout.VERTICAL);
        drawer.setBackgroundColor(0xF2161B22);
        drawer.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("分组");
        title.setTextColor(0xFF58A6FF);
        title.setTextSize(14);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        head.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView closeBtn = new TextView(this);
        closeBtn.setText("‹");
        closeBtn.setTextSize(18);
        closeBtn.setTextColor(0xFF8B949E);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        closeBtn.setOnClickListener(v -> closeDrawer());
        head.addView(closeBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(this, 28)));
        drawer.addView(head, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        groupBox = new LinearLayout(this);
        groupBox.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(groupBox, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        drawer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        drawerGroupLabel = new TextView(this);
        drawerGroupLabel.setTextColor(0xFF8B949E);
        drawerGroupLabel.setTextSize(11);
        drawerGroupLabel.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 4));
        drawer.addView(drawerGroupLabel);

        // 常用功能（“该有的功能也在里面”）
        drawer.addView(drawerAction("↻ 重连本页", this::reconnectPage));
        drawer.addView(drawerAction("🩺 检测无画面并删除", this::cleanNoFrame));
        drawer.addView(drawerAction("✎ 编辑分组（重命名）", this::renameGroupDialog));
        drawer.addView(drawerAction("⇆ 本组首屏", () -> {
            base = 0;
            loadPage();
            closeDrawer();
        }));
        drawer.addView(drawerAction("⛶ 竖屏浏览", () -> {
            setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            finish();
        }));
        drawer.addView(drawerAction("⚙ 设置", () -> {
            closeDrawer();
            startActivity(new Intent(this, SettingsActivity.class));
        }));
        drawer.addView(drawerAction("⌂ 回主界面", () -> finish()));

        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(drawerW,
                FrameLayout.LayoutParams.MATCH_PARENT);
        dlp.gravity = Gravity.TOP | Gravity.LEFT;
        drawer.setTranslationX(-drawerW);
        drawer.setVisibility(View.INVISIBLE);
        root.addView(drawer, dlp);
    }

    private TextView drawerAction(String text, Runnable run) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFC9D1D9);
        t.setTextSize(13);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 9), Ui.dp(this, 4), Ui.dp(this, 9));
        t.setOnClickListener(v -> run.run());
        return t;
    }

    private void toggleDrawer() {
        if (drawerOpen) closeDrawer();
        else openDrawer();
    }

    private void openDrawer() {
        if (drawer == null) return;
        drawerOpen = true;
        refreshGroupList();
        scrim.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(180).start();
        drawer.setVisibility(View.VISIBLE);
        drawer.animate().translationX(0f).setDuration(180).start();
    }

    private void closeDrawer() {
        if (drawer == null || !drawerOpen) return;
        drawerOpen = false;
        scrim.animate().alpha(0f).setDuration(160).withEndAction(
                () -> scrim.setVisibility(View.GONE)).start();
        drawer.animate().translationX(-drawerW).setDuration(160).withEndAction(
                () -> drawer.setVisibility(View.INVISIBLE)).start();
    }

    private void reconnectPage() {
        for (CameraStream s : streams) {
            try {
                s.setActive(true);
                s.restart();
            } catch (Throwable ignored) { }
        }
        closeDrawer();
    }

    private void refreshGroupList() {
        if (groupBox == null) return;
        groupBox.removeAllViews();
        for (String g : App.cfg().groups.keySet()) {
            List<String> ips = App.cfg().groups.get(g);
            boolean cur = g.equals(groupName);
            TextView item = new TextView(this);
            item.setText((cur ? "● " : "○ ") + g + "  " + (ips == null ? 0 : ips.size()));
            item.setTextSize(13);
            item.setTextColor(cur ? 0xFF58A6FF : 0xFFC9D1D9);
            item.setPadding(Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8));
            if (cur) item.setBackgroundColor(0x22FFFFFF);
            final String target = g;
            item.setOnClickListener(v -> switchGroup(target));
            groupBox.addView(item, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        drawerGroupLabel.setText("当前：" + groupName + " · " + groupIps().size() + " 路");
    }

    private void switchGroup(String g) {
        if (g == null || g.equals(groupName)) {
            closeDrawer();
            return;
        }
        groupName = g;
        App.cfg().currentGroup = g;
        base = 0;
        App.cfg().save();
        loadPage();
        refreshGroupList();
        closeDrawer();
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
            cell.setOnLongClickListener(v -> {
                showCellMenu(target);
                return true;
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
            if (s.noFrame()) {
                t.setVisibility(View.VISIBLE);
                t.setText("⚠ 无画面 · 长按可删除");
                t.setTextColor(0xFFF85149);
                continue;
            }
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

    // ==================== 单元格长按编辑 ====================

    private void showCellMenu(final String ip) {
        String[] items = {"⛶ 全屏放大", "♻ 重新连接", "🔄 旋转画面", "↔ 水平翻转", "↕ 垂直翻转",
                "🔐 编辑账号密码", "📁 移动到其他分组", "🗑 从分组删除", "✎ 编辑分组（重命名）"};
        Ui.actionSheet(this, ip, items, (index, text) -> {
            CameraInfo info = App.cfg().findCamera(ip);
            CameraStream s = streamOf(ip);
            switch (index) {
                case 0: {
                    Intent i = new Intent(this, FullscreenActivity.class);
                    i.putExtra("ip", ip);
                    startActivity(i);
                    break;
                }
                case 1:
                    if (s != null) { s.setActive(true); s.restart(); }
                    Ui.toast(this, "正在重新连接 " + ip);
                    break;
                case 2:
                    if (info != null) pickRotation(info, s);
                    break;
                case 3:
                    if (s != null) { s.toggleFlipH(); App.cfg().save(); }
                    break;
                case 4:
                    if (s != null) { s.toggleFlipV(); App.cfg().save(); }
                    break;
                case 5:
                    editCredentials(ip);
                    break;
                case 6:
                    moveToGroup(ip);
                    break;
                case 7:
                    confirmDelete(ip);
                    break;
                case 8:
                    renameGroupDialog();
                    break;
            }
        });
    }

    private CameraStream streamOf(String ip) {
        for (CameraStream s : streams) {
            if (s.info != null && ip.equals(s.info.ip)) return s;
        }
        return null;
    }

    private void pickRotation(final CameraInfo info, CameraStream s) {
        String[] items = {"0°", "90°", "180°", "270°"};
        Ui.actionSheet(this, "旋转画面", items, (index, text) -> {
            int deg = index * 90;
            if (s != null) s.setRotation(deg);
            info.rotation = deg;
            App.cfg().save();
            loadPage();   // 重建格子，保证旋转生效
        });
    }

    private void editCredentials(final String ip) {
        CameraInfo info = App.cfg().findCamera(ip);
        if (info == null) return;
        LinearLayout col = Ui.column(this);
        EditText u = Ui.input(this, info.user, "用户名", false);
        EditText p = Ui.input(this, info.password, "密码", true);
        col.addView(u);
        col.addView(p);
        AlertDialog d = Ui.dialog(this).setTitle("账号密码 · " + ip)
                .setView(col)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        Ui.styleDialog(d);
        d.setOnShowListener(dd -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            App.cfg().setCameraCredentials(ip, u.getText().toString().trim(), p.getText().toString().trim());
            d.dismiss();
            Ui.toast(this, "已保存，正在重连 " + ip);
            CameraStream s = streamOf(ip);
            if (s != null) { s.restart(); }
        }));
        d.show();
    }

    private void moveToGroup(final String ip) {
        List<String> groups = App.cfg().groupNames();
        groups.remove(groupName);
        if (groups.isEmpty()) {
            Ui.toast(this, "没有其他分组");
            return;
        }
        String[] arr = groups.toArray(new String[0]);
        Ui.actionSheet(this, "移动到分组 · " + ip, arr, (index, text) -> {
            App.cfg().moveCamera(ip, groupName, arr[index]);
            Ui.toast(this, "已移动到「" + arr[index] + "」");
            loadPage();
            refreshGroupList();
        });
    }

    private void confirmDelete(final String ip) {
        AlertDialog d = Ui.dialog(this).setTitle("删除摄像头")
                .setMessage("确定从「" + groupName + "」删除 " + ip + " ？")
                .setPositiveButton("删除", (dd, w) -> {
                    App.cfg().removeCamera(ip);
                    Ui.toast(this, "已删除 " + ip);
                    loadPage();
                    refreshGroupList();
                })
                .setNegativeButton("取消", null)
                .create();
        Ui.styleDialog(d);
        d.show();
    }

    private void renameGroupDialog() {
        EditText input = Ui.input(this, groupName, "新分组名", false);
        AlertDialog d = Ui.dialog(this).setTitle("编辑分组")
                .setView(input)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        Ui.styleDialog(d);
        d.setOnShowListener(dd -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { Ui.toast(this, "名字不能为空"); return; }
            if (name.equals(groupName)) { d.dismiss(); return; }
            if (!App.cfg().renameGroup(groupName, name)) {
                Ui.toast(this, "该分组名已存在");
                return;
            }
            groupName = name;
            App.cfg().currentGroup = name;
            App.cfg().save();
            d.dismiss();
            refreshGroupList();
            Ui.toast(this, "已重命名为「" + name + "」");
        }));
        d.show();
    }

    /** 检测本页已连接但长时间没画面的路，确认后批量删除 */
    private void cleanNoFrame() {
        List<String> bad = new ArrayList<>();
        for (CameraStream s : streams) {
            if (s != null && s.info != null && s.noFrame()) bad.add(s.info.ip);
        }
        if (bad.isEmpty()) {
            Ui.toast(this, "本页没有无画面的摄像头");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String ip : bad) sb.append("· ").append(ip).append("\n");
        AlertDialog d = Ui.dialog(this).setTitle("无画面摄像头 " + bad.size() + " 个")
                .setMessage("以下设备已连接但 20 秒以上没有画面：\n" + sb
                        + "\n删除后可从扫描页重新添加。")
                .setPositiveButton("全部删除", (dd, w) -> {
                    for (String ip : bad) App.cfg().removeCamera(ip);
                    Ui.toast(this, "已删除 " + bad.size() + " 个");
                    loadPage();
                    refreshGroupList();
                })
                .setNegativeButton("取消", null)
                .create();
        Ui.styleDialog(d);
        d.show();
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
