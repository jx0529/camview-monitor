package com.camview.monitor;

import java.util.Locale;

/**
 * 账号密码清洗与自检。
 *
 * 手机端从聊天/记事本/网页里复制密码时，很容易带上看不见的字符：
 *   空格、换行（\n \r）、制表符、不换行空格（\u00A0）、零宽空格（\u200B）、BOM（\uFEFF），
 * 或者被输入法替换成全角（ａｄｍｉｎ１２３、全角空格）。
 * 网页登录框通常会自己 trim 掉这些，所以网页能进；
 * 但 RTSP / HTTP 接口是逐字节比较的，多一个字节就 401——这就是"网页能用、App 通不过"的最常见原因。
 *
 * 这里统一在发给设备之前做一次清洗，并在诊断报告里把原始字符串的特征打出来，方便定位。
 */
public class Creds {

    /** 去掉首尾和中间的空白类不可见字符，并把全角 ASCII 转成半角 */
    public static String clean(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // 不可见字符直接丢弃
            if (c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF'
                    || c == '\u0000' || c == '\r' || c == '\n' || c == '\t' || c == '\u000B' || c == '\f') {
                continue;
            }
            // 各种空格统一成空格（后面再 trim 掉首尾）
            if (c == '\u00A0' || c == '\u3000' || c == '\u2007' || c == '\u202F') {
                sb.append(' ');
                continue;
            }
            // 全角 ASCII（FF01-FF5E）转半角
            if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - 0xFEE0));
                continue;
            }
            sb.append(c);
        }
        return sb.toString().trim();
    }

    /** 是否包含可疑字符（空白/全角/零宽） */
    public static boolean looksDirty(String s) {
        if (s == null) return false;
        String c = clean(s);
        return !c.equals(s);
    }

    /** 生成一段可读的自检说明，写进诊断报告 */
    public static String inspect(String label, String raw) {
        String c = clean(raw);
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(": ");
        if (c.isEmpty()) {
            sb.append("(空)");
            return sb.toString();
        }
        sb.append("长度 ").append(c.length()).append(" 位");
        if (!c.equals(raw)) {
            sb.append(" · ⚠ 原始值里有空白/换行/全角字符，已自动清理（原始长度 ")
                    .append(raw == null ? 0 : raw.length()).append("）");
        } else {
            sb.append(" · 无异常字符");
        }
        // 打末几位字符的编码，方便核对（不直接暴露完整密码）
        sb.append(" · 末 3 位编码: ");
        int from = Math.max(0, c.length() - 3);
        for (int i = from; i < c.length(); i++) {
            sb.append(String.format(Locale.US, "U+%04X ", (int) c.charAt(i)));
        }
        return sb.toString().trim();
    }
}
