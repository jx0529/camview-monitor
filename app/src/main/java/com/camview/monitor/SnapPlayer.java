package com.camview.monitor;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 网页抓图模式（HTTP JPEG 轮询）。
 *
 * 为什么需要它：很多"网页能看"的机器（雄迈 / 天视通 / VStarcam / 海康 ISAPI 等）
 * 除了 RTSP，还提供一个返回 JPEG 的 HTTP 接口。RTSP 的 Digest(qop) 写法千奇百怪时，
 * 直接把这个接口按 2~5 帧/秒轮询，就能在 App 里看到画面（不是实时流，但可靠）。
 *
 * 这里自己实现了 Basic + Digest(qop=auth, MD5) 认证，不依赖任何第三方库；
 * 并且会在多个常见路径里自动找一个能出图的。
 */
public class SnapPlayer {

    private static final String TAG = "SnapPlayer";

    public interface Listener {
        /** 收到一张图（回调在主线程） */
        void onFrame(Bitmap bmp);

        /** 状态变化，取 CameraStream.ST_* 常量 */
        void onState(int state);

        /** 彻底失败（所有候选地址都不出图） */
        void onFail(String reason);

        /** 当前实际抓图帧率 */
        void onFps(float fps);
    }

    /** 常见 JPEG 抓图路径（{user}/{password} 会被替换） */
    private static final String[] CANDIDATES = {
            "/ISAPI/Streaming/channels/101/picture",
            "/cgi-bin/snapshot.cgi?channel=1",
            "/cgi-bin/snapshot.cgi",
            "/snapshot.cgi",
            "/webcapture.jpg?command=snap&channel=1",
            "/jpg/image.jpg",
            "/tmpfs/auto.jpg",
            "/image/jpeg.cgi",
            "/cgi-bin/hi3510/snap.cgi?&-snap",
            "/onvif/snapshot?channel=1",
            "/snapshot",
            "/img/snapshot.cgi?size=2",
            "/cgi-bin/getimage.cgi",
            "/cgi-bin/snapshot.cgi?loginuse={user}&loginpas={password}&channel=1",
            "/snapshot.jpg",
            "/video.jpg",
            "/live.jpg"
    };

    private static final int INTERVAL_MS = 450;

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final Listener listener;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private volatile boolean running = false;
    private volatile boolean active = true;
    private Thread thread;
    private String workingUrl;      // 已经找到能出图的地址（带 scheme/host）
    private int candidateIndex = 0;
    private String lastFail = "";
    private long frames = 0;
    private long fpsStart = 0;
    private int targetWidth = 0;

    public SnapPlayer(String host, String ip, String user, String password, Listener l) {
        this.host = host;
        this.port = 80;
        this.user = Creds.clean(user);
        this.password = Creds.clean(password);
        this.listener = l;
    }

    /** 指定一张固定地址（用户手动填过就别再乱试） */
    public void setFixedUrl(String url) {
        this.workingUrl = url;
    }

    public void setTargetWidth(int px) {
        this.targetWidth = px;
    }

    public String getWorkingUrl() {
        return workingUrl;
    }

    public void start() {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "snap-" + host);
        thread.start();
    }

    public void stop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }

    public void setActive(boolean value) {
        active = value;
    }

    public boolean isRunning() {
        return running;
    }

    private void loop() {
        int consecutiveFail = 0;
        while (running) {
            try {
                if (!active) {
                    Thread.sleep(600);
                    continue;
                }
                String url = chooseUrl();
                if (url == null) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onFail(lastFail.isEmpty() ? "找不到可用的抓图地址" : lastFail);
                        }
                    });
                    return;
                }
                Bitmap bmp = fetchBitmap(url);
                if (bmp != null) {
                    consecutiveFail = 0;
                    workingUrl = url;
                    frames++;
                    final Bitmap fb = bmp;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onFrame(fb);
                            listener.onState(CameraStream.ST_LIVE);
                        }
                    });
                    tickFps();
                    Thread.sleep(INTERVAL_MS);
                } else {
                    consecutiveFail++;
                    if (workingUrl == null) {
                        candidateIndex++;      // 换下一个候选路径
                        Thread.sleep(120);
                        if (candidateIndex >= candidates().size()) {
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    listener.onFail(lastFail.isEmpty() ? "所有抓图路径都不出图" : lastFail);
                                }
                            });
                            return;
                        }
                        continue;
                    }
                    if (consecutiveFail >= 6) {
                        // 之前能用，现在连续失败：重连（可能是设备重启或会话过期）
                        consecutiveFail = 0;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                listener.onState(CameraStream.ST_CONNECTING);
                            }
                        });
                    }
                    Thread.sleep(900);
                }
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                Log.w(TAG, host + " 抓图异常: " + t);
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    private void tickFps() {
        long now = System.currentTimeMillis();
        if (fpsStart == 0) fpsStart = now;
        long dt = now - fpsStart;
        if (dt >= 1000) {
            final float f = frames * 1000f / dt;
            frames = 0;
            fpsStart = now;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    listener.onFps(f);
                }
            });
        }
    }

    private List<String> candidates() {
        List<String> list = new ArrayList<>();
        for (String p : CANDIDATES) {
            String path = p.replace("{user}", enc(user)).replace("{password}", enc(password));
            list.add("http://" + host + (port == 80 ? "" : ":" + port) + path);
        }
        return list;
    }

    private String chooseUrl() {
        if (workingUrl != null) return workingUrl;
        List<String> c = candidates();
        while (candidateIndex < c.size()) {
            return c.get(candidateIndex);
        }
        return null;
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ==================== HTTP 认证（Basic + Digest qop） ====================

    private Bitmap fetchBitmap(String url) {
        try {
            byte[] data = httpGet(url, 6, 4000);
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0) {
                lastFail = "地址返回的不是图片";
                return null;
            }
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            if (targetWidth > 0 && o.outWidth > targetWidth * 2) {
                o2.inSampleSize = 2;
                while (o.outWidth / (o2.inSampleSize * 2) > targetWidth) o2.inSampleSize *= 2;
            }
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable t) {
            lastFail = t.getClass().getSimpleName();
            return null;
        }
    }

    /** 发 GET 拿字节：先裸奔，401 时按服务端要求算 Basic / Digest(qop) 再重发一次 */
    private byte[] httpGet(String urlStr, int redirect, int connectTimeout) throws Exception {
        URL u = new URL(urlStr);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(connectTimeout);
        c.setReadTimeout(connectTimeout);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "CamView/1.0 Android");
        c.setRequestProperty("Accept", "image/jpeg,image/*,*/*");
        int code = c.getResponseCode();
        if (code == 401) {
            String ch = c.getHeaderField("WWW-Authenticate");
            String auth = buildAuthHeader(ch, "GET", u.getFile());
            c.disconnect();
            if (auth == null) return null;
            HttpURLConnection c2 = (HttpURLConnection) u.openConnection();
            c2.setConnectTimeout(connectTimeout);
            c2.setReadTimeout(connectTimeout);
            c2.setRequestProperty("User-Agent", "CamView/1.0 Android");
            c2.setRequestProperty("Accept", "image/jpeg,image/*,*/*");
            c2.setRequestProperty("Authorization", auth);
            code = c2.getResponseCode();
            if (code != 200) {
                lastFail = "抓图 401 认证失败（密码不对）";
                c2.disconnect();
                return null;
            }
            byte[] b = readBytes(c2);
            c2.disconnect();
            return b;
        }
        if (code == 404) {
            lastFail = "抓图 404 路径不存在";
            c.disconnect();
            return null;
        }
        if (code != 200) {
            lastFail = "抓图返回 " + code;
            c.disconnect();
            return null;
        }
        byte[] b = readBytes(c);
        c.disconnect();
        return b;
    }

    private static byte[] readBytes(HttpURLConnection c) throws Exception {
        InputStream in = c.getInputStream();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        long deadline = System.currentTimeMillis() + 6000;
        int n;
        while ((n = in.read(tmp)) > 0) {
            bos.write(tmp, 0, n);
            if (bos.size() > 3 * 1024 * 1024) break;
            if (System.currentTimeMillis() > deadline) break;
        }
        in.close();
        return bos.size() == 0 ? null : bos.toByteArray();
    }

    /** 真正实现的版本（需要账号密码，作为实例方法） */
    private String buildAuthHeader(String challenge, String method, String uri) {
        if (challenge == null || challenge.isEmpty()) return null;
        String lower = challenge.toLowerCase(Locale.US);
        if (!lower.contains("digest")) {
            if (lower.contains("basic")) {
                String v = user + ":" + password;
                return "Basic " + android.util.Base64.encodeToString(v.getBytes(), android.util.Base64.NO_WRAP);
            }
            return null;
        }
        Map<String, String> p = parseKv(challenge);
        String realm = p.containsKey("realm") ? p.get("realm") : "";
        String nonce = p.containsKey("nonce") ? p.get("nonce") : "";
        String qop = p.get("qop");
        String opaque = p.get("opaque");
        String cnonce = md5(String.valueOf(System.currentTimeMillis())).substring(0, 16);
        String nc = "00000001";
        String ha1 = md5(user + ":" + realm + ":" + password);
        String ha2 = md5(method + ":" + uri);
        boolean useQop = qop != null && qop.toLowerCase(Locale.US).contains("auth");
        String response = useQop
                ? md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2)
                : md5(ha1 + ":" + nonce + ":" + ha2);
        StringBuilder sb = new StringBuilder("Digest ");
        sb.append("username=\"").append(user).append('"');
        sb.append(", realm=\"").append(realm).append('"');
        sb.append(", nonce=\"").append(nonce).append('"');
        sb.append(", uri=\"").append(uri).append('"');
        sb.append(", algorithm=MD5");
        sb.append(", response=\"").append(response).append('"');
        if (useQop) sb.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"');
        if (opaque != null) sb.append(", opaque=\"").append(opaque).append('"');
        return sb.toString();
    }

    private static Map<String, String> parseKv(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        Matcher mt = Pattern.compile("([a-zA-Z0-9_-]+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^,\\s]+)")
                .matcher(s == null ? "" : s);
        while (mt.find()) {
            String v = mt.group(2);
            if (v.length() > 1 && (v.startsWith("\"") || v.startsWith("'"))) v = v.substring(1, v.length() - 1);
            m.put(mt.group(1).toLowerCase(Locale.US), v);
        }
        return m;
    }

    static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
