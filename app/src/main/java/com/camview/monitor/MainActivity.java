package com.camview.monitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 主界面：工具栏 + 单列摄像头列表（一屏一行）+ 状态栏。
 * 列表按页显示，只有出现在屏幕上的摄像头才会真正开始解码（懒加载），
 * 滚出屏幕的会自动暂停，所以手机上多路也不会卡。
 */
public class MainActivity extends Activity {

    private static final int REQ_SETTINGS = 1001;
    private static final int REQ_IMPORT = 1002;

    private AppConfig cfg;
    private final List<CameraRow> rows = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout toolbar;
    private ScrollView listScroll;
    private LinearLayout listContainer;
    private TextView pageLabel, groupStats, statusBar, emptyView;
    private TextView prevBtn, nextBtn;
    private TextView groupChip;

    private int lastRevision = -1;
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    private final Runnable clockTask = new Runnable() {
        @Override
        public void run() {
            updateStatusBar();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cfg = App.cfg();
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        toolbar = findViewById(R.id.toolbar);
        listScroll = (ScrollView) findViewById(R.id.listScroll);
        listContainer = (LinearLayout) findViewById(R.id.listContainer);
        pageLabel = (TextView) findViewById(R.id.pageLabel);
        groupStats = (TextView) findViewById(R.id.groupStats);
        statusBar = (TextView) findViewById(R.id.statusBar);
        prevBtn = (TextView) findViewById(R.id.prevBtn);
        nextBtn = (TextView) findViewById(R.id.nextBtn);

        buildToolbar();

        prevBtn.setOnClickListener(v -> prevPage());
        nextBtn.setOnClickListener(v -> nextPage());

        listScroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int scrollX, int scrollY, int oldX, int oldY) {
                updateVisibility();
            }
        });

        renderPage();
        lastRevision = cfg.revision;
        ui.post(clockTask);

        if (cfg.autoUpdate) {
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    UpdateChecker.check(MainActivity.this, true);
                }
            }, 2500);
        }
    }

    // ==================== 工具栏 ====================

    private void buildToolbar() {
        toolbar.removeAllViews();
        groupChip = toolButton("📁 " + cfg.currentGroup, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showGroupDialog();
            }
        });
        groupChip.setTextColor(0xFF58A6FF);
        toolbar.addView(groupChip);

        toolbar.addView(toolButton("📷 摄像头", v -> showCameraMenu()));
        toolbar.addView(toolButton("🔃 排序", v -> showSortMenu()));
        toolbar.addView(toolButton("⚙ 设置", v -> startActivityForResult(
                new Intent(this, SettingsActivity.class), REQ_SETTINGS)));
        toolbar.addView(toolButton("🔄 重启", v -> restartAll()));
        toolbar.addView(toolButton("⋯ 更多", v -> showMoreMenu()));
        toolbar.addView(toolButton("✕", v -> finish()));
    }

    private TextView toolButton(String text, View.OnClickListener listener) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(0xFFC9D1D9);
        b.setTextSize(13);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(R.drawable.bg_toolbar_button);
        b.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        b.setClickable(true);
        b.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Ui.dp(this, 36));
        lp.rightMargin = Ui.dp(this, 6);
        b.setLayoutParams(lp);
        b.setOnClickListener(listener);
        return b;
    }

    private void showCameraMenu() {
        String[] items = {"➕ 添加单个摄像头", "➕➕ 批量添加摄像头", "🔍 扫描网段", "☑ 多选：批量移动 / 删除（本组）",
                "🩺 检测无画面摄像头（可删除）", "🗑 删除摄像头（跨分组勾选）", "🧹 删除全部摄像头（需二次确认）"};
        Ui.actionSheet(this, "摄像头", items, (index, text) -> {
            switch (index) {
                case 0:
                    addSingleCamera();
                    break;
                case 1:
                    addBatchCamera();
                    break;
                case 2:
                    startActivity(new Intent(this, ScanActivity.class));
                    break;
                case 3:
                    enterSelectMode(null);
                    break;
                case 4:
                    openNoFrameCheck();
                    break;
                case 5:
                    deleteCameras();
                    break;
                case 6:
                    deleteAllCameras();
                    break;
            }
        });
    }

    /** 删除全部摄像头：两步确认，避免误触清空 200 多路配置 */
    private void deleteAllCameras() {
        final int total = cfg.totalCameraCount();
        if (total <= 0) {
            Ui.toast(this, "当前没有任何摄像头");
            return;
        }
        new AlertDialog.Builder(this, R.style.Theme_CamView_Dialog)
                .setTitle("删除全部摄像头")
                .setMessage("将删除所有分组里的 " + total + " 个摄像头。\n\n"
                        + "分组名称会保留，只清空摄像头列表。\n删除后需要重新添加或重新扫描。")
                .setPositiveButton("继续", (d, w) -> confirmDeleteAllAgain(total))
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDeleteAllAgain(final int total) {
        new AlertDialog.Builder(this, R.style.Theme_CamView_Dialog)
                .setTitle("最后确认")
                .setMessage("真的要删除全部 " + total + " 个摄像头吗？\n此操作不可撤销。")
                .setPositiveButton("全部删除", (d, w) -> {
                    int removed = cfg.clearAllCameras();
                    refreshAll();
                    Ui.toast(this, "已删除 " + removed + " 个摄像头");
                })
                .setNegativeButton("我再想想", null)
                .show();
    }

    private void showSortMenu() {
        String[] items = {"当前组按 IP 排序", "所有组按 IP 排序", "分组名称排序"};
        Ui.actionSheet(this, "排序", items, (index, text) -> {
            switch (index) {
                case 0:
                    cfg.sortCurrentGroup();
                    Ui.toast(this, "已按 IP 排序：" + cfg.currentGroup);
                    break;
                case 1:
                    cfg.sortAllGroups();
                    Ui.toast(this, "所有分组已按 IP 排序");
                    break;
                case 2:
                    cfg.sortGroupsByName();
                    Ui.toast(this, "分组已按名称排序");
                    break;
            }
            refreshAll();
        });
    }

    /** 防止横屏时反复自动弹出四路 */
    private boolean quadOpening = false;

    /** 就地多选（不弹独立窗口） */
    private boolean selectMode = false;
    private final java.util.LinkedHashSet<String> selectedIps = new java.util.LinkedHashSet<>();
    private LinearLayout selectBar;
    private TextView selectCountLabel;

    private void showMoreMenu() {
        String[] items = {"▦ 横屏四路同屏（选 4 路）", "重启所有摄像头", "🔐 全部改用 Basic 隧道（推荐）",
                "♻ 全部改回自动认证", "检查更新", "导入电脑端配置", "关于 / 使用说明"};
        Ui.actionSheet(this, "更多", items, (index, text) -> {
            switch (index) {
                case 0:
                    pickQuadCameras();
                    break;
                case 1:
                    restartAll();
                    break;
                case 2:
                    setAllAuthMode("basic");
                    break;
                case 3:
                    setAllAuthMode("");
                    break;
                case 4:
                    UpdateChecker.check(this, false);
                    break;
                case 5:
                    importConfig();
                    break;
                case 6:
                    showAbout();
                    break;
            }
        });
    }

    /** 选 4 路进横屏同屏 */
    private void pickQuadCameras() {
        List<String> groupIps = cfg.groups.get(cfg.currentGroup);
        if (groupIps == null) groupIps = new ArrayList<>();
        if (groupIps.isEmpty()) {
            Ui.toast(this, "当前分组没有摄像头");
            return;
        }
        final String[] arr = groupIps.toArray(new String[0]);
        final boolean[] checked = new boolean[arr.length];
        // 默认勾选当前页的前 4 路
        int start = cfg.currentPage * cfg.itemsPerPage;
        for (int i = 0; i < 4 && start + i < arr.length; i++) checked[start + i] = true;

        AlertDialog.Builder b = Ui.dialog(this);
        b.setTitle("选 4 路横屏同屏");
        b.setMultiChoiceItems(arr, checked, (d, which, isChecked) -> {
            checked[which] = isChecked;
            int n = 0;
            for (boolean c : checked) if (c) n++;
            if (n > 4) {
                checked[which] = false;
                ((AlertDialog) d).getListView().setItemChecked(which, false);
                Ui.toast(this, "最多选 4 路");
            }
        });
        b.setPositiveButton("打开", (d, w) -> {
            List<String> sel = new ArrayList<>();
            for (int i = 0; i < arr.length && sel.size() < 4; i++) if (checked[i]) sel.add(arr[i]);
            if (sel.size() < 4) {
                Ui.toast(this, "请选满 4 路（选中 " + sel.size() + " 路）");
                return;
            }
            Intent i = new Intent(this, QuadActivity.class);
            i.putExtra("group", cfg.currentGroup);
            i.putExtra("start", cfg.currentPage * cfg.itemsPerPage);
            startActivity(i);
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    /** 一键把全部摄像头切换认证方式（Basic 隧道 / 自动） */
    private void setAllAuthMode(String mode) {
        final int total = cfg.totalCameraCount();
        if (total == 0) {
            Ui.toast(this, "还没有摄像头");
            return;
        }
        String title = mode.equals("basic") ? "全部改用 Basic 隧道" : "全部改回自动认证";
        String msg = mode.equals("basic")
                ? "将把全部 " + total + " 个摄像头改成 Basic 注入隧道播放（设备只认 Basic 时用）。\n改完会刷新并重连，确认继续？"
                : "将把全部 " + total + " 个摄像头改回自动认证（Media3 优先）。\n确认继续？";
        new AlertDialog.Builder(this, R.style.Theme_CamView_Dialog)
                .setTitle(title)
                .setMessage(msg)
                .setPositiveButton("确定", (d, w) -> {
                    cfg.setAllAuthMode(mode);
                    refreshAll();
                    restartAll();
                    Ui.toast(this, "已应用：" + title + "（" + total + " 路）");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 分组 ====================

    private void showGroupDialog() {
        final List<String> names = cfg.groupNames();
        final String[] arr = names.toArray(new String[0]);
        int checked = names.indexOf(cfg.currentGroup);

        AlertDialog.Builder b = Ui.dialog(this);
        b.setTitle("📁 分组管理");
        b.setSingleChoiceItems(arr, checked, (dialog, which) -> {
            String target = arr[which];
            if (!target.equals(cfg.currentGroup)) {
                cfg.currentGroup = target;
                cfg.currentPage = 0;
                cfg.save();
                refreshAll();
            }
            dialog.dismiss();
        });
        b.setPositiveButton("新建", (d, w) -> newGroupDialog());
        b.setNeutralButton("重命名", (d, w) -> renameGroupDialog());
        b.setNegativeButton("删除", (d, w) -> deleteGroupDialog());
        AlertDialog d = b.create();
        Ui.styleDialog(d);
        d.show();
    }

    private void newGroupDialog() {
        LinearLayout box = Ui.column(this);
        box.addView(Ui.title(this, "新建分组"));
        final EditText input = Ui.input(this, "", "分组名称", false);
        box.addView(input);
        AlertDialog d = Ui.dialog(this).setView(box)
                .setPositiveButton("创建", null)
                .setNegativeButton("取消", null)
                .create();
        Ui.styleDialog(d);
        d.setOnShowListener(dd -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.length() == 0) {
                Ui.toast(this, "请输入分组名称");
                return;
            }
            if (!cfg.addGroup(name)) {
                Ui.toast(this, "分组已存在");
                return;
            }
            cfg.currentGroup = name;
            cfg.currentPage = 0;
            cfg.save();
            d.dismiss();
            refreshAll();
        }));
        d.show();
    }

    private void renameGroupDialog() {
        if (cfg.currentGroup.equals(AppConfig.DEFAULT_GROUP)) {
            Ui.toast(this, "默认分组不能重命名");
            return;
        }
        LinearLayout box = Ui.column(this);
        box.addView(Ui.title(this, "重命名分组"));
        final EditText input = Ui.input(this, cfg.currentGroup, "新名称", false);
        box.addView(input);
        AlertDialog d = Ui.dialog(this).setView(box)
                .setPositiveButton("保存", null)
                .setNegativeButton("取消", null)
                .create();
        Ui.styleDialog(d);
        d.setOnShowListener(dd -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (!cfg.renameGroup(cfg.currentGroup, name)) {
                Ui.toast(this, "名称无效或已存在");
                return;
            }
            d.dismiss();
            refreshAll();
        }));
        d.show();
    }

    private void deleteGroupDialog() {
        if (cfg.currentGroup.equals(AppConfig.DEFAULT_GROUP)) {
            Ui.toast(this, "默认分组不能删除");
            return;
        }
        final String target = cfg.currentGroup;
        new AlertDialog.Builder(this, R.style.Theme_CamView_Dialog)
                .setTitle("删除分组")
                .setMessage("确定删除分组「" + target + "」？\n该分组下的摄像头会移动到「默认分组」。")
                .setPositiveButton("删除", (d, w) -> {
                    cfg.deleteGroup(target);
                    refreshAll();
                    Ui.toast(this, "分组已删除");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 添加 / 删除摄像头 ====================

    private void addSingleCamera() {
        LinearLayout box = Ui.column(this);
        box.addView(Ui.title(this, "添加摄像头"));
        box.addView(Ui.label(this, "IP 地址（也可以直接粘贴浏览器里能打开的那个地址）"));
        final EditText ipIn = Ui.input(this, "", "192.168.31.89", false);
        ipIn.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(ipIn);
        box.addView(Ui.label(this, "用户名（留空用默认：" + cfg.defaultUser + "）"));
        final EditText userIn = Ui.input(this, "", cfg.defaultUser, false);
        box.addView(userIn);
        box.addView(Ui.label(this, "密码（留空用默认）"));
        final EditText pwdIn = Ui.input(this, "", cfg.defaultPassword, true);
        box.addView(pwdIn);
        box.addView(Ui.label(this, "将添加到分组：" + cfg.currentGroup));

        AlertDialog d = Ui.dialog(this).setView(box)
                .setPositiveButton("添加", null)
                .setNegativeButton("取消", null)
                .create();
        Ui.styleDialog(d);
        d.setOnShowListener(dd -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String ip = RtspProbe.normalizeHost(ipIn.getText().toString());
            if (!NetUtil.isValidIp(ip)) {
                Ui.toast(this, "IP 格式不正确：从地址里没识别出 IP");
                return;
            }
            String user = userIn.getText().toString().trim();
            String pwd = pwdIn.getText().toString().trim();
            if (!cfg.addCamera(ip, user, pwd)) {
                Ui.toast(this, "该摄像头已存在");
                return;
            }
            d.dismiss();
            cfg.currentPage = cfg.totalPages() - 1;
            refreshAll();
            Ui.toast(this, "已添加 " + ip);
        }));
        d.show();
    }

    private void addBatchCamera() {
        LinearLayout box = Ui.column(this);
        box.addView(Ui.title(this, "批量添加摄像头"));
        box.addView(Ui.label(this, "IP 范围（支持 1-254、完整范围、/24 网段，多个用逗号分隔）"));
        final EditText rangeIn = Ui.input(this, "", "192.168.31.88-120", false);
        box.addView(rangeIn);
        box.addView(Ui.label(this, "用户名（留空用默认）"));
        final EditText userIn = Ui.input(this, "", cfg.defaultUser, false);
        box.addView(userIn);
        box.addView(Ui.label(this, "密码（留空用默认）"));
        final EditText pwdIn = Ui.input(this, "", cfg.defaultPassword, true);
        box.addView(pwdIn);

        AlertDialog d = Ui.dialog(this).setView(box)
                .setPositiveButton("批量添加", null)
                .setNegativeButton("取消", null)
                .create();
        Ui.styleDialog(d);
        d.setOnShowListener(dd -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String input = rangeIn.getText().toString().trim();
            if (input.length() == 0) {
                Ui.toast(this, "请输入 IP 范围");
                return;
            }
            List<String> ips = NetUtil.expandInput(input, 2000);
            if (ips.isEmpty()) {
                Ui.toast(this, "IP 范围格式不正确");
                return;
            }
            String user = userIn.getText().toString().trim();
            String pwd = pwdIn.getText().toString().trim();
            int added = cfg.addCameras(ips, user, pwd);
            d.dismiss();
            refreshAll();
            Ui.toast(this, "成功添加 " + added + " 个摄像头");
        }));
        d.show();
    }

    /** 进入摄像头体检（真实解码预览，判断哪些连得上但没画面） */
    private void openNoFrameCheck() {
        String[] items = {"只查当前分组（" + cfg.groupCameraCount() + " 路，较快）",
                "查全部摄像头（" + cfg.totalCameraCount() + " 路，较慢）"};
        Ui.actionSheet(this, "检测范围", items, (index, text) -> {
            Intent i = new Intent(this, CheckActivity.class);
            i.putExtra("all", index == 1);
            startActivity(i);
        });
    }

    // ==================== 就地多选（不弹窗） ====================

    /** 在列表里直接进入勾选模式；seed 为长按的那一路（自动勾上） */
    private void enterSelectMode(String seedIp) {
        if (cfg.groupCameraCount() <= 0) {
            Ui.toast(this, "当前分组没有摄像头");
            return;
        }
        selectMode = true;
        selectedIps.clear();
        if (seedIp != null) selectedIps.add(seedIp);
        renderPage();
        showSelectBar(true);
        Ui.toast(this, "已进入多选：点摄像头勾选，完成后用底部按钮操作");
    }

    private void exitSelectMode() {
        selectMode = false;
        selectedIps.clear();
        showSelectBar(false);
        // 只恢复点击行为，不重建播放器（重建会让所有摄像头重新连接，非常卡）
        for (CameraRow r : rows) bindRowNormal(r);
    }

    /** 把某一行恢复成正常行为（点=全屏，长按=功能菜单） */
    private void bindRowNormal(final CameraRow row) {
        row.setSelectMode(false, false);
        row.setOnClickListener(v -> openFullscreen(row.ip));
        row.setOnLongClickListener(v -> {
            showCameraItemMenu(row);
            return true;
        });
    }

    /** 移动/删除后只摘掉这几行，其余画面保持连续，不重新加载 */
    private void removeRowsQuietly(java.util.Collection<String> gone) {
        for (CameraRow r : new ArrayList<>(rows)) {
            if (gone.contains(r.ip)) {
                listContainer.removeView(r);
                try { r.destroy(); } catch (Throwable ignored) { }
                rows.remove(r);
            } else {
                bindRowNormal(r);
            }
        }
        // 当前页被搬空且后面还有设备 → 补一页（只在这种情况下才真正重载）
        if (rows.isEmpty() && cfg.groupCameraCount() > 0) {
            if (cfg.currentPage > 0 && cfg.currentPage >= cfg.totalPages()) {
                cfg.currentPage = Math.max(0, cfg.totalPages() - 1);
            }
            renderPage();
            return;
        }
        updatePageControls();
        updateVisibility();
    }

    private void toggleSelect(String ip, CameraRow row) {
        if (selectedIps.contains(ip)) selectedIps.remove(ip);
        else selectedIps.add(ip);
        row.setSelected(selectedIps.contains(ip));
        updateSelectBar();
    }

    private void toggleSelect(String ip) {
        if (selectedIps.contains(ip)) selectedIps.remove(ip);
        else selectedIps.add(ip);
        for (CameraRow r : rows) {
            if (ip.equals(r.ip)) r.setSelected(selectedIps.contains(ip));
        }
        updateSelectBar();
    }

    private void showSelectBar(boolean show) {
        if (selectBar == null) buildSelectBar();
        selectBar.setVisibility(show ? android.view.View.VISIBLE : android.view.View.GONE);
        if (show) updateSelectBar();
    }

    private void buildSelectBar() {
        selectBar = new LinearLayout(this);
        selectBar.setOrientation(LinearLayout.VERTICAL);
        selectBar.setBackgroundColor(0xF2161B22);
        selectBar.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));

        selectCountLabel = new TextView(this);
        selectCountLabel.setTextColor(0xFF58A6FF);
        selectCountLabel.setTextSize(12);
        selectCountLabel.setPadding(Ui.dp(this, 4), 0, 0, Ui.dp(this, 2));
        selectBar.addView(selectCountLabel);

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"全选本组", "清空", "移动到分组", "删除", "取消"};
        int[] colors = {0xFFC9D1D9, 0xFFC9D1D9, 0xFF3FB950, 0xFFF85149, 0xFF8B949E};
        Runnable[] actions = {
                this::selectAllInGroup, this::clearSelection,
                this::moveSelectedToGroup, this::deleteSelected, this::exitSelectMode
        };
        for (int i = 0; i < labels.length; i++) {
            TextView b = new TextView(this);
            b.setText(labels[i]);
            b.setTextSize(13);
            b.setTextColor(colors[i]);
            b.setGravity(android.view.Gravity.CENTER);
            b.setBackgroundResource(R.drawable.bg_toolbar_button);
            final Runnable act = actions[i];
            b.setOnClickListener(v -> act.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(this, 38), 1f);
            lp.leftMargin = Ui.dp(this, 2);
            lp.rightMargin = Ui.dp(this, 2);
            line.addView(b, lp);
        }
        selectBar.addView(line);

        // 压在列表上方（列表区域底部）
        LinearLayout root = (LinearLayout) listScroll.getParent();
        int index = root.indexOfChild(statusBar);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        root.addView(selectBar, Math.max(0, index), lp);
        selectBar.setVisibility(android.view.View.GONE);
    }

    private void updateSelectBar() {
        if (selectCountLabel != null) {
            selectCountLabel.setText("已勾选 " + selectedIps.size() + " 个 · 点摄像头可勾选/取消");
        }
    }

    private void selectAllInGroup() {
        selectedIps.clear();
        List<String> ips = cfg.groups.get(cfg.currentGroup);
        if (ips != null) selectedIps.addAll(ips);
        for (CameraRow r : rows) r.setSelected(selectedIps.contains(r.ip));
        updateSelectBar();
    }

    private void clearSelection() {
        selectedIps.clear();
        for (CameraRow r : rows) r.setSelected(false);
        updateSelectBar();
    }

    private void moveSelectedToGroup() {
        if (selectedIps.isEmpty()) {
            Ui.toast(this, "还没有勾选摄像头");
            return;
        }
        List<String> targets = cfg.groupNames();
        targets.remove(cfg.currentGroup);
        if (targets.isEmpty()) {
            Ui.toast(this, "没有其他分组，请先新建分组");
            return;
        }
        final String[] arr = targets.toArray(new String[0]);
        Ui.actionSheet(this, "移动 " + selectedIps.size() + " 路到", arr, (index, text) -> {
            List<String> picked = new ArrayList<>(selectedIps);
            List<String> movedIps = new ArrayList<>();
            int moved = 0;
            // 直接改内存里的分组表，最后统一 save 一次（逐个 save 会反复写盘）
            List<String> src = cfg.groups.get(cfg.currentGroup);
            List<String> dst = cfg.groups.get(arr[index]);
            if (src != null && dst != null) {
                for (String ip : picked) {
                    if (src.remove(ip)) {
                        if (!dst.contains(ip)) dst.add(ip);
                        movedIps.add(ip);
                        moved++;
                    }
                }
                cfg.save();
            }
            selectMode = false;
            selectedIps.clear();
            showSelectBar(false);
            removeRowsQuietly(movedIps);
            if (groupChip != null) groupChip.setText("📁 " + cfg.currentGroup);
            Ui.toast(this, "已移动 " + moved + " 个到「" + arr[index] + "」");
        });
    }

    private void deleteSelected() {
        if (selectedIps.isEmpty()) {
            Ui.toast(this, "还没有勾选摄像头");
            return;
        }
        final List<String> picked = new ArrayList<>(selectedIps);
        new AlertDialog.Builder(this, R.style.Theme_CamView_Dialog)
                .setTitle("删除 " + picked.size() + " 个摄像头")
                .setMessage("将删除以下设备：\n" + picked.subList(0, Math.min(8, picked.size()))
                        .toString().replace("[", "").replace("]", "")
                        + (picked.size() > 8 ? " 等 " + picked.size() + " 个" : ""))
                .setPositiveButton("删除", (d, w) -> {
                    for (String ip : picked) cfg.removeCamera(ip);
                    selectMode = false;
                    selectedIps.clear();
                    showSelectBar(false);
                    removeRowsQuietly(picked);
                    Ui.toast(this, "已删除 " + picked.size() + " 个");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteCameras() {
        final List<String> all = cfg.allIps();
        if (all.isEmpty()) {
            Ui.toast(this, "系统中还没有摄像头");
            return;
        }
        final String[] arr = all.toArray(new String[0]);
        final boolean[] checked = new boolean[arr.length];
        new AlertDialog.Builder(this, R.style.Theme_CamView_Dialog)
                .setTitle("删除摄像头（可多选）")
                .setMultiChoiceItems(arr, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("删除选中", (d, w) -> {
                    int n = 0;
                    for (int i = 0; i < arr.length; i++) {
                        if (checked[i]) {
                            cfg.removeCamera(arr[i]);
                            n++;
                        }
                    }
                    refreshAll();
                    Ui.toast(this, "已删除 " + n + " 个摄像头");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ==================== 列表 / 分页 ====================

    private void renderPage() {
        for (CameraRow r : rows) {
            listContainer.removeView(r);
            r.destroy();
        }
        rows.clear();
        listContainer.removeAllViews();

        List<String> ips = cfg.pageCameras();
        if (ips.isEmpty()) {
            emptyView = new TextView(this);
            emptyView.setText("还没有摄像头\n\n点击上方「📷 摄像头」添加，或「🔍 扫描网段」自动发现");
            emptyView.setTextColor(0xFF8B949E);
            emptyView.setTextSize(14);
            emptyView.setGravity(Gravity.CENTER);
            emptyView.setPadding(0, Ui.dp(this, 80), 0, 0);
            listContainer.addView(emptyView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            emptyView = null;
        }

        for (final String ip : ips) {
            CameraInfo info = cfg.findCamera(ip);
            if (info == null) info = cfg.getOrCreateCamera(ip);
            final CameraRow row = new CameraRow(this, cfg, info);
            if (selectMode) {
                row.setSelectMode(true, selectedIps.contains(ip));
                row.setOnClickListener(v -> toggleSelect(ip, row));
                row.setOnLongClickListener(v -> {
                    toggleSelect(ip, row);
                    return true;
                });
            } else {
                bindRowNormal(row);
            }
            listContainer.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rows.add(row);
        }

        updatePageControls();
        listScroll.post(new Runnable() {
            @Override
            public void run() {
                listScroll.scrollTo(0, 0);
                updateVisibility();
            }
        });
    }

    private void refreshAll() {
        for (CameraRow r : rows) {
            listContainer.removeView(r);
            r.destroy();
        }
        rows.clear();
        listContainer.removeAllViews();
        if (groupChip != null) groupChip.setText("📁 " + cfg.currentGroup);
        renderPage();
        lastRevision = cfg.revision;
    }

    private void updatePageControls() {
        int total = cfg.totalPages();
        if (cfg.currentPage >= total) cfg.currentPage = total - 1;
        if (cfg.currentPage < 0) cfg.currentPage = 0;
        pageLabel.setText("第 " + (cfg.currentPage + 1) + " / " + total + " 页");
        prevBtn.setEnabled(cfg.currentPage > 0);
        prevBtn.setAlpha(cfg.currentPage > 0 ? 1f : 0.4f);
        nextBtn.setEnabled(cfg.currentPage + 1 < total);
        nextBtn.setAlpha(cfg.currentPage + 1 < total ? 1f : 0.4f);
        groupStats.setText("本组 " + cfg.groupCameraCount() + " 个 · 总计 " + cfg.totalCameraCount() + " 个");
        updateStatusBar();
    }

    private void prevPage() {
        if (cfg.currentPage > 0) {
            cfg.currentPage--;
            renderPage();
        }
    }

    private void nextPage() {
        if (cfg.currentPage + 1 < cfg.totalPages()) {
            cfg.currentPage++;
            renderPage();
        }
    }

    // ==================== 可见性（懒加载/自动暂停） ====================

    private void updateVisibility() {
        int sy = listScroll.getScrollY();
        int vh = listScroll.getHeight();
        int margin = Ui.dp(this, 120);
        for (CameraRow row : rows) {
            boolean visible = row.getBottom() > sy - margin && row.getTop() < sy + vh + margin;
            if (row.stream.isActive() != visible) {
                row.setRowActive(visible);
            }
        }
        updateStatusBar();
    }

    private void updateStatusBar() {
        int live = 0;
        for (CameraRow r : rows) {
            if (r.stream.state == CameraStream.ST_LIVE
                    && (r.stream.fps > 0.01f || r.stream.isVlcMode())) live++;
        }
        String decode = cfg.hwDecode ? "硬件解码(MediaCodec)" : "软件解码";
        if (cfg.hwDecode && ("cuda".equals(cfg.gpuMode) || "hardware".equals(cfg.gpuMode))) {
            decode = "强制硬件解码";
        }
        statusBar.setText(decode + " · " + cfg.currentGroup + " · 活跃 " + live + "/" + rows.size()
                + " · 总计 " + cfg.totalCameraCount() + " 个 · " + timeFmt.format(new Date()));
    }

    // ==================== 单路操作 ====================

    private void openFullscreen(String ip) {
        Intent i = new Intent(this, FullscreenActivity.class);
        i.putExtra("ip", ip);
        startActivity(i);
    }

    /** 手动填一条 HTTP 抓图地址（浏览器 F12 里抓到的那个 image/jpeg 请求） */
    private void customSnapUrl(final CameraRow row) {
        LinearLayout box = Ui.column(this);
        box.addView(Ui.title(this, "自定义抓图地址"));
        box.addView(Ui.label(this, "填网页里返回图片的那个地址（http://... 开头）"));
        final EditText input = Ui.input(this, "http://" + row.ip + "/cgi-bin/snapshot.cgi?channel=1",
                row.info.snapUrl == null ? "" : row.info.snapUrl, false);
        box.addView(input);
        AlertDialog d = Ui.dialog(this).setView(box)
                .setPositiveButton("使用这个地址", null)
                .setNegativeButton("取消", null)
                .create();
        Ui.styleDialog(d);
        d.setOnShowListener(dd -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String url = input.getText().toString().trim();
            if (!url.startsWith("http")) {
                Ui.toast(this, "地址要以 http:// 开头");
                return;
            }
            d.dismiss();
            applySnapMode(row, url);
        }));
        d.show();
    }

    private void showCameraItemMenu(final CameraRow row) {
        String[] items = {"🔍 放大画面", "🩺 连接诊断（连不上先点这个）", "📷 网页抓图模式（从 HTTP 拉图）",
                "☑ 多选（在当前列表勾选，再一起移动）", "📁 移动到分组", "🔄 旋转画面", "↔ 水平翻转", "↕ 垂直翻转", "♻ 重新连接", "🗑 删除摄像头", "🔗 自定义抓图地址", "🔐 强制 Basic 认证（隧道）"};
        Ui.actionSheet(this, row.ip, items, (index, text) -> {
            switch (index) {
                case 0:
                    openFullscreen(row.ip);
                    break;
                case 1:
                    diagnoseCamera(row);
                    break;
                case 2:
                    row.stream.switchToSnap(null);
                    Ui.toast(this, "已切换到网页抓图模式（HTTP 拉图）\n" + row.ip);
                    break;
                case 3:
                    enterSelectMode(row.ip);
                    break;
                case 4:
                    moveCameraDialog(row.ip);
                    break;
                case 5:
                    chooseRotation(row);
                    break;
                case 6:
                    row.stream.toggleFlipH();
                    cfg.save();
                    row.updateTag();
                    break;
                case 7:
                    row.stream.toggleFlipV();
                    cfg.save();
                    row.updateTag();
                    break;
                case 8:
                    row.stream.restart();
                    Ui.toast(this, "正在重新连接 " + row.ip);
                    break;
                case 9:
                    new AlertDialog.Builder(this, R.style.Theme_CamView_Dialog)
                            .setTitle("删除摄像头")
                            .setMessage("确定删除 " + row.ip + " ？")
                            .setPositiveButton("删除", (d, w) -> {
                                cfg.removeCamera(row.ip);
                                refreshAll();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    break;
                case 10:
                    customSnapUrl(row);
                    break;
                case 11:
                    boolean on = "basic".equals(row.info.authMode);
                    row.info.authMode = on ? "" : "basic";
                    cfg.save();
                    row.stream.setAuthMode(row.info.authMode);
                    Ui.toast(this, on ? "已关闭 Basic 隧道，改回自动" : "已启用 Basic 隧道（设备只认 Basic 时用）");
                    break;
            }
        });
    }

    // ==================== 连接诊断 ====================

    private void diagnoseCamera(final CameraRow row) {
        final CameraInfo info = row.info;
        final AlertDialog progress = new AlertDialog.Builder(this, R.style.Theme_CamView_Dialog)
                .setTitle("正在诊断 " + info.ip)
                .setMessage("探测端口 → 认证 → 路径 → 编码，大约 10~20 秒…")
                .setCancelable(false)
                .create();
        progress.show();
        final Context appCtx = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String report = RtspProbe.run(info.ip, info.user, info.password,
                        Math.min(cfg.timeoutMs, 3000));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try { progress.dismiss(); } catch (Exception ignored) { }
                        showReportDialog("诊断报告 · " + info.ip, report, row);
                    }
                });
            }
        }).start();
    }

    /** 诊断发现可用路径后，一键把它设成第一优先模板 */
    private void applyWorkingTemplate(final CameraRow row) {
        String tpl = RtspProbe.workingTemplate;
        if (tpl == null || tpl.isEmpty()) {
            Ui.toast(this, "没有探测到可用路径，先看报告里的结论");
            return;
        }
        List<String> tpls = new ArrayList<>(cfg.rtspTemplates);
        tpls.remove(tpl);
        tpls.add(0, tpl);
        cfg.rtspTemplates = tpls;
        if (RtspProbe.workingBasic) {
            row.info.authMode = "basic";
        }
        cfg.save();
        row.stream.setAuthMode(row.info.authMode == null ? "" : row.info.authMode);
        Ui.toast(this, "已设为第一优先地址，正在重连：" + row.ip);
    }

    /** 诊断发现可用抓图地址后，切成网页抓图模式 */
    private void applySnapMode(final CameraRow row, String url) {
        row.info.snapUrl = url;
        row.stream.switchToSnap(url);
        cfg.save();
        Ui.toast(this, "已切到网页抓图模式：" + url);
    }

    private void showReportDialog(String title, String report, final CameraRow row) {
        final boolean hasTemplate = RtspProbe.workingTemplate != null;
        final String snapUrl = RtspProbe.workingSnap;
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(report);
        tv.setTextSize(13);
        tv.setTextColor(0xFFE6EDF3);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setLineSpacing(0, 1.4f);
        int p = Ui.dp(this, 16);
        tv.setPadding(p, p, p, p);
        tv.setTextIsSelectable(true);
        sv.addView(tv);
        AlertDialog.Builder b = new AlertDialog.Builder(this, R.style.Theme_CamView_Dialog)
                .setTitle(title)
                .setView(sv);
        if (snapUrl != null && !snapUrl.isEmpty()) {
            b.setPositiveButton("📷 用网页抓图模式", (d, w) -> applySnapMode(row, snapUrl));
        } else if (hasTemplate) {
            b.setPositiveButton("一键用这个地址", (d, w) -> applyWorkingTemplate(row));
        }
        b.setNeutralButton("🔑 换个密码重测", (d, w) -> retestWithPassword(row));
        b.setNegativeButton("关闭", null);
        b.show();
    }

    /** 用用户输入的账号密码重新诊断，通过就保存到这台摄像头 */
    private void retestWithPassword(final CameraRow row) {
        LinearLayout box = Ui.column(this);
        box.addView(Ui.title(this, "用正确的账号密码重测"));
        box.addView(Ui.label(this, "账号"));
        final EditText userIn = Ui.input(this, "",
                row.info.user == null || row.info.user.isEmpty() ? "admin" : row.info.user, false);
        box.addView(userIn);
        box.addView(Ui.label(this, "密码（填网页登录时用的那个，如 admin123）"));
        final EditText pwdIn = Ui.input(this, "", row.info.password == null ? "" : row.info.password, true);
        box.addView(pwdIn);
        box.addView(Ui.label(this, "测试通过会自动保存到这台摄像头并重连"));
        AlertDialog d = Ui.dialog(this).setView(box)
                .setPositiveButton("开始测试", null)
                .setNegativeButton("取消", null)
                .create();
        Ui.styleDialog(d);
        d.setOnShowListener(dd -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String user = userIn.getText().toString().trim();
            final String pwd = pwdIn.getText().toString();
            d.dismiss();
            final AlertDialog prog = new AlertDialog.Builder(this, R.style.Theme_CamView_Dialog)
                    .setTitle("正在测试 " + row.ip)
                    .setMessage("探测端口 → 认证 → 路径，约 10~20 秒…")
                    .setCancelable(false)
                    .create();
            prog.show();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String report = RtspProbe.run(row.ip, user, pwd, Math.min(cfg.timeoutMs, 3000));
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try { prog.dismiss(); } catch (Exception ignored) { }
                            if (RtspProbe.workingTemplate != null || RtspProbe.credentialsValid) {
                                cfg.setCameraCredentials(row.ip, user, pwd);
                                row.info.user = user;
                                row.info.password = pwd;
                                if (RtspProbe.workingTemplate != null) {
                                    applyWorkingTemplate(row);
                                } else {
                                    row.stream.restart();
                                }
                                Ui.toast(MainActivity.this, "认证通过，已保存账号密码并重连");
                            }
                            showReportDialog("诊断报告 · " + row.ip, report, row);
                        }
                    });
                }
            }).start();
        }));
        d.show();
    }

    private void chooseRotation(final CameraRow row) {
        String[] items = {"0°", "90°", "180°", "270°"};
        Ui.actionSheet(this, "旋转画面", items, (index, text) -> {
            row.stream.setRotation(index * 90);
            cfg.save();
            row.updateTag();
        });
    }

    private void moveCameraDialog(final String ip) {
        List<String> groups = cfg.groupNames();
        groups.remove(cfg.currentGroup);
        if (groups.isEmpty()) {
            Ui.toast(this, "没有其他分组，请先新建分组");
            return;
        }
        final String[] arr = groups.toArray(new String[0]);
        new AlertDialog.Builder(this, R.style.Theme_CamView_Dialog)
                .setTitle("移动到分组")
                .setItems(arr, (d, which) -> {
                    cfg.moveCamera(ip, cfg.currentGroup, arr[which]);
                    refreshAll();
                    Ui.toast(this, "已移动 " + ip + " 到「" + arr[which] + "」");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void restartAll() {
        for (CameraRow r : rows) r.stream.restart();
        Ui.toast(this, "正在重新连接所有摄像头…");
    }

    private void importConfig() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "text/*"});
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (Exception e) {
            Ui.toast(this, "无法打开文件选择器");
        }
    }

    private void showAbout() {
        String ver = BuildConfig.VERSION_NAME;
        String repo = cfg.githubRepo.length() == 0 ? "（未配置更新仓库）" : cfg.githubRepo;
        String msg = "多路监控 v" + ver + "\n\n"
                + "· 手机端采用单列布局，一行一路，滚出屏幕自动暂停解码，保证流畅。\n"
                + "· GPU 加速：使用 MediaCodec 硬件解码（H.264/H.265），解码与上屏都走 GPU；"
                + "设置里可切换「强制硬件 / 软件兼容」。\n"
                + "· 支持分组管理、分页、批量添加、网段扫描、旋转翻转、放大画面（可拖动/双指缩放）。\n"
                + "· 配置文件与电脑端 camera_system_config.json 字段一致，可从「更多 → 导入电脑端配置」迁移。\n\n"
                + "更新仓库：" + repo;
        new AlertDialog.Builder(this, R.style.Theme_CamView_Dialog)
                .setTitle("关于 / 使用说明")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .setNeutralButton("检查更新", (d, w) -> UpdateChecker.check(this, false))
                .show();
    }

    // ==================== 生命周期 ====================

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
            quadOpening = false;
            return;
        }
        if (newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                && !quadOpening) {
            quadOpening = true;
            Intent i = new Intent(this, QuadActivity.class);
            i.putExtra("group", cfg.currentGroup);
            i.putExtra("start", cfg.currentPage * cfg.itemsPerPage);
            startActivity(i);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (cfg.revision != lastRevision) {
            refreshAll();
        } else {
            listScroll.post(new Runnable() {
                @Override
                public void run() {
                    updateVisibility();
                }
            });
        }
        ui.removeCallbacks(clockTask);
        ui.post(clockTask);
    }

    @Override
    protected void onPause() {
        super.onPause();
        for (CameraRow r : rows) r.setRowActive(false);
        ui.removeCallbacks(clockTask);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        for (CameraRow r : rows) r.destroy();
        rows.clear();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] buf = new byte[65536];
                int n;
                StringBuilder sb = new StringBuilder();
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                String result = cfg.importFromJson(sb.toString());
                refreshAll();
                Ui.toast(this, result);
            } catch (Exception e) {
                Ui.toast(this, "导入失败：" + e.getMessage());
            }
        }
    }
}
