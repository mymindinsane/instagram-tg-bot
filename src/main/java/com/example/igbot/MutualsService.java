package com.example.igbot;

import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

public class MutualsService {
    public static class Result {
        public final List<String> mutuals;
        public final List<String> notFollowingBack;   // you follow them, they don't follow you
        public final List<String> notFollowedByYou;   // they follow you, you don't follow them

        public Result(List<String> mutuals, List<String> notFollowingBack, List<String> notFollowedByYou) {
            this.mutuals = mutuals;
            this.notFollowingBack = notFollowingBack;
            this.notFollowedByYou = notFollowedByYou;
        }
    }

    public static Result compute(Set<String> followers, Set<String> following) {
        // Normalize usernames robustly before comparisons
        Set<String> followersCI = followers.stream()
                .map(MutualsService::normalize)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
        Set<String> followingCI = following.stream()
                .map(MutualsService::normalize)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));

        List<String> mutuals = followersCI.stream().filter(followingCI::contains).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
        List<String> notFollowingBack = followingCI.stream().filter(u -> !followersCI.contains(u)).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
        List<String> notFollowedByYou = followersCI.stream().filter(u -> !followingCI.contains(u)).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
        return new Result(mutuals, notFollowingBack, notFollowedByYou);
    }

    private static String normalize(String s) {
        if (s == null) return "";
        String t = s;
        try { t = Normalizer.normalize(t, Normalizer.Form.NFKC); } catch (Exception ignored) {}
        // remove zero-width and spaces
        t = t.replaceAll("[\\u200B-\\u200D\\uFEFF]", "");
        t = t.replace(" ", "");
        if (t.startsWith("@")) t = t.substring(1);
        t = t.toLowerCase(Locale.ROOT);
        // keep only allowed chars
        t = t.replaceAll("[^a-z0-9._]", "");
        return t;
    }
}
