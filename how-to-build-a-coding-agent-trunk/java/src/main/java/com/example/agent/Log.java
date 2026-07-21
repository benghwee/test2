package com.example.agent;

/**
 * Tiny logging helper that mirrors the Go {@code --verbose} behaviour:
 * verbose logs go to stderr, normal mode is silent for logs.
 */
public final class Log {
    private static boolean verbose = false;

    private Log() {}

    public static void setVerbose(boolean v) {
        verbose = v;
    }

    public static boolean isVerbose() {
        return verbose;
    }

    public static void v(String fmt, Object... args) {
        if (verbose) {
            System.err.println("[verbose] " + String.format(fmt, args));
        }
    }
}
