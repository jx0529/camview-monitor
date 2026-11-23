package com.camview.monitor;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.TextureView;

import java.util.Locale;

/**
 * 放大画面：全屏单路，支持双指缩放、拖动平移、双击还原，
 * 旋转/翻转设置与主界面同步。
 */
public class FullscreenActivity extends Activity {

    private CameraInfo info;
    private CameraStream stream;
    private TextureView texture;
    private TextView fpsText, statusText;
    private View dot;

    private float scale = 1f, transX = 0f, transY = 0f;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector gestureDetector;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String ip = getIntent().getStringExtra("ip");
        if (ip == null) {
            finish();
            return;
        }
        info = App.cfg().findCamera(ip);
        if (info == null) {
            finish();
            return;
        }
        setContentView(buildUi(ip));

        scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                scale = Math.max(1f, Math.min(4f, scale * detector.getScaleFactor()));
                applyTransform();
                return true;
            }
        });

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scale > 1f) {
                    transX -= dx;
                    transY -= dy;
                    clampTranslation();
                    applyTransform();
                    return true;
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                scale = 1f;
                transX = 0f;
                transY = 0f;
                applyTransform();
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                return false;
            }
        });

        texture.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                scaleDetector.onTouchEvent(event);
                gestureDetector.onTouchEvent(event);
                return true;
            }
        });

        stream = new CameraStream(this, App.cfg(), info);
        stream.rotation = info.rotation;
        stream.flipH = info.flipH;
        stream.flipV = info.flipV;
        stream.attach(texture, (FrameLayout) texture.getParent());

        ui.post(new Runnable() {
            @Override
            public void run() {
                updateHeader();
                ui.postDelayed(this, 800);
            }
        });
    }

    private View buildUi(String ip) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        FrameLayout holder = new FrameLayout(this);
        holder.setBackgroundColor(0xFF010409);
        holder.setClipChildren(true);
        root.addView(holder, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        texture = new TextureView(this);
        holder.addView(texture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        statusText = new TextView(this);
        statusText.setText("连接中…");
        statusText.setTextColor(0xFF8B949E);
        statusText.setTextSize(14);
        statusText.setBackgroundResource(R.drawable.bg_input);
        statusText.setPadding(Ui.dp(this, 16), Ui.dp(this, 7), Ui.dp(this, 16), Ui.dp(this, 7));
        FrameLayout.LayoutParams stp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stp.gravity = Gravity.CENTER;
        holder.addView(statusText, stp);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setBackgroundColor(0xCC161B22);
        header.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 6), 0);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        hlp.gravity = Gravity.TOP;
        root.addView(header, hlp);

        dot = new View(this);
        dot.setBackgroundResource(R.drawable.dot_orange);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(Ui.dp(this, 9), Ui.dp(this, 9));
        dlp.rightMargin = Ui.dp(this, 8);
        header.addView(dot, dlp);

        TextView title = new TextView(this);
        title.setText(ip);
        title.setTextColor(0xFFC9D1D9);
        title.setTextSize(15);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(title);

        fpsText = new TextView(this);
        fpsText.setTextColor(0xFF8B949E);
        fpsText.setTextSize(12);
        fpsText.setPadding(0, 0, Ui.dp(this, 10), 0);
        header.addView(fpsText);

        TextView close = new TextView(this);
        close.setText("✕");
        close.setTextColor(0xFFC9D1D9);
        close.setTextSize(20);
        close.setGravity(Gravity.CENTER);
        close.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        close.setOnClickListener(v -> finish());
        header.addView(close, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 46)));

        TextView hint = new TextView(this);
        hint.setText("双指缩放 · 拖动平移 · 双击还原");
        hint.setTextColor(0x99C9D1D9);
        hint.setTextSize(11);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 14));
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.BOTTOM;
        root.addView(hint, hp);

        return root;
    }

    private void clampTranslation() {
        int w = texture.getWidth();
        int h = texture.getHeight();
        float maxX = Math.max(0f, (scale - 1f) * w / 2f);
        float maxY = Math.max(0f, (scale - 1f) * h / 2f);
        transX = Math.max(-maxX, Math.min(maxX, transX));
        transY = Math.max(-maxY, Math.min(maxY, transY));
    }

    private void applyTransform() {
        if (texture == null) return;
        texture.setRotation(info.rotation);
        texture.setScaleX(scale * (info.flipH ? -1f : 1f));
        texture.setScaleY(scale * (info.flipV ? -1f : 1f));
        texture.setTranslationX(transX);
        texture.setTranslationY(transY);
    }

    private void updateHeader() {
        if (stream == null) return;
        float fps = stream.fps;
        boolean vlc = stream.isVlcMode();
        if (stream.state == CameraStream.ST_LIVE) {
            dot.setBackgroundResource(R.drawable.dot_green);
            statusText.setVisibility((vlc || fps > 0.01f) ? View.GONE : View.VISIBLE);
            if (!vlc && fps <= 0.01f) statusText.setText("已连接，等待画面…");
        } else if (stream.state == CameraStream.ST_FAILED) {
            dot.setBackgroundResource(R.drawable.dot_red);
            statusText.setVisibility(View.VISIBLE);
            String err = stream.lastError;
            statusText.setText(err == null || err.isEmpty() ? "连接失败，正在重试…" : "连接失败：" + err);
        } else {
            dot.setBackgroundResource(R.drawable.dot_orange);
            statusText.setVisibility(View.VISIBLE);
            statusText.setText("连接中…");
        }
        if (vlc) {
            fpsText.setText(stream.streamRate > 0.5f
                    ? String.format(Locale.US, "%.0f fps(兼容模式)", stream.streamRate)
                    : "兼容模式");
        } else {
            fpsText.setText(String.format(Locale.US, "%.1f fps", fps));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (stream != null) stream.setActive(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (stream != null) stream.setActive(true);
        applyTransform();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (stream != null) stream.release();
        stream = null;
        super.onDestroy();
    }
}
