package com.example.igbot.util;

public class AppCookie {
    public final String name;
    public final String value;
    public final String domain;
    public final String path;
    public final Long expiresEpochSeconds; // nullable
    public final boolean httpOnly;
    public final boolean secure;

    public AppCookie(String name, String value, String domain, String path, Long expiresEpochSeconds, boolean httpOnly, boolean secure) {
        this.name = name;
        this.value = value;
        this.domain = domain;
        this.path = path;
        this.expiresEpochSeconds = expiresEpochSeconds;
        this.httpOnly = httpOnly;
        this.secure = secure;
    }
}
