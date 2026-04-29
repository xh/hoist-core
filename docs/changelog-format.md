# CHANGELOG Entry Format

Reference for writing and reviewing CHANGELOG entries in the **hoist-core library** `CHANGELOG.md`.

> **Library vs. Application changelogs:** This guide applies to hoist-core and other Hoist library
> packages. Hoist *application* changelogs have different formatting requirements — see the
> [Application Changelogs](#application-changelogs) section at the end of this document.

## Entry Structure

Every major version entry should use this structure. Minor/patch releases use whichever sections
apply.

```markdown
## {VERSION} - {YYYY-MM-DD}

### 💥 Breaking Changes (upgrade difficulty: {RATING})

See [`docs/upgrade-notes/v{NN}-upgrade-notes.md`](docs/upgrade-notes/v{NN}-upgrade-notes.md) for
detailed, step-by-step upgrade instructions with before/after code examples.

* {Required change 1}
* {Required change 2}
    * {Sub-detail if needed}
* ...

### 🎁 New Features

* {Feature description}

### 🐞 Bug Fixes

* {Fix description}

### ⚙️ Technical

* {Internal change description}

### 🤖 AI Docs + Tooling

* {AI docs/tooling change description}

### 📚 Libraries

* {Library} `{old} → {new}`
```

## Section Headers

Use these emoji-prefixed headers consistently:

| Section | Header | When to Include |
|---------|--------|-----------------|
| Breaking Changes | `### 💥 Breaking Changes` | Required app changes exist |
| New Features | `### 🎁 New Features` | New capabilities added |
| Bug Fixes | `### 🐞 Bug Fixes` | Bugs fixed |
| Technical | `### ⚙️ Technical` | Internal changes worth noting |
| AI Docs + Tooling | `### 🤖 AI Docs + Tooling` | AI assistant docs, MCP server, CLI tools |
| Libraries | `### 📚 Libraries` | Major dependency version bumps |

## Voice and Tense

- **Past tense, action-driven** for descriptions: "Enhanced", "Fixed", "Added", "Improved",
  "Removed", "Refactored"
- **Imperative** for developer instructions: "Update", "Adjust", "Remove", "Migrate"
- Avoid starting bullets with "New" (prefer "Added"), "Support for" (prefer "Added support for"),
  or present tense verbs like "Fix", "Allow", "Enable"

### Examples

Good:
```markdown
* Enhanced `JSONClient` exception handling to capture raw string messages.
* Added support for `bcc` and `markImportant` in `EmailService.sendEmail`.
* Fixed regression with `LdapObject` subclasses not fully populating properties.
```

Bad:
```markdown
* New support for bcc in EmailService     (use "Added", not "New support for")
* Fix to regression in LdapObject         (use "Fixed regression", not "Fix to")
* Allow improved editing of Views         (use "Enabled" or "Added", not "Allow")
```

## Breaking Changes Section

**Every major version with breaking changes MUST include all of the following.** Do not skip or
reorder these requirements:

1. **Difficulty rating in the header** — append `(upgrade difficulty: {RATING})` to the section
   header. See Difficulty Ratings below for the rating scale.
2. **Upgrade notes link as a standalone sentence** — immediately after the header (before any
   bullets), include a sentence linking to the upgrade notes file. This is **not** a bullet point —
   it is a standalone paragraph. Use this exact format:
   ```markdown
   See [`docs/upgrade-notes/v{NN}-upgrade-notes.md`](docs/upgrade-notes/v{NN}-upgrade-notes.md) for
   detailed, step-by-step upgrade instructions with before/after code examples.
   ```
3. **List** every required app-level change as a separate bullet
4. **Be specific** — name exact classes, methods, and config keys
5. **Link** to relevant framework upgrade guides (e.g. Grails, Spring Boot) when applicable

Each bullet should be concise (1-2 lines). The upgrade notes file handles expanded detail with
before/after code examples.

### Difficulty Ratings

When upgrade notes exist for a major version, include a difficulty rating:

```markdown
### 💥 Breaking Changes (upgrade difficulty: 🎉 TRIVIAL)
### 💥 Breaking Changes (upgrade difficulty: 🟢 LOW - {brief description})
### 💥 Breaking Changes (upgrade difficulty: 🟠 MEDIUM - {brief description})
### 💥 Breaking Changes (upgrade difficulty: 🔴 HIGH - {brief description})
```

## Libraries Section

List major dependency version changes with backtick-wrapped versions:

```markdown
### 📚 Libraries

* Grails `6.2.3 → 7.0`
* Groovy `3.0.23 → 4.0`
* Spring Boot `2.7 → 3.5`
```

Use abbreviated versions where the minor/patch isn't significant (e.g. `7.0` not `7.0.5`).

## General Guidelines

- **Positive tone**: Favor words like "Enhanced", "Improved", "Streamlined" where accurate.
  Concisely note *why* a change is an improvement when not obvious from context (e.g.
  "Improved shutdown handling — ensures full cleanup if Hazelcast terminates unexpectedly").
  Accuracy always takes precedence — bug fixes should be reported clearly as such.
- **Conciseness**: This is a changelog, not a guide. One bullet = one change, 1-3 lines max.
  Upgrade notes provide granular detail when needed; keep changelog entries brief and scannable.
- **Specificity**: Name classes, methods, and config keys in backticks.
- **Completeness**: Changes that modify behavior, APIs, or configuration in ways developers need
  to know about should be accounted for. Trivial changes (formatting, internal refactors with no
  behavioral impact, tooling updates) do not need to be included.
- **No duplication**: Don't repeat the same change across sections. Pick the most relevant section.
- **Punctuation**: End each bullet with a period.
- **Plain ASCII punctuation**: Use a single hyphen (` - `) for in-sentence breaks. Do not use
  em dashes (`—`), en dashes (`–`), or double hyphens (`--`). The CHANGELOG is grep'd, parsed,
  and viewed across many tools where Unicode dashes cause encoding friction with no rendering
  benefit. This is stricter than the general
  [coding-conventions](./coding-conventions.md#avoid-unicode-in-code-comments) rule, which
  allows em dashes in narrative markdown.

## Application Changelogs

Hoist *application* changelogs (e.g. in app repos that depend on `io.xh:hoist-core`) follow
different formatting rules than the library changelog described above. Application changelogs are
parsed at runtime by a Hoist release notes feature that displays them within the app UI.

**Do NOT hard-wrap list items in application changelogs.** Each bullet point must be a single
unwrapped line — the release notes parser treats line breaks within a list item as separate entries.
Let the viewing tool handle display wrapping.

All other conventions (section headers, voice/tense, backtick-wrapped specificity) apply to both
library and application changelogs.
