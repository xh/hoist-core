package io.xh.hoist.mcp.util

/**
 * Simple stderr-only logging. Stdout is reserved for JSON-RPC protocol messages
 * (MCP server) and CLI command output (CLI mode).
 *
 * The CLI sets {@link #quiet} to suppress info messages by default, so users
 * see only command output and warnings/errors. Set {@code HOIST_MCP_DEBUG=1}
 * (or call {@code setQuiet(false)}) to restore info-level output.
 */
class McpLog {

    static volatile boolean quiet = false

    static void setQuiet(boolean value) {
        quiet = value
    }

    static void info(String msg) {
        if (quiet) return
        System.err.println("[hoist-core-mcp] $msg")
    }

    static void warn(String msg) {
        System.err.println("[hoist-core-mcp] WARN: $msg")
    }

    static void error(String msg) {
        System.err.println("[hoist-core-mcp] ERROR: $msg")
    }
}
