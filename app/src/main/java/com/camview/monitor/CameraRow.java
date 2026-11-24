package com.camview.monitor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.view.TextureView;

public class CameraRow extends FrameLayout {

    public final String ip;
    public final CameraInfo info;
    public final CameraStream stream;

    private final TextureView texture;
    private final FrameLayout videoHolder;
    private final TextView statusText;
    private final TextView ipText;
    private final TextView fpsText;
    private final TextView tagText;
    private final View dot;
    private android.widget.CheckBox selectBox;
    private boolean selectMode = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AppConfig cfg;
    private boolean destroyed = false;
    private boolean attached = false;
    private long lastLiveTs = 0;

    public CameraRow(Context ctx, AppConfig config, CameraInfo cam) {
        super(ctx);
        this.cfg = config;
        this.info = cam;
        this.ip = cam.ip;
        LayoutInflater.from(ctx).inflate(R.layout.view_camera_row, this, true);

        texture = findViewById(R.id.texture);
        videoHolder = findViewById(R.id.videoHolder);
        statusText = findViewById(R.id.statusText);
        ipText = findViewById(R.id.ipText);
        fpsText = findViewById(R.id.fpsText);
        tagText = findViewById(R.id.tagText);
        dot = findViewById(R.id.dot);

        ipText.setText(cam.ip);

        selectBox = new android.widget.CheckBox(ctx);
        selectBox.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFF58A6FF));
        selectBox.setClickable(false);
        selectBox.setFocusable(false);
        selectBox.setVisibility(GONE);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                Ui.dp(ctx, 34), Ui.dp(ctx, 34));
        slp.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        slp.leftMargin = Ui.dp(ctx, 4);
        slp.topMargin = Ui.dp(ctx, 4);
        addView(selectBox, slp);

        stream = new CameraStream(ctx, config, cam);
        updateTag();
        updateUi();

        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
                updateUi();
                ui.postDelayed(this, 1200);
            }
        }, 800);
    }

    /** 进入可见区域时调用 */
    public void attachStream() {
        if (attached || destroyed) return;
        attached = true;
        stream.attach(texture, videoHolder);
    }

    public void setRowActive(boolean active) {
        if (destroyed) return;
        if (active) attachStream();
        stream.setActive(active);
    }

    /** 进入/退出勾选模式：显示勾选框并禁用放大点击 */
    public void setSelectMode(boolean on, boolean checked) {
        selectMode = on;
        if (selectBox == null) return;
        selectBox.setVisibility(on ? VISIBLE : GONE);
        selectBox.setChecked(checked);
    }

    public void setSelected(boolean checked) {
        if (selectBox != null) selectBox.setChecked(checked);
    }

    public boolean isSelectMode() {
        return selectMode;
    }

    public void updateTag() {
        StringBuilder sb = new StringBuilder();
        if (info.rotation != 0) sb.append("旋转").append(info.rotation).append("°");
        if (info.flipH) sb.append(sb.length() > 0 ? " · " : "").append("水平翻转");
        if (info.flipV) sb.append(sb.length() > 0 ? " · " : "").append("垂直翻转");
        if (stream.isSnapMode()) sb.append(sb.length() > 0 ? " · " : "").append("网页抓图");
        tagText.setText(sb.toString());
        tagText.setVisibility(sb.length() > 0 ? VISIBLE : GONE);
        // 变换改了要重新应用
        if (attached) {
            stream.rotation = info.rotation;
            stream.flipH = info.flipH;
            stream.flipV = info.flipV;
            stream.applyTransform();
        }
    }

    private void updateUi() {
        if (destroyed) return;
        int state = stream.state;
        float fps = stream.fps;
        if (state == CameraStream.ST_LIVE) lastLiveTs = System.currentTimeMillis();

        switch (state) {
            case CameraStream.ST_LIVE:
                dot.setBackgroundResource(R.drawable.dot_green);
                if (stream.noFrame()) {
                    // 已连上但 20 秒没有画面 → 直接标红提示（长按可删除）
                    dot.setBackgroundResource(R.drawable.dot_red);
                    statusText.setVisibility(View.VISIBLE);
                    statusText.setTextColor(0xFFF85149);
                    statusText.setText("⚠ 无画面（长按可删除）");
                    break;
                }
                statusText.setTextColor(0xFF8B949E);
                // VLC/抓图通道拿不到 fps 统计，不能据此判定“没有画面”
                boolean frameSeen = stream.isVlcMode() || stream.isSnapMode() || fps > 0.01f;
                statusText.setVisibility(frameSeen ? View.GONE : View.VISIBLE);
                if (!frameSeen) statusText.setText("已连接，等待画面…");
                break;
            case CameraStream.ST_CONNECTING:
                dot.setBackgroundResource(R.drawable.dot_orange);
                statusText.setVisibility(VISIBLE);
                statusText.setText("连接中…");
                break;
            case CameraStream.ST_FAILED:
                dot.setBackgroundResource(R.drawable.dot_red);
                statusText.setVisibility(VISIBLE);
                String err = stream.lastError;
                statusText.setText(err == null || err.isEmpty()
                        ? "连接失败，正在重试…" : "连接失败：" + err);
                break;
            default:
                dot.setBackgroundResource(R.drawable.dot_gray);
                statusText.setVisibility(VISIBLE);
                statusText.setText("等待中");
                break;
        }

        if (cfg.showFps) {
            fpsText.setVisibility(VISIBLE);
            if (stream.isSnapMode()) {
                fpsText.setText(String.format(java.util.Locale.US, "抓图 %.1f fps", stream.snapFps));
                fpsText.setTextColor(stream.snapFps > 0.5f ? 0xFF3FB950 : 0xFFD29922);
            } else if (stream.isVlcMode()) {
                // 硬解通道拿不到实时帧率，不显示占位文字（“实时”观感像水印）
                if (stream.streamRate > 0.5f) {
                    fpsText.setText(String.format(java.util.Locale.US, "%.0f fps", stream.streamRate));
                    fpsText.setTextColor(0xFF8B949E);
                    fpsText.setVisibility(VISIBLE);
                } else {
                    fpsText.setVisibility(GONE);
                }
            } else {
                fpsText.setText(String.format(java.util.Locale.US, "%.1f fps", fps));
                if (fps > 15) fpsText.setTextColor(0xFF3FB950);
                else if (fps > 5) fpsText.setTextColor(0xFFD29922);
                else if (fps > 0.01f) fpsText.setTextColor(0xFFF85149);
                else fpsText.setTextColor(0xFF8B949E);
            }
        } else {
            fpsText.setVisibility(GONE);
        }
    }

    public boolean hasLiveFrame() {
        return System.currentTimeMillis() - lastLiveTs < 2000
                && (stream.fps > 0.01f || stream.isVlcMode());
    }

    public void destroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        stream.release();
    }
}
