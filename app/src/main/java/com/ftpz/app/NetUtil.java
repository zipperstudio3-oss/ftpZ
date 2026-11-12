package com.ftpz.app;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Finds the phone's local IPv4 address (Wi-Fi, hotspot, USB/ethernet tethering). */
public final class NetUtil {

    private NetUtil() {
    }

    /** @return the best local IPv4 address, or null when the phone is not on any network. */
    public static String getLocalIp() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase();
                // skip mobile-data interfaces
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp")
                        || name.startsWith("tun") || name.startsWith("dummy")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    if (name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("swlan")
                            || name.startsWith("eth") || name.startsWith("rndis")) {
                        return ip;
                    }
                    if (fallback == null) fallback = ip;
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }
}
