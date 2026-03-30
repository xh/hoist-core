#!/usr/bin/env bash
# Bootstrap script for the hoist-core MCP server.
#
# Usage:
#   bootstrap.sh                          # local mode (wrapper repo)
#   bootstrap.sh --source github:v37.0.0  # GitHub archive mode
#   bootstrap.sh --version 37.0.0         # download JAR from Maven Central + GitHub archive
#   bootstrap.sh --version 37.0-SNAPSHOT  # download latest SNAPSHOT from Sonatype

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
    mkdir -p "$CACHE_DIR/jars"

    if [[ "$VERSION" == *-SNAPSHOT ]]; then
        # Sonatype snapshot mode: resolve timestamped JAR from maven-metadata.xml
        SNAP_REPO="https://central.sonatype.com/repository/maven-snapshots"
        META_URL="$SNAP_REPO/io/xh/hoist-core-mcp/$VERSION/maven-metadata.xml"
        META=$(curl -sf --ssl-no-revoke "$META_URL") || {
            echo "[hoist-core-mcp] ERROR: Failed to fetch snapshot metadata from $META_URL" >&2; exit 1
        }
        TIMESTAMP=$(echo "$META" | sed -n 's/.*<timestamp>\(.*\)<\/timestamp>.*/\1/p' | head -1)
        BUILD_NUM=$(echo "$META" | sed -n 's/.*<buildNumber>\(.*\)<\/buildNumber>.*/\1/p' | head -1)
        BASE_VER="${VERSION%-SNAPSHOT}"
        SNAP_VER="$BASE_VER-$TIMESTAMP-$BUILD_NUM"
        JAR="$CACHE_DIR/jars/hoist-core-mcp-$SNAP_VER-all.jar"
        if [ ! -f "$JAR" ]; then
            echo "[hoist-core-mcp] Downloading MCP server $VERSION (build $BUILD_NUM)..." >&2
            JAR_URL="$SNAP_REPO/io/xh/hoist-core-mcp/$VERSION/hoist-core-mcp-$SNAP_VER-all.jar"
            curl -sfL --ssl-no-revoke "$JAR_URL" -o "$JAR" >&2 || {
                echo "[hoist-core-mcp] ERROR: Failed to download from $JAR_URL" >&2; exit 1
            }
        fi
        # Default to develop branch for snapshot content
        [ -z "$SOURCE_ARGS" ] && SOURCE_ARGS="--source github:develop"
    else
        # Maven Central release mode: download JAR if not cached
        JAR="$CACHE_DIR/jars/hoist-core-mcp-$VERSION-all.jar"
        if [ ! -f "$JAR" ]; then
            echo "[hoist-core-mcp] Downloading MCP server v$VERSION from Maven Central..." >&2
            MVN_URL="https://repo1.maven.org/maven2/io/xh/hoist-core-mcp/$VERSION/hoist-core-mcp-$VERSION-all.jar"
            curl -sfL --ssl-no-revoke "$MVN_URL" -o "$JAR" >&2 || {
                echo "[hoist-core-mcp] ERROR: Failed to download from $MVN_URL" >&2; exit 1
            }
        fi
        [ -z "$SOURCE_ARGS" ] && SOURCE_ARGS="--source github:v$VERSION"
    fi

    exec java -jar "$JAR" $SOURCE_ARGS
else
    # Local mode: always build to ensure JAR reflects current source.
    # Gradle's up-to-date checking makes this a no-op when nothing has changed.
    echo "[hoist-core-mcp] Building MCP server..." >&2
    (cd "$REPO_ROOT" && ./gradlew :mcp:shadowJar --console=plain --quiet) >&2
    JAR=$(ls "$SCRIPT_DIR"/build/libs/mcp-*-all.jar 2>/dev/null | head -1)
    if [ -z "$JAR" ]; then
        echo "[hoist-core-mcp] ERROR: Could not find or build MCP JAR" >&2; exit 1
    fi
    exec java -jar "$JAR" $SOURCE_ARGS
fi
