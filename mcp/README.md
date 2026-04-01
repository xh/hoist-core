# MCP Server

## Overview

The hoist-core MCP (Model Context Protocol) server gives AI coding assistants structured access to
hoist-core's documentation and Groovy/Java symbol information. It runs as a local stdio-based server
that any MCP-compatible client (e.g. Claude Code) can connect to.

**What it provides:**

- **Documentation tools** -- search and browse all hoist-core feature docs, concept docs, and upgrade notes
- **Groovy/Java tools** -- search symbols, inspect classes, and list class members via AST parsing

**Audience:** AI assistants working with hoist-core codebases, and developers configuring those
assistants. The server is not used at runtime by applications.

**Why embed it:** Unlike external documentation services, this MCP server reads directly from the
framework source -- no indexing service, no API key, no sync lag. Documentation and source code are
always consistent with the developer's checked-out version of hoist-core.

## Architecture

### Directory Structure

```
docs/
└── doc-registry.json             # Shared documentation inventory (entries, categories, metadata)

mcp/
├── bootstrap.sh                  # Bootstrap script -- local, GitHub, Maven Central, or snapshot modes
├── build.gradle                  # Gradle build with Shadow JAR plugin for fat JAR distribution
├── src/main/groovy/io/xh/hoist/mcp/
│   ├── HoistCoreMcpServer.groovy # Entry point -- parses args, creates ContentSource, starts server
│   ├── ContentSource.groovy      # Interface: readFile, fileExists, findFiles
│   ├── LocalContentSource.groovy # ContentSource backed by local filesystem checkout
│   ├── GitHubContentSource.groovy# ContentSource backed by downloaded GitHub tarball
│   ├── data/
│   │   ├── DocRegistry.groovy    # Loads doc-registry.json, provides metadata lookup and search
│   │   └── GroovyRegistry.groovy # AST-based Groovy symbol index with background initialization
│   ├── tools/
│   │   ├── DocTools.groovy       # Documentation tools (search-docs, list-docs, ping)
│   │   └── GroovyTools.groovy    # Groovy symbol tools (search-symbols, get-symbol, get-members)
│   └── util/
│       └── McpLog.groovy         # Stderr-only logging (protects stdio JSON-RPC)
```

### Data Flow

```
MCP Client (e.g. Claude Code)
    │
    │  JSON-RPC over stdio
    │
    ▼
HoistCoreMcpServer
    │
    ├── tools/DocTools     ──► data/DocRegistry    ──► doc files via ContentSource
    └── tools/GroovyTools  ──► data/GroovyRegistry ──► Groovy source files via ContentSource
```

### Design Decisions

**ContentSource abstraction.** The server supports two content backends: `LocalContentSource` reads
files from a local checkout, while `GitHubContentSource` downloads and caches a tarball from GitHub.
This lets the server run against any hoist-core version -- a developer's local working copy or a
specific published release. The `--source` and `--root` CLI args select the backend.

**JSON-driven doc registry over filesystem scanning.** The doc registry is defined in
`docs/doc-registry.json` and loaded by `data/DocRegistry.groovy` at startup rather than
discovering files on disk. This was chosen because the documentation corpus is bounded and
well-known (~25 files), and each entry needs curated metadata (title, description, category,
search keywords) that cannot be reliably derived from filenames alone. The registry is shared
with other consumers (e.g. documentation viewers) and aligned with the `docs/README.md` index
tables. The tradeoff is manual maintenance -- see
[Maintaining the MCP Server](#maintaining-the-mcp-server).

**Eager Groovy AST initialization.** Parsing hoist-core's Groovy source files with the Groovy
compiler's AST is expensive. After the server starts, `beginInitialization()` kicks off index
building in a background thread so it runs concurrently while the client sets up. If a tool call
arrives before init completes, `ensureInitialized()` blocks until the in-flight work finishes. In
practice, the index is typically ready before the first tool invocation. The index is built using
Groovy's `CompilationUnit` at the `CONVERSION` phase -- enough for class/method/property extraction
without full type resolution.

**Stdio transport with stderr logging discipline.** Stdout corruption from stray print statements
is the most common bug in MCP server implementations. A single log statement corrupts the JSON-RPC
stream, manifesting as mysterious protocol errors. The `McpLog` utility writes exclusively to stderr.

**Shadow JAR distribution.** The server is distributed as a fat JAR via Maven Central, packaging all
dependencies (MCP SDK, Groovy, Jackson, Commons Compress) into a single artifact. This avoids
classpath management for consumers -- `java -jar hoist-core-mcp-all.jar` is all that's needed.

## Setup

### Prerequisites

- Java 17+
- A hoist-core repository checkout (for local mode) or internet access (for GitHub/Maven Central mode)

### Starting the Server

**Method 1: `.mcp.json` (recommended for Claude Code)**

The repository includes a `.mcp.json` file that Claude Code reads automatically. No manual setup is
needed -- Claude Code discovers and starts the server when you open a session in a project that
references hoist-core.

**Method 2: bootstrap.sh (local development)**

```bash
# Local mode -- builds from source if needed, reads from local checkout
mcp/bootstrap.sh

# GitHub archive mode -- downloads content from a specific ref
mcp/bootstrap.sh --source github:v37.0.0

# Maven Central release mode -- downloads published JAR + GitHub archive
mcp/bootstrap.sh --version 37.0.0

# Sonatype snapshot mode -- downloads latest SNAPSHOT build
mcp/bootstrap.sh --version 37.0-SNAPSHOT
```

**Method 3: Direct JAR execution**

```bash
java -jar mcp/build/libs/mcp-*-all.jar                    # local mode
java -jar mcp-all.jar --root /path/to/hoist-core           # explicit root
java -jar mcp-all.jar --source github:develop              # GitHub mode
```

### Verification

After starting, call the `hoist-core-ping` tool to verify connectivity. In Claude Code, MCP tools
appear automatically in the tool list (e.g. `mcp__hoist-core__hoist-core-ping`).

### Debug Logging

The server logs to stderr. To see log output when running under Claude Code, check the MCP server
logs in Claude Code's output panel. Set `HOIST_MCP_DEBUG=1` in the `.mcp.json` env block for
verbose output.

## Tools Reference

### Documentation Tools

#### `hoist-core-search-docs`

Search across all hoist-core documentation by keyword. Returns matching documents with context
snippets showing where terms appear.

| Parameter  | Type   | Required | Description |
|------------|--------|----------|-------------|
| `query`    | string | Yes      | Search keywords (e.g. `"BaseService lifecycle"`, `"authentication OAuth"`) |
| `category` | enum   | No       | Filter: `package`, `concept`, `devops`, `conventions`, `index`, `all` (default) |
| `limit`    | number | No       | Max results, 1-20. Default: 10 |

#### `hoist-core-list-docs`

List all available documentation with descriptions, grouped by category.

| Parameter  | Type | Required | Description |
|------------|------|----------|-------------|
| `category` | enum | No       | Filter by category (same values as search). Default: all |

#### `hoist-core-ping`

Verify the MCP server is running and responsive. Takes no parameters.

### Groovy/Java Tools

#### `hoist-core-search-symbols`

Search for Groovy/Java classes, interfaces, traits, and enums by name. Also searches public members
(properties, methods, fields) of key framework classes, returning results in two sections: matching
symbols and matching members with their owning class.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `query`   | string | Yes      | Symbol or member name (e.g. `"BaseService"`, `"Cache"`, `"createTimer"`) |
| `kind`    | enum   | No       | Filter symbols: `class`, `interface`, `trait`, `enum` |
| `limit`   | number | No       | Max symbol results, 1-50. Default: 20 |

**Member-indexed classes:** BaseService, BaseController, RestController, HoistUser, Cache,
CachedValue, Timer, Filter, FieldFilter, CompoundFilter, JSONClient, ClusterService, ConfigService,
MonitorResult, LogSupport, HttpException, IdentitySupport. Only public members are indexed (members
prefixed with `_` or `$` are excluded).

**Note:** The Groovy symbol index is built asynchronously after server startup. It is typically
ready before the first tool call. Subsequent calls are fast in-memory lookups.

#### `hoist-core-get-symbol`

Get detailed type information for a specific symbol: signature, Groovydoc, inheritance, annotations,
and source location. Use `hoist-core-search-symbols` first to find the exact name.

| Parameter  | Type   | Required | Description |
|------------|--------|----------|-------------|
| `name`     | string | Yes      | Exact symbol name (e.g. `"BaseService"`, `"Cache"`) |
| `filePath` | string | No       | Source file path to disambiguate duplicate names |

#### `hoist-core-get-members`

List all properties and methods of a class or interface with types, annotations, and Groovydoc.

| Parameter  | Type   | Required | Description |
|------------|--------|----------|-------------|
| `name`     | string | Yes      | Class or interface name (e.g. `"BaseService"`, `"Cache"`) |
| `filePath` | string | No       | Source file path to disambiguate duplicate names |

## Maintaining the MCP Server

The MCP server contains several hardcoded data points that must be kept in sync with the hoist-core
codebase. This section catalogs each maintenance point, its location, and when updates are needed.

### Doc Registry Entries

**File:** `docs/doc-registry.json`

The doc registry is the single source of truth for all documentation that the MCP server can search
and serve. Each entry specifies an `id` (which doubles as the relative file path from the repo
root), `title`, `mcpCategory`, `description`, and `keywords` list. The registry also defines
`mcpCategories` (used by the MCP server tools) and `viewerCategories` (used by the Toolbox doc
viewer UI). `DocRegistry.groovy` loads and indexes this JSON at startup.

**When to update:**
- A new feature doc or concept doc is added to hoist-core
- A new major version's upgrade notes are created in `docs/upgrade-notes/`
- A documentation file is renamed or moved
- A documentation file is removed
- The description or key topics for a doc change significantly

**How to update:** Add, modify, or remove the corresponding entry object in `doc-registry.json`.
The `id` field is the file path relative to the repo root. Assign an appropriate `mcpCategory`
and `viewerCategory` to place the entry in the correct groupings.

**Automated support:** The `xh-update-doc-links` Claude Code skill
(`.claude/skills/xh-update-doc-links/`) includes a dedicated step that reconciles the doc registry
against documentation files on disk. Running this skill after editing or adding docs will detect
missing, stale, or moved entries and update the registry accordingly.

### Groovy Symbol Registry

**File:** `mcp/src/main/groovy/io/xh/hoist/mcp/data/GroovyRegistry.groovy`

Two constants control which source directories are scanned and which classes have their members
indexed for search:

**`SOURCE_DIRS`** -- lists all source directories to scan for Groovy files. Update when new
top-level source directories are added to hoist-core.

**`MEMBER_INDEXED_CLASSES`** -- lists classes whose public members are individually indexed for
search. Update when a new key base class is added to the framework and should have its members
searchable, or when an indexed class is renamed or removed.

### Summary: Maintenance Checklist

| Change | Files to Update |
|--------|----------------|
| Add/rename/remove a documentation file | `docs/doc-registry.json`, `docs/README.md` |
| Add upgrade notes for a new major version | `docs/doc-registry.json`, `docs/README.md` |
| Add/rename/remove a source directory | `GroovyRegistry.groovy` (`SOURCE_DIRS`) |
| Add/rename/remove a member-indexed class | `GroovyRegistry.groovy` (`MEMBER_INDEXED_CLASSES`) |

## Common Pitfalls

### Stdout Corruption

All logging **must** go to stderr, never stdout. The MCP protocol uses stdout exclusively for
JSON-RPC messages. Use the `McpLog` class -- never `System.out.println()` or Groovy's `println`.

```groovy
// Do: Use McpLog
McpLog.info('Server started')

// Don't: Use println (corrupts JSON-RPC on stdout)
println 'Server started'
```

### Path Traversal

Both `LocalContentSource` and `GitHubContentSource` validate that resolved paths stay within the
content root. They reject paths containing `..` segments and verify the canonical path starts with
the root directory. Always use the `ContentSource` interface when resolving file paths from external
input.

### Registry Sync

The doc registry is defined in `docs/doc-registry.json`, not filesystem-scanned. When documentation
files are added or removed, the registry must be updated manually. If a file referenced by a
registry entry is missing on disk, the entry is logged as a warning and skipped at startup -- it
does not cause a crash.

See [Maintaining the MCP Server](#maintaining-the-mcp-server) for the full maintenance checklist.
