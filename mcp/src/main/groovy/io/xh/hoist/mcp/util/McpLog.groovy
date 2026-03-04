package io.xh.hoist.mcp.util

/**
 * Simple stderr-only logging. Stdout is reserved for JSON-RPC protocol messages.
 */
class McpLog {
    static void info(String msg)  { System.err.println("[hoist-core-mcp] $msg") }
    static void warn(String msg)  { System.err.println("[hoist-core-mcp] WARN: $msg") }
    static void error(String msg) { System.err.println("[hoist-core-mcp] ERROR: $msg") }
}
