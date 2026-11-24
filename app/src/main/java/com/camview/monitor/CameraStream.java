package com.camview.monitor;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IMedia;
import org.videolan.libvlc.interfaces.IVLCVout;

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
    /** 最后一次真正刷出画面的时间戳（用于判定“已连接但无画面”） */
    public volatile long lastFrameTs = 0L;
    /** 连接成功时刻 */
    public volatile long connectTs = 0L;
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

    // ---- VLC 备用通道（海康/大华 Digest(qop) 认证 Media3 不支持时使用）----
    private static LibVLC libVlc;
    private MediaPlayer vlcPlayer;
    private boolean vlcMode = false;
    /** VLC 模式下码流的标称帧率（实时帧率统计不到，用它显示） */
    public volatile float streamRate = 0f;

    // ---- 网页抓图模式（HTTP JPEG 轮询，RTSP 死活认证不过时的兜底）----
    private SnapPlayer snapPlayer;
    private boolean snapMode = false;
    private ImageView snapView;
    /** 抓图模式下的实际帧率 */
    public volatile float snapFps = 0f;

    // ---- Basic 认证注入隧道（设备只认 Basic、却抛 Digest 挑战时用）----
    private RtspBasicTunnel tunnel;
    private boolean tunnelTried = false;
    /** VLC 已从 UDP 退回 TCP */
    private boolean vlcForceTcp = false;
    /** 本轮流是否已经真的出过帧 */
    private volatile boolean vlcGotFrame = false;
    /** VLC 播放时间是否在推进（有推进 = 确实在出画面） */
    private volatile boolean vlcAliveSignal = false;
    private long lastVlcTime = -1;
    /** 硬解（MediaCodec）失败过一次 → 本流改走软解 */
    private boolean vlcHwOff = false;

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
            // VLC 模式下 Vout 事件只触发一次，不能靠它做帧统计：
            // 用播放时间是否推进来判断“确实在出画面”
            if (vlcMode && vlcPlayer != null) {
                try {
                    long t = vlcPlayer.getTime();
                    if (t > 0 && t != lastVlcTime) {
                        lastVlcTime = t;
                        vlcAliveSignal = true;
                        lastFrameTs = now;
                    }
                } catch (Throwable ignored) {
                }
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
        if (snapMode) startSnap();
        else startPlayer();
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
            if (vlcPlayer != null) {
                try {
                    vlcPlayer.pause();
                } catch (Throwable ignored) {
                }
            }
            if (snapPlayer != null) snapPlayer.setActive(false);
            return;
        }
        if (wasActive) {
            if (player != null && state != ST_FAILED) player.setPlayWhenReady(true);
            if (vlcPlayer != null && state != ST_FAILED) {
                try {
                    vlcPlayer.play();
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        long pausedMs = inactiveSince == 0 ? 0 : System.currentTimeMillis() - inactiveSince;
        inactiveSince = 0;
        if (snapMode) {
            if (snapPlayer != null) snapPlayer.setActive(true);
            if (state == ST_FAILED || snapPlayer == null) {
                handler.removeCallbacks(reconnectTask);
                startSnap();
            }
            return;
        }
        if (vlcMode) {
            if (state == ST_FAILED || pausedMs > 8000 || vlcPlayer == null) {
                retryCount = 0;
                templateIndex = 0;
                handler.removeCallbacks(reconnectTask);
                if (textureView != null) startVlc();
            } else {
                try {
                    vlcPlayer.play();
                } catch (Throwable ignored) {
                }
            }
            return;
        }
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

    public boolean isVlcMode() {
        return vlcMode;
    }

    public void release() {
        released = true;
        handler.removeCallbacksAndMessages(null);
        releasePlayer();
        stopVlc();
        stopSnap();
        if (tunnel != null) {
            try { tunnel.stop(); } catch (Throwable ignored) { }
            tunnel = null;
        }
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
        if (vlcMode || isBasicMode()) {
            vlcMode = true;
            startVlc();
            return;
        }
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
        if (useTunnel()) url = tunnelUrl(url);
        currentUrl = maskPassword(url);
        RtspMediaSource.Factory factory = new RtspMediaSource.Factory()
                .setForceUseRtpTcp(useTcp)
                .setTimeoutMs(Math.max(3000, Math.min(cfg.timeoutMs, 20000)))
                .setUserAgent("CamView/1.0 Android");
        return factory.createMediaSource(MediaItem.fromUri(url));
    }

    /** 设备只认 Basic（诊断确认后写进配置）→ 播放直接走 VLC + 隧道，最稳 */
    private boolean isBasicMode() {
        return "basic".equals(info.authMode);
    }

    /** 是否需要 Basic 注入隧道 */
    private boolean useTunnel() {
        return tunnelTried || "basic".equals(info.authMode);
    }

    /**
     * 把 rtsp://user:pass@192.168.31.90:554/xxx 换成 rtsp://127.0.0.1:<隧道端口>/xxx
     * 隧道会自动补 Authorization: Basic，并把响应里的设备地址改回本地地址。
     */
    private String tunnelUrl(String rendered) {
        try {
            Uri u = Uri.parse(rendered);
            int port = u.getPort() > 0 ? u.getPort() : 554;
            String path = u.getEncodedPath();
            if (path == null || path.isEmpty()) path = "/";
            String query = u.getEncodedQuery();
            if (tunnel == null || !tunnel.isRunning()) {
                if (tunnel != null) tunnel.stop();
                tunnel = new RtspBasicTunnel(info.ip, port, info.user, info.password, true);
                int lp = tunnel.start();
                if (lp <= 0) {
                    tunnel = null;
                    return rendered;
                }
            }
            return "rtsp://127.0.0.1:" + tunnel.localPort + path
                    + (query != null ? "?" + query : "");
        } catch (Throwable t) {
            Log.w(TAG, info.ip + " 隧道地址生成失败: " + t);
            return rendered;
        }
    }

    /** 手动切换认证方式（"" 自动 / "basic" 隧道） */
    public void setAuthMode(String mode) {
        info.authMode = mode == null ? "" : mode;
        tunnelTried = "basic".equals(info.authMode);
        lastError = "";
        retryCount = 0;
        templateIndex = 0;
        state = ST_CONNECTING;
        handler.removeCallbacks(reconnectTask);
        vlcMode = false;
        stopVlc();
        releasePlayer();
        if (textureView != null) startPlayer();
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
        // ① 认证类错误：先试「Basic 注入隧道」（很多设备只认 Basic、却抛 Digest 挑战）
        if (!vlcMode && !tunnelTried && isAuthProblem(lastError)) {
            tunnelTried = true;
            retryCount = 0;
            templateIndex = 0;
            useTcp = cfg.rtspTcp;
            lastError = "";
            releasePlayer();
            handler.removeCallbacks(reconnectTask);
            state = ST_CONNECTING;
            Log.i(TAG, info.ip + " 认证失败 → 改用 Basic 注入隧道重试");
            handler.postDelayed(reconnectTask, 300);
            return;
        }
        // ② 还是认证不过：切 VLC 通道
        if (!vlcMode && isAuthProblem(lastError)) {
            vlcMode = true;
            retryCount = 0;
            templateIndex = 0;
            useTcp = cfg.rtspTcp;
            lastError = "";
            releasePlayer();
            stopVlc();
            handler.removeCallbacks(reconnectTask);
            startVlc();
            return;
        }
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
            // 所有模板 + 两种协议都过完了还不通：交给 VLC 兼容通道再试一次
            // （它支持完整的 Digest(qop) 认证，海康这类设备常见只认这个）
            if (!vlcMode) {
                vlcMode = true;
                retryCount = 0;
                templateIndex = 0;
                useTcp = cfg.rtspTcp;
                lastError = "";
                releasePlayer();
                stopVlc();
                handler.removeCallbacks(reconnectTask);
                startVlc();
                return;
            }
            // 连 VLC 都播不出来 → 改用「网页抓图模式」（HTTP JPEG 轮询）
            if (!snapMode) {
                snapMode = true;
                retryCount = 0;
                lastError = "";
                handler.removeCallbacks(reconnectTask);
                startSnap();
                return;
            }
            scheduleReconnect();
        }
    }

    private static boolean isAuthProblem(String err) {
        if (err == null) return false;
        String e = err.toLowerCase();
        return e.contains("401") || e.contains("认证") || e.contains("unauthoriz")
                || e.contains("digest") || e.contains("authentic");
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
        lastFrameTs = 0L;
        connectTs = 0L;
        templateIndex = 0;
        retryCount = 0;
        useTcp = cfg.rtspTcp;
        lastError = "";
        state = ST_CONNECTING;
        handler.removeCallbacks(reconnectTask);
        if (snapMode) startSnap();
        else if (textureView != null) startPlayer();
    }


    // ==================== 网页抓图模式 ====================

    /** 用户手动选择"用网页抓图模式" */
    public void switchToSnap(String url) {
        if (url != null && !url.isEmpty()) info.snapUrl = url;
        snapMode = true;
        releasePlayer();
        stopVlc();
        retryCount = 0;
        lastError = "";
        handler.removeCallbacks(reconnectTask);
        startSnap();
    }

    /** 已连接（或正在尝试）但长时间没有任何画面帧 → 疑似黑屏 */
    public boolean noFrame() {
        if (!active) return false;
        if (state != ST_LIVE) return false;
        // VLC 通道：只有确认过“播放时间在推进”才敢判黑屏，否则容易把正常画面误判
        if (isVlcMode() && !vlcAliveSignal) return false;
        if (lastFrameTs == 0L) {
            // 刚连上还没出过帧：从连接成功那刻算起
            return connectTs > 0 && System.currentTimeMillis() - connectTs > 20000;
        }
        return System.currentTimeMillis() - lastFrameTs > 20000;
    }

    public boolean isSnapMode() {
        return snapMode;
    }

    private void ensureSnapView() {
        if (snapView != null || holder == null) return;
        ImageView iv = new ImageView(ctx);
        iv.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        float rot = info.rotation;
        iv.setRotation(rot);
        if (info.flipH) iv.setScaleX(-1f);
        if (info.flipV) iv.setScaleY(-1f);
        holder.addView(iv);
        snapView = iv;
    }

    /** 开始按 HTTP 抓图（每 400ms 一张）显示画面 */
    private void startSnap() {
        if (released || holder == null) return;
        ensureSnapView();
        state = ST_CONNECTING;
        lastError = "";
        if (info.snapUrl != null && !info.snapUrl.isEmpty()) lastError = "";
        stopSnap();
        snapMode = true;
        SnapPlayer sp = new SnapPlayer(info.ip, info.ip,
                info.user == null ? "" : info.user,
                info.password == null ? "" : info.password,
                new SnapPlayer.Listener() {
                    @Override
                    public void onFrame(android.graphics.Bitmap bmp) {
                        if (snapView != null) snapView.setImageBitmap(bmp);
                        lastFrameTs = System.currentTimeMillis();
                        state = ST_LIVE;
                    }

                    @Override
                    public void onState(int st) {
                        if (snapPlayer != null && snapPlayer.getWorkingUrl() != null) {
                            info.snapUrl = snapPlayer.getWorkingUrl();
                        }
                        if (st == ST_LIVE) {
                            state = ST_LIVE;
                        } else if (state == ST_LIVE && st == ST_CONNECTING) {
                            state = ST_CONNECTING;
                        }
                    }

                    @Override
                    public void onFail(String reason) {
                        lastError = "抓图失败：" + reason;
                        state = ST_FAILED;
                        retryCount++;
                        handler.removeCallbacks(reconnectTask);
                        handler.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (!released && snapMode) startSnap();
                            }
                        }, 20000);
                    }

                    @Override
                    public void onFps(float f) {
                        snapFps = f;
                        fps = f;
                    }
                });
        if (info.snapUrl != null && !info.snapUrl.isEmpty()) sp.setFixedUrl(info.snapUrl);
        int w = holder.getWidth();
        sp.setTargetWidth(w > 0 ? w : 600);
        sp.setActive(active);
        snapPlayer = sp;
        sp.start();
        currentUrl = info.snapUrl != null ? info.snapUrl : ("http://" + info.ip + "/");
    }

    private void stopSnap() {
        if (snapPlayer != null) {
            try {
                snapPlayer.stop();
            } catch (Throwable ignored) {
            }
            snapPlayer = null;
        }
        if (snapView != null) {
            snapView.setImageDrawable(null);
        }
    }

    // ==================== VLC 备用通道 ====================

    /**
     * 海康/大华等设备返回的 401 头是 `Digest qop="auth", realm=..., nonce=...`，
     * Media3 内置的解析器只认 realm 打头，且摘要不带 qop，因此永远认证不过。
     * 这里用 LibVLC 兜底：它支持完整的 Digest(qop) 认证、RTSP over TCP/UDP、H.264/H.265。
     */
    private void startVlc() {
        if (released || textureView == null) return;
        stopVlc();
        state = ST_CONNECTING;
        lastError = "";
        vlcGotFrame = false;
        vlcAliveSignal = false;
        lastVlcTime = -1;
        try {
            if (libVlc == null) {
                List<String> args = new ArrayList<>();
                args.add("--no-audio");
                args.add("--no-video-title-show");
                args.add("--no-sub-autodetect-file");
                args.add("--network-caching=" + Math.max(0, cfg.cacheMs));
                args.add("--live-caching=" + Math.max(0, cfg.cacheMs));
                args.add("--clock-synchro=0");
                if (cfg.hwDecode) args.add("--avcodec-hw=mediacodec");
                libVlc = new LibVLC(ctx, args);
            }
            MediaPlayer p = new MediaPlayer(libVlc);
            IVLCVout vout = p.getVLCVout();
            vout.setVideoView(textureView);
            vout.attachViews();
            resetTextureFull();
            try {
                vout.setWindowSize(Math.max(1, holder.getWidth()), Math.max(1, holder.getHeight()));
            } catch (Throwable ignored) {
            }
            String vurl = buildUrl();
            if (useTunnel()) vurl = tunnelUrl(vurl);
            Log.i(TAG, info.ip + " VLC 地址: " + maskPassword(vurl));
            Media m = new Media(libVlc, Uri.parse(vurl));
            m.setHWDecoderEnabled(cfg.hwDecode, false);
            // 低延迟模式默认走 UDP（不再强制 TCP，隧道只做认证，不搬流）
            boolean tcp = vlcForceTcp || !cfg.forceUdp;
            if (tcp) m.addOption(":rtsp-tcp");
            m.addOption(":network-caching=" + Math.max(0, cfg.cacheMs));
            m.addOption(":live-caching=" + Math.max(0, cfg.cacheMs));
            m.addOption(":clock-synchro=0");
            m.addOption(":drop-late-frames");
            m.addOption(":skip-frames");
            m.addOption(":no-audio");
            // 强制 MediaCodec 硬解：软解多路时 CPU 打满会「一卡一流畅」
            m.addOption(":avcodec-hw=" + ((cfg.hwDecode && !vlcHwOff) ? "mediacodec" : "any"));
            p.setEventListener(new MediaPlayer.EventListener() {
                @Override
                public void onEvent(MediaPlayer.Event event) {
                    onVlcEvent(event);
                }
            });
            p.setMedia(m);
            m.release();
            vlcPlayer = p;
            currentUrl = maskPassword(buildUrl());
            p.play();
            applyTransform();
        } catch (Throwable t) {
            Log.e(TAG, "VLC 启动失败: " + t);
            lastError = "备用播放器启动失败：" + t.getClass().getSimpleName();
            state = ST_FAILED;
            scheduleReconnect();
        }
    }

    private final Runnable vlcWatchdog = new Runnable() {
        @Override
        public void run() {
            if (released || !vlcMode || vlcGotFrame) return;
            if (vlcForceTcp) return;
            Log.w(TAG, info.ip + " 8 秒无画面 → 从 UDP 退回 TCP");
            vlcForceTcp = true;
            lastError = "";
            state = ST_CONNECTING;
            stopVlc();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!released && vlcMode) startVlc();
                }
            }, 500);
        }
    };

    private void onVlcEvent(MediaPlayer.Event event) {
        if (!vlcMode) return;
        switch (event.type) {
            case MediaPlayer.Event.Playing:
                state = ST_LIVE;
                if (connectTs == 0L) connectTs = System.currentTimeMillis();
                retryCount = 0;
                updateVlcVideoSize();
                // 看门狗：UDP 下 RTSP 握手成功但 RTP 收不到时，画面永远黑 →
                // 8 秒没出帧就自动退回 TCP 重来一次
                handler.postDelayed(vlcWatchdog, 8000);
                break;
            case MediaPlayer.Event.Buffering:
            case MediaPlayer.Event.Opening:
                if (state != ST_LIVE) state = ST_CONNECTING;
                break;
            case MediaPlayer.Event.Vout:
                vlcGotFrame = true;
                lastFrameTs = System.currentTimeMillis();
                updateVlcVideoSize();
                resizeVlcWindow();
                break;
            case MediaPlayer.Event.EncounteredError:
                if (!vlcHwOff && cfg.hwDecode && !cfg.forceUdp) {
                    // 硬解不支持该码流 → 关掉硬解重试（软解能出画）
                    vlcHwOff = true;
                    lastError = "";
                    state = ST_CONNECTING;
                    stopVlc();
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!released && vlcMode) startVlc();
                        }
                    }, 400);
                    return;
                }
                if (!vlcForceTcp && cfg.forceUdp) {
                    // UDP 播不了 → 退回 TCP 再试一次（UDP 在某设备/网络下可能被丢）
                    vlcForceTcp = true;
                    lastError = "";
                    state = ST_CONNECTING;
                    stopVlc();
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!released && vlcMode) startVlc();
                        }
                    }, 500);
                    return;
                }
                lastError = "播放失败：设备拒绝或码流不可解析";
                state = ST_FAILED;
                scheduleReconnect();
                break;
            case MediaPlayer.Event.TimeChanged:
                vlcAliveSignal = true;
                lastFrameTs = System.currentTimeMillis();
                break;
            case MediaPlayer.Event.EndReached:
                state = ST_FAILED;
                lastError = "连接被断开";
                scheduleReconnect();
                break;
            default:
                break;
        }
    }

    private void updateVlcVideoSize() {
        if (vlcPlayer == null) return;
        try {
            IMedia.VideoTrack vt = vlcPlayer.getCurrentVideoTrack();
            if (vt != null && vt.width > 0 && vt.height > 0) {
                videoW = vt.width;
                videoH = vt.height;
                if (vt.frameRateDen > 0) streamRate = vt.frameRateNum / (float) vt.frameRateDen;
                resizeVlcWindow();
            }
        } catch (Throwable ignored) {
        }
    }

    private void stopVlc() {
        if (vlcPlayer == null) return;
        try {
            vlcPlayer.getVLCVout().detachViews();
        } catch (Throwable ignored) {
        }
        try {
            vlcPlayer.stop();
        } catch (Throwable ignored) {
        }
        try {
            vlcPlayer.release();
        } catch (Throwable ignored) {
        }
        vlcPlayer = null;
    }

    // ==================== 帧统计 / 画面变换 ====================

    @Override
    public void onVideoFrameAboutToBeRendered(long presentationTimeUs, long releaseTimeNs,
                                              Format format, android.media.MediaFormat mediaFormat) {
        frames++;
        lastFrameTs = System.currentTimeMillis();
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
        if (vlcMode) resizeVlcWindow();
    }

    /** 按视频宽高比把画面装进容器，避免拉伸变形 */
    private void layoutVideo() {
        if (textureView == null || holder == null) return;
        if (vlcMode) {
            // VLC 自己按窗口大小做等比缩放，这里只需要把真实窗口尺寸告诉它
            resetTextureFull();
            resizeVlcWindow();
            return;
        }
        if (videoW <= 0 || videoH <= 0) return;
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

    /** VLC 模式：TextureView 铺满容器，由 VLC 自己等比缩放 */
    private void resetTextureFull() {
        if (textureView == null || holder == null) return;
        TextureView tv = textureView;
        FrameLayout hd = holder;
        hd.post(new Runnable() {
            @Override
            public void run() {
                ViewGroup.LayoutParams lp = tv.getLayoutParams();
                if (lp != null && (lp.width != ViewGroup.LayoutParams.MATCH_PARENT
                        || lp.height != ViewGroup.LayoutParams.MATCH_PARENT)) {
                    lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
                    lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                    if (lp instanceof FrameLayout.LayoutParams) {
                        ((FrameLayout.LayoutParams) lp).gravity = android.view.Gravity.CENTER;
                    }
                    tv.setLayoutParams(lp);
                }
                try {
                    IVLCVout vout = vlcPlayer == null ? null : vlcPlayer.getVLCVout();
                    if (vout != null && vout.areViewsAttached()) {
                        vout.setWindowSize(Math.max(1, hd.getWidth()), Math.max(1, hd.getHeight()));
                    }
                } catch (Throwable ignored) {
                }
            }
        });
    }

    /** 把容器真实像素尺寸告知 VLC（不给的话画面会缩成一条或错位） */
    private void resizeVlcWindow() {
        if (vlcPlayer == null || holder == null) return;
        try {
            IVLCVout vout = vlcPlayer.getVLCVout();
            if (vout.areViewsAttached()) {
                vout.setWindowSize(Math.max(1, holder.getWidth()), Math.max(1, holder.getHeight()));
            }
        } catch (Throwable ignored) {
        }
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
