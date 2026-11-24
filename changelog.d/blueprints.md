### 💥 Breaking Changes

See [`docs/upgrade-notes/v46-upgrade-notes.md`](docs/upgrade-notes/v46-upgrade-notes.md) for
detailed, step-by-step upgrade instructions with before and after code examples.

* Apps that manage their own schema must create the `xh_blueprint`, `xh_blueprint_publish` and
  `xh_blueprint_personalization` tables. Apps that run `dbCreate: 'update'` get them at startup.

### 🎁 New Features

* Added a server-side store for Hoist Blueprints: `BlueprintService` and the `xhBlueprint/*`
  endpoints behind a client `BlueprintRepository`. It keeps a draft and numbered publishes per
  blueprint, with access per blueprint, a version check under a row lock, and per-user
  personalizations. Each blueprint has a `type` that scopes every call. It is off until an app
  sets `enabled` in the new `xhBlueprintConfig`.
* Added `BlueprintService.updatePublish()` and `xhBlueprint/updatePublish`, for writers to change a
  publish's note, label or pin. Its content never changes. The `xh_blueprint_publish` table gains
  `label` and `pinned` columns, which the v46 upgrade notes add for apps that manage their schema.
