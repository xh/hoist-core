# Blueprints

## Overview

Hoist Blueprints are JSON documents that describe a dashboard or report: its data sources, its
widgets and its layout. The client library defines a `BlueprintRepository` contract for storing
them, and an app chooses how to implement it. Hoist Core provides a server-side implementation
with real integrity and access control: `BlueprintService` and the `xhBlueprint/*` endpoints.

Each blueprint has one draft, which designers edit, and a series of numbered publishes, which never
change. Users run the latest publish. Each user can also keep personalizations of a blueprint,
one per preset.

Blueprint storage is off by default. An app turns it on with `enabled` in the `xhBlueprintConfig`
soft config. While it is off, every call to `BlueprintService` fails, and the endpoints return a
400 response.

`BlueprintService` checks access on every call, so the `@AccessAll` endpoints are safe to call from
any client. The service stores blueprint content as given. The client validates it against the
blueprint format.

## Source Files

| File | Location | Role |
|------|----------|------|
| `Blueprint` | `grails-app/domain/io/xh/hoist/blueprint/` | GORM domain - a blueprint's draft, metadata and access lists |
| `BlueprintPublish` | `grails-app/domain/io/xh/hoist/blueprint/` | GORM domain - one numbered publish, insert-only |
| `BlueprintPersonalization` | `grails-app/domain/io/xh/hoist/blueprint/` | GORM domain - one user's personalization for one preset |
| `BlueprintService` | `grails-app/services/io/xh/hoist/blueprint/` | Primary service - storage, versions and access control |
| `BlueprintConfig` | `src/main/groovy/io/xh/hoist/blueprint/` | Typed `xhBlueprintConfig` soft config |
| `XhBlueprintController` | `grails-app/controllers/io/xh/hoist/impl/` | Client-facing `xhBlueprint/*` endpoints, one per repository method |

## Key Classes

### Domain classes

| Domain | Table | Holds |
|--------|-------|-------|
| `Blueprint` | `xh_blueprint` | `token` (the client's `id`), name, description, `owner`, `writers`, `readers`, the `draft` JSON, `draftVersion`, `pendingRenames`, `publishedVersion`, and the source of a copy |
| `BlueprintPublish` | `xh_blueprint_publish` | The publish `number`, its content, the renames it carries, a note and `publishedBy` |
| `BlueprintPersonalization` | `xh_blueprint_personalization` | `username`, `preset`, `set` and `remove` ops, and the `publishVersion` they were made against |

A publish and a personalization reference their blueprint by foreign key. A unique index on the
blueprint and publish number keeps a number from being created twice. A unique index on the
blueprint, user and preset keeps one personalization per preset.

### BlueprintService

| Method | Access needed | Returns |
|--------|---------------|---------|
| `list()` | Read | Summaries of the blueprints the user may read |
| `getDraft(token)` | Write | The blueprint with its draft, or null |
| `getPublish(token, number?)` | Read for the latest, write for an older one | The publish, or null |
| `listPublishes(token)` | Write | Every publish, oldest first, without content |
| `create(name, description, blueprint, writers?, readers?)` | One of `createRoles` | The new blueprint, owned by the current user |
| `saveDraft(token, blueprint, base, renames)` | Write | The draft's new version |
| `publish(token, base, note?)` | Write, plus `publishRoles` if set | The new publish |
| `copy(token, name, kind?, number?)` | Read on the source version, plus one of `createRoles` | The copy, or null if the source has no publish |
| `delete(token)` | Owner or admin | Nothing. Also deletes its publishes and every user's personalizations |
| `updateAccess(token, writers, readers)` | Owner or admin | The blueprint |
| `listPersonalizations(token)` | Read | The current user's personalizations |
| `savePersonalization(token, preset, ops, publishVersion)` | Read | The saved personalization |

## Access Control

Each blueprint has an `owner`, a list of `writers` and a list of `readers`. Each entry in a list is
a username, `role:<ROLE>` for every user with that role, or `*` for all users. The service checks
the apparent user, so an admin who impersonates a user has that user's access.

An admin who impersonates a user can view blueprints and edit drafts as that user, but cannot
publish, share or delete. Those calls return a 403 that names the reason, so the audit trail never
records an admin's change as the user's own.

| Access | Granted to | Allows |
|--------|------------|--------|
| Write | The owner, a writer, or one of `adminRoles` | The draft, every publish, saving and publishing |
| Read | A reader | The latest publish only, and personalizations |
| Manage | The owner, or one of `adminRoles` | Deleting the blueprint and changing its writers and readers |

A blueprint without a publish is listed only for users with write access. A blueprint that a user
may not read looks the same as a missing one: `getDraft` and `getPublish` return null, and every
other method throws `BlueprintNotFoundException`, which is a routine 404. A reader who asks for a
writer-only action gets a 403.

Summaries include the access lists only for users who may manage access.

## Versions and Concurrency

`draftVersion` counts the draft's saves. A save or publish names the version it was based on as
`base`. If the draft is no longer at `base`, the service throws `BlueprintStaleBaseException`, and
the endpoint returns an `edit.staleBase` issue.

Every method that changes a blueprint's row takes a database row lock on it first, with
`SELECT ... FOR UPDATE`. Two saves on the same base can therefore never both apply, on any
number of cluster instances.

Publishing creates publish number `publishedVersion + 1`, with the draft's pending renames. It
then clears those renames and increments `draftVersion`. A save based on the draft from before the
publish therefore fails as stale, and cannot add the published renames again.

## Configuration

`xhBlueprintConfig` is a JSON soft config, read as `BlueprintConfig`.

| Key | Default | Description |
|-----|---------|-------------|
| `enabled` | `false` | Turns blueprint storage on |
| `adminRoles` | `['HOIST_ADMIN']` | Roles with write and manage access to every blueprint |
| `createRoles` | `['HOIST_ADMIN']` | Roles that may create blueprints, including copies |
| `publishRoles` | `[]` | Roles a writer also needs to publish. Empty means write access is enough |
| `maxBytes` | `2097152` | Max size of a stored blueprint, or of one personalization's ops, as serialized JSON |
| `maxPersonalizations` | `50` | Max personalizations per user and blueprint |

In any list, the role `*` grants that right to all users.

## Endpoints

`XhBlueprintController` serves one action per `BlueprintRepository` method. The JSON matches the
library's types, with a blueprint's token as its `id`.

| Action | Method | Body or params | Response |
|--------|--------|----------------|----------|
| `list` | GET | | Blueprint summaries |
| `canCreate` | GET | | `{canCreate}` |
| `open` | GET | `id`, `as` (`draft` or `publish`), `version` | A record, or null |
| `create` | POST | `{name, description, blueprint}` | The draft record |
| `saveDraft` | POST | `id`, `{blueprint, base, renames}` | `{version, issues}` |
| `publish` | POST | `id`, `{base, note}` | `{publish, draftVersion, issues}` |
| `listPublishes` | GET | `id` | Publishes, oldest first |
| `copy` | POST | `id`, `{name, from}` | The copy's draft record, or null |
| `delete` | POST | `id` | 204 |
| `personalizations` | GET | `blueprintId` | The user's personalizations |
| `savePersonalization` | POST | `{blueprintId, preset, ops, publishVersion}` | The saved personalization |
| `updateAccess` | POST | `id`, `{writers, readers}` | 204 |

Summaries and records carry `access`, `canPublish` and `canShare`. They carry `sharing`, with the
writers and readers, only when `canShare` is true. A stale base returns HTTP 200 with an
`edit.staleBase` issue. A malformed request returns 400.

## What the Server Checks

The server treats blueprint content as opaque JSON. It checks structure only:

- A blueprint is a JSON object, and renames and ops are lists of the expected shape.
- No key or ID is `__proto__`, `prototype` or `constructor`.
- Personalization ops are `set` or `remove`, with a JSON Pointer path.
- Each stored document is within `maxBytes`.

The client validates content against the blueprint format when it opens a blueprint. The server
does not check that format, so a writer with a modified client can store a blueprint that fails
validation. Readers then see its issues rather than run it.

## Schema

The three tables ship with Hoist Core. Apps that run `dbCreate: 'update'` get them at startup. For
apps that manage their schema, the [v45 upgrade notes](./upgrade-notes/v45-upgrade-notes.md) give
the DDL.

## Common Pitfalls

### Expecting blueprints to work without enabling them

Storage is off by default. Set `enabled` in `xhBlueprintConfig`, and set `createRoles` to the roles
that should create blueprints.

### Expecting readers to copy blueprints by default

A copy is a new blueprint, so it needs one of `createRoles`. The default is `HOIST_ADMIN` only.
Widen `createRoles` if users without write access should save their own copies.

### Defining an app service named `BlueprintService`

Grails resolves services by bean name. An app service named `BlueprintService` replaces or collides
with Hoist Core's. Use another name for app code that works with blueprints.

### Writing grantees in another form

The access lists accept a username, `role:<ROLE>` or `*`. A bare role name matches only a user with
that username.
