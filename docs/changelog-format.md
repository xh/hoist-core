# CHANGELOG Entry Format

This document is the reference for how to write and review CHANGELOG entries in the **hoist-core
library** `CHANGELOG.md`.

> **Library and application changelogs are different:** This guide applies to hoist-core and other
> Hoist library packages. Hoist *application* changelogs have different formatting requirements. See
> the [Application Changelogs](#application-changelogs) section at the end of this document.

## Entry Structure

Every major version entry uses this structure. A minor or patch release uses only the sections that
apply.

```markdown
## {VERSION} - {YYYY-MM-DD}

### 💥 Breaking Changes (upgrade difficulty: {RATING})

See [`docs/upgrade-notes/v{NN}-upgrade-notes.md`](docs/upgrade-notes/v{NN}-upgrade-notes.md) for
detailed, step-by-step upgrade instructions with before and after code examples.

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

* {AI docs and tooling change description}

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

**Every bullet must have an explicit grammatical subject.** Open with either a past-tense verb (the
action taken) or the symbol that changed (the API that now behaves differently). Both forms are
correct. Choose the one that matches what the reader scans for.

### Verb-first

Use this form when the bullet reports an action on the codebase, not the behavior of an existing
API. Examples include removals, renames, deprecations, bug fixes, and work that spans several
symbols. The symbol-first form makes no sense if the release deletes or renames that symbol.

```markdown
* Fixed regression with `LdapObject` subclasses not fully populating properties.
* Removed `xhTraceConfig.alwaysSampleErrors` - the flag was inappropriate for head-based sampling.
* Renamed all Hoist built-in metrics from the `hoist.*` prefix to `xh.*` for brevity.
* Improved logging and performance of `Cache` and `Timer`.
* Disabled three unused Spring Boot auto-configurations to avoid coupling to a specific Apache
  HttpClient version.
```

Use the past tense: "Added", "Fixed", "Improved", "Enhanced", "Removed", "Renamed", "Reworked",
"Deprecated". Do not use the present tense: "Fix", "Allow", "Enable".

### Symbol-first

Use this form when an existing, named API gains or changes behavior. The symbol is the most valuable
token for a reader who asks "does this touch something I call?", so put it first. Pair it with `now`
and a present-tense verb.

```markdown
* `EmailService.sendEmail` now supports `bcc` and `markImportant`.
* `RestController.update` now also accepts `PATCH` (in addition to `PUT`).
* `JSONFormatCached` now holds its cached JSON in a `transient` field, so it is no longer serialized
  into Hazelcast structures or cross-instance call results.
* New `BaseController.renderNDJSON()` streams an `Iterable` to the client as newline-delimited JSON.
```

Do not force these into the verb-first form. "Changed `RestController.update` to also accept
`PATCH`" buries the symbol behind a verb that carries no information. The `New {symbol}` opener
above is a valid variant for a new API whose behavior needs a full sentence.

Additions are the one genuinely ambiguous case. "Added support for `bcc` in `EmailService`" is
acceptable, but the symbol-first form is better if the change lands on one specific API that you can
name. Use "Added ..." only for an addition with no single home, or for a true one-line entry.

### Imperative

Use the imperative for developer instructions. Breaking Changes bullets and sub-bullets tell an app
what to do: "Update", "Adjust", "Remove", "Migrate".

### Subject-less bullets

A bullet reads as a fragment if its subject is an unstated "this release", or if it is a bare noun
phrase and not a sentence. The reader must then supply the missing actor.

Bad:
```markdown
* Provides support for nested view groups in the hoist-react v87 `ViewManager`.
* Misc. improvements to logging and performance of `Cache` and `Timer`.
* Additional validation of parameters to the `/userAdmin/users` endpoint.
* Support for `bcc` in `EmailService`.
* Fix to regression in `LdapObject`.
```

Good:
```markdown
* `ViewService` now supports the nested view groups used by the hoist-react v87 `ViewManager`.
* Improved logging and performance of `Cache` and `Timer`.
* The `/userAdmin/users` endpoint now validates its parameters.
* `EmailService.sendEmail` now supports `bcc`.
* Fixed regression in `LdapObject`.
```

## Simplified Technical English

Entries follow [ASD-STE100](https://asd-ste100.org) (Simplified Technical English). Our readers
include non-native English speakers. Readers also paste entries into tickets, translate them, and
skim them under time pressure. STE is built for these conditions. It gives short sentences that
carry one idea and have one possible reading.

### Core Rules

Each row shows the same content in both columns. The lesson is the difference between the two
cells, so read them as a pair.

| Rule | Write this | Not this |
|---|---|---|
| One idea per sentence, 25 words max | `View groups are now paths that use a forward slash as the delimiter. A path can have an unlimited number of levels.` | `View groups are now slash-delimited paths supporting unlimited nesting, and a path can have an unlimited number of levels.` |
| Active voice with a named subject | `Hoist creates these config rows with the value {}.` | `These config rows are created as {}.` |
| No participial (`-ing`) clause attached to a main clause | `renameGroup writes a new meta.group value to all blobs of a given type.` | `renameGroup provides support, rewriting meta.group across all blobs of a given type.` |
| Simple present or simple past only | `The handler already sends a 400 status.` | `The handler has already sent a 400 status.` |
| One word, one meaning - no synonyms for variety | `The rename changes the group. The change cascades to every view.` | `The rename changes the group. The modification cascades to every view.` |
| Three words max in a noun cluster | `the defaults that the code declares` | `the code-declared config default values` |
| `because` for cause, never `as` or `since` | `Existing databases do not change, because their rows already have values.` | `Existing databases do not change, as their rows already have values.` |
| Full stop, not a semicolon or a comma splice | `Earlier clients still work. This release adds two endpoints.` | `Earlier clients still work; this release adds two endpoints.` |
| No slash as a conjunction - write `and` or `or` | `minor and patch releases` | `minor/patch releases` |

Two further rules need no example. Keep every article, and do not use a contraction.

### Modals

STE assigns one job to each modal. Do not use `should`, because it is ambiguous between a
requirement and a recommendation.

| Meaning | Use |
|---|---|
| Requirement - the app breaks without it | `must` |
| Ability or permission | `can` |
| Recommendation | Do not use a modal. State the consequence. |

The last row applies to the ⚠️ advisory sub-bullets. "Apps should now declare `defaultValue: [:]`"
overstates a warning as a requirement. Write what actually happens:

```markdown
* ⚠️ An app that bootstraps a typed config with `typedClass` now gets a WARN at startup unless it
  declares `defaultValue: [:]` in the `ConfigSpec`.
```

### Relationship to Voice and Tense

STE governs sentence construction. It does **not** replace the conventions above. Keep the
past-tense verb-first and symbol-first openers, the explicit grammatical subject, backticked
symbols, and the ASCII punctuation rule. A bullet that opens with `Added ...` or `Fixed ...` is an
established elliptical form here. Keep it.

If the two rules conflict, the section-specific rule wins. Breaking Changes sub-bullets stay
imperative (`Update`, `Migrate`), even though STE prefers a full subject.

### Worked Example

Before:

```markdown
* `JsonBlobService.renameGroup` provides the underlying support, rewriting `meta.group` across
  all blobs of a given type within a single owner namespace in one transaction.
```

After:

```markdown
* `JsonBlobService.renameGroup` writes a new `meta.group` value to all blobs of a given type in
  one owner namespace, in a single transaction. `ViewService` builds on this method.
```

The symbol still leads. The `-ing` clause carried all the real content, so it became the sentence,
and the vague main clause about "underlying support" went away. Do not look for a synonym for a
weak verb. Delete the clause that the verb sits in. The relationship to `ViewService` is worth one
short sentence at the end, where it does not block the reader from the substance. `within` also
became `in` - one preposition, one meaning.

## Breaking Changes Section

**Every major version with breaking changes MUST include all of the following.** Do not skip or
reorder these requirements:

1. **Difficulty rating in the header** - append `(upgrade difficulty: {RATING})` to the section
   header. See Difficulty Ratings below for the rating scale.
2. **Upgrade notes link as a standalone sentence** - immediately after the header, and before any
   bullets, include a sentence that links to the upgrade notes file. This is **not** a bullet
   point. It is a standalone paragraph. Use this exact format:
   ```markdown
   See [`docs/upgrade-notes/v{NN}-upgrade-notes.md`](docs/upgrade-notes/v{NN}-upgrade-notes.md) for
   detailed, step-by-step upgrade instructions with before and after code examples.
   ```
3. **List** every required app-level change as a separate bullet
4. **Be specific** - name exact classes, methods, and config keys
5. **Link** to relevant framework upgrade guides, for example Grails or Spring Boot, when applicable

Keep each bullet concise, at one or two lines. The upgrade notes file carries the expanded detail,
with before and after code examples.

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

* Grails `6.2 → 7.0`
* Groovy `3.0 → 4.0`
* Spring Boot `2.7 → 3.5`
```

Use an abbreviated version if the patch number is not significant. For example, use `7.0`, not
`7.0.5`. Abbreviate both sides of the arrow to the same depth. A bump of `6.2.3 → 7.0` reads as a
patch-level change on the left and a major change on the right.

Keep the patch number when it is the point of the entry, as in `8.14.4 → 8.14.5`.

## General Guidelines

- **Positive tone**: Use words like "Enhanced", "Improved", and "Streamlined" where they are
  accurate. Note *why* a change is an improvement if the context does not make it clear. For
  example: "Improved shutdown handling ensures full cleanup if Hazelcast terminates unexpectedly".
  Accuracy always comes first. Report a bug fix clearly as a bug fix.
- **Conciseness**: This is a changelog, not a guide. One bullet reports one change, at one to three
  lines. The upgrade notes give the detailed explanation. Keep changelog entries brief and easy to
  scan.
- **Specificity**: Name classes, methods, and config keys in backticks.
- **Completeness**: Include every change to behavior, APIs, or configuration that a developer needs
  to know about. Omit a trivial change, such as formatting, an internal refactor with no effect on
  behavior, or a tooling update.
- **No duplication**: Do not repeat the same change across sections. Pick the most relevant section.
- **Punctuation**: End each bullet with a period.
- **Plain ASCII punctuation**: Use a single hyphen (` - `) for in-sentence breaks. Do not use
  em dashes (`—`), en dashes (`–`), or double hyphens (`--`). Many tools grep, parse, and display
  the CHANGELOG. In these tools, a Unicode dash causes encoding problems and gives no benefit. This
  rule is stricter than the general
  [coding-conventions](./coding-conventions.md#avoid-unicode-in-code-comments) rule, which allows
  em dashes in narrative markdown.

## Application Changelogs

Hoist *application* changelogs, for example those in app repos that depend on `io.xh:hoist-core`,
follow different formatting rules than the library changelog rules above. A Hoist release notes
feature parses application changelogs at runtime and displays them in the app UI.

**Do NOT hard-wrap list items in application changelogs.** Each bullet point must be a single
unwrapped line. The release notes parser treats a line break inside a list item as a separate entry.
Let the display tool wrap the text.

All other conventions apply to both library and application changelogs: section headers, voice and
tense, and backtick-wrapped specificity.
