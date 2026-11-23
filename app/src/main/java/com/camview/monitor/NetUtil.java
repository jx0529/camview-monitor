package com.camview.monitor;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;

import java.net.Inet4Address;
import java.util.ArrayList;
import java.util.List;

/** IP 网段解析 / 本机网段探测 */
public class NetUtil {

    private static long toLong(String ip) {
        String[] p = ip.trim().split("\\.");
        if (p.length != 4) throw new IllegalArgumentException("IP 格式错误: " + ip);
        long v = 0;
        for (String s : p) {
            int n = Integer.parseInt(s);
            if (n < 0 || n > 255) throw new IllegalArgumentException("IP 段超出范围: " + ip);
            v = (v << 8) | n;
        }
        return v;
    }

    private static String toIp(long v) {
        return ((v >> 24) & 255) + "." + ((v >> 16) & 255) + "." + ((v >> 8) & 255) + "." + (v & 255);
    }

    /** 解析一条范围，返回 long[]{start,end} */
    public static long[] parseRange(String part) {
        part = part.trim();
        if (part.length() == 0) return null;
        try {
            if (part.contains("/")) {
                String[] sp = part.split("/");
                int prefix = Integer.parseInt(sp[1].trim());
                long ip = toLong(sp[0]);
                long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
                long start = (ip & mask) + 1;      // 跳过网络地址
                long end = (ip & mask) + (1L << (32 - prefix)) - 2; // 跳过广播地址
                if (end < start) return null;
                return new long[]{start, end};
            }
            if (part.contains("-")) {
                String[] sp = part.split("-");
                String left = sp[0].trim();
                String right = sp[1].trim();
                if (right.contains(".")) {
                    long a = toLong(left);
                    long b = toLong(right);
                    return new long[]{Math.min(a, b), Math.max(a, b)};
                }
                int dot = left.lastIndexOf('.');
                if (dot < 0) return null;
                String prefix = left.substring(0, dot + 1);
                int s = Integer.parseInt(left.substring(dot + 1));
                int e = Integer.parseInt(right);
                long a = toLong(prefix + s);
                long b = toLong(prefix + e);
                return new long[]{Math.min(a, b), Math.max(a, b)};
            }
            long single = toLong(part);
            return new long[]{single, single};
        } catch (Exception e) {
            return null;
        }
    }

    /** 展开一段范围内的所有 IP（带数量上限保护） */
    public static List<String> expand(long[] range, int limit) {
        List<String> out = new ArrayList<>();
        if (range == null) return out;
        for (long i = range[0]; i <= range[1] && out.size() < limit; i++) {
            out.add(toIp(i));
        }
        return out;
    }

    public static List<String> expandInput(String input, int limit) {
        List<String> out = new ArrayList<>();
        for (String part : input.split(",")) {
            long[] r = parseRange(part);
            if (r == null) continue;
            for (long i = r[0]; i <= r[1] && out.size() < limit; i++) out.add(toIp(i));
        }
        return out;
    }

    public static boolean isValidIp(String ip) {
        if (ip == null) return false;
        try {
            toLong(ip);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 读取本机当前网络所在网段，失败返回 null */
    public static long[] localSubnet(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            Network net = cm.getActiveNetwork();
            if (net == null) return null;
            LinkProperties lp = cm.getLinkProperties(net);
            if (lp == null) return null;
            for (LinkAddress la : lp.getLinkAddresses()) {
                if (la.getAddress() instanceof Inet4Address) {
                    Inet4Address a = (Inet4Address) la.getAddress();
                    String host = a.getHostAddress();
                    if (host.startsWith("127.")) continue;
                    int prefix = la.getPrefixLength();
                    long ip = toLong(host);
                    long mask = prefix == 0 ? 0 : (0xFFFFFFFFL << (32 - prefix)) & 0xFFFFFFFFL;
                    long start = (ip & mask) + 1;
                    long end = (ip & mask) + (1L << (32 - prefix)) - 2;
                    if (end < start) return null;
                    return new long[]{start, end};
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static String subnetText(long[] r) {
        if (r == null) return "";
        return toIp(r[0]) + " - " + toIp(r[1]);
    }
}
