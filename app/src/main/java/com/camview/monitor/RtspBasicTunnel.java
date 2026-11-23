package com.camview.monitor;

import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 本地 RTSP「Basic 认证注入隧道」。
 *
 * 背景：有些设备（很常见的一批海康/国产机）在 401 里只抛 Digest 挑战，
 * 但实际只校验 Basic。Media3 / VLC 都会照着 Digest 去算，于是永远 401，
 * 而我们的诊断里"Digest 失败后补一次 Basic"就能拿到 200 —— 说明设备认 Basic。
 *
 * 播放器改不了"用哪种认证"，那就让它们连一个本地假服务器：
 *   App 内起一个只监听 127.0.0.1 的 TCP 隧道 → 每条发给摄像机的 RTSP 请求，
 *   自动补上 Authorization: Basic xxx → 再把摄像机的响应原样转回播放器。
 *   同时把响应里摄像机的 host:port 改写成 127.0.0.1:本地端口，
 *   这样播放器后续的 SETUP/PLAY 仍然会回到隧道里，不会绕过认证直连设备。
 *
 * RTP 走 TCP（interleaved，`$` 开头的帧）时是同一个 socket，纯字节转发即可。
 */
public class RtspBasicTunnel {

    private static final String TAG = "RtspTunnel";

    private final String camHost;
    private final int camPort;
    private final String authHeader;
    /** true = 把请求里自带的 Authorization 换成我们的 Basic（VLC 会先发 Digest，必须替换） */
    private final boolean replaceAuth;

    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running = false;
    private final List<Socket> sockets = new ArrayList<>();
    public int localPort = -1;

    public RtspBasicTunnel(String camHost, int camPort, String user, String password) {
        this(camHost, camPort, user, password, false);
    }

    public RtspBasicTunnel(String camHost, int camPort, String user, String password, boolean replaceAuth) {
        this.replaceAuth = replaceAuth;
        this.camHost = camHost;
        this.camPort = camPort <= 0 ? 554 : camPort;
        String u = Creds.clean(user);
        String p = Creds.clean(password);
        String v = u + ":" + p;
        this.authHeader = "Authorization: Basic "
                + android.util.Base64.encodeToString(v.getBytes(), android.util.Base64.NO_WRAP) + "\r\n";
    }

    /** 启动隧道，返回本地端口；失败返回 -1 */
    public int start() {
        if (running) return localPort;
        try {
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            localPort = server.getLocalPort();
        } catch (IOException e) {
            Log.e(TAG, "隧道监听失败: " + e);
            localPort = -1;
            return -1;
        }
        running = true;
        acceptThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Socket client = server.accept();
                        client.setTcpNoDelay(true);
                        Socket cam = new Socket(camHost, camPort);
                        cam.setTcpNoDelay(true);
                        synchronized (sockets) {
                            sockets.add(client);
                            sockets.add(cam);
                        }
                        new Thread(new Pump(client, cam)).start();
                        new Thread(new Pump(cam, client)).start();
                    } catch (IOException e) {
                        if (running) Log.w(TAG, "accept 失败: " + e);
                    }
                }
            }
        }, "tunnel-accept");
        acceptThread.start();
        Log.i(TAG, "隧道已启动 127.0.0.1:" + localPort + " → " + camHost + ":" + camPort);
        return localPort;
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        synchronized (sockets) {
            for (Socket s : sockets) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
            sockets.clear();
        }
        Thread t = acceptThread;
        acceptThread = null;
        if (t != null) t.interrupt();
        server = null;
        localPort = -1;
    }

    public boolean isRunning() {
        return running;
    }

    /** 一个方向的数据泵：client→camera 时补 Basic 头；camera→client 时改写地址 */
    private class Pump implements Runnable {
        private final Socket in;
        private final Socket out;
        private final boolean upstream;   // true = 播放器 → 摄像机

        Pump(Socket in, Socket out) {
            this.in = in;
            this.out = out;
            this.upstream = true;
        }

        @Override
        public void run() {
            // 两个构造方向共用一个类：用 in 是不是 server 侧来判断方向
            // in 是本地监听侧（accept 出来的连接）⇒ 播放器发来的请求，需要补认证头
            boolean toCamera = isServerSide(in);
            try {
                InputStream is = in.getInputStream();
                OutputStream os = out.getOutputStream();
                if (toCamera) {
                    pumpRequest(is, os);
                } else {
                    pumpResponse(is, os);
                }
            } catch (IOException ignored) {
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
                try {
                    out.close();
                } catch (IOException ignored) {
                }
                synchronized (sockets) {
                    sockets.remove(in);
                    sockets.remove(out);
                }
            }
        }

        private boolean isServerSide(Socket s) {
            return s.getLocalPort() == localPort;
        }

        /** 播放器 → 摄像机：按 RTSP 请求边界切分，缺 Authorization 就补上 */
        private void pumpRequest(InputStream is, OutputStream os) throws IOException {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            while (true) {
                int n = is.read(tmp);
                if (n <= 0) return;
                // 纯 RTP/RTCP interleaved 帧（'$' 0x24）：原样转发
                if (buf.size() == 0 && (tmp[0] == '$')) {
                    os.write(tmp, 0, n);
                    os.flush();
                    continue;
                }
                buf.write(tmp, 0, n);
                byte[] data = buf.toByteArray();
                while (true) {
                    if (data.length >= 1 && data[0] == '$') {
                        if (data.length < 4) break;
                        int len = ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                        if (data.length < 4 + len) {
                            // 帧还没收完，等下一次读
                            break;
                        }
                        os.write(data, 0, 4 + len);
                        os.flush();
                        data = tail(data, 4 + len);
                        continue;
                    }
                    int end = indexOf(data, CRLF2, 0);
                    if (end < 0) break;
                    String head = new String(data, 0, end, "UTF-8");
                    StringBuilder clean = new StringBuilder();
                    for (String line : head.split("\r\n")) {
                        if (line.toLowerCase().startsWith("authorization:")) {
                            if (!replaceAuth) clean.append(line).append("\r\n");
                            // replaceAuth 时直接丢掉，换成我们的 Basic
                        } else {
                            clean.append(line).append("\r\n");
                        }
                    }
                    String out = clean.toString() + authHeader + "\r\n";
                    os.write(out.getBytes("UTF-8"));
                    os.flush();
                    data = tail(data, end + 4);
                }
                buf.reset();
                if (data.length > 0) buf.write(data, 0, data.length);
            }
        }

        /** 摄像机 → 播放器：按整条报文转发（文本报文改写地址，二进制帧原样转发） */
        private void pumpResponse(InputStream is, OutputStream os) throws IOException {
            BufferedInputStream in = new BufferedInputStream(is, 8192);
            while (true) {
                int first;
                try {
                    first = in.read();
                } catch (IOException e) {
                    return;
                }
                if (first < 0) return;
                if (first == '$') {
                    // interleaved 帧：$ + 通道号 + 2 字节长度 + 负载
                    int ch = readByte(in);
                    if (ch < 0) return;
                    int hi = readByte(in);
                    int lo = readByte(in);
                    if (hi < 0 || lo < 0) return;
                    int len = ((hi & 0xFF) << 8) | (lo & 0xFF);
                    byte[] payload = readN(in, len);
                    os.write(new byte[]{'$', (byte) ch, (byte) hi, (byte) lo}, 0, 4);
                    if (payload.length > 0) os.write(payload, 0, payload.length);
                    os.flush();
                    continue;
                }
                // 文本报文：首行 + 头 + 空行 + 正文
                StringBuilder head = new StringBuilder();
                head.append((char) first);
                String rest;
                try {
                    rest = readUntilCrlf2(in);
                } catch (IOException e) {
                    return;
                }
                if (rest == null) return;
                head.append(rest);
                String msg = head.toString();
                int contentLength = parseContentLength(msg);
                String body = "";
                if (contentLength > 0) {
                    byte[] b = readN(in, contentLength);
                    body = new String(b, "ISO-8859-1");
                    msg = msg + body;
                }
                os.write(rewriteHost(msg).getBytes("ISO-8859-1"));
                os.flush();
            }
        }

        private String rewriteHost(String s) {
            String local = "127.0.0.1:" + localPort;
            s = s.replace("rtsp://" + camHost + ":" + camPort, "rtsp://" + local);
            s = s.replace("rtsp://" + camHost + "/", "rtsp://" + local + "/");
            s = s.replace(camHost + ":" + camPort, local);
            return s;
        }
    }

    private static final byte[] CRLF2 = {'\r', '\n', '\r', '\n'};

    private static int readByte(InputStream in) throws IOException {
        return in.read();
    }

    private static byte[] readN(InputStream in, int n) throws IOException {
        byte[] out = new byte[0];
        if (n <= 0) return out;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(n);
        byte[] tmp = new byte[Math.min(n, 8192)];
        int left = n;
        while (left > 0) {
            int r = in.read(tmp, 0, Math.min(left, tmp.length));
            if (r <= 0) break;
            bos.write(tmp, 0, r);
            left -= r;
        }
        return bos.toByteArray();
    }

    /** 读到 "\r\n\r\n" 为止（含），返回从当前位置到结束的整段文本 */
    private static String readUntilCrlf2(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] tmp = new byte[1];
        while (true) {
            int r = in.read(tmp, 0, 1);
            if (r <= 0) return bos.size() == 0 ? null : bos.toString("ISO-8859-1");
            bos.write(tmp[0]);
            String s = bos.toString("ISO-8859-1");
            if (s.endsWith("\r\n\r\n")) return s;
            if (bos.size() > 64 * 1024) return s;
        }
    }

    private static int parseContentLength(String head) {
        String lower = head.toLowerCase();
        int i = lower.indexOf("content-length:");
        if (i < 0) return 0;
        int e = head.indexOf("\r\n", i);
        String v = head.substring(i + 15, e < 0 ? head.length() : e).trim();
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static int indexOf(byte[] data, byte[] pat, int from) {
        outer:
        for (int i = from; i <= data.length - pat.length; i++) {
            for (int j = 0; j < pat.length; j++) {
                if (data[i + j] != pat[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static byte[] tail(byte[] data, int from) {
        if (from >= data.length) return new byte[0];
        byte[] r = new byte[data.length - from];
        System.arraycopy(data, from, r, 0, r.length);
        return r;
    }
}
