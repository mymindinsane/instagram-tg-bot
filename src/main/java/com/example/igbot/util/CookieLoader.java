package com.example.igbot.util;

import java.net.HttpCookie;
import java.util.*;

public class CookieLoader {
    public static Set<AppCookie> parse(byte[] bytes) {
        String s = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replace("\r", "\n");
        List<String> lines = Arrays.asList(s.split("\n"));
        if (!lines.isEmpty() && lines.get(0).startsWith("# Netscape HTTP Cookie File")) {
            return parseNetscape(lines);
        }
        return parseNameValue(lines);
    }

    private static Set<AppCookie> parseNetscape(List<String> lines) {
        Set<AppCookie> out = new LinkedHashSet<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] p = t.split("\t");
            if (p.length < 7) continue;
            String domain = p[0];
            String path = p[2];
            Long expiry = null;
            try { expiry = Long.parseLong(p[4]); } catch (Exception ignored) {}
            String name = p[5];
            String value = p[6];
            out.add(new AppCookie(name, value,
                    domain == null || domain.isBlank() ? ".instagram.com" : domain,
                    path == null || path.isBlank() ? "/" : path,
                    expiry,
                    false,
                    false));
        }
        return out;
    }

    private static Set<AppCookie> parseNameValue(List<String> lines) {
        Set<AppCookie> out = new LinkedHashSet<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (!t.contains("=")) continue;
            List<HttpCookie> parsed = HttpCookie.parse(t);
            for (HttpCookie hc : parsed) {
                out.add(new AppCookie(
                        hc.getName(), hc.getValue(),
                        ".instagram.com",
                        "/",
                        null,
                        false,
                        false
                ));
            }
        }
        return out;
    }
}
