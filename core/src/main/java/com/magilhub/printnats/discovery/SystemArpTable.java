package com.magilhub.printnats.discovery;

import com.magilhub.printnats.spi.ArpTable;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the ARP cache the way legacy (stealthcopter ARPInfo) did on Android — {@code /proc/net/arp}, then
 * {@code ip neigh show} — plus {@code arp -a} for Windows/macOS desktops. Android 10+ may deny both for apps
 * targeting API 29+; legacy had the same limit, and the result is then just empty (logged by the caller).
 */
public final class SystemArpTable implements ArpTable {
    private static final Pattern IPV4 = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3})");
    private static final Pattern MAC = Pattern.compile("([0-9A-Fa-f]{1,2}(?:[:-][0-9A-Fa-f]{1,2}){5})");

    @Override
    public Map<String, String> ipToMac() {
        Map<String, String> out = new LinkedHashMap<>();
        File proc = new File("/proc/net/arp");
        if (proc.canRead()) {
            try (InputStream in = new FileInputStream(proc)) {
                out.putAll(parse(read(in)));
            } catch (Exception ignored) {
                // denied on newer Android — fall through to the commands
            }
        }
        if (!out.isEmpty()) return out;
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String[][] commands = windows ? new String[][]{{"arp", "-a"}} : new String[][]{{"ip", "neigh", "show"}, {"arp", "-a"}};
        for (String[] cmd : commands) {
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String text = read(p.getInputStream());
                p.waitFor();
                out.putAll(parse(text));
                if (!out.isEmpty()) return out;
            } catch (Exception ignored) {
                // command missing / denied
            }
        }
        return out;
    }

    /** Any line with an IPv4 address and a MAC (all three formats put both on one line). */
    public static Map<String, String> parse(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\\r?\\n")) {
            Matcher ip = IPV4.matcher(line);
            Matcher mac = MAC.matcher(line);
            if (!ip.find() || !mac.find()) continue;
            String m = Macs.normalize(mac.group(1));
            if (m != null && !out.containsKey(ip.group(1))) out.put(ip.group(1), m);
        }
        return out;
    }

    private static String read(InputStream in) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }
}
