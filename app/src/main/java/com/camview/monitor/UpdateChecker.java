package com.camview.monitor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 在线更新。
 *
 * 更新源支持两种：
 *  1) 仓库里的 update.json（推荐，无需打 Release）
 *     https://raw.githubusercontent.com/{repo}/main/update.json
 *  2) GitHub Releases 的最新 Release + APK 资产
 *
 * 因为 APK 是二进制、只能以文本方式提交到仓库，这里同时兼容
 * "APK 直链" 与 "APK 的 base64 文件" 两种形式；
 * 国内访问 raw.githubusercontent 不稳定时自动切到 jsDelivr 镜像。
 */
public class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    public static void check(final Activity act, final boolean silent) {
        String repo = App.cfg().githubRepo;
        if (repo == null) repo = "";
        repo = repo.trim();
        if (!repo.contains("/")) {
            if (!silent) Ui.toast(act, "还没配置更新仓库，请在「设置 → 在线更新」填写 owner/repo");
            return;
        }
        final String finalRepo = repo;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Info info = fetchInfo(finalRepo);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (act.isFinishing()) return;
                            if (info == null) {
                                if (!silent) Ui.toast(act, "检查更新失败：读取不到更新信息");
                                return;
                            }
                            if (!info.newer()) {
                                if (!silent) Ui.toast(act, "已是最新版本 v" + BuildConfig.VERSION_NAME);
                                return;
                            }
                            showUpdateDialog(act, info);
                        }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "检查更新失败: " + e);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (!silent) Ui.toast(act, "检查更新失败：" + e.getMessage());
                        }
                    });
                }
            }
        }, "update-check").start();
    }

    private static class Info {
        String version = "";
        String notes = "";
        String apkUrl = "";
        boolean base64 = false;
        long size = 0;

        boolean newer() {
            return compareVersion(version, BuildConfig.VERSION_NAME) > 0;
        }
    }

    private static Info fetchInfo(String repo) {
        String[] jsonUrls = {
                "https://raw.githubusercontent.com/" + repo + "/main/update.json",
                "https://cdn.jsdelivr.net/gh/" + repo + "@main/update.json"
        };
        for (String u : jsonUrls) {
            try {
                JSONObject o = getJson(u);
                Info info = new Info();
                info.version = cleanVersion(o.optString("version", ""));
                info.notes = o.optString("notes", "");
                info.size = o.optLong("size", 0);
                info.base64 = "base64".equalsIgnoreCase(o.optString("encoding", ""));
                info.apkUrl = o.optString("apk", "");
                if (info.apkUrl.length() == 0) {
                    String path = o.optString("apk_path", "");
                    if (path.length() > 0) {
                        info.apkUrl = "https://raw.githubusercontent.com/" + repo + "/main/" + path;
                        if (path.toLowerCase().endsWith(".b64")) info.base64 = true;
                    }
                }
                if (info.version.length() > 0 && info.apkUrl.length() > 0) return info;
            } catch (Exception ignored) {
            }
        }

        try {
            JSONObject rel = getJson("https://api.github.com/repos/" + repo + "/releases/latest");
            Info info = new Info();
            info.version = cleanVersion(rel.optString("tag_name", ""));
            info.notes = rel.optString("body", "");
            JSONArray assets = rel.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    String lower = name.toLowerCase();
                    if (lower.endsWith(".apk") || lower.endsWith(".apk.b64")) {
                        info.apkUrl = a.optString("browser_download_url", "");
                        info.size = a.optLong("size", 0);
                        info.base64 = lower.endsWith(".b64");
                        if (info.apkUrl.length() > 0) return info;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String cleanVersion(String v) {
        v = v == null ? "" : v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        return v;
    }

    private static void showUpdateDialog(final Activity act, final Info info) {
        String notes = info.notes;
        if (notes.length() > 600) notes = notes.substring(0, 600) + "…";
        String msg = "当前版本 v" + BuildConfig.VERSION_NAME + "，最新版本 v" + info.version + "\n\n"
                + (notes.length() > 0 ? notes : "（无更新说明）")
                + (info.size > 0 ? "\n\n安装包大小：" + (info.size / 1024 / 1024) + " MB" : "");
        new AlertDialog.Builder(act, R.style.Theme_CamView_Dialog)
                .setTitle("发现新版本 v" + info.version)
                .setMessage(msg)
                .setPositiveButton("立即更新", (d, w) -> downloadAndInstall(act, info))
                .setNegativeButton("稍后", null)
                .show();
    }

    private static void downloadAndInstall(final Activity act, final Info info) {
        final ProgressDialog pd = new ProgressDialog(act, R.style.Theme_CamView_Dialog);
        pd.setTitle("正在下载 v" + info.version);
        pd.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        pd.setMax(100);
        pd.setCancelable(false);
        pd.show();

        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    File dir = new File(act.getCacheDir(), "updates");
                    if (!dir.exists()) dir.mkdirs();
                    File apk = new File(dir, "camview-" + info.version + ".apk");
                    if (apk.exists() && !apk.delete()) {
                        // 忽略，后面写文件会覆盖
                    }

                    Exception lastError = null;
                    boolean ok = false;
                    for (String url : downloadCandidates(info.apkUrl)) {
                        try {
                            download(url, apk, info, pd, ui);
                            ok = true;
                            break;
                        } catch (Exception e) {
                            lastError = e;
                            if (apk.exists()) apk.delete();
                            Log.w(TAG, "下载失败 " + url + " : " + e);
                        }
                    }
                    if (!ok) throw lastError == null ? new Exception("下载失败") : lastError;

                    final File f = apk;
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            installApk(act, f);
                        }
                    });
                } catch (final Exception e) {
                    Log.w(TAG, "下载失败: " + e);
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            pd.dismiss();
                            Ui.toast(act, "下载失败：" + e.getMessage());
                        }
                    });
                }
            }
        }, "update-download").start();
    }

    /** raw.githubusercontent 在国内可能连不上，自动加一个 jsDelivr 镜像 */
    private static String[] downloadCandidates(String url) {
        String mirror = null;
        try {
            String prefix = "https://raw.githubusercontent.com/";
            if (url.startsWith(prefix)) {
                String rest = url.substring(prefix.length());
                String[] first = rest.split("/", 3); // owner / repo / branch/path...
                if (first.length == 3) {
                    int slash = first[2].indexOf('/');
                    if (slash > 0) {
                        String branch = first[2].substring(0, slash);
                        String path = first[2].substring(slash + 1);
                        mirror = "https://cdn.jsdelivr.net/gh/" + first[0] + "/" + first[1]
                                + "@" + branch + "/" + path;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return mirror == null ? new String[]{url} : new String[]{url, mirror};
    }

    private static void download(String url, File apk, Info info, ProgressDialog pd, Handler ui)
            throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "CamView-Monitor");
        try {
            int code = conn.getResponseCode();
            if (code / 100 == 3) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                conn = (HttpURLConnection) new URL(loc).openConnection();
                conn.setRequestProperty("User-Agent", "CamView-Monitor");
                code = conn.getResponseCode();
            }
            if (code != 200) throw new Exception("HTTP " + code);
            long total = info.size > 0 ? info.size : conn.getContentLength();
            InputStream in = conn.getInputStream();

            if (info.base64) {
                ByteArrayOutputStream textBuf = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                long got = 0;
                while ((n = in.read(buf)) > 0) {
                    textBuf.write(buf, 0, n);
                    got += n;
                    if (total > 0) postProgress(ui, pd, got * 100 / total);
                }
                in.close();
                byte[] decoded = Base64.decode(textBuf.toByteArray(), Base64.DEFAULT);
                FileOutputStream out = new FileOutputStream(apk);
                out.write(decoded);
                out.flush();
                out.close();
            } else {
                FileOutputStream out = new FileOutputStream(apk);
                byte[] buf = new byte[16384];
                int n;
                long got = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    got += n;
                    if (total > 0) postProgress(ui, pd, got * 100 / total);
                }
                out.flush();
                out.close();
                in.close();
            }
            postProgress(ui, pd, 100);
        } finally {
            conn.disconnect();
        }
    }

    private static void postProgress(Handler ui, final ProgressDialog pd, long percent) {
        final int p = (int) Math.max(0, Math.min(99, percent));
        ui.post(new Runnable() {
            @Override
            public void run() {
                pd.setProgress(p);
            }
        });
    }

    private static void installApk(final Activity act, File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !act.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(act, R.style.Theme_CamView_Dialog)
                    .setTitle("需要安装权限")
                    .setMessage("请允许「多路监控」安装应用，然后重新点击更新。")
                    .setPositiveButton("去设置", (d, w) -> {
                        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + act.getPackageName()));
                        act.startActivity(i);
                    })
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(act, act.getPackageName() + ".fileprovider", apk);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        act.startActivity(i);
    }

    private static JSONObject getJson(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setRequestProperty("User-Agent", "CamView-Monitor");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = conn.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            InputStream in = conn.getInputStream();
            byte[] buf = new byte[8192];
            int n;
            StringBuilder sb = new StringBuilder();
            while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            in.close();
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    /** 简易版本号比较：1.2.3 > 1.2.0 */
    public static int compareVersion(String a, String b) {
        String[] x = a.split("\\.");
        String[] y = b.split("\\.");
        int len = Math.max(x.length, y.length);
        for (int i = 0; i < len; i++) {
            int xi = i < x.length ? parseIntSafe(x[i]) : 0;
            int yi = i < y.length ? parseIntSafe(y[i]) : 0;
            if (xi != yi) return xi > yi ? 1 : -1;
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            StringBuilder num = new StringBuilder();
            for (char c : s.toCharArray()) {
                if (Character.isDigit(c)) num.append(c);
                else break;
            }
            return num.length() == 0 ? 0 : Integer.parseInt(num.toString());
        } catch (Exception e) {
            return 0;
        }
    }
}
