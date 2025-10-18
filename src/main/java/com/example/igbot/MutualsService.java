package com.example.igbot;

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
        // Normalize to case-insensitive comparison but stable, lists sorted
        Set<String> followersCI = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        followersCI.addAll(followers);
        Set<String> followingCI = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        followingCI.addAll(following);

        List<String> mutuals = followersCI.stream().filter(followingCI::contains).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
        List<String> notFollowingBack = followingCI.stream().filter(u -> !followersCI.contains(u)).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
        List<String> notFollowedByYou = followersCI.stream().filter(u -> !followingCI.contains(u)).sorted(String.CASE_INSENSITIVE_ORDER).collect(Collectors.toList());
        return new Result(mutuals, notFollowingBack, notFollowedByYou);
    }
}
