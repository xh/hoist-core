# Hoist Core v42 Upgrade Notes

> **From:** v41.x → v42.0.0 | **Released:** 2026-09-28 | **Difficulty:** 🟢 LOW

## Overview

Hoist Core v42 closes a gap in `JsonBlobService` access control. Previously a blob with
`acl: '*'` granted every user *write* access, so any authenticated user could modify or archive
shared and global blobs - including ViewManager global views - regardless of the client-side
`manageGlobal` gate. Write access is now limited to a blob's owner, and global (null-owner) blobs
are gated by role via the new `xhJsonBlobConfig` soft config. The release also finishes the
`DirectoryService` refactor begun in v41, removing the `doLoadUsersForDirectoryGroups` extension
point in favor of a single `getDirectoryService` override.

The most significant app-level impacts are:

- **Global view write roles move to the server** - apps whose global views are managed by a
  role other than `HOIST_ADMIN` must declare that role in `xhJsonBlobConfig.globalWriteRoles`.
  Apps where only `HOIST_ADMIN` users manage global views need no changes.
- **`doLoadUsersForDirectoryGroups` removed** - apps that override it now override
  `getDirectoryService` to return their own `DirectoryService` implementation. Apps that do not
  override it need no changes.
- **`EmailService.sendEmail` takes named parameters** - existing calls compile and run
  unchanged, but an unrecognized argument name now throws.

There are no database schema changes in this release.

## Prerequisites

Before starting, make sure:

- [ ] The app builds and runs on hoist-core v41.x.
- [ ] Recommended - pair with `@xh/hoist >= 88.0`, which reads the server's `manageGlobal`
      answer so apps can drop the client-side `manageGlobal` config. Older clients keep working
      but see server-side 403s for users who set `manageGlobal: true` without the required role
      (see step 2).

## Upgrade Steps

### 1. Update the hoist-core version

**File:** `gradle.properties`

```properties
hoistCoreVersion=42.0.0
```

### 2. Review global view write roles

`JsonBlobService` now requires a role for any write to a global (null-owner) blob - create,
update, archive, or group rename. Roles come from the new `xhJsonBlobConfig` soft config, which
hoist-core bootstraps on startup with this default:

```json
{
  "globalWriteRoles": {
    "*": ["HOIST_ADMIN"]
  }
}
```

`globalWriteRoles` is keyed by blob `type` - the `type` passed to `ViewManagerModel` on the
client - with `*` as the fallback for types not listed. A user needs any one of the listed roles.
A listed type's roles replace the fallback rather than adding to it, so repeat `HOIST_ADMIN` on a
per-type entry if admins should keep access.

Find the roles the app grants on the client today:

```bash
grep -rn "manageGlobal" client-app/src/
```

Then choose one of the following:

**Only `HOIST_ADMIN` users manage global views** - no change required. The default applies.

**A custom role manages global views** - mirror it in the config, per type or as the fallback.
Edit `xhJsonBlobConfig` in the Admin Console, or seed it from the app's `BootStrap.groovy`:

```groovy
new ConfigSpec(
    name: 'xhJsonBlobConfig',
    valueType: 'json',
    defaultValue: [
        globalWriteRoles: [
            '*'             : ['HOIST_ADMIN'],
            'portfolioGrid' : ['HOIST_ADMIN', 'PORTFOLIO_MANAGER']
        ]
    ],
    groupName: 'xh.io'
)
```

**All users manage global views** (prior behavior) - include the role `*`:

```json
{
  "globalWriteRoles": {
    "*": ["*"]
  }
}
```

Once on `@xh/hoist >= 88.0`, remove the client-side `manageGlobal` config from each
`ViewManagerModel` - the client now derives it from the `xhView/allData` response. Toolbox's
commit "Drop client-side manageGlobal from ViewManager configs" shows the change:

Before:
```typescript
ViewManagerModel.create({
    type: 'portfolioGrid',
    enableAutoSave: false,
    manageGlobal: XH.getUser().isHoistAdmin
});
```

After:
```typescript
ViewManagerModel.create({
    type: 'portfolioGrid',
    enableAutoSave: false
});
```

An explicit `manageGlobal: false` remains useful to hide global view management on a model that
shares its `type` with another.

**Direct `JsonBlobService` callers** - if the app calls `update`, `archive`, `renameGroup`, or
`create` on blobs it does not own, those calls now throw `NotAuthorizedException`. Search for
them:

```bash
grep -rn "jsonBlobService\." grails-app/ src/
```

Server-side code that must write on behalf of a user should pass that user's `username` as the
final argument, or run as a user holding one of the `globalWriteRoles`.

### 3. Replace a `doLoadUsersForDirectoryGroups` override

`DefaultRoleService.doLoadUsersForDirectoryGroups` has been removed. Apps that resolved
directory groups from a custom source by overriding it now provide a `DirectoryService`
implementation and return it from `getDirectoryService`. This routes membership resolution,
group display names, and group search through the same object, fixing the Admin Console errors
those apps saw on the Roles tab.

**Find affected files:**
```bash
grep -rn "doLoadUsersForDirectoryGroups" grails-app/ src/
```

Before - `grails-app/services/{app}/RoleService.groovy`:
```groovy
class RoleService extends DefaultRoleService {

    protected Map<String, ErrorOr<Set<String>>> doLoadUsersForDirectoryGroups(Set<String> groups, boolean strictMode) {
        groups.collectEntries { group ->
            Set<String> users = lookupUsersInCustomSource(group)
            users != null ?
                [group, ErrorOr.of(users)] :
                [group, ErrorOr.error('Directory Group not found')]
        }
    }
}
```

After - move the lookup into a new service implementing `DirectoryService`,
`grails-app/services/{app}/CustomDirectoryService.groovy`:
```groovy
import io.xh.hoist.BaseService
import io.xh.hoist.directory.DirectoryService
import io.xh.hoist.util.ErrorOr

class CustomDirectoryService extends BaseService implements DirectoryService {

    boolean getEnabled() { true }

    String getDirectoryGroupsDescription() { 'Enter a group name from the custom directory.' }

    Map<String, ErrorOr<Set<String>>> loadUsersForDirectoryGroups(Set<String> groups, boolean strictMode) {
        groups.collectEntries { group ->
            Set<String> users = lookupUsersInCustomSource(group)
            users != null ?
                [group, ErrorOr.of(users)] :
                [group, ErrorOr.error('Directory Group not found')]
        }
    }

    Map<String, ErrorOr<Map>> describeDirectoryGroups(Set<String> groups) {
        groups.collectEntries { [it, ErrorOr.of([displayName: it])] }
    }

    List<Map> searchDirectoryGroups(String namePart) {
        []
    }
}
```

Then point `RoleService` at it:
```groovy
import io.xh.hoist.directory.DirectoryService

class RoleService extends DefaultRoleService {

    CustomDirectoryService customDirectoryService

    protected DirectoryService getDirectoryService() {
        customDirectoryService
    }
}
```

To fall back to the custom source only when neither `LdapService` nor `EntraIdService` is
enabled, delegate to `super` first:
```groovy
protected DirectoryService getDirectoryService() {
    def svc = super.getDirectoryService()
    svc.enabled ? svc : customDirectoryService
}
```

See Toolbox's `MockDirectoryService` and `RoleService` for a complete, working example, and
[`directory-services.md`](../directory-services.md) for the interface contract.

### 4. Check `sendEmail` callers for unrecognized argument names

`EmailService.sendEmail` now declares its arguments as Groovy named parameters. Existing calls
that pass a map literal or `key: value` pairs work unchanged. An argument name the method does
not recognize (for example a typo such as `subjet`, or an app-specific key) now throws an
`AssertionError`, and `throwError: false` does not suppress it.

**Find callers:**
```bash
grep -rn "sendEmail(" grails-app/ src/
```

Confirm each call uses only the documented names: `to`, `cc`, `bcc`, `from`, `subject`, `html`,
`text`, `attachments`, `markImportant`, `async`, `doLog`, `logIdentifier`, `throwError`. See
[`email.md`](../email.md) for the full reference.

Also note that a blank `xhEmailOverride` or `xhEmailFilter` entry is now discarded rather than
expanded into a bare `@domain` address, and `sendEmail` throws when no sender address resolves
from `from` or `xhEmailDefaultSender`.

### 5. Optional - drop `defaultValue: [:]` from typed config specs

`ConfigService.ensureRequiredConfigsCreated` now seeds a `ConfigSpec` that declares a
`typedClass` with an empty JSON object when `defaultValue` is omitted. The explicit `[:]`
that v40.5 asked for keeps working, so this is a cleanup.

```bash
grep -rn -B2 -A2 "typedClass:" grails-app/init/
```

Before:
```groovy
new ConfigSpec(
    name: 'myFeatureConfig',
    valueType: 'json',
    defaultValue: [:],
    typedClass: MyFeatureConfig,
    groupName: 'MyApp'
)
```

After:
```groovy
new ConfigSpec(
    name: 'myFeatureConfig',
    valueType: 'json',
    typedClass: MyFeatureConfig,
    groupName: 'MyApp'
)
```

### 6. Optional - authenticate `EntraIdService` with a certificate

Apps using `EntraIdService` can replace the client secret with a certificate via the new
`xhEntraClientPfx` (base64-encoded PKCS#12 bundle holding the certificate, its chain, and its
private key) and `xhEntraClientPfxPassword` configs. When configured, the certificate takes
precedence over `xhEntraClientSecret`. Convert PEM material with:

```bash
openssl pkcs12 -export -in cert.pem -inkey key.pem | base64
```

See [`directory-services.md`](../directory-services.md) for details.

## Verification Checklist

After completing all steps:

- [ ] `./gradlew compileGroovy` succeeds
- [ ] Application starts without errors and `xhJsonBlobConfig` appears in the Admin Console
- [ ] A user with a `globalWriteRoles` role can save, edit, and delete a global view
- [ ] A user without such a role sees only their own views as editable
- [ ] Users receive their expected roles, including directory-group-based memberships
- [ ] The Roles tab in the Admin Console shows group display names without errors
- [ ] Outbound email sends from at least one call site
- [ ] No removed patterns remain: `grep -rn "doLoadUsersForDirectoryGroups" grails-app/ src/`

## Reference

- [`jsonblob.md`](../jsonblob.md) - `JsonBlobService` access control and `xhJsonBlobConfig`
- [`directory-services.md`](../directory-services.md) - `DirectoryService` contract and
  Entra ID configuration
- [`email.md`](../email.md) - `sendEmail` parameter reference
- [`configuration.md`](../configuration.md) - typed config conventions
- [hoist-react v88 changelog](https://github.com/xh/hoist-react/blob/develop/CHANGELOG.md) -
  client-side `manageGlobal` changes
- [Toolbox on GitHub](https://github.com/xh/toolbox) - canonical example of a Hoist app
