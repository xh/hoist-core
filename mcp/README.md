# MCP Server and CLI Tools

| Section | Description |
|---------|-------------|
| [Overview](#overview) | Purpose, audience, and design rationale |
| [Architecture](#architecture) | Directory structure, data flow, and design decisions |
| [CLI Tools](#cli-tools) | `hoist-core-docs` and `hoist-core-symbols` shell commands |
| [App-Side Distribution](#app-side-distribution) | Gradle snippet that installs the launchers in a consuming app |
| [MCP Server Setup](#mcp-server-setup) | Prerequisites, startup methods, debug logging |
| [Tools Reference](#tools-reference) | Documentation and Groovy/Java tool APIs |
| [Maintaining the Developer Tools](#maintaining-the-developer-tools) | Registry sync, maintenance checklist |
| [Common Pitfalls](#common-pitfalls) | Stdout corruption, path traversal, registry sync |

## Overview

The hoist-core developer tools give AI coding assistants structured access to hoist-core's
documentation and Groovy/Java symbol information. Two interfaces share the same underlying
registries and produce identical output:

- **MCP Server** -- a stdio-based server for MCP-compatible clients (e.g. Claude Code)
- **CLI Tools** -- `hoist-core-docs` and `hoist-core-symbols` commands for shell-capable agents
  and developers, intended for environments that block MCP traffic

Both interfaces share a fat JAR (`io.xh:hoist-core-mcp:<version>:all`) that ships content
bundled inside it, so the tools work fully offline once the JAR is resolved through Maven Central
or an internal Artifactory mirror.

**What they provide:**

- **Documentation tools** -- search, list, and read all hoist-core feature docs, concept docs, and upgrade notes
- **Groovy/Java tools** -- search symbols, inspect classes, and list class members via AST parsing

**Audience:** AI assistants working with hoist-core codebases, and developers configuring those
assistants. These tools are not used at runtime by applications.

**Why embed them:** Unlike external documentation services, these tools read directly from
hoist-core source bundled into the JAR -- no indexing service, no API key, no sync lag, no runtime
network access. Documentation, type information, and source code are always consistent with the
hoist-core version the project actually depends on.

## Architecture

### Directory Structure

```
docs/
└── doc-registry.json                # Shared documentation inventory (entries, categories, metadata)

mcp/
├── bootstrap.sh                     # Bootstrap script -- local, GitHub, Maven Central, snapshot modes (MCP server)
├── build.gradle                     # Gradle build with Shadow JAR plugin; bundles docs + sources into fat JAR
├── src/main/groovy/io/xh/hoist/mcp/
│   ├── HoistCoreMcpServer.groovy    # Entry point -- dispatches MCP server (default) or CLI mode (`cli` first arg)
│   ├── ContentSource.groovy         # Interface: readFile, fileExists, findFiles
│   ├── LocalContentSource.groovy    # ContentSource backed by local filesystem checkout
│   ├── GitHubContentSource.groovy   # ContentSource backed by downloaded GitHub tarball
│   ├── BundledContentSource.groovy  # ContentSource backed by JAR-bundled content (default for CLI mode)
│   ├── cli/
│   │   ├── HoistCoreCli.groovy      # Picocli root -- routes to docs/symbols subtrees
│   │   ├── CliContext.groovy        # Lazily builds ContentSource + registries for CLI commands
│   │   ├── DocsCli.groovy           # `hoist-core-docs` subcommands
│   │   └── SymbolsCli.groovy        # `hoist-core-symbols` subcommands
│   ├── data/
│   │   ├── DocRegistry.groovy       # Loads doc-registry.json, provides metadata lookup and search
│   │   └── GroovyRegistry.groovy    # AST-based Groovy symbol index with background initialization
│   ├── formatters/
│   │   ├── DocFormatter.groovy      # Shared text + JSON shapes for doc output
│   │   └── GroovyFormatter.groovy   # Shared text + JSON shapes for symbol output
│   ├── tools/
│   │   ├── DocTools.groovy          # MCP doc tools (search-docs, list-docs, read-doc, ping)
│   │   └── GroovyTools.groovy       # MCP symbol tools (search-symbols, get-symbol, get-members)
│   ├── resources/
│   │   └── DocResources.groovy      # MCP resource bindings for doc URIs
│   └── util/
│       └── McpLog.groovy            # Stderr-only logging (protects stdio JSON-RPC); quiet mode for CLI
```

### Data Flow

```
MCP Client (e.g. Claude Code)            Shell / AI Agent
    │                                          │
    │  JSON-RPC over stdio                     │  bash commands
    ▼                                          ▼
HoistCoreMcpServer.main(args)
    │                                          │
    │ args[0] == 'cli' ?  ─────────────────────┘ (yes -> dispatch)
    │
    ├── tools/DocTools     ──┐                 ├── cli/DocsCli
    └── tools/GroovyTools  ──┤                 └── cli/SymbolsCli
                             ▼                              │
                    formatters/DocFormatter, GroovyFormatter
                                   │
                                   ▼
                    data/DocRegistry, data/GroovyRegistry
                                   │
                                   ▼
                    ContentSource (Bundled / Local / GitHub)
```

### Design Decisions

**ContentSource abstraction.** Three content backends share a common interface:
`BundledContentSource` reads from JAR resources packed at build time (default for CLI mode and
the recommended distribution path for app developers); `LocalContentSource` reads from a local
checkout (used for framework development); `GitHubContentSource` downloads a tarball at runtime
(used by the existing version-mode bootstrap of the MCP server). The `--source` and `--root`
flags select the backend.

**Bundled content over runtime downloads.** The fat JAR bundles `docs/`, `grails-app/{controllers,domain,services,init}`,
and `src/main/groovy/` under the resource prefix `hoist-core-content/` at build time. This keeps
the JAR fully self-contained: an app developer pulling `io.xh:hoist-core-mcp:<version>:all`
from Maven Central or an internal Artifactory mirror gets everything the tools need without
further network access. Content is version-locked to the JAR -- the docs and source you query
match the hoist-core version your project actually depends on.

**Single fat JAR, dual entry points.** `HoistCoreMcpServer.main()` dispatches based on the first
argument: `cli` routes to the picocli command tree in `cli/`, anything else (or no args) starts
the stdio MCP server. This lets one published artifact back both the MCP server and the CLI
without classpath fragmentation. Wrapper scripts produced by the app-side install task (see
[App-Side Distribution](#app-side-distribution)) hide this dispatch from end users.

**Shared formatters between MCP tools and CLI commands.** `formatters/DocFormatter` and
`formatters/GroovyFormatter` are pure functions producing both human-readable text and parallel
JSON shapes. Both `tools/*` (MCP) and `cli/*` (CLI) call into them, so output is identical
across the two surfaces and `--json` payloads from the CLI match the structure that an MCP
client would receive in `outputSchema`-style responses.

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
dependencies (MCP SDK, Groovy, Jackson, Commons Compress, Picocli) plus the bundled hoist-core
content into a single artifact. This avoids classpath management for consumers --
`java -jar hoist-core-mcp-all.jar` is all that's needed.

## CLI Tools

The CLI tools provide the same documentation and Groovy/Java capabilities as the MCP server, but
via shell commands. They are the recommended interface for environments that block MCP traffic
and for AI agents without MCP support.

### `hoist-core-docs` -- Documentation Search and Reading

```bash
# Search documentation by keyword
hoist-core-docs search "BaseService lifecycle"
hoist-core-docs search "authentication" --category concept
hoist-core-docs search "Cache" -l 5

# List all available documents
hoist-core-docs list
hoist-core-docs list --category package

# Read a specific document by id
hoist-core-docs read docs/base-classes.md
hoist-core-docs read docs/coding-conventions.md

# Shortcuts for common documents
hoist-core-docs conventions     # docs/coding-conventions.md
hoist-core-docs index           # docs/README.md

# Sanity check
hoist-core-docs ping
```

Run `hoist-core-docs --help` (and `hoist-core-docs <subcommand> --help`) for full usage.

### `hoist-core-symbols` -- Groovy/Java Symbol Exploration

```bash
# Search symbols by name (also searches indexed members of key framework classes)
hoist-core-symbols search BaseService
hoist-core-symbols search Cache --kind class
hoist-core-symbols search createTimer

# Get detailed type information for a symbol
hoist-core-symbols symbol BaseService
hoist-core-symbols symbol Cache --file src/main/groovy/io/xh/hoist/cache/Cache.groovy

# List all members of a class or interface
hoist-core-symbols members BaseService
hoist-core-symbols members Cache
```

Run `hoist-core-symbols --help` for full usage.

**Note:** The first symbols invocation in a process builds the Groovy AST index (~2-3s cold
start). Each CLI invocation pays this cost; the MCP server amortizes it by warming the index in
the background after startup.

### Machine-readable JSON output

Every subcommand accepts `--json` to emit structured output instead of formatted text:

```bash
hoist-core-docs list --json
hoist-core-symbols members BaseService --json
```

JSON payloads include a `schemaVersion: 1` field and parallel field shapes across the CLI and
MCP surfaces, so a skill that consumes one source can adapt to the other without rework.

### Direct invocation (no wrapper scripts)

Either of the following works against the same fat JAR:

```bash
java -jar hoist-core-mcp-<version>-all.jar cli docs search "BaseService"
java -jar hoist-core-mcp-<version>-all.jar cli symbols members Cache
```

Pass `--source local --root <repo>` for live working-tree access (framework dev), or
`--source github:<ref>` to read from a downloaded tarball. Default is `--source bundled`
(JAR-embedded content).

## App-Side Distribution

Application projects depending on hoist-core consume the same fat JAR through their existing
Gradle dependency resolution -- no separate downloads, no shell scripts that fetch from
GitHub. Add this snippet to the app's `build.gradle`:

```groovy
configurations {
    hoistCoreCli
}

dependencies {
    hoistCoreCli "io.xh:hoist-core-mcp:${hoistCoreVersion}:all@jar"
}

tasks.register('installHoistCoreTools', Sync) {
    description = 'Install version-locked launchers for the hoist-core MCP server and CLI tools.'
    group = 'hoist'
    from configurations.hoistCoreCli
    into "$buildDir/hoist-core-tools/lib"
    doLast {
        def jar = fileTree("$buildDir/hoist-core-tools/lib").singleFile
        def binDir = file('bin')
        binDir.mkdirs()
        ['mcp', 'docs', 'symbols'].each { topic ->
            def cliPrefix = topic == 'mcp' ? '' : "cli ${topic}"
            new File(binDir, "hoist-core-${topic}").with {
                text = "#!/usr/bin/env bash\nexec java -jar \"${jar.absolutePath}\" ${cliPrefix} \"\$@\"\n"
                setExecutable(true)
            }
            new File(binDir, "hoist-core-${topic}.bat").text =
                "@echo off\r\njava -jar \"${jar.absolutePath}\" ${cliPrefix} %*\r\n"
        }
    }
}
```

Run once after adding the snippet, and again after a version bump:

```bash
./gradlew installHoistCoreTools
```

This produces project-local launchers under `<project>/bin/`:

```
<project>/
├── bin/
│   ├── hoist-core-mcp        # invoked by .mcp.json (replaces start-hoist-core-mcp.sh)
│   ├── hoist-core-docs       # CLI: docs search/list/read/conventions/index/ping
│   ├── hoist-core-symbols    # CLI: symbols search/symbol/members
│   └── *.bat                 # Windows equivalents
└── build/hoist-core-tools/lib/
    └── hoist-core-mcp-<version>-all.jar
```

Then update `.mcp.json` to point at the local launcher:

```json
{
  "mcpServers": {
    "hoist-core": {
      "command": "./bin/hoist-core-mcp"
    }
  }
}
```

The launchers can be `.gitignore`d (regenerated on demand) or committed -- the snippet writes
them deterministically either way. The JAR itself comes through Gradle / the project's resolved
Maven repositories, so enterprise Artifactory mirrors with their scanning rules see it on the
same channel as `io.xh:hoist-core` itself.

**Future plugin path.** This snippet is intentionally plugin-shaped: a future
`io.xh.hoist-core-cli` Gradle plugin will register the configuration and task on `apply plugin`,
removing the boilerplate. Until then, the snippet lives next to the other hoist-core build
config the project already maintains.

## MCP Server Setup

### Prerequisites

- Java 17+
- For app-side use: the project's standard Gradle dependency resolution (Maven Central or an
  internal Artifactory mirror configured in `repositories {}`)
- For framework-dev local mode: a hoist-core repository checkout

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

#### `hoist-core-read-doc`

Read the full content of a hoist-core documentation file by id. Use `hoist-core-search-docs` or
`hoist-core-list-docs` first to discover ids.

| Parameter | Type   | Required | Description |
|-----------|--------|----------|-------------|
| `id`      | string | Yes      | Document id (e.g. `"docs/base-classes.md"`, `"docs/coding-conventions.md"`) |

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

## Maintaining the Developer Tools

The developer tools contain several hardcoded data points that must be kept in sync with the
hoist-core codebase. This section catalogs each maintenance point, its location, and when
updates are needed.

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

### Bundled JAR Content

**File:** `mcp/build.gradle` (`shadowJar { from(rootProject.projectDir) { include ... } }`)

The fat JAR bundles `docs/`, the `grails-app/{controllers,domain,services,init}` directories,
and `src/main/groovy/` under the `hoist-core-content/` resource prefix. The `include` patterns
must match the `SOURCE_DIRS` constant in `data/GroovyRegistry.groovy` -- any new top-level
source directory must be added to both places.

**When to update:**
- A new top-level source directory is added that the symbol index should scan
- A new top-level docs subdirectory is added that should be searchable

### Summary: Maintenance Checklist

| Change | Files to Update |
|--------|----------------|
| Add/rename/remove a documentation file | `docs/doc-registry.json`, `docs/README.md` |
| Add upgrade notes for a new major version | `docs/doc-registry.json`, `docs/README.md` |
| Add/rename/remove a source directory | `GroovyRegistry.groovy` (`SOURCE_DIRS`) AND `mcp/build.gradle` (shadowJar `include`) |
| Add/rename/remove a member-indexed class | `GroovyRegistry.groovy` (`MEMBER_INDEXED_CLASSES`) |
| Add a new MCP tool or CLI subcommand | `tools/*.groovy` and/or `cli/*.groovy`; update `formatters/*.groovy` if needed |

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

`LocalContentSource`, `GitHubContentSource`, and `BundledContentSource` all reject paths
containing `..` segments and validate that resolved paths stay within their content root.
Always use the `ContentSource` interface when resolving file paths from external input.

### Registry Sync

The doc registry is defined in `docs/doc-registry.json`, not filesystem-scanned. When documentation
files are added or removed, the registry must be updated manually. If a file referenced by a
registry entry is missing on disk, the entry is logged as a warning and skipped at startup -- it
does not cause a crash.

See [Maintaining the Developer Tools](#maintaining-the-developer-tools) for the full maintenance
checklist.

### CLI vs MCP Output Drift

`tools/*.groovy` and `cli/*.groovy` both call into `formatters/*.groovy` so their output stays in
lockstep. Avoid inlining string formatting in either consumer -- if a tool needs a new output
shape, add it to the formatter and have both surfaces use it. The `--json` payload from a CLI
command and the structured output of the corresponding MCP tool should always be derivable from
the same formatter `*AsMap()` method.
