# JsonBlobs

## Overview

A `JsonBlob` is a named chunk of JSON persisted to the database. It is a general-purpose store for
application state that does not warrant a domain class of its own. Blobs are grouped by an
application-defined `type` and belong to an `owner` (a user, or no one for global blobs). An owner
can share a blob read-only with all users.

JsonBlobs back several Hoist features:

- **ViewManager** - hoist-react's saved views (grid layouts, dashboards, etc.) are stored as blobs
  via `ViewService`, one blob per view plus a per-user state blob.
- **Framework services** - `AlertBannerService` and `MemoryMonitoringService` store their own
  state as service-owned blobs.
- **Application code** - apps can read and write blobs directly, on the server via
  `JsonBlobService` or on the client via `XH.jsonBlobService`.

`JsonBlobService` enforces read and write access on every call, so the `@AccessAll` endpoints that
expose it are safe to call from any client. Writing global blobs requires a role configured per
`type` in the `xhJsonBlobConfig` soft config.

## Source Files

| File | Location | Role |
|------|----------|------|
| `JsonBlob` | `grails-app/domain/io/xh/hoist/jsonblob/` | GORM domain - the persisted blob |
| `JsonBlobService` | `grails-app/services/io/xh/hoist/jsonblob/` | Primary service - CRUD, group rename, access control |
| `JsonBlobConfig` | `src/main/groovy/io/xh/hoist/jsonblob/` | Typed `xhJsonBlobConfig` soft config |
| `ViewService` | `grails-app/services/io/xh/hoist/view/` | ViewManager views and per-user view state, built on `JsonBlobService` |
| `XhController` | `grails-app/controllers/io/xh/hoist/impl/` | Client-facing `xh/*JsonBlob*` endpoints |
| `XhViewController` | `grails-app/controllers/io/xh/hoist/impl/` | Client-facing `xhView/*` endpoints for ViewManager |
| `JsonBlobDiffService` | `grails-app/services/io/xh/hoist/jsonblob/` | Cross-environment blob synchronization |
| `JsonBlobAdminController` | `grails-app/controllers/io/xh/hoist/admin/` | Admin Console CRUD endpoints |
| `JsonBlobDiffAdminController` | `grails-app/controllers/io/xh/hoist/admin/` | Admin Console diff tool endpoints |

## Key Classes

### JsonBlob

A GORM domain class stored in the `xh_json_blob` table, with Hibernate second-level caching
enabled.

| Property | Type | Description |
|----------|------|-------------|
| `token` | `String` | Generated 8-character unique identifier, used by clients to address blobs (the database primary key is `id`) |
| `type` | `String` | App-defined discriminator for a class of blobs (max 50 chars). Fixed once created |
| `owner` | `String` | Owning username, or `null` for a global blob (max 50 chars) |
| `acl` | `String` | `'*'` to grant read access to all users, otherwise `null` |
| `name` | `String` | Display name - unique among active blobs with the same `type` and `owner` |
| `value` | `String` | The blob's JSON contents (TEXT column, validated as JSON) |
| `meta` | `String` | Optional JSON metadata for app use - e.g. ViewManager's `group` and `isShared` |
| `description` | `String` | Optional description |
| `archivedDate` | `long` | `0` while active, or the timestamp when archived (soft-deleted) |
| `dateCreated`, `lastUpdated` | `Date` | Timestamps managed by GORM |
| `lastUpdatedBy` | `String` | Username of the last modifier |

`acl` is a TEXT column, but `'*'` is the only value Hoist currently honors - there is no per-user
or per-role ACL.

The domain class provides two serializations. `formatForJSON()` serves the Admin Console and
returns `value` and `meta` as raw strings. `formatForClient(includeValue)` serves end-user
endpoints and returns them as parsed JSON. Call `formatForClient(false)` to omit `value` for
lightweight listings.

### JsonBlobService

The primary API. Most methods take an optional trailing `username`, defaulting to the current
(apparent) user, and check it against the rules in [Access Control](#access-control).
`canWriteGlobal` requires an explicit `username`, and `deleteByNameAndOwner` takes none.

| Method | Description |
|--------|-------------|
| `get(token)` | Get an active blob. Throws if not found or not readable |
| `find(type, name, owner)` | Get an active blob by its unique `type` / `name` / `owner`, or `null` |
| `list(type)` | All active, readable blobs of a type - owned by the user or shared via `acl: '*'` |
| `listTokens(type)` | Tokens of the same blobs, via a projection query |
| `create(data)` | Create a blob. `owner` defaults to the user - pass `owner: null` for a global blob |
| `update(token, data)` | Update an active blob |
| `createOrUpdate(type, name, data)` | Update the user's own blob with this `type` and `name`, or create it |
| `archive(token)` | Soft-delete a blob by setting `archivedDate` |
| `renameGroup(type, ownerName, from, to)` | Rewrite `meta.group` across one owner's blobs, cascading to nested paths |
| `canWriteGlobal(type, username)` | True if the user may write global blobs of the type |
| `deleteByNameAndOwner(name, owner)` | Hard-delete all blobs with a name and owner, across types, active and archived. No access check |

`create` and `update` bind only caller-settable fields - `type` (create only), `name`,
`description`, `value`, `meta`, `owner`, and `acl`. The service manages `token`, `archivedDate`,
`lastUpdatedBy`, and the timestamps, and silently ignores any values supplied for them. `value` and
`meta` are passed as Maps or Lists and serialized by the service.

```groovy
class PortfolioLayoutService extends BaseService {

    def jsonBlobService

    Map getLayout(String name) {
        def blob = jsonBlobService.find('portfolioLayout', name, username)
        return blob ? parseObject(blob.value) : null
    }

    void saveLayout(String name, Map layout) {
        jsonBlobService.createOrUpdate('portfolioLayout', name, [value: layout])
    }
}
```

### ViewService

Persists hoist-react ViewManager views as blobs, with the ViewManager's `type` as the blob type.
`ViewService` translates between view properties and blob fields:

| View | `owner` | `acl` | `meta` |
|------|---------|-------|--------|
| Private | the user | `null` | `{group, isShared: false}` |
| Shared | the user | `'*'` | `{group, isShared: true}` |
| Global | `null` | `'*'` | `{group}` |

Each user also has a state blob per type, named `xhUserState`. It holds their last-selected view
per `viewInstance`, their pinned and unpinned views, and their auto-save setting. `getAllData`
returns views and state together, along with `manageGlobal` - the result of
`JsonBlobService.canWriteGlobal()` for the requesting user.

`ViewService` relies on `JsonBlobService` for all access control. Bulk operations
(`bulkUpdateInfo`, `delete`) are best-effort: they attempt every view and then throw one exception
summarizing any failures.

### JsonBlobDiffService

Backs the Admin Console's JsonBlob diff tool. The tool compares blobs across environments and
applies selected remote values locally, for example to promote global views from staging to
production. It writes to the domain directly, bypassing `JsonBlobService` access checks - its
endpoint requires `HOIST_ADMIN`.

## Access Control

`JsonBlobService` separates read access from write access.

**Read** (`get`, `find`, `list`, `listTokens`) is granted when the user is the blob's `owner`, or
when the blob's `acl` is `'*'`. A global blob has no owner, so it is readable only with
`acl: '*'`. No user can read a global blob with `acl: null`, including users permitted to write it.
`ViewService` always sets `acl: '*'` on global views. Set it explicitly when you create global
blobs in app code.

**Write** (`update`, `archive`, `renameGroup`) is granted only to the owner namespace:

- For an owned blob, the user must be the `owner`. `acl: '*'` does not grant write access - a
  shared blob stays read-only to everyone but its owner.
- For a global blob (`owner: null`), the user must hold a role configured for the blob's `type` in
  `xhJsonBlobConfig.globalWriteRoles` - see [Configuration](#configuration).

`create` checks write access to the new blob's owner namespace. Users can create blobs only for
themselves, or global blobs of a type they may write. An `update` that changes `owner` checks write
access to both the current and the new owner. For example, promoting a view to global requires the
global role, and a user cannot hand a blob to another user.

A failed check throws `NotAuthorizedException` (HTTP 403).

Checks use the apparent user - an admin impersonating another user has that user's access.

Framework services that store their own blobs pass their service name as both `owner` and
`username` (e.g. `'xhAlertBannerService'`), so they always pass the owner check. The Admin Console
blob editor and diff tool write to the domain directly and are gated by `HOIST_ADMIN`.

## Configuration

| Config | Type | Description |
|--------|------|-------------|
| `xhJsonBlobConfig` | `json` | `JsonBlobService` settings. Currently only `globalWriteRoles` |

`globalWriteRoles` maps blob `type` to the roles permitted to create, modify, and archive global
blobs of that type. The `*` entry is the fallback for types the config does not list. The default is
`{"*": ["HOIST_ADMIN"]}`.

```json
{
  "globalWriteRoles": {
    "tradeGridView": ["MANAGE_TRADE_VIEWS"],
    "portfolioGridView": ["MANAGE_PORTFOLIO_VIEWS", "HOIST_ADMIN"],
    "*": ["HOIST_ADMIN"]
  }
}
```

- A user needs any one of the listed roles.
- A type's own entry replaces the `*` fallback rather than adding to it. Above, `HOIST_ADMIN` alone
  cannot manage `tradeGridView` global views.
- The role `*` allows all users.

Because each ViewManager uses its own `type`, this config can require a different role for each
ViewManager in an app. Two ViewManagers that share a `type` - distinguished only by
`viewInstance` - share one role list.

## Common Patterns

### Service-Owned Blobs

A service can keep its own state in blobs by using a fixed, non-user name as both owner and
username. With `acl: null`, users cannot read these blobs through the client-facing endpoints.

```groovy
class TradeLimitsService extends BaseService {

    def jsonBlobService

    private static final String BLOB_TYPE = 'tradeLimits',
        BLOB_OWNER = 'tradeLimitsService'

    Map getLimits() {
        def blob = jsonBlobService.find(BLOB_TYPE, 'current', BLOB_OWNER, BLOB_OWNER)
        return blob ? parseObject(blob.value) : [:]
    }

    void setLimits(Map limits) {
        jsonBlobService.createOrUpdate(BLOB_TYPE, 'current', [value: limits], BLOB_OWNER)
    }
}
```

### Checking Global Write Access in App Code

Use `canWriteGlobal()` to apply the same rule outside `JsonBlobService` - for example, to decide
whether to offer a "publish to all users" action:

```groovy
boolean canPublish = jsonBlobService.canWriteGlobal('portfolioGridView', username)
```

## Client Integration

hoist-react's `XH.jsonBlobService` calls the `XhController` endpoints (`xh/getJsonBlob`,
`xh/listJsonBlobs`, `xh/createJsonBlob`, `xh/updateJsonBlob`, `xh/createOrUpdateJsonBlob`,
`xh/archiveJsonBlob`, `xh/findJsonBlob`). Its `createAsync` does not send an `owner`, so blobs it
creates are owned by the current user. Global blobs are typically created through ViewManager or
server-side code.

`ViewManagerModel` calls the `xhView/*` endpoints on `XhViewController`. It uses the `manageGlobal`
flag from `xhView/allData` as its default for `ViewManagerModel.manageGlobal`, so apps configure the
global views role only in `xhJsonBlobConfig`. This requires hoist-react v88+. Older clients
ignore the server's flag and rely solely on the client-side `manageGlobal` config. Apps on those
versions must configure the role in both places. See the
[hoist-react ViewManager documentation](https://github.com/xh/hoist-react/blob/develop/cmp/viewmanager/README.md)
for the client side.

## Common Pitfalls

### Configuring the global views role only on the client

Before hoist-core v42, `ViewManagerModel.manageGlobal` was the only gate on global views, and any
user could modify them by calling the endpoints directly. The server now enforces
`xhJsonBlobConfig.globalWriteRoles`, and an explicit client-side `manageGlobal: true` cannot grant
more than the server allows. Apps that gate global views on a custom role must add that role to
this config.

### Expecting `acl: '*'` to grant write access

`acl: '*'` makes a blob readable by all users, not writable. Only the owner can modify a shared
blob. For data that many users must edit, use a global blob of a type with a suitable
`globalWriteRoles` entry, or a dedicated domain class.

### Storing large or relational data in blobs

`list` loads whole blobs, `value` included, and blobs have no indexes on their contents. Blobs suit
modest documents read and written as a unit. Data that is large, queried by field, or related to
other records belongs in a proper domain class.

### Reusing a `type` across unrelated features

`type` scopes listing, name uniqueness, group rename, and global write roles. Sharing a type between
unrelated features mixes their blobs together and forces them to share one global role list. Give
each feature, and each ViewManager, its own type.
