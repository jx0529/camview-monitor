package com.camview.monitor;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 配置中心。
 * 文件名与字段名与桌面版 camera_system_config.json 保持一致，
 * 因此桌面端的配置文件可以直接导入到手机端使用。
 */
public class AppConfig {

    private static final String TAG = "AppConfig";
    public static final String FILE_NAME = "camera_system_config.json";
    public static final String DEFAULT_GROUP = "默认分组";

    public String defaultUser = "admin";
    public String defaultPassword = "admin123";
    public List<String> rtspTemplates = defaultTemplates();

    public static List<String> defaultTemplates() {
        return new ArrayList<>(Arrays.asList(
                "rtsp://{user}:{password}@{ip}:554/Streaming/Channels/102",
                "rtsp://{user}:{password}@{ip}:554/Streaming/Channels/101",
                "rtsp://{user}:{password}@{ip}:554/cam/realmonitor?channel=1&subtype=0",
                "rtsp://{user}:{password}@{ip}:554/onvif1",
                "rtsp://{user}:{password}@{ip}:554/h264"));
    }

    /** 手机端固定单列显示，保留该字段以兼容旧配置 */
    public int maxCols = 1;
    public int timeoutMs = 8000;
    public int itemsPerPage = 6;
    public boolean showFps = true;
    /** true = MediaCodec 硬件解码（GPU）；false = 软件解码 */
    public boolean hwDecode = true;
    /** RTSP 走 TCP，穿透路由/公网更稳定 */
    public boolean rtspTcp = true;
    /** 低延迟模式：优先 UDP + 小缓存（实时性优先，弱网可能花屏） */
    public boolean lowLatency = true;
    /** 播放缓存（毫秒），越小越实时 */
    public int cacheMs = 150;
    public boolean autoUpdate = true;
    public String gpuMode = "auto";
    /** GitHub 仓库 owner/repo，用于检查更新 */
    public String githubRepo = "jx0529/camview-monitor";

    public String currentGroup = DEFAULT_GROUP;
    public LinkedHashMap<String, List<String>> groups = new LinkedHashMap<>();
    public final ArrayList<CameraInfo> cameras = new ArrayList<>();

    private final File file;

    public AppConfig(Context ctx) {
        file = new File(ctx.getFilesDir(), FILE_NAME);
        groups.put(DEFAULT_GROUP, new ArrayList<String>());
        load();
    }

    // ==================== 读写 ====================

    public synchronized void load() {
        if (!file.exists()) {
            save();
            return;
        }
        try {
            JSONObject o = new JSONObject(readAll(file));
            defaultUser = o.optString("default_user", defaultUser);
            defaultPassword = o.optString("default_password", defaultPassword);
            maxCols = o.optInt("max_cols", 1);
            timeoutMs = o.optInt("connection_timeout_ms", timeoutMs);
            // 旧版本默认只给了 3 秒，手机上很容易超时，抬到 8 秒
            if (timeoutMs < 5000) timeoutMs = 8000;
            itemsPerPage = o.optInt("items_per_page", itemsPerPage);
            showFps = o.optBoolean("show_fps", showFps);
            hwDecode = o.optBoolean("hw_decode", hwDecode);
            rtspTcp = o.optBoolean("rtsp_tcp", rtspTcp);
            autoUpdate = o.optBoolean("auto_check_update", autoUpdate);
            gpuMode = o.optString("gpu_mode", "auto");
            githubRepo = o.optString("github_repo", "");
            currentGroup = o.optString("current_group", DEFAULT_GROUP);

            List<String> tpl = new ArrayList<>();
            JSONArray ta = o.optJSONArray("rtsp_templates");
            if (ta != null) {
                for (int i = 0; i < ta.length(); i++) {
                    String s = ta.optString(i, "").trim();
                    if (s.length() > 0) tpl.add(s);
                }
            }
            if (!tpl.isEmpty()) rtspTemplates = tpl;

            LinkedHashMap<String, List<String>> g = new LinkedHashMap<>();
            JSONObject go = o.optJSONObject("camera_groups");
            if (go != null) {
                java.util.Iterator<String> it = go.keys();
                while (it.hasNext()) {
                    String name = it.next();
                    JSONArray arr = go.optJSONArray(name);
                    List<String> ips = new ArrayList<>();
                    if (arr != null) {
                        for (int i = 0; i < arr.length(); i++) {
                            String ip = arr.optString(i, "").trim();
                            if (ip.length() > 0 && !ips.contains(ip)) ips.add(ip);
                        }
                    }
                    g.put(name, ips);
                }
            }
            if (g.isEmpty()) g.put(DEFAULT_GROUP, new ArrayList<String>());
            if (!g.containsKey(DEFAULT_GROUP)) g.put(DEFAULT_GROUP, new ArrayList<>());
            groups = g;

            cameras.clear();
            JSONArray ca = o.optJSONArray("saved_cameras");
            if (ca != null) {
                for (int i = 0; i < ca.length(); i++) {
                    CameraInfo c = CameraInfo.fromJson(ca.optJSONObject(i), defaultUser, defaultPassword);
                    if (c != null) cameras.add(c);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "配置读取失败: " + e);
        }

        if (!groups.containsKey(currentGroup)) {
            currentGroup = groups.keySet().iterator().next();
        }
        if (itemsPerPage < 1) itemsPerPage = 6;
        ensureCameraEntries();
    }

    /** 兼容桌面版逻辑：分组里出现但没有详细信息的 IP，补齐一条记录 */
    public void ensureCameraEntries() {
        for (String ip : allIps()) {
            if (findCamera(ip) == null) {
                cameras.add(new CameraInfo(ip, defaultUser, defaultPassword));
            }
        }
    }

    public synchronized void save() {
        revision++;
        try {
            JSONObject o = new JSONObject();
            o.put("default_user", defaultUser);
            o.put("default_password", defaultPassword);
            o.put("max_cols", maxCols);
            o.put("connection_timeout_ms", timeoutMs);
            o.put("items_per_page", itemsPerPage);
            o.put("show_fps", showFps);
            o.put("hw_decode", hwDecode);
            o.put("rtsp_tcp", rtspTcp);
            o.put("auto_check_update", autoUpdate);
            o.put("gpu_mode", gpuMode);
            o.put("github_repo", githubRepo);
            o.put("current_group", currentGroup);

            JSONArray ta = new JSONArray();
            for (String t : rtspTemplates) ta.put(t);
            o.put("rtsp_templates", ta);

            JSONObject go = new JSONObject();
            for (Map.Entry<String, List<String>> e : groups.entrySet()) {
                JSONArray arr = new JSONArray();
                for (String ip : e.getValue()) arr.put(ip);
                go.put(e.getKey(), arr);
            }
            o.put("camera_groups", go);

            JSONArray ca = new JSONArray();
            for (CameraInfo c : cameras) ca.put(c.toJson());
            o.put("saved_cameras", ca);

            writeAll(file, o.toString(2));
        } catch (Exception e) {
            Log.w(TAG, "配置保存失败: " + e);
        }
    }

    public String getGpuMode() {
        return gpuMode;
    }

    // ==================== 摄像头 ====================

    public CameraInfo findCamera(String ip) {
        for (CameraInfo c : cameras) {
            if (c.ip.equals(ip)) return c;
        }
        return null;
    }

    public CameraInfo getOrCreateCamera(String ip) {
        CameraInfo c = findCamera(ip);
        if (c == null) {
            c = new CameraInfo(ip, defaultUser, defaultPassword);
            cameras.add(c);
        }
        return c;
    }

    /** 添加摄像头到当前分组；已存在返回 false */
    public synchronized boolean addCamera(String ip, String user, String password) {
        ip = ip == null ? "" : ip.trim();
        if (ip.length() == 0) return false;
        if (findCamera(ip) != null) return false;
        String cu = Creds.clean(user);
        String cp = Creds.clean(password);
        CameraInfo c = new CameraInfo(ip,
                cu.isEmpty() ? defaultUser : cu,
                cp.isEmpty() ? defaultPassword : cp);
        cameras.add(c);
        List<String> list = groups.get(currentGroup);
        if (list == null) {
            list = new ArrayList<>();
            groups.put(currentGroup, list);
        }
        if (!list.contains(ip)) list.add(ip);
        save();
        return true;
    }

    /** 批量添加（一次写盘） */
    public synchronized int addCameras(List<String> ips, String user, String password) {
        int added = 0;
        List<String> list = groups.get(currentGroup);
        if (list == null) {
            list = new ArrayList<>();
            groups.put(currentGroup, list);
        }
        for (String raw : ips) {
            String ip = raw == null ? "" : raw.trim();
            if (ip.length() == 0 || findCamera(ip) != null) continue;
            String cu = Creds.clean(user);
            String cp = Creds.clean(password);
            cameras.add(new CameraInfo(ip, cu.isEmpty() ? defaultUser : cu, cp.isEmpty() ? defaultPassword : cp));
            if (!list.contains(ip)) list.add(ip);
            added++;
        }
        if (added > 0) save();
        return added;
    }

    public synchronized void removeCamera(String ip) {
        for (List<String> ips : groups.values()) ips.remove(ip);
        for (int i = cameras.size() - 1; i >= 0; i--) {
            if (cameras.get(i).ip.equals(ip)) cameras.remove(i);
        }
        save();
    }

    /** 批量设置认证方式（"" 自动 / "basic" 全部走 Basic 隧道） */
    public synchronized int setAllAuthMode(String mode) {
        int n = 0;
        for (CameraInfo c : cameras) {
            c.authMode = mode == null ? "" : mode;
            n++;
        }
        revision++;
        save();
        return n;
    }

    /** 修改某台摄像头的账号密码 */
    public synchronized void setCameraCredentials(String ip, String user, String password) {
        CameraInfo c = findCamera(ip);
        if (c == null) return;
        c.user = Creds.clean(user);
        c.password = Creds.clean(password);
        revision++;
        save();
    }

    /** 删除全部摄像头（分组名称保留，摄像头配置清空） */
    public synchronized int clearAllCameras() {
        int n = totalCameraCount();
        cameras.clear();
        for (List<String> ips : groups.values()) ips.clear();
        currentPage = 1;
        save();
        return n;
    }

    public List<String> allIps() {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        for (List<String> ips : groups.values()) set.addAll(ips);
        return new ArrayList<>(set);
    }

    public int totalCameraCount() {
        int n = 0;
        for (List<String> ips : groups.values()) n += ips.size();
        return n;
    }

    // ==================== 分组 ====================

    public List<String> groupNames() {
        return new ArrayList<>(groups.keySet());
    }

    public synchronized boolean addGroup(String name) {
        name = name == null ? "" : name.trim();
        if (name.length() == 0 || groups.containsKey(name)) return false;
        groups.put(name, new ArrayList<String>());
        save();
        return true;
    }

    public synchronized boolean renameGroup(String oldName, String newName) {
        newName = newName == null ? "" : newName.trim();
        if (!groups.containsKey(oldName) || newName.length() == 0
                || groups.containsKey(newName) || oldName.equals(DEFAULT_GROUP)) return false;
        LinkedHashMap<String, List<String>> g = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : groups.entrySet()) {
            if (e.getKey().equals(oldName)) g.put(newName, e.getValue());
            else g.put(e.getKey(), e.getValue());
        }
        groups = g;
        if (currentGroup.equals(oldName)) currentGroup = newName;
        save();
        return true;
    }

    /** 删除分组，摄像头并入默认分组 */
    public synchronized boolean deleteGroup(String name) {
        if (name == null || name.equals(DEFAULT_GROUP) || !groups.containsKey(name)) return false;
        List<String> ips = groups.remove(name);
        List<String> def = groups.get(DEFAULT_GROUP);
        if (def == null) {
            def = new ArrayList<>();
            groups.put(DEFAULT_GROUP, def);
        }
        for (String ip : ips) if (!def.contains(ip)) def.add(ip);
        if (currentGroup.equals(name)) currentGroup = DEFAULT_GROUP;
        save();
        return true;
    }

    public synchronized boolean moveCamera(String ip, String from, String to) {
        List<String> src = groups.get(from);
        List<String> dst = groups.get(to);
        if (src == null || dst == null) return false;
        if (!src.contains(ip)) return false;
        src.remove(ip);
        if (!dst.contains(ip)) dst.add(ip);
        save();
        return true;
    }

    public synchronized void sortCurrentGroup() {
        List<String> ips = groups.get(currentGroup);
        if (ips != null) Collections.sort(ips, (a, b) -> Long.compare(ipValue(a), ipValue(b)));
        save();
    }

    public synchronized void sortAllGroups() {
        for (List<String> ips : groups.values()) {
            Collections.sort(ips, (a, b) -> Long.compare(ipValue(a), ipValue(b)));
        }
        save();
    }

    public synchronized void sortGroupsByName() {
        LinkedHashMap<String, List<String>> g = new LinkedHashMap<>();
        if (groups.containsKey(DEFAULT_GROUP)) g.put(DEFAULT_GROUP, groups.get(DEFAULT_GROUP));
        List<String> others = new ArrayList<>(groups.keySet());
        others.remove(DEFAULT_GROUP);
        Collections.sort(others);
        for (String n : others) g.put(n, groups.get(n));
        groups = g;
        save();
    }

    public static long ipValue(String ip) {
        try {
            String[] p = ip.split("\\.");
            return ((long) Integer.parseInt(p[0]) << 24) + ((long) Integer.parseInt(p[1]) << 16)
                    + ((long) Integer.parseInt(p[2]) << 8) + Integer.parseInt(p[3]);
        } catch (Exception e) {
            return 0L;
        }
    }

    // ==================== 分页 ====================

    public int currentPage = 0;
    /** 配置版本号，任何保存都会 +1，界面据此判断是否需要刷新 */
    public int revision = 0;

    public int totalPages() {
        List<String> ips = groups.get(currentGroup);
        int n = ips == null ? 0 : ips.size();
        return Math.max(1, (n + itemsPerPage - 1) / itemsPerPage);
    }

    public List<String> pageCameras() {
        List<String> ips = groups.get(currentGroup);
        if (ips == null) return new ArrayList<>();
        int start = currentPage * itemsPerPage;
        if (start >= ips.size()) {
            currentPage = Math.max(0, totalPages() - 1);
            start = currentPage * itemsPerPage;
        }
        int end = Math.min(ips.size(), start + itemsPerPage);
        return new ArrayList<>(ips.subList(start, end));
    }

    public int groupCameraCount() {
        List<String> ips = groups.get(currentGroup);
        return ips == null ? 0 : ips.size();
    }

    // ==================== 导入桌面配置 ====================

    public synchronized String importFromJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            int imported = 0;
            JSONObject go = o.optJSONObject("camera_groups");
            if (go != null) {
                java.util.Iterator<String> it = go.keys();
                while (it.hasNext()) {
                    String name = it.next();
                    JSONArray arr = go.optJSONArray(name);
                    if (arr == null) continue;
                    if (!groups.containsKey(name)) groups.put(name, new ArrayList<>());
                    List<String> target = groups.get(name);
                    for (int i = 0; i < arr.length(); i++) {
                        String ip = arr.optString(i, "").trim();
                        if (ip.length() > 0 && !target.contains(ip)) {
                            target.add(ip);
                            imported++;
                        }
                    }
                }
            }
            JSONArray ca = o.optJSONArray("saved_cameras");
            if (ca != null) {
                for (int i = 0; i < ca.length(); i++) {
                    CameraInfo c = CameraInfo.fromJson(ca.optJSONObject(i), defaultUser, defaultPassword);
                    if (c == null) continue;
                    CameraInfo exist = findCamera(c.ip);
                    if (exist == null) cameras.add(c);
                    else {
                        exist.user = c.user;
                        exist.password = c.password;
                        exist.rotation = c.rotation;
                        exist.flipH = c.flipH;
                        exist.flipV = c.flipV;
                    }
                }
            }
            ensureCameraEntries();
            save();
            return "导入成功，新增 " + imported + " 个摄像头";
        } catch (Exception e) {
            return "导入失败：" + e.getMessage();
        }
    }

    // ==================== 工具 ====================

    /** URL 里账号密码必须转义，否则密码含 @ : / 等字符时整条地址会被解析错 */
    public static String encodeAuth(String s) {
        if (s == null) return "";
        try {
            // 先清掉粘贴带进来的换行/空格/全角字符，再做 URL 转义
            return java.net.URLEncoder.encode(Creds.clean(s), "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    public static String renderTemplate(String template, String ip, String user, String password) {
        String u = Creds.clean(user);
        String p = Creds.clean(password);
        String auth = "";
        if (!u.isEmpty() || !p.isEmpty()) {
            auth = encodeAuth(u) + ":" + encodeAuth(p) + "@";
        }
        // 模板里的 "{user}:{password}@" 整体替换，避免出现 rtsp://:@ip 这种非法地址
        String out = template
                .replace("{user}:{password}@", auth)
                .replace("{user}", encodeAuth(u))
                .replace("{password}", encodeAuth(p))
                .replace("{ip}", ip);
        return out;
    }

    private static String readAll(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void writeAll(File f, String content) throws Exception {
        File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (!tmp.renameTo(f)) {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            tmp.delete();
        }
    }
}
