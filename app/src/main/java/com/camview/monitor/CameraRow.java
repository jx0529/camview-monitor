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
        stream = new CameraStream(ctx, config, cam);
        updateTag();
        updateUi();

        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (destroyed) return;
                updateUi();
                ui.postDelayed(this, 800);
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

    public void updateTag() {
        StringBuilder sb = new StringBuilder();
        if (info.rotation != 0) sb.append("旋转").append(info.rotation).append("°");
        if (info.flipH) sb.append(sb.length() > 0 ? " · " : "").append("水平翻转");
        if (info.flipV) sb.append(sb.length() > 0 ? " · " : "").append("垂直翻转");
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
                statusText.setVisibility(fps <= 0.01f ? View.VISIBLE : View.GONE);
                if (fps <= 0.01f) statusText.setText("已连接，等待画面…");
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
            if (stream.isVlcMode()) {
                fpsText.setText(stream.streamRate > 0.5f
                        ? String.format(java.util.Locale.US, "%.0f fps", stream.streamRate)
                        : "实时");
                fpsText.setTextColor(0xFF8B949E);
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
