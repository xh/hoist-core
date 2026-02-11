# Upgrade Notes Template

Use this template when creating `docs/upgrade-notes/v{NN}-upgrade-notes.md`. Replace all `{placeholders}` with
actual values. Remove sections that don't apply, and add version-specific sections as needed.

---

```markdown
# Hoist Core v{NN} Upgrade Notes

> **From:** v{PRIOR}.x -> v{VERSION} | **Released:** {DATE} | **Difficulty:** {DIFFICULTY}

## Overview

{1-2 paragraphs describing what changed and why. Name the major framework/library changes and
their most significant app-level impacts as a bulleted list.}

The most significant app-level impacts are:

- **{Impact 1}** -- {brief description}
- **{Impact 2}** -- {brief description}
- ...

## Prerequisites

Before starting, ensure:

- [ ] **{Prerequisite 1}** (e.g. Java version, tool version)
- [ ] You are upgrading from hoist-core **v{PRIOR}.x** (the immediately prior major version)
- [ ] Your app compiles and runs cleanly on v{PRIOR}.x before starting

## Upgrade Steps

### 1. {Step Title}

{Brief description of what to change and why.}

**File:** `{path/to/file}`

Before:
```{language}
{existing code}
```

After:
```{language}
{updated code}
```

{Optional notes, caveats, or sub-steps.}

### 2. {Step Title}

{Continue with additional steps...}

**Find affected files:**
```bash
grep -r "{pattern}" {path/}
```

{Before/after examples...}

## Verification Checklist

After completing all steps:

- [ ] `./gradlew compileGroovy` succeeds
- [ ] Application starts without errors
- [ ] Log files are created in the expected directory
- [ ] Admin Console loads and is functional
- [ ] Authentication works (login/logout)
- [ ] {Version-specific verification items}
- [ ] No deprecated patterns remain: `grep -r "{pattern}" {path/}`

## Reference

- {Link to relevant framework upgrade guide, if applicable}
- [Toolbox on GitHub](https://github.com/xh/toolbox) -- canonical example of a Hoist app
- [CHANGELOG](../CHANGELOG.md) -- concise summary of all changes
```

---

## Difficulty Ratings

Use one of these ratings in both the CHANGELOG Breaking Changes header and the upgrade notes
header:

| Rating | Emoji | Meaning |
|--------|-------|---------|
| TRIVIAL | 🎉 | Rename or simple find-replace, no functional changes |
| LOW | 🟢 | Minor adjustments, typically < 30 min |
| MEDIUM | 🟠 | Significant changes to build or source, typically 1-4 hours |
| HIGH | 🔴 | Major restructuring, potential data migration, multi-day effort |

## Step Ordering Convention

Order steps from infrastructure outward to application code:

1. Docker / deployment configuration
2. Gradle wrapper
3. gradle.properties
4. build.gradle
5. settings.gradle
6. Framework configuration files (logging, application config, etc.)
7. Import / namespace changes
8. API changes (method renames, signature changes, etc.)
9. Database migrations (if any)
10. Cleanup of obsolete configuration

This order lets developers build and test incrementally — the app should compile after the build
system steps, then progressively fix source-level issues.

## Database Migration Convention

When an upgrade introduces new domain fields requiring schema changes, the upgrade notes should:

1. **Note that GORM auto-updates may handle it** — if the app has `dbCreate = "update"` configured
   and its service account has DDL privileges, Grails/GORM will add new nullable columns on
   startup automatically.
2. **Provide explicit SQL** for environments where auto-updates are disabled or unavailable.
3. **Never suggest that an LLM agent execute SQL directly** — always relay the SQL to the developer
   for review and manual execution. The developer can request agent-assisted execution if they
   wish, but it must not be a default behavior.
