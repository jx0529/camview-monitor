package com.camview.monitor;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.video.VideoFrameMetadataListener;

import java.util.ArrayList;
import java.util.List;

/**
 * 单路 RTSP 摄像头。
 *
 * 手机端"GPU 加速"的实现方式（与桌面版 CuPy/OpenCL 不同）：
 *  - 视频解码交给手机 GPU 上的硬件解码器 MediaCodec（H.264/H.265 硬解），
 *    这样 CPU 占用极低，多路也不卡；播放画面通过 GPU 纹理（TextureView）上屏。
 *  - 设置里可切换：自动（硬件优先）/ 强制硬件 / 软件兼容（部分老摄像头只支持特定 profile 时使用）。
 */
public class CameraStream implements Player.Listener, VideoFrameMetadataListener {

    private static final String TAG = "CameraStream";

    public static final int ST_IDLE = 0;
    public static final int ST_CONNECTING = 1;
    public static final int ST_LIVE = 2;
    public static final int ST_FAILED = 3;

    public final CameraInfo info;
    private final Context ctx;
    private final AppConfig cfg;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public int state = ST_IDLE;
    public volatile float fps = 0f;
    /** 最近一次失败原因（给界面显示，方便定位问题） */
    public volatile String lastError = "";
    /** 当前正在尝试的地址（不含密码） */
    public volatile String currentUrl = "";

    private ExoPlayer player;
    private TextureView textureView;
    private FrameLayout holder;
    private boolean released = false;
    private boolean active = true;
    private int templateIndex = 0;
    private int retryCount = 0;
    /** 当前是否用 RTP over TCP；失败一轮后自动退回 UDP 再试 */
    private boolean useTcp = true;
    private long frames = 0;
    private long fpsStart = 0;
    private int videoW = 0, videoH = 0;

    private final Runnable fpsTick = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (fpsStart == 0) fpsStart = now;
            long dt = now - fpsStart;
            if (dt >= 1000) {
                fps = frames * 1000f / dt;
                frames = 0;
                fpsStart = now;
            }
            if (!released) handler.postDelayed(this, 1000);
        }
    };

    private final Runnable reconnectTask = new Runnable() {
        @Override
        public void run() {
            if (released) return;
            startPlayer();
        }
    };

    public CameraStream(Context context, AppConfig config, CameraInfo info) {
        this.ctx = context.getApplicationContext();
        this.cfg = config;
        this.info = info;
        this.rotation = info.rotation;
        this.flipH = info.flipH;
        this.flipV = info.flipV;
    }

    public int rotation;
    public boolean flipH, flipV;

    // ==================== 生命周期 ====================

    public void attach(TextureView view, FrameLayout videoHolder) {
        this.textureView = view;
        this.holder = videoHolder;
        released = false;
        applyTransform();
        startPlayer();
        handler.post(fpsTick);
    }

    private long inactiveSince = 0;

    /** 视图滚出屏幕时暂停解码，滚回来再恢复（这是"不卡"的关键） */
    public void setActive(boolean value) {
        boolean wasActive = active;
        active = value;
        if (!value) {
            inactiveSince = System.currentTimeMillis();
            if (player != null) player.setPlayWhenReady(false);
            return;
        }
        if (wasActive) {
            if (player != null && state != ST_FAILED) player.setPlayWhenReady(true);
            return;
        }
        long pausedMs = inactiveSince == 0 ? 0 : System.currentTimeMillis() - inactiveSince;
        inactiveSince = 0;
        if (state == ST_FAILED) {
            // 失败状态回来时立刻重试
            retryCount = 0;
            templateIndex = 0;
            handler.removeCallbacks(reconnectTask);
            if (textureView != null) startPlayer();
        } else if (player != null && pausedMs > 8000) {
            // 暂停太久会积累延迟，重新连一次拿到最新画面
            restart();
        } else if (player != null) {
            player.setPlayWhenReady(true);
        }
    }

    public boolean isActive() {
        return active;
    }

    public void release() {
        released = true;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        textureView = null;
        holder = null;
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.setVideoTextureView(null);
            } catch (Exception ignored) {
            }
            try {
                player.release();
            } catch (Exception ignored) {
            }
            player = null;
        }
    }

    // ==================== 播放器构建 ====================

    private void startPlayer() {
        if (released || textureView == null) return;
        releasePlayer();
        lastError = "";
        state = ST_CONNECTING;
        try {
            DefaultRenderersFactory renderers = new DefaultRenderersFactory(ctx)
                    .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
                    .setEnableDecoderFallback(true)
                    .setMediaCodecSelector(codecSelector());

            DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1200, 5000, 900, 1500)
                    .build();

            player = new ExoPlayer.Builder(ctx, renderers)
                    .setLoadControl(loadControl)
                    .build();
            player.addListener(this);
            player.setVideoFrameMetadataListener(this);
            player.setVideoTextureView(textureView);
            player.setMediaSource(buildSource());
            player.prepare();
            player.setPlayWhenReady(active);

            if (fpsStart == 0) fpsStart = System.currentTimeMillis();
        } catch (Exception e) {
            Log.e(TAG, "创建播放器失败: " + e);
            state = ST_FAILED;
            scheduleReconnect();
        }
    }

    private MediaSource buildSource() {
        String url = buildUrl();
        currentUrl = maskPassword(url);
        RtspMediaSource.Factory factory = new RtspMediaSource.Factory()
                .setForceUseRtpTcp(useTcp)
                .setTimeoutMs(Math.max(3000, Math.min(cfg.timeoutMs, 20000)))
                .setUserAgent("CamView/1.0 Android");
        return factory.createMediaSource(MediaItem.fromUri(url));
    }

    private static String maskPassword(String url) {
        int a = url.indexOf("://");
        int at = url.indexOf('@');
        if (a < 0 || at < 0) return url;
        int colon = url.indexOf(':', a + 3);
        if (colon < 0 || colon > at) return url;
        return url.substring(0, colon + 1) + "***" + url.substring(at);
    }

    private String buildUrl() {
        List<String> templates = cfg.rtspTemplates;
        if (templates == null || templates.isEmpty()) {
            String u = info.user == null ? "" : info.user;
            String pw = info.password == null ? "" : info.password;
            String auth = (u.isEmpty() && pw.isEmpty()) ? ""
                    : AppConfig.encodeAuth(u) + ":" + AppConfig.encodeAuth(pw) + "@";
            return "rtsp://" + auth + info.ip + ":554/Streaming/Channels/102";
        }
        int idx = Math.max(0, Math.min(templateIndex, templates.size() - 1));
        return AppConfig.renderTemplate(templates.get(idx), info.ip, info.user, info.password);
    }

    /** 解码器选择：硬件优先 / 只用硬件 / 软件兼容 */
    private MediaCodecSelector codecSelector() {
        String mode = cfg.gpuMode == null ? "auto" : cfg.gpuMode;
        if (!cfg.hwDecode || mode.equals("opencl") || mode.equals("software")) {
            return MediaCodecSelector.PREFER_SOFTWARE;
        }
        if (mode.equals("cuda") || mode.equals("hardware")) {
            return new MediaCodecSelector() {
                @Override
                public List<MediaCodecInfo> getDecoderInfos(String mimeType,
                                                            boolean requiresSecureDecoder,
                                                            boolean requiresTunnelingDecoder)
                        throws MediaCodecUtil.DecoderQueryException {
                    List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT
                            .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                    List<MediaCodecInfo> hw = new ArrayList<>();
                    for (MediaCodecInfo i : all) if (i.hardwareAccelerated) hw.add(i);
                    return hw.isEmpty() ? all : hw;
                }
            };
        }
        // auto：硬件解码器排在最前
        return new MediaCodecSelector() {
            @Override
            public List<MediaCodecInfo> getDecoderInfos(String mimeType,
                                                        boolean requiresSecureDecoder,
                                                        boolean requiresTunnelingDecoder)
                    throws MediaCodecUtil.DecoderQueryException {
                List<MediaCodecInfo> all = MediaCodecSelector.DEFAULT
                        .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                List<MediaCodecInfo> hw = new ArrayList<>();
                List<MediaCodecInfo> sw = new ArrayList<>();
                for (MediaCodecInfo i : all) {
                    if (i.hardwareAccelerated) hw.add(i);
                    else sw.add(i);
                }
                hw.addAll(sw);
                return hw;
            }
        };
    }

    // ==================== 播放事件 ====================

    @Override
    public void onPlaybackStateChanged(int playbackState) {
        switch (playbackState) {
            case Player.STATE_READY:
                state = ST_LIVE;
                retryCount = 0;
                break;
            case Player.STATE_BUFFERING:
                if (state != ST_LIVE) state = ST_CONNECTING;
                break;
            case Player.STATE_ENDED:
            case Player.STATE_IDLE:
                break;
        }
    }

    @Override
    public void onPlayerError(PlaybackException error) {
        lastError = explain(error);
        Log.w(TAG, info.ip + " 播放错误: " + lastError);
        handleFailure();
    }

    /** 把播放器错误翻译成一句人能看懂的话 */
    public static String explain(PlaybackException error) {
        String raw = error.getMessage() == null ? "" : error.getMessage();
        Throwable c = error.getCause();
        String low = (raw + " " + (c == null ? "" : String.valueOf(c.getMessage()))).toLowerCase();
        if (low.contains("unauthoriz") || low.contains("401"))
            return "401 认证失败：用户名或密码不对";
        if (low.contains("404") || low.contains("not found"))
            return "404 路径不对：设备 RTSP 路径与该模板不符";
        if (low.contains("timed out") || low.contains("timeout"))
            return "连接超时：设备响应太慢或被拦截";
        if (low.contains("refused") || low.contains("failed to connect"))
            return "连不上：554 端口未开放或被防火墙拦了";
        if (low.contains("unsupported") || low.contains("codec"))
            return "解码失败：编码不支持，试试「软件兼容」模式";
        switch (error.errorCode) {
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
                return "连接超时（检查 IP 与端口）";
            case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
                return "网络连接失败（端口不通）";
            case PlaybackException.ERROR_CODE_DECODING_FAILED:
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
                return "解码器初始化失败（切「软件兼容」再试）";
            default:
                break;
        }
        if (raw.length() > 90) raw = raw.substring(0, 90) + "…";
        return raw.isEmpty() ? ("播放失败（错误码 " + error.errorCode + "）") : raw;
    }

    private void handleFailure() {
        if (released) return;
        state = ST_FAILED;
        releasePlayer();
        int count = cfg.rtspTemplates == null ? 0 : cfg.rtspTemplates.size();
        handler.removeCallbacks(reconnectTask);
        if (templateIndex + 1 < count) {
            // 换下一个 RTSP 模板（不同品牌摄像头的路径不同）
            templateIndex++;
            state = ST_CONNECTING;
            handler.postDelayed(reconnectTask, 1000);
        } else if (useTcp && count > 0) {
            // TCP 全失败，再按 UDP 把模板过一遍（有的机器只认 UDP）
            useTcp = false;
            templateIndex = 0;
            state = ST_CONNECTING;
            handler.postDelayed(reconnectTask, 1200);
        } else {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (released) return;
        templateIndex = 0;
        useTcp = cfg.rtspTcp;
        retryCount++;
        long delay = Math.min(15000L, 2000L * retryCount);
        handler.removeCallbacks(reconnectTask);
        handler.postDelayed(reconnectTask, delay);
    }

    /** 手动重连（"重启摄像头"用） */
    public void restart() {
        templateIndex = 0;
        retryCount = 0;
        useTcp = cfg.rtspTcp;
        lastError = "";
        state = ST_CONNECTING;
        handler.removeCallbacks(reconnectTask);
        if (textureView != null) startPlayer();
    }

    // ==================== 帧统计 / 画面变换 ====================

    @Override
    public void onVideoFrameAboutToBeRendered(long presentationTimeUs, long releaseTimeNs,
                                              Format format, android.media.MediaFormat mediaFormat) {
        frames++;
    }

    @Override
    public void onVideoSizeChanged(VideoSize videoSize) {
        videoW = videoSize.width;
        videoH = videoSize.height;
        layoutVideo();
    }

    public void applyTransform() {
        if (textureView == null) return;
        textureView.setRotation(rotation);
        textureView.setScaleX(flipH ? -1f : 1f);
        textureView.setScaleY(flipV ? -1f : 1f);
        layoutVideo();
    }

    /** 按视频宽高比把画面装进容器，避免拉伸变形 */
    private void layoutVideo() {
        if (textureView == null || holder == null || videoW <= 0 || videoH <= 0) return;
        holder.post(new Runnable() {
            @Override
            public void run() {
                if (textureView == null || holder == null) return;
                int w = holder.getWidth();
                int h = holder.getHeight();
                if (w <= 0 || h <= 0) return;

                boolean swap = (rotation == 90 || rotation == 270);
                float vw = swap ? videoH : videoW;
                float vh = swap ? videoW : videoH;
                float scale = Math.min(w / vw, h / vh);

                int nw = Math.max(1, (int) (videoW * scale));
                int nh = Math.max(1, (int) (videoH * scale));
                ViewGroup.LayoutParams lp = textureView.getLayoutParams();
                if (lp.width != nw || lp.height != nh) {
                    lp.width = nw;
                    lp.height = nh;
                    if (lp instanceof FrameLayout.LayoutParams) {
                        ((FrameLayout.LayoutParams) lp).gravity = android.view.Gravity.CENTER;
                    }
                    textureView.setLayoutParams(lp);
                }
            }
        });
    }

    public void setRotation(int angle) {
        rotation = ((angle % 360) + 360) % 360;
        info.rotation = rotation;
        applyTransform();
    }

    public void toggleFlipH() {
        flipH = !flipH;
        info.flipH = flipH;
        applyTransform();
    }

    public void toggleFlipV() {
        flipV = !flipV;
        info.flipV = flipV;
        applyTransform();
    }
}
