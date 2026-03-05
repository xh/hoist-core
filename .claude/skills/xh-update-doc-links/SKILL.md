---
name: xh-update-doc-links
description: Pre-commit documentation consistency check. Ensures docs/README.md index, docs/planning/docs-roadmap.md, and the MCP server's hardcoded document registry stay in sync with documentation files on disk. Validates inter-doc links and enhances cross-references when new docs are added. Invoke after editing feature-area docs, before committing.
tools: Read, Glob, Grep, Bash, Edit, Write
---

# xh-update-doc-links — Documentation Consistency Check

Pre-commit skill to ensure documentation index files, the MCP document registry, inter-doc links,
and cross-references stay consistent after editing feature-area docs.

## Step 1: Discover Documentation Files

Build a complete inventory of documentation files on disk.

1. Use `Glob` to find all files matching `docs/*.md` (feature-area docs).
2. Use `Glob` to find all files matching `docs/upgrade-notes/*.md`.
3. **Exclude** `docs/planning/` from the doc inventory — those are meta/planning files,
   not feature-area documentation.
4. Run `git diff --name-only` and `git status --porcelain` to identify which docs were
   recently changed or added.
5. Build a master list of all documentation files, noting which are new or recently modified.

## Step 2: Read Index Files

Read the two index files and parse their current entries.

1. Read `docs/README.md` — focus on the **Feature Documentation** section (the tables under
   "Core Framework", "Core Features", "Infrastructure & Operations", "Grails Platform", and
   "Supporting Features") and the **Quick Reference by Task** table.
   - Parse each table row to extract the linked filename and description.

2. Read `docs/planning/docs-roadmap.md` — parse all priority tables and the Grails Platform
   table.
   - Extract each document name, description, and status value.
   - Note which entries are `Planned`, `Draft`, or `Done`.

## Step 3: Reconcile Indexes

Compare documentation on disk against both index files.

### docs/README.md Reconciliation

For each feature-area doc on disk:
- Check if it has an entry in the appropriate `docs/README.md` table (Core Framework, Core
  Features, Infrastructure & Operations, Grails Platform, or Supporting Features).
- **Missing entries:** Add a new table row in the correct section using this format:
  ```markdown
  | [`filename.md`](./filename.md) | One-sentence description | Key, Topics, Here |
  ```
- **Stale entries:** If an entry links to a doc that no longer exists, remove it.
- **Quick Reference:** Check if a new doc should have a "If you need to..." entry in the
  Quick Reference by Task table. Add one if the doc covers a common task.
- **AGENTS.md directive:** Verify that `AGENTS.md` still contains the directive pointing
  to `docs/README.md` (the "Documentation" section). Do not re-add documentation tables
  to AGENTS.md.

### docs-roadmap.md Reconciliation

For each feature-area doc on disk:
- Check if it has an entry in `docs/planning/docs-roadmap.md` with the correct status.
- **Status updates:** Change `Planned` → `Draft` if the file exists and contains a DRAFT
  banner. Change `Draft` → `Done` if the file exists and the DRAFT banner has been removed.
- **Missing entries:** Add entries for docs not yet on the roadmap.
- **Progress notes:** Append a progress note entry for the current date to
  `docs/planning/docs-roadmap-log.md`. Follow the existing chronological format.

## Step 4: Validate Inter-Doc Links

Scan every documentation file (`docs/*.md` and `docs/upgrade-notes/*.md`) for relative
markdown links.

1. For each file, extract all markdown links matching `[text](path)` where `path` is a
   relative path (not a URL).
2. Resolve each relative path from the source file's directory.
3. Verify the target exists on disk.
4. **Broken links:** Report and fix. Common fixes include:
   - Correcting `../` depth for moved files
   - Updating paths for renamed files
   - Removing links to deleted files

## Step 5: Enhance Cross-Links

When new or recently changed docs are detected, look for cross-linking opportunities.

1. **Topic-based linking:** Scan existing docs for sections that discuss the same topic
   as a new doc. Where an existing doc has a brief treatment of a topic that now has
   dedicated documentation, add a contextual "See [`filename.md`](path) for details" link.

2. **Client Integration sections:** Check if a new or updated doc references hoist-react
   features that have corresponding client-side documentation. Add cross-links to the
   `## Client Integration` section using the format:
   ```markdown
   - [hoist-react doc name](https://github.com/xh/hoist-react/tree/develop/docs/doc-name.md)
   ```

3. **Be conservative:** Only add links where the existing text already discusses the topic.
   Do not restructure existing content or add new sections just to create links.

## Step 6: Reconcile MCP Document Registry

Ensure the MCP server's hardcoded document registry reflects the current documentation on disk.

For background on the registry format, entry structure, and maintenance expectations, see the
[Maintaining the MCP Server](../../mcp/README.md#maintaining-the-mcp-server) section of the MCP
README — specifically the **Doc Registry Entries** subsection.

1. **Read and parse** `mcp/src/main/groovy/io/xh/hoist/mcp/data/DocRegistry.groovy`, extracting
   all `DocEntry` objects from the `buildRegistry()` method. Note the file path in each entry's
   `filePath` field.

2. **Detect missing entries** — compare the Step 1 documentation inventory against registry
   `filePath` fields. For each unregistered doc, add a new `DocEntry` with these fields:
   - `id`: filename stem for feature docs (e.g. `authentication`), `vNN-upgrade-notes` for
     upgrade notes.
   - `title`: derived from the doc's top-level `# heading`.
   - `filePath`: path relative to repo root (e.g. `docs/authentication.md`).
   - `category`: one of the values in the `CATEGORY_ORDER` constant — `core-framework`,
     `core-features`, `infrastructure`, `app-development`, `grails-platform`, `supporting`,
     `build`, `upgrade`, or `index`.
   - `description`: concise one-sentence summary matching existing entry style.
   - `keywords`: 5–12 key terms as a `List<String>` — include API names, class names, and
     topic terms.

3. **Place entries correctly** in the right section of `buildRegistry()`, which is organized by
   comment dividers matching the `CATEGORY_ORDER` constant (Core Framework, Core Features,
   Infrastructure & Operations, Application Development, Grails Platform, Supporting Features,
   Build & Publishing, Upgrade Notes, Doc index).

4. **Remove stale entries** whose `filePath` no longer exists on disk.

5. **Update moved/renamed docs** — fix `filePath` values (and `id`/`title` if the change is
   structural, not just a path correction).

6. **Verify existing entries** — spot-check that metadata (title, description, keywords) is still
   accurate for recently changed docs. Only fix entries that are clearly stale or incorrect; do not
   rewrite working entries for style.

## Step 7: Report

Output a summary organized into these sections:

1. **Index Updates** — `docs/README.md` entries added, updated, or removed.
2. **Roadmap Updates** — Status changes and new entries in `docs-roadmap.md`, progress notes
   appended to `docs-roadmap-log.md`.
3. **Broken Links Fixed** — Source file, broken target, and fix applied.
4. **New Cross-Links Added** — Source file, target doc, and surrounding context.
5. **MCP Registry Updates** — Entries added, removed, or updated in `DocRegistry.groovy`, with
   `id` and `filePath` for each change.
6. **Items Needing Review** — Ambiguities or items requiring human judgment.

If no changes were needed in a category, note "None" for that section.
