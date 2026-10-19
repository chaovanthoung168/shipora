package com.shipora.application.domain;

import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.shipora.application.domain.ApplicationException.Kind.INVALID;

public final class ApplicationRules {
    private ApplicationRules() {}
    public static String text(String value, int max, String field) {
        if (value == null || value.isBlank() || value.strip().length() > max || value.chars().anyMatch(Character::isISOControl))
            throw invalid("Invalid " + field + ".");
        return value.strip();
    }
    public static String slug(String value) {
        if (value == null || !value.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || value.length() > 100) throw invalid("Use a lowercase slug with letters, numbers and single hyphens.");
        return value;
    }
    public static String branch(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_][A-Za-z0-9_./-]{0,254}") || value.contains("..") || value.contains("//")
                || value.endsWith("/") || value.endsWith(".") || Arrays.stream(value.split("/")).anyMatch(s -> s.startsWith(".") || s.endsWith(".lock")))
            throw invalid("Invalid branch name.");
        return value;
    }
    public static String path(String value, boolean context) {
        if (context && ".".equals(value)) return value;
        if (value == null || value.length() > 255 || !value.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*")
                || Arrays.stream(value.split("/")).anyMatch(s -> s.equals(".") || s.equals("..")))
            throw invalid("Build paths must be relative repository paths without traversal.");
        return value;
    }
    public static String health(String value) {
        if (value == null || value.length() > 255 || !value.matches("/[A-Za-z0-9_./-]*") || value.startsWith("//")
                || Arrays.stream(value.split("/")).anyMatch(s -> s.equals("..") || s.equals(".")))
            throw invalid("Health path must be an absolute HTTP path without a host, query or fragment.");
        return value;
    }
    public static int port(Integer value) {
        if (value == null || value < 1 || value > 65535) throw invalid("Container port must be between 1 and 65535.");
        return value;
    }
    public static String key(String key) {
        if (key == null || !key.matches("[A-Za-z_][A-Za-z0-9_]{0,254}")) throw invalid("Invalid environment or secret key.");
        return key;
    }
    public static String value(String value) {
        if (value == null || value.length() > 16384 || value.indexOf(0) >= 0) throw invalid("Values must contain at most 16384 characters and no NUL bytes.");
        return value;
    }
    public static Map<String,String> environment(Map<String,String> variables) {
        if (variables == null || variables.size() > 100) throw invalid("Supply at most 100 environment variables.");
        long bytes=0;
        for (var entry:variables.entrySet()) {
            key(entry.getKey()); value(entry.getValue());
            bytes+=entry.getKey().getBytes(StandardCharsets.UTF_8).length+entry.getValue().getBytes(StandardCharsets.UTF_8).length;
        }
        if (bytes > 262144) throw invalid("Environment configuration exceeds 256 KiB.");
        return Map.copyOf(variables);
    }
    public static void version(long version) { if (version < 0) throw invalid("Version must be non-negative."); }
    public static UUID id(UUID id) { if (id == null) throw invalid("Project, repository and server IDs are required."); return id; }
    public static int offset(int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (long)page*size > Integer.MAX_VALUE) throw invalid("Use page >= 0 and size between 1 and 100.");
        return page*size;
    }
    private static ApplicationException invalid(String message) { return new ApplicationException(INVALID,message); }
}
