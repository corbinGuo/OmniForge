package com.omniforge.core.persistence.service;

import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Formatter;

/**
 * 硬件指纹（Phase 4 License 校验基础）：首个可用网卡 MAC + 主机名的 SHA-256。
 * 取不到网卡时回退主机名+用户目录路径，保证可计算但降低唯一性。
 */
public final class HardwareFingerprint {

    private HardwareFingerprint() {
    }

    /** 计算本机指纹（64 位十六进制） */
    public static String of() {
        String raw = firstMac() + "|" + System.getProperty("user.name", "")
                + "|" + System.getProperty("os.name", "");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            Formatter formatter = new Formatter();
            for (byte b : hash) {
                formatter.format("%02x", b);
            }
            String result = formatter.toString();
            formatter.close();
            return result;
        } catch (Exception e) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static String firstMac() {
        try {
            var interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                byte[] mac = networkInterface.getHardwareAddress();
                if (mac != null && mac.length > 0 && !networkInterface.isLoopback()) {
                    StringBuilder sb = new StringBuilder();
                    for (byte b : mac) {
                        sb.append(String.format("%02x", b));
                    }
                    return sb.toString();
                }
            }
        } catch (Exception ignored) {
            // 回退
        }
        return "no-mac";
    }
}
