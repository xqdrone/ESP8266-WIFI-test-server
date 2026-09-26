package com.example.esp8266.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;

/**
 * 网络工具：列出本机所有可用的 IPv4 地址。
 * <p>单片机需要连接 PC 的局域网 IP，界面上直接把这些地址显示出来，省去手动 ipconfig。</p>
 */
public final class NetUtils {

    private NetUtils() {
    }

    /**
     * @return 本机非回环 IPv4 地址，常见私网地址（192.168.x.x / 10.x.x.x / 172.16-31.x.x）排在前面。
     */
    public static List<String> localIpv4Addresses() {
        List<String> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface nic = interfaces.nextElement();
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = nic.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        String host = address.getHostAddress();
                        if (!result.contains(host)) {
                            result.add(host);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 受限环境下枚举网卡可能失败，返回已收集到的地址即可
        }
        result.sort(Comparator.comparingInt(NetUtils::rank));
        return Collections.unmodifiableList(result);
    }

    /** 私网地址优先，方便用户一眼找到该填进单片机代码的那个 IP。 */
    private static int rank(String ip) {
        if (ip.startsWith("192.168.")) {
            return 0;
        }
        if (ip.startsWith("10.")) {
            return 1;
        }
        if (ip.startsWith("172.")) {
            return 2;
        }
        if (ip.startsWith("169.254.")) {
            return 9;
        }
        return 5;
    }
}
