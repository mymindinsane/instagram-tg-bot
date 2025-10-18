package com.example.igbot.selenium;

import org.openqa.selenium.Cookie;

import java.net.HttpCookie;
import java.time.Instant;
import java.util.*;

public class CookieLoader {
    public static Set<Cookie> parse(byte[] bytes) {
        String s = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).replace("\r", "\n");
        List<String> lines = Arrays.asList(s.split("\n"));
        if (!lines.isEmpty() && lines.get(0).startsWith("# Netscape HTTP Cookie File")) {
            return parseNetscape(lines);
        }
        return parseNameValue(lines);
    }

    private static Set<Cookie> parseNetscape(List<String> lines) {
        Set<Cookie> out = new LinkedHashSet<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            String[] p = t.split("\t");
            if (p.length < 7) continue;
            String domain = p[0];
            String path = p[2];
            long expiry = 0L;
            try { expiry = Long.parseLong(p[4]); } catch (Exception ignored) {}
            String name = p[5];
            String value = p[6];
            Cookie.Builder b = new Cookie.Builder(name, value).domain(domain).path(path);
            if (expiry > 0) b.expiresOn(Date.from(Instant.ofEpochSecond(expiry)));
            out.add(b.build());
        }
        return out;
    }

    private static Set<Cookie> parseNameValue(List<String> lines) {
        Set<Cookie> out = new LinkedHashSet<>();
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (!t.contains("=")) continue;
            List<HttpCookie> parsed = HttpCookie.parse(t);
            for (HttpCookie hc : parsed) {
                Cookie c = new Cookie.Builder(hc.getName(), hc.getValue())
                        .domain(".instagram.com")
                        .path("/")
                        .build();
                out.add(c);
            }
        }
        return out;
    }
}
