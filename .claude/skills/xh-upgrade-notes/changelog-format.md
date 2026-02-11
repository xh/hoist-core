# CHANGELOG Entry Format

Reference for writing and reviewing CHANGELOG entries in `CHANGELOG.md`.

## Entry Structure

Every major version entry should use this structure. Minor/patch releases use whichever sections
apply.

```markdown
## {VERSION} - {YYYY-MM-DD}

### Breaking Changes (upgrade difficulty: {RATING} - {brief description})

* {Introductory bullet with overview and link to upgrade notes}
* {Required change 1}
* {Required change 2}
    * {Sub-detail if needed}
* ...

See the [{framework} upgrade guide]({url}) for additional background.

### New Features

* {Feature description}

### Bug Fixes

* {Fix description}

### Technical

* {Internal change description}

### Libraries

* {Library} `{old} -> {new}`
```

## Section Headers

Use these emoji-prefixed headers consistently:

| Section | Header | When to Include |
|---------|--------|-----------------|
| Breaking Changes | `### Breaking Changes` | Required app changes exist |
| New Features | `### New Features` | New capabilities added |
| Bug Fixes | `### Bug Fixes` | Bugs fixed |
| Technical | `### Technical` | Internal changes worth noting |
| Libraries | `### Libraries` | Major dependency version bumps |

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

For major versions, this section should:

1. **Start** with an overview bullet that summarizes the upgrade and links to the detailed
   upgrade notes file: `docs/upgrade-notes/v{NN}-upgrade-notes.md`
2. **List** every required app-level change as a separate bullet
3. **Include** a difficulty rating in the header parenthetical
4. **End** with a link to the relevant framework upgrade guide (if applicable)

Each bullet should be concise (1-2 lines). The upgrade notes file handles expanded detail with
before/after code examples.

### Difficulty Ratings

Include in the Breaking Changes header:

```markdown
### Breaking Changes (upgrade difficulty: 🎉 TRIVIAL)
### Breaking Changes (upgrade difficulty: 🟢 LOW - {brief description})
### Breaking Changes (upgrade difficulty: 🟠 MEDIUM - {brief description})
### Breaking Changes (upgrade difficulty: 🔴 HIGH - {brief description})
```

## Libraries Section

List major dependency version changes with backtick-wrapped versions:

```markdown
### Libraries

* Grails `6.2.3 -> 7.0`
* Groovy `3.0.23 -> 4.0`
* Spring Boot `2.7 -> 3.5`
```

Use abbreviated versions where the minor/patch isn't significant (e.g. `7.0` not `7.0.5`).

## General Guidelines

- **Positive tone**: Favor words like "Enhanced", "Improved", "Streamlined" where accurate.
  Concisely note *why* a change is an improvement when not obvious from context (e.g.
  "Improved shutdown handling — ensures full cleanup if Hazelcast terminates unexpectedly").
  Accuracy always takes precedence — bug fixes should be reported clearly as such.
- **Conciseness**: This is a changelog, not a guide. One bullet = one change, 1-2 lines max.
- **Specificity**: Name classes, methods, and config keys in backticks.
- **Completeness**: Changes that modify behavior, APIs, or configuration in ways developers need
  to know about should be accounted for. Trivial changes (formatting, internal refactors with no
  behavioral impact, tooling updates) do not need to be included.
- **No duplication**: Don't repeat the same change across sections. Pick the most relevant section.
- **Punctuation**: End each bullet with a period.
