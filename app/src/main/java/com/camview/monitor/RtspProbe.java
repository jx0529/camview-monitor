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
 * 连接诊断：不依赖播放器，直接用 socket 发 DESCRIBE / HTTP GET，
 * 把"端口通不通、认证方式、账号对不对、路径对不对、编码是什么"逐条打印出来。
 *
 * 解决"网页能看、App 连不上"：RTSP 401 分两种原因——
 *  ① 密码不对；② 这台设备的 Digest 写法特殊（要求 uri 只带路径、带/不带 algorithm 等）。
 * 这里会逐个变体重试，并用 HTTP 快照接口单独验证账号密码，从而把两种原因区分开。
 */
public class RtspProbe {

    private static final int[] PORTS = {554, 8554, 10554, 80, 8000};
    private static final String[] PATHS = {
            "/Streaming/Channels/101",
            "/Streaming/Channels/102",
            "/cam/realmonitor?channel=1&subtype=0",
            "/onvif1",
            "/h264"
    };
    /** 典型 HTTP 快照接口，用来验证账号密码是否正确 */
    private static final String[] HTTP_PATHS = {
            "/ISAPI/Streaming/channels/101/picture",
            "/cgi-bin/snapshot.cgi?channel=1",
            "/cgi-bin/snapshot.cgi",
            "/snapshot.cgi",
            "/webcapture.jpg?command=snap&channel=1",
            "/jpg/image.jpg",
            "/tmpfs/auto.jpg",
            "/image/jpeg.cgi",
            "/snapshot",
            "/cgi-bin/hi3510/snap.cgi?&-snap"
    };

    /** 探测到的可用抓图地址（用于「网页抓图模式」） */
    public static volatile String workingSnap = null;

    /** 探测成功后的可用模板（内含 {user}/{password}/{ip} 占位符），失败为 null */
    public static volatile String workingTemplate = null;
    /** 设备返回的 WWW-Authenticate 认证方式 */
    public static volatile String challenge = "";
    /** HTTP 接口是否认证成功（true = 密码没问题，问题在 RTSP 写法） */
    public static volatile boolean credentialsValid = false;

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
            socket.setSoTimeout(1000);
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
                if (buf.size() > 400 * 1024) break;
                String soFar = buf.toString("UTF-8");
                int hdrEnd = soFar.indexOf("\r\n\r\n");
                if (hdrEnd > 0 && (soFar.startsWith("RTSP/") || soFar.startsWith("HTTP/")) && soFar.length() - hdrEnd > 400) {
                    break;
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
        if (status.startsWith("RTSP/") || status.startsWith("HTTP/")) {
            String[] parts = status.split(" ", 3);
            if (parts.length >= 2) {
                try { r.code = Integer.parseInt(parts[1].trim()); } catch (Exception ignored) { }
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
        Matcher mt = Pattern.compile("([a-zA-Z0-9_-]+)\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^,\\s]+)")
                .matcher(s == null ? "" : s);
        while (mt.find()) {
            String v = mt.group(2);
            if (v.length() > 1 && (v.startsWith("\"") || v.startsWith("'"))) v = v.substring(1, v.length() - 1);
            m.put(mt.group(1).toLowerCase(Locale.US), v);
        }
        return m;
    }

    /** 生成认证头（Basic / Digest MD5） */
    private static String authHeader(String challengeText, String user, String password,
                                     String method, String uri, boolean withAlgorithm) {
        if (challengeText == null || challengeText.isEmpty()) return null;
        String scheme = challengeText.trim().split(" ")[0].toLowerCase(Locale.US);
        if (scheme.equals("basic")) {
            String v = user + ":" + password;
            return "Basic " + android.util.Base64.encodeToString(v.getBytes(), android.util.Base64.NO_WRAP);
        }
        if (!scheme.equals("digest")) return null;
        Map<String, String> p = parseKv(challengeText);
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
        StringBuilder sb = new StringBuilder();
        sb.append("Digest username=\"").append(user).append('"');
        sb.append(", realm=\"").append(realm).append('"');
        sb.append(", nonce=\"").append(nonce).append('"');
        sb.append(", uri=\"").append(uri).append('"');
        if (withAlgorithm) sb.append(", algorithm=MD5");
        sb.append(", response=\"").append(response).append('"');
        if (useQop) {
            sb.append(", qop=auth, nc=").append(nc).append(", cnonce=\"").append(cnonce).append('"');
        }
        if (opaque != null) sb.append(", opaque=\"").append(opaque).append('"');
        return sb.toString();
    }

    private static String describe(Resp r) {
        if (r.code <= 0) return "无响应（" + r.reason + "）";
        if (r.code == 401) return "401 认证失败（用户名或密码不正确）";
        if (r.code == 404) return "404 路径不存在";
        if (r.code != 200 && r.code != 201) return r.code + " " + r.reason;
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
                    String v = t.substring(c).trim().toUpperCase(Locale.US);
                    if (v.startsWith("H264")) codec = "H.264";
                    else if (v.startsWith("H265")) codec = "H.265(HEVC)";
                    else if (v.startsWith("JPEG")) codec = "MJPEG";
                    else if (v.startsWith("MP4V")) codec = "MPEG-4";
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

    /**
     * DESCRIBE 一个路径；401 时用 4 种 Digest 写法重试
     * （uri 完整/仅路径 × 带/不带 algorithm），有的设备只认其中一种。
     */
    private static String describePath(String host, int port, String uri, String user, String password,
                                       int timeoutMs) {
        Resp a = fetch(host, port, buildRtspRequest("DESCRIBE", uri, 1, null), timeoutMs);
        if (a.code <= 0) return "无响应（" + a.reason + "）";
        if (a.code == 404) return "404 路径不存在";
        if (a.code != 401) return describe(a);
        if (a.wwwAuth == null || a.wwwAuth.isEmpty()) return "401 未授权（服务器没给认证头）";
        String ch = a.wwwAuth.split("\\|")[0].trim();
        if (challenge.isEmpty()) challenge = ch;

        int schemeEnd = uri.indexOf("://");
        int pathStart = schemeEnd < 0 ? -1 : uri.indexOf('/', schemeEnd + 3);
        String pathOnly = pathStart > 0 ? uri.substring(pathStart) : uri;

        String last = "401 认证失败（用户名或密码不正确）";
        String[][] variants = {
                {uri, "", "alg"},
                {uri, "", "noalg"},
                {pathOnly, "（认证用仅路径）", "alg"},
                {pathOnly, "（认证用仅路径）", "noalg"}
        };
        for (String[] v : variants) {
            String auth = authHeader(ch, user, password, "DESCRIBE", v[0], "alg".equals(v[2]));
            if (auth == null) continue;
            Resp b = fetch(host, port, buildRtspRequest("DESCRIBE", uri, 2, auth), timeoutMs);
            if (b.code == 200 || b.code == 201) return describe(b) + v[1];
            if (b.code == 401) {
                last = "401 认证失败（用户名或密码不正确）";
            } else if (b.code <= 0) {
                last = "无响应（" + b.reason + "）";
            } else {
                return b.code + " " + b.reason;
            }
        }
        // 有些海康设备实际只接受 Basic（网页里 安全管理→认证方式→RTSP认证 设 Basic 后），
        // 但 401 里仍然只给 Digest 挑战，所以要主动试一次 Basic
        String basic = "Basic " + android.util.Base64.encodeToString(
                (user + ":" + password).getBytes(), android.util.Base64.NO_WRAP);
        Resp c = fetch(host, port, buildRtspRequest("DESCRIBE", uri, 3, basic), timeoutMs);
        if (c.code == 200 || c.code == 201) return describe(c) + "（Basic 认证）";
        if (c.code > 0 && c.code != 401) return c.code + " " + c.reason;
        return last;
    }

    private static String buildRtspRequest(String method, String uri, int cseq, String auth) {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n");
        sb.append("CSeq: ").append(cseq).append("\r\n");
        sb.append("Accept: application/sdp\r\n");
        sb.append("User-Agent: CamViewProbe/1.0\r\n");
        if (auth != null) sb.append("Authorization: ").append(auth).append("\r\n");
        sb.append("\r\n");
        return sb.toString();
    }

    private static String buildHttpGet(String path, String host, String auth) {
        StringBuilder sb = new StringBuilder();
        sb.append("GET ").append(path).append(" HTTP/1.0\r\n");
        sb.append("Host: ").append(host).append("\r\n");
        sb.append("User-Agent: CamViewProbe/1.0\r\n");
        if (auth != null) sb.append("Authorization: ").append(auth).append("\r\n");
        sb.append("\r\n");
        return sb.toString();
    }

    /** 用 HTTP 快照接口验证账号密码（顺便看能不能直接拿图） */
    private static String httpCredentialCheck(String host, int port, String path,
                                              String user, String password, int timeoutMs) {
        Resp a = fetch(host, port, buildHttpGet(path, host, null), timeoutMs);
        if (a.code <= 0) return "无响应";
        if (a.code != 401) {
            String len = a.headers.get("content-length");
            return a.code + " " + (a.reason == null ? "" : a.reason) + (len != null ? " · " + len + "B" : "");
        }
        if (a.wwwAuth == null || a.wwwAuth.isEmpty()) return "401 无认证头";
        String ch = a.wwwAuth.split("\\|")[0].trim();
        if (challenge.isEmpty()) challenge = ch;
        String auth = authHeader(ch, user, password, "GET", path, true);
        if (auth == null) return "401（不支持的认证方式）";
        Resp b = fetch(host, port, buildHttpGet(path, host, auth), timeoutMs);
        if (b.code == 200 || b.code == 201) {
            String type = b.headers.get("content-type");
            String len = b.headers.get("content-length");
            credentialsValid = true;
            return "200 认证成功" + (type != null ? " · " + type : "") + (len != null ? " · " + len + "B" : "");
        }
        if (b.code == 401) {
            // 再试一次 Basic，海康有些固件网页/ISAPI 也认 Basic
            String basic = "Basic " + android.util.Base64.encodeToString(
                    (user + ":" + password).getBytes(), android.util.Base64.NO_WRAP);
            Resp c = fetch(host, port, buildHttpGet(path, host, basic), timeoutMs);
            if (c.code == 200 || c.code == 201) {
                credentialsValid = true;
                String len2 = c.headers.get("content-length");
                return "200 认证成功（Basic）" + (len2 != null ? " · " + len2 + "B" : "");
            }
            return "401 认证失败（这个密码不对）";
        }
        if (b.code == 404) return "404 设备没有这个接口";
        return b.code + " " + (b.reason == null ? "" : b.reason);
    }

    /** 账号密码写进路径的常见写法（雄迈 / 天视通 / 通用国产机） */
    private static List<String> credPaths(String host, String user, String password) {
        List<String> out = new ArrayList<>();
        String u = user == null ? "" : user;
        String p = password == null ? "" : password;
        out.add("/user=" + u + "&password=" + p + "&channel=1&stream=0.sdp?real_stream");
        out.add("/user=" + u + "&password=" + p + "&channel=1&stream=1.sdp?real_stream");
        out.add("/user=" + u + "&password=" + p + "&channel=0&stream=0.sdp?real_stream");
        out.add("/user=" + u + "&password=" + p + "&channel=1&stream=0.sdp");
        return out;
    }

    /** 直接 DESCRIBE（地址里已带账号），不带 Authorization 头 */
    private static String describePlain(String host, int port, String path, int timeoutMs) {
        String uri = "rtsp://" + host + ":" + port + path;
        Resp a = fetch(host, port, buildRtspRequest("DESCRIBE", uri, 1, null), timeoutMs);
        if (a.code <= 0) return "无响应（" + a.reason + "）";
        if (a.code == 404) return "404 路径不存在";
        if (a.code == 401) return "401 认证失败";
        return describe(a);
    }

    /**
     * 用网页首页验证账号密码。
     * 返回 1=认证通过（200/302/403 之外的正常页面）、0=认证失败、-1=不确定（该路径不需要认证或不存在）。
     */
    private static int rootCredCheck(String host, int port, String path, String user, String password,
                                     int timeoutMs, StringBuilder log) {
        Resp a = fetch(host, port, buildHttpGet(path, host, null), timeoutMs);
        if (a.code <= 0) {
            log.append("  ").append(path).append("  → 无响应\n");
            return -1;
        }
        if (a.code != 401) {
            log.append("  ").append(path).append("  → ").append(a.code).append("（不需要认证，无法验证密码）\n");
            return -1;
        }
        if (a.wwwAuth == null || a.wwwAuth.isEmpty()) {
            log.append("  ").append(path).append("  → 401 但没有认证头\n");
            return -1;
        }
        String ch = a.wwwAuth.split("\\|")[0].trim();
        String auth = authHeader(ch, user, password, "GET", path, true);
        int code = -1;
        if (auth != null) {
            Resp b = fetch(host, port, buildHttpGet(path, host, auth), timeoutMs);
            code = b.code;
            if (code == 200 || code == 302 || code == 301) {
                log.append("  ").append(path).append("  → 200 ✅ 认证通过\n");
                return 1;
            }
            log.append("  ").append(path).append("  → Digest ").append(code).append("\n");
        }
        // 再试 Basic（不少国产机只认 Basic，或者网页只认 Basic）
        String basic = "Basic " + android.util.Base64.encodeToString(
                (user + ":" + password).getBytes(), android.util.Base64.NO_WRAP);
        Resp c = fetch(host, port, buildHttpGet(path, host, basic), timeoutMs);
        if (c.code == 200 || c.code == 301 || c.code == 302) {
            log.append("  ").append(path).append("  → 200 ✅ 认证通过（Basic）\n");
            return 1;
        }
        log.append("  ").append(path).append("  → Basic/").append(c.code).append(" ✖\n");
        return 0;
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
    public static String run(String rawIp, String rawUser, String rawPassword, int timeoutMs) {
        // 关键：先把账号密码里的换行、空格、全角字符清掉再发给设备
        String user = Creds.clean(rawUser);
        String password = Creds.clean(rawPassword);
        String host = normalizeHost(rawIp);
        workingTemplate = null;
        challenge = "";
        credentialsValid = false;
        workingSnap = null;
        StringBuilder r = new StringBuilder();
        r.append("目标: ").append(host.isEmpty() ? "(空)" : host).append("\n");
        String pw = password == null ? "" : password;
        r.append(Creds.inspect("账号", rawUser)).append("\n");
        r.append(Creds.inspect("密码", rawPassword)).append("\n");
        r.append("  实际发送: 账号=").append(user.isEmpty() ? "(空)" : user)
                .append("  密码=").append(pw.isEmpty() ? "(空)" : (pw.length() + " 位")).append("\n\n");
        if (host.isEmpty()) {
            return r.append("❌ IP 为空，先填写摄像头 IP（例如 192.168.31.89）").toString();
        }
        int to = Math.max(1500, Math.min(timeoutMs, 4000));

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

        r.append("【2】RTSP 路径探测\n");
        boolean anyOk = false;
        String okPath = null;
        String okDesc = null;
        if (rtspPort < 0) {
            r.append("  跳过：RTSP 端口 554/8554/10554 都没有开放\n");
        } else {
            r.append("  端口 ").append(rtspPort).append("\n");
            for (String path : PATHS) {
                String uri = "rtsp://" + host + ":" + rtspPort + path;
                String res = describePath(host, rtspPort, uri, user == null ? "" : user, pw, to);
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
        }
        if (!challenge.isEmpty()) r.append("  认证方式: ").append(challenge).append("\n");

        // 很多国产机（雄迈/天视通等）只认把账号密码写在路径里的地址
        if (!anyOk && rtspPort > 0 && user != null && !user.isEmpty()) {
            r.append("  再试「账号写进地址」写法（国产机型常用）\n");
            for (String path : credPaths(host, user, pw)) {
                String res = describePlain(host, rtspPort, path, to);
                if (res.startsWith("200")) {
                    anyOk = true;
                    okPath = path;
                    okDesc = res;
                    workingTemplate = "rtsp://{ip}:" + rtspPort + path.replace(user, "{user}").replace(pw, "{password}");
                    r.append("  ✅ ").append(path).append("  →  ").append(res).append("\n");
                    break;
                } else {
                    r.append("  ✖ ").append(path).append("  →  ").append(res).append("\n");
                }
            }
        }

        boolean httpOpen = open.contains(80) || open.contains(8000);
        boolean gotAuth = false;
        if (httpOpen) {
            int port = open.contains(80) ? 80 : 8000;
            r.append("\n【3】HTTP 账号校验（用来判断密码对不对）\n");
            int i = 0;
            for (String hp : HTTP_PATHS) {
                if (workingSnap != null) break;
                String res = httpCredentialCheck(host, port, hp, user == null ? "" : user, pw, to);
                if (res.startsWith("200 认证成功")) {
                    gotAuth = true;
                    workingSnap = "http://" + host + (port == 80 ? "" : ":" + port) + hp;
                }
                r.append("  ").append(hp).append("  →  ").append(res).append("\n");
                if (++i >= 6) break;
            }
            if (workingSnap != null) {
                r.append("  ✅ 可用的抓图地址: ").append(workingSnap).append("\n");
            }
        }

        // 【3.5】用网页首页/登录页验证账号密码 —— 这是最可靠的一招：
        // 这类设备的任意路径未认证都会先回 401，所以"某个接口 401"不等于密码错；
        // 而首页带认证后能返回 200/302，就说明账号密码是对的。
        int rootVerdict = -1;
        String rootDetail = "";
        if (httpOpen) {
            int port = open.contains(80) ? 80 : 8000;
            r.append("\n【3.5】网页根路径验证（最可靠：认证通过会返回 200/302）\n");
            String[] roots = {"/", "/doc/page/login.asp", "/index.html", "/login.asp", "/cgi-bin/luci"};
            for (String path : roots) {
                int v = rootCredCheck(host, port, path, user, pw, to, r);
                if (v == 1) {
                    rootVerdict = 1;
                    rootDetail = path;
                    break;
                } else if (v == -1 && rootVerdict < 0) {
                    rootDetail = path;
                }
            }
            if (rootVerdict == 1) {
                credentialsValid = true;
                r.append("  ✅ 账号密码确认正确（").append(rootDetail).append(" 认证后返回正常页面）\n");
            } else {
                r.append("  ✖ 首页也没通过认证 → 这台设备不认这套账号密码，或它用的不是 Basic/Digest\n");
            }
        }

        r.append("\n【4】结论\n");
        if (anyOk) {
            r.append("  ✅ RTSP 通路正常：").append(okPath).append(" → ").append(okDesc).append("\n\n");
            r.append("  App 里如果还是不出画面：\n");
            r.append("  1) 点「一键用这个地址」把它设为第一优先；\n");
            r.append("  2) 解码模式改「软件兼容」，每页数量改 4；\n");
            if (okDesc != null && okDesc.contains("H.265")) {
                r.append("  ! 这是 H.265 码流，老机型硬解不支持，务必用软件解码。\n");
            }
            return r.toString();
        }
        if (!httpOpen) {
            r.append("  ❌ RTSP 与 HTTP 都不通：确认手机和摄像机在同一网段，并在设备后台确认 RTSP 端口。\n");
            return r.toString();
        }
        if (gotAuth || rootVerdict == 1) {
            r.append("  ✅ 账号密码是对的（HTTP 认证通过），问题在 RTSP 认证写法/路径，不是密码。\n");
            r.append("  App 会自动改用 VLC 兼容通道播放。若仍不出画面，到设备后台把认证方式改成 Basic，\n");
            r.append("  或关闭「认证增强 / 安全模式」，再重连。\n");
            return r.toString();
        }
        if (workingSnap != null) {
            r.append("  ✅ 这台设备可以「从网页拉图」：\n");
            r.append("     ").append(workingSnap).append("\n");
            r.append("  App 会用这个地址按 2~4 帧/秒抓图显示（不是实时流，但保证有画面）。\n");
            r.append("  点下面「📷 用网页抓图模式」即可切换。\n");
            return r.toString();
        }
        r.append("  ❌ RTSP 全部 401，HTTP 也认证失败。两种可能：\n");
        r.append("    a) 密码不对（网页能进说明密码是另一套，确认 App 里填的是同一套）；\n");
        r.append("    b) 设备只接受某一种认证方式（比如只认 Basic），而它回的 401 挑战是 Digest，\n");
        r.append("       导致客户端按 Digest 计算永远对不上。\n");
        r.append("  网页登录用的密码，要和这台摄像头在 App 里填的一致；\n");
        r.append("  批量添加时留空会用「设置 → 默认密码」。\n");
        r.append("  点下面「换个密码重测」，输入网页登录那个密码再测，成功会自动保存并重连。\n");
        r.append("  → 若密码确认没错：到设备后台「安全管理 → 认证方式 → RTSP认证」切成 Basic 并点保存。\n");
        return r.toString();
    }
}
