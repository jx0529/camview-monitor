package com.camview.monitor;

import org.json.JSONException;
import org.json.JSONObject;

/** 单个摄像头的配置数据（与桌面版 saved_cameras 结构保持兼容） */
public class CameraInfo {
    public String ip;
    public String user;
    public String password;
    public int rotation = 0;
    public boolean flipH = false;
    public boolean flipV = false;
    /** 网页抓图模式用到的 HTTP 抓图地址（探测出来后自动记住） */
    public String snapUrl = "";
    /** 认证方式："" 自动 / "basic" 强制走 Basic 注入隧道 */
    public String authMode = "";
    /** 仅内存字段：最近一次连接是否失败 */
    public boolean lastFailed = false;

    public CameraInfo(String ip, String user, String password) {
        this.ip = ip;
        this.user = user;
        this.password = password;
    }

    public static CameraInfo fromJson(JSONObject o, String defUser, String defPwd) {
        String ip = o.optString("ip", "");
        if (ip.length() == 0) return null;
        CameraInfo c = new CameraInfo(ip,
                o.optString("user", defUser),
                o.optString("password", defPwd));
        c.rotation = o.optInt("rotation", 0);
        c.flipH = o.optBoolean("flip_h", false);
        c.flipV = o.optBoolean("flip_v", false);
        c.snapUrl = o.optString("snap_url", "");
        c.authMode = o.optString("auth_mode", "");
        return c;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("ip", ip);
            o.put("user", user);
            o.put("password", password);
            o.put("rotation", rotation);
            o.put("flip_h", flipH);
            o.put("flip_v", flipV);
            if (snapUrl != null && !snapUrl.isEmpty()) o.put("snap_url", snapUrl);
            if (authMode != null && !authMode.isEmpty()) o.put("auth_mode", authMode);
        } catch (JSONException ignored) {
        }
        return o;
    }
}
