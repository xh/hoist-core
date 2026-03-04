#!/usr/bin/env bash
# Bootstrap script for the hoist-core MCP server.
#
# Usage:
#   bootstrap.sh                          # local mode (wrapper repo)
#   bootstrap.sh --source github:v37.0.0  # GitHub archive mode
#   bootstrap.sh --version 37.0.0         # download JAR from Maven Central + GitHub archive

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CACHE_DIR="$HOME/.cache/hoist-core-mcp"

# Parse args
VERSION="" SOURCE_ARGS=""
while [[ $# -gt 0 ]]; do
    case $1 in
        --version) VERSION="$2"; shift 2 ;;
        --source)  SOURCE_ARGS="--source $2"; shift 2 ;;
        *)         SOURCE_ARGS="$SOURCE_ARGS $1"; shift ;;
    esac
done

if [ -n "$VERSION" ]; then
    # Maven Central mode: download JAR if not cached, use GitHub archive for content
    JAR="$CACHE_DIR/jars/hoist-core-mcp-$VERSION-all.jar"
    if [ ! -f "$JAR" ]; then
        echo "[hoist-core-mcp] Downloading MCP server v$VERSION from Maven Central..." >&2
        mkdir -p "$CACHE_DIR/jars"
        MVN_URL="https://repo1.maven.org/maven2/io/xh/hoist-core-mcp/$VERSION/hoist-core-mcp-$VERSION-all.jar"
        curl -sfL "$MVN_URL" -o "$JAR" >&2 || {
            echo "[hoist-core-mcp] ERROR: Failed to download from $MVN_URL" >&2; exit 1
        }
    fi
    exec java -jar "$JAR" --source "github:v$VERSION" $SOURCE_ARGS
else
    # Local mode: build from source if needed
    JAR=$(ls "$SCRIPT_DIR"/build/libs/mcp-*-all.jar 2>/dev/null | head -1)
    if [ -z "$JAR" ]; then
        echo "[hoist-core-mcp] Building MCP server..." >&2
        (cd "$REPO_ROOT" && ./gradlew :mcp:shadowJar --console=plain --quiet) >&2
        JAR=$(ls "$SCRIPT_DIR"/build/libs/mcp-*-all.jar 2>/dev/null | head -1)
    fi
    if [ -z "$JAR" ]; then
        echo "[hoist-core-mcp] ERROR: Could not find or build MCP JAR" >&2; exit 1
    fi
    exec java -jar "$JAR" $SOURCE_ARGS
fi
