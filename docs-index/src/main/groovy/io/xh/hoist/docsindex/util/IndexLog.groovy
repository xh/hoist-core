package io.xh.hoist.docsindex.util

/**
 * Simple stderr-only logging for the index builder. Set {@link #quiet} to suppress info messages.
 */
class IndexLog {

    static volatile boolean quiet = false

    static void setQuiet(boolean value) {
        quiet = value
    }

    static void info(String msg) {
        if (quiet) return
        System.err.println("[hoist-core-docs-index] $msg")
    }

    static void warn(String msg) {
        System.err.println("[hoist-core-docs-index] WARN: $msg")
    }

    static void error(String msg) {
        System.err.println("[hoist-core-docs-index] ERROR: $msg")
    }
}
