# Hoist Core v45 Upgrade Notes

> **From:** v44.x → v45.0.0 | **Released:** 2026-11-20 | **Difficulty:** 🟢 LOW

## Overview

Hoist Core v45 moves its MCP docs and symbol tools for AI coding agents to
[Hoist Docs](https://github.com/xh/hoist-docs) (`@xh/hoist-docs`), one MCP server for hoist-core and
hoist-react. hoist-core no longer publishes the `io.xh:hoist-core-mcp` artifact. Instead, each
build publishes a `docs-index` artifact beside its jar, which Hoist Docs fetches for the app's
`hoistCoreVersion`. There are no changes to runtime APIs, and no database schema changes.

The most significant app-level impacts are:

- **Requires hoist-react v91** - hoist-react v91 moves to Hoist Docs at the same time. Its upgrade
  notes cover adding `@xh/hoist-docs` and the new `.mcp.json` entry.
- **`installHoistCoreTools` removed** - Apps must delete this task from `build.gradle`. It fails on
  v45, because the artifact it downloads is no longer published.
- **`hoist-core` MCP server removed** - Apps must remove the `hoist-core` entry from `.mcp.json`
  and the launchers that the task wrote to `bin/`.

v45 also fixes `DefaultRoleService`, so the local development `bootstrapAdminUser` now gets all
roles that inherit from its admin roles, such as `HOIST_IMPERSONATOR`. This needs no app change.

## Prerequisites

Before starting, ensure:

- [ ] **The app is on hoist-core v44.x** - see the [v44 upgrade notes](./v44-upgrade-notes.md).
- [ ] **The app is on `@xh/hoist` v91 or later** - see the
  [hoist-react v91 upgrade notes](https://github.com/xh/hoist-react/blob/develop/docs/upgrade-notes/v91-upgrade-notes.md).
  Hoist Docs is the only source of hoist-core docs for agents from v45 onward. On an earlier
  hoist-react, agents have no hoist-core reference.

## Upgrade Steps

### 1. Bump `hoistCoreVersion`

**File:** `gradle.properties`

Before:
```properties
hoistCoreVersion=44.1.0
```

After:
```properties
hoistCoreVersion=45.0.0
```

Then find the old tooling that the steps below remove. If these commands find nothing, the upgrade
is complete after this step.

```bash
grep -n "installHoistCoreTools\|hoistCoreCli\|hoist-core-mcp" build.gradle
grep -rn "hoist-core-mcp\|hoist-core-docs\|hoist-core-symbols\|mcp__hoist-core" \
    .mcp.json CLAUDE.md AGENTS.md .claude 2>/dev/null
ls bin/hoist-core-* 2>/dev/null
```

### 2. Remove `installHoistCoreTools` from `build.gradle`

The task resolves `io.xh:hoist-core-mcp` at the app's `hoistCoreVersion`. hoist-core v45 does not
publish that artifact, so the task fails.

**File:** `build.gradle`

Delete the whole block, from the `hoistCoreCli` configuration through the `installHoistCoreTools`
task. In recent versions it sits between `hoist-ai-snippet` markers:

```groovy
//------------------------------------------------------------------
// hoist-core MCP server + CLI tools — version-locked launchers
//------------------------------------------------------------------
// hoist-ai-snippet: hoist-core-install/v2 -- DO NOT REMOVE (drift marker, see hoist-core/mcp/README.md)
configurations {
    hoistCoreCli
}

configurations.hoistCoreCli.withDependencies { deps ->
    // ...
    deps.add(project.dependencies.create("io.xh:hoist-core-mcp:${hc.moduleVersion.version}:all@jar"))
}

tasks.register('installHoistCoreTools', Sync) {
    // ...
}
// end hoist-ai-snippet
```

Older copies of the snippet may have no markers, or may pin the `hoist-core-mcp` version
directly. Delete them the same way. Also remove any other reference to the task, such as a
`dependsOn` or a call in a setup script.

If the app locks its dependencies, refresh the lockfiles afterwards, so they drop the
`hoistCoreCli` configuration:

```bash
./gradlew resolveAndLockAll --write-locks
```

### 3. Replace the `hoist-core` MCP server entry

**File:** `.mcp.json`

Before:
```json
{
  "mcpServers": {
    "hoist-core": {
      "command": "./bin/hoist-core-mcp"
    }
  }
}
```

After:
```json
{
  "mcpServers": {
    "hoist-docs": {
      "type": "stdio",
      "command": "node",
      "args": ["client-app/node_modules/@xh/hoist-docs/bin/hoist-docs.mjs"]
    }
  }
}
```

The `hoist-docs` entry replaces both the `hoist-core` and `hoist-react` entries. Step 9 of the
[hoist-react v91 upgrade notes](https://github.com/xh/hoist-react/blob/develop/docs/upgrade-notes/v91-upgrade-notes.md#9-switch-agents-to-hoist-docs)
adds it, along with the `@xh/hoist-docs` dev dependency. If you already did that step, just
delete the `hoist-core` entry.

Hoist Docs fetches hoist-core's docs index for the app's `hoistCoreVersion` from Maven - Maven
Central for releases, Sonatype's snapshot repository for snapshots. Behind an organization's Maven
mirror, set `HOIST_DOCS_MAVEN_REPO` and `HOIST_DOCS_MAVEN_SNAPSHOT_REPO` as described in the
hoist-react notes.

### 4. Update agent permissions and instructions

**File:** `.claude/settings.json` (and any `settings.local.json`)

Before:
```json
{
  "permissions": {
    "allow": ["mcp__hoist-core__*"]
  },
  "enabledMcpjsonServers": ["hoist-core"]
}
```

After:
```json
{
  "permissions": {
    "allow": ["mcp__hoist-docs__*"]
  },
  "enabledMcpjsonServers": ["hoist-docs"]
}
```

In `CLAUDE.md`, `AGENTS.md`, or other agent instructions, replace references to the
`hoist-core` server, `./bin/hoist-core-*` launchers, and `installHoistCoreTools`. The tools keep
their behavior, but their names drop the `hoist-core-` prefix. Pass `library: "core"` to scope one
to hoist-core:

| Before | After |
|--------|-------|
| `hoist-core-search-docs` | `hoist-search-docs` |
| `hoist-core-list-docs` | `hoist-list-docs` |
| `hoist-core-read-doc` | `hoist-read-doc` |
| `hoist-core-search-symbols` | `hoist-search-symbols` |
| `hoist-core-get-symbol` | `hoist-get-symbol` |
| `hoist-core-get-members` | `hoist-get-members` |
| `hoist-core-ping` | `hoist-ping` |

### 5. Delete the generated launchers

`installHoistCoreTools` wrote launchers into `bin/`, with a `bin/.gitignore` that excludes them
from Git. Delete these files. Leave anything else in `bin/`, such as IDE build output, alone.

```bash
rm -f bin/hoist-core-mcp bin/hoist-core-mcp.bat \
      bin/hoist-core-docs bin/hoist-core-docs.bat \
      bin/hoist-core-symbols bin/hoist-core-symbols.bat \
      bin/.gitignore
```

Check `bin/.gitignore` before you delete it, if your app has added its own entries to it. The task
overwrites the file with only the launcher names, so it usually holds nothing else. Each developer
should run this cleanup in their own checkout, because the launchers are not committed.

## Verification Checklist

After you complete all steps:

- [ ] `./gradlew compileGroovy` succeeds
- [ ] The application starts without errors
- [ ] The Admin Console loads and works
- [ ] Authentication works (login and logout)
- [ ] The agent's MCP server list shows `hoist-docs` connected, and no `hoist-core` server
- [ ] `hoist-search-docs` with `library: "core"` returns hoist-core results
- [ ] No old tooling remains:
  `grep -rn "installHoistCoreTools\|hoist-core-mcp\|mcp__hoist-core" build.gradle .mcp.json CLAUDE.md AGENTS.md .claude 2>/dev/null`

## Reference

- [Hoist Docs on GitHub](https://github.com/xh/hoist-docs) - setup, environment variables, and HTTP
  serving
- [hoist-react v91 upgrade notes](https://github.com/xh/hoist-react/blob/develop/docs/upgrade-notes/v91-upgrade-notes.md)
- [Toolbox on GitHub](https://github.com/xh/toolbox) - canonical example of a Hoist app
