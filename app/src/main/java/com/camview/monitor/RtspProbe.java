package com.camview.monitor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RTSP 连接诊断：不依赖播放器，直接用 socket 发 OPTIONS / DESCRIBE，
 * 把"端口通不通、认证方式、路径对不对、编码是什么"逐条打印出来。
 * 用于定位"网页能看、App 连不上"这类问题。
 */
public class RtspProbe {

    /** 探测成功后的可用模板（内含 {user}/{password}/{ip} 占位符），失败为 null */
    public static volatile String workingTemplate = null;

    private static final int[] PORTS = {554, 8554, 10554, 80, 8000};
    private static final String[] PATHS = {
            "/Streaming/Channels/101",
            "/Streaming/Channels/102",
            "/cam/realmonitor?channel=1&subtype=0",
            "/onvif1",
            "/h264"
    };

    /** 从用户输入里抠出纯 IP / 主机名（允许整段 http://ip/doc/page/preview.asp 直接粘贴） */
    public static String normalizeHost(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty()) return "";
        s = s.replaceFirst("(?i)(rtsp|http|https)://", "");
        int slash = s.indexOf('/');
        if (slash >= 0) s = s.substring(0, slash);
        int at = s.lastIndexOf('@');
        if (at >= 0) s = s.substring(at + 1);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);
        return s.trim();
    }

    private static String md5(String s) {
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

    private static class Resp {
        int code = -1;
        String reason = "";
        Map<String, String> headers = new LinkedHashMap<>();
        String wwwAuth = "";
        String body = "";
        String raw = "";
    }

    private static Resp fetch(String host, int port, String requestText, int timeoutMs) {
        Resp r = new Resp();
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(1200);
            OutputStream out = socket.getOutputStream();
            out.write(requestText.getBytes("UTF-8"));
            out.flush();
            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                int n;
                try {
                    n = in.read(tmp);
                } catch (java.net.SocketTimeoutException te) {
                    break;
                }
                if (n <= 0) break;
                buf.write(tmp, 0, n);
                if (buf.size() > 200 * 1024) break;
                String soFar = buf.toString("UTF-8");
                int hdrEnd = soFar.indexOf("\r\n\r\n");
                if (hdrEnd > 0 && soFar.startsWith("RTSP/")) {
                    // 已经有头，且读到一段内容后还等不到更多数据就收工
                    if (soFar.length() - hdrEnd > 200) break;
                }
            }
            r.raw = buf.toString("UTF-8");
        } catch (Exception e) {
            r.raw = "";
            r.reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            if (socket != null) try { socket.close(); } catch (Exception ignored) { }
        }
        parse(r);
        return r;
    }

    private static void parse(Resp r) {
        if (r.raw == null || r.raw.isEmpty()) return;
        String[] lines = r.raw.split("\r\n");
        if (lines.length == 0) return;
        String status = lines[0];
        if (status.startsWith("RTSP/")) {
            String[] parts = status.split(" ", 3);
            if (parts.length >= 2) {
                try {
                    r.code = Integer.parseInt(parts[1].trim());
                } catch (Exception ignored) { }
            }
            if (parts.length >= 3) r.reason = parts[2];
        }
        int bodyStart = r.raw.indexOf("\r\n\r\n");
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i];
            if (l.isEmpty()) break;
            int c = l.indexOf(':');
            if (c <= 0) continue;
            String k = l.substring(0, c).trim().toLowerCase(Locale.US);
            String v = l.substring(c + 1).trim();
            if (!r.headers.containsKey(k)) r.headers.put(k, v);
            if (k.equals("www-authenticate")) {
                r.wwwAuth = r.wwwAuth.isEmpty() ? v : (r.wwwAuth + " | " + v);
            }
        }
        if (bodyStart >= 0) r.body = r.raw.substring(bodyStart + 4);
    }

    private static Map<String, String> parseKv(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        Matcher mt = Pattern.compile("([a-zA-Z0-9_-]+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^,\\s]+)").matcher(s == null ? "" : s);
        while (mt.find()) {
            String v = mt.group(2);
            if (v.length() > 1 && (v.startsWith("\"") || v.startsWith("'"))) {
                v = v.substring(1, v.length() - 1);
            }
            m.put(mt.group(1).toLowerCase(Locale.US), v);
        }
        return m;
    }

    /** 生成认证头（支持 Basic / Digest MD5），失败返回 null */
    private static String authHeader(String challenge, String user, String password,
                                     String method, String uri) {
        if (challenge == null || challenge.isEmpty()) return null;
        String scheme = challenge.trim().split(" ")[0].toLowerCase(Locale.US);
        if (scheme.equals("basic")) {
            String v = user + ":" + password;
            String b64 = android.util.Base64.encodeToString(v.getBytes(),
                    android.util.Base64.NO_WRAP);
            return "Basic " + b64;
        }
        if (scheme.equals("digest")) {
            Map<String, String> p = parseKv(challenge);
            String realm = p.get("realm") != null ? p.get("realm") : "";
            String nonce = p.get("nonce") != null ? p.get("nonce") : "";
            String qop = p.get("qop");
            String opaque = p.get("opaque");
            String cnonce = md5(String.valueOf(System.currentTimeMillis())).substring(0, 16);
            String nc = "00000001";
            String ha1 = md5(user + ":" + realm + ":" + password);
            String ha2 = md5(method + ":" + uri);
            String response;
            if (qop != null && qop.toLowerCase(Locale.US).contains("auth")) {
                response = md5(ha1 + ":" + nonce + ":" + nc + ":" + cnonce + ":auth:" + ha2);
            } else {
                response = md5(ha1 + ":" + nonce + ":" + ha2);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Digest username=\"").append(user).append("\"");
            sb.append(", realm=\"").append(realm).append("\"");
            sb.append(", nonce=\"").append(nonce).append("\"");
            sb.append(", uri=\"").append(uri).append("\"");
            sb.append(", algorithm=MD5");
            sb.append(", response=\"").append(response).append("\"");
            if (qop != null && qop.toLowerCase(Locale.US).contains("auth")) {
                sb.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append("\"");
            }
            if (opaque != null) sb.append(", opaque=\"").append(opaque).append("\"");
            return sb.toString();
        }
        return null;
    }

    private static String buildRequest(String method, String uri, int cseq, String auth) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(" ").append(uri).append(" RTSP/1.0\r\n");
        sb.append("CSeq: ").append(cseq).append("\r\n");
        sb.append("User-Agent: CamViewProbe/1.0\r\n");
        if (auth != null) sb.append("Authorization: ").append(auth).append("\r\n");
        sb.append("\r\n");
        return sb.toString();
    }

    /** DESCRIBE 一个路径，自动处理一次 401 认证 */
    private static String describePath(String host, int port, String uri, String user, String password,
                                       int timeoutMs) {
        Resp a = fetch(host, port, buildRequest("DESCRIBE", uri, 1, null), timeoutMs);
        if (a.code == 401 && a.wwwAuth != null && !a.wwwAuth.isEmpty()) {
            String auth = authHeader(a.wwwAuth.split("\\|")[0], user, password, "DESCRIBE", uri);
            if (auth != null) {
                Resp b = fetch(host, port, buildRequest("DESCRIBE", uri, 2, auth), timeoutMs);
                return describe(b);
            }
            return "401 需要认证（" + a.wwwAuth.split("\\|")[0].trim()
                    + "），请确认用户名/密码";
        }
        if (a.code == 404) return "404 路径不存在";
        if (a.code == 401) return "401 未授权（服务器没给认证头）";
        if (a.code <= 0) return "无响应（" + a.reason + "）";
        if (a.code == 200 || a.code == 201) return describe(a);
        return a.code + " " + a.reason;
    }

    private static String describe(Resp r) {
        if (r.code <= 0) return "无响应（" + r.reason + "）";
        if (r.code == 401) return "401 认证失败（用户名或密码不正确）";
        if (r.code == 404) return "404 路径不存在";
        if (r.code != 200 && r.code != 201) return r.code + " " + r.reason;
        // 解析 SDP
        String codec = "";
        String fps = "";
        String extra = "";
        for (String line : r.body.split("\r\n")) {
            String t = line.trim();
            if (t.startsWith("m=video")) {
                extra = t;
            } else if (t.startsWith("a=rtpmap")) {
                int c = t.indexOf(' ');
                if (c > 0) {
                    String v = t.substring(c).trim();
                    if (v.toUpperCase(Locale.US).startsWith("H264")) codec = "H.264";
                    else if (v.toUpperCase(Locale.US).startsWith("H265")) codec = "H.265(HEVC)";
                    else if (v.toUpperCase(Locale.US).startsWith("JPEG")) codec = "MJPEG";
                    else if (v.toUpperCase(Locale.US).startsWith("MP4V")) codec = "MPEG-4";
                    else if (codec.isEmpty()) codec = v;
                }
            } else if (t.startsWith("a=framerate")) {
                int c = t.indexOf(':');
                if (c > 0) fps = t.substring(c + 1).trim();
            }
        }
        StringBuilder sb = new StringBuilder("200 OK");
        if (!codec.isEmpty()) sb.append(" · ").append(codec);
        if (!fps.isEmpty()) sb.append(" · ").append(fps).append("fps");
        if (!extra.isEmpty()) sb.append(" · ").append(extra);
        if (r.body.isEmpty()) sb.append(" · （返回空 SDP）");
        return sb.toString();
    }

    private static long tcpLatency(String host, int port, int timeoutMs) {
        Socket s = null;
        long t0 = System.currentTimeMillis();
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return System.currentTimeMillis() - t0;
        } catch (Exception e) {
            return -1;
        } finally {
            if (s != null) try { s.close(); } catch (Exception ignored) { }
        }
    }

    /** 生成诊断报告文本（在子线程调用，不要在主线程跑） */
    public static String run(String rawIp, String user, String password, int timeoutMs) {
        String host = normalizeHost(rawIp);
        workingTemplate = null;
        StringBuilder r = new StringBuilder();
        r.append("目标: ").append(host.isEmpty() ? "(空)" : host).append("\n");
        r.append("账号: ").append(user == null || user.isEmpty() ? "(空)" : user).append("\n\n");
        if (host.isEmpty()) {
            return r.append("❌ IP 为空，先填写摄像头 IP（例如 192.168.31.89）").toString();
        }

        r.append("【1】端口连通性\n");
        List<Integer> open = new ArrayList<>();
        for (int port : PORTS) {
            long lat = tcpLatency(host, port, 2000);
            if (lat >= 0) {
                open.add(port);
                r.append("  ").append(port).append("  开放 (").append(lat).append("ms)\n");
            } else {
                r.append("  ").append(port).append("  不通\n");
            }
        }
        r.append("\n");

        int rtspPort = -1;
        for (int p : new int[]{554, 8554, 10554}) {
            if (open.contains(p)) { rtspPort = p; break; }
        }
        if (rtspPort < 0) {
            r.append("【2】RTSP 探测\n  跳过（RTSP 端口没有开放）\n\n");
            r.append("【3】结论\n");
            if (open.contains(80) || open.contains(8000)) {
                r.append("  ❌ 网页端口(80/8000)是通的，但 RTSP 端口 554/8554/10554 都不通。\n");
                r.append("  这基本可以确定：设备的 RTSP 服务被关闭、或改到了别的端口。\n");
                r.append("  → 打开摄像头后台（浏览器里那个页面）找「网络 → 高级设置 → RTSP」，\n");
                r.append("     把「启用 RTSP」打开；若端口不是 554，把 设置 → RTSP 模板里的端口改掉。\n");
                return r.toString();
            }
            r.append("  ❌ 554 / 8554 / 10554 / 80 都没有响应。\n");
            r.append("  HTTP 能打开说明网络可达，所以问题在设备侧：\n");
            r.append("  a) 摄像头后台「网络→高级→RTSP」里 RTSP 端口被关闭或改成了别的端口，去打开或按实际端口改；\n");
            r.append("  b) 设备开了「IP 地址过滤 / 白名单」，手机 IP 不在允许范围；\n");
            r.append("  c) 路由器开了 AP 隔离 / 客户端隔离，或访客网络把 554 拦了；\n");
            r.append("  d) 部分设备的 RTSP 只允许内网访问且需要「码流加密」关闭。\n");
            return r.toString();
        }

        r.append("【2】RTSP 路径探测（端口 ").append(rtspPort).append("）\n");
        boolean anyOk = false;
        String okPath = null;
        String okDesc = null;
        for (String path : PATHS) {
            String uri = "rtsp://" + host + ":" + rtspPort + path;
            String res = describePath(host, rtspPort, uri, user == null ? "" : user,
                    password == null ? "" : password, Math.max(2000, timeoutMs));
            if (res.startsWith("200")) {
                anyOk = true;
                if (okPath == null) {
                    okPath = path;
                    okDesc = res;
                    workingTemplate = "rtsp://{user}:{password}@{ip}:" + rtspPort + path;
                }
                r.append("  ✅ ").append(path).append("  →  ").append(res).append("\n");
            } else {
                r.append("  ✖ ").append(path).append("  →  ").append(res).append("\n");
            }
        }
        r.append("\n【3】结论\n");

        if (anyOk) {
            r.append("  ✅ RTSP 通路正常：").append(okPath).append(" → ").append(okDesc).append("\n\n");
            r.append("  如果 App 里还是不出画面，按顺序试：\n");
            r.append("  1) 设置 → 每页数量改成 4，减少同时解码路数；\n");
            r.append("  2) 设置 → 解码模式改成「软件兼容」；\n");
            r.append("  3) 设置 → 连接超时改 8000；\n");
            r.append("  4) 设置 → RTSP 模板，把能用的这条放到第一行：\n");
            r.append("     rtsp://{user}:{password}@{ip}:").append(rtspPort).append(okPath).append("\n");
            if (okPath != null && okPath.contains("102")) {
                r.append("  5) 你连的是子码流，分辨率低、最流畅，建议就用它。\n");
            }
            if (okDesc != null && okDesc.contains("H.265")) {
                r.append("  ! 该码流是 H.265，老手机硬解可能不支持，务必试「软件兼容」。\n");
            }
        } else {
            r.append("  ❌ 所有路径都连不上 RTSP。\n");
            r.append("  看上面的每一条：\n");
            r.append("  · 全是 401 → 用户名或密码不对（注意大小写；海康连续错 5 次会锁定 30 分钟，\n");
            r.append("    锁定期间网页也会提示「登录失败次数过多」，等半小时或用另一组账号）；\n");
            r.append("  · 全是 404 → 设备不是海康/大华这类路径，去设备后台或说明书里找 RTSP 地址，\n");
            r.append("    填到 设置 → RTSP 模板；\n");
            r.append("  · 无响应/超时 → 端口虽然开着但不是 RTSP 服务（比如 80 是网页端口），\n");
            r.append("    请在设备后台确认 RTSP 端口号，并把模板里的 554 改成那个端口。\n");
        }
        return r.toString();
    }
}
