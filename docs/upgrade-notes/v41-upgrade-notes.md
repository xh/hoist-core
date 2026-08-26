# Hoist Core v41 Upgrade Notes

> **From:** v40.x → v41.0.0 | **Released:** unreleased | **Difficulty:** 🟢 LOW

## Overview

Hoist Core v41 expands the framework's corporate directory integration. A new `EntraIdService`
queries Microsoft Entra ID via the Microsoft Graph API, as an opt-in alternative to
`LdapService`. Both services implement a new `DirectoryService` interface, and
`DefaultRoleService` selects an enabled implementation to resolve directory-group role
memberships. See [`directory-services.md`](../directory-services.md) for full documentation.

The most significant app-level impacts are:

- **`doLoadUsersForDirectoryGroups` returns typed `ErrorOr` results** - the one breaking
  change. Apps that override this `DefaultRoleService` method (or call it directly) require a
  small mechanical update. Apps that do not can upgrade with no code changes.
- **Entra ID directory groups** - apps whose corporate directory lives in Entra ID can now
  resolve role memberships from Entra ID groups, without LDAP connectivity.

Apps that use `LdapService` today do not need to change their configs or role data.

There are no database schema changes in this release.

## Prerequisites

Before starting, make sure:

- [ ] The app builds and runs on hoist-core v40.x.
- [ ] Optional - to show the new Roles admin features (directory group display names and
      search), pair with `@xh/hoist >= 87.0`. Older clients degrade gracefully.

## Upgrade Steps

### 1. Update the hoist-core version

**File:** `gradle.properties`

```properties
hoistCoreVersion=41.0.0
```

### 2. Update `doLoadUsersForDirectoryGroups` overrides

The `DefaultRoleService.doLoadUsersForDirectoryGroups` extension point now returns
`Map<String, ErrorOr<Set<String>>>`. Each entry holds either the resolved usernames or a
String error description, in a typed `ErrorOr` holder, instead of the previous untyped
`Set`-or-`String` value. Wrap returned values with the `ErrorOr.of()` / `ErrorOr.error()`
factories.

**Find affected files:**
```bash
grep -r "doLoadUsersForDirectoryGroups\|loadUsersForDirectoryGroups" grails-app/
```

**File:** `grails-app/services/{app}/RoleService.groovy` (or wherever the app overrides it)

Before:
```groovy
protected Map<String, Object> doLoadUsersForDirectoryGroups(Set<String> groups, boolean strictMode) {
    groups.collectEntries { group ->
        Set<String> users = lookupUsersInCustomSource(group)
        users != null ?
            [group, users] :
            [group, 'Directory Group not found']
    }
}
```

After:
```groovy
import io.xh.hoist.util.ErrorOr

protected Map<String, ErrorOr<Set<String>>> doLoadUsersForDirectoryGroups(Set<String> groups, boolean strictMode) {
    groups.collectEntries { group ->
        Set<String> users = lookupUsersInCustomSource(group)
        users != null ?
            [group, ErrorOr.of(users)] :
            [group, ErrorOr.error('Directory Group not found')]
    }
}
```

If the app *calls* `loadUsersForDirectoryGroups` and checks results with `instanceof Set`,
read the `ErrorOr` properties instead:

Before:
```groovy
lookup.each { group, result ->
    if (result instanceof Set) handleUsers(group, result)
    else logError('Group failed to resolve', group, result)
}
```

After:
```groovy
lookup.each { group, result ->
    if (result.success) handleUsers(group, result.value)
    else logError('Group failed to resolve', group, result.error)
}
```

See Toolbox's `RoleService` for a complete, working override migrated to the new contract.

### 3. Optional - adopt `AppConfig.NONE` in config declarations

The placeholder value that Hoist bootstraps into a config an app may leave unset is now available
as the `AppConfig.NONE` constant. The bare `'none'` string keeps working, so this step is a cleanup
rather than a required change - but the constant removes a class of typo that leaves a config
reading as set when it is not.

Find bare placeholder strings in the app's own config declarations:

```bash
grep -rnE "defaultValue: *['\"]none['\"]" grails-app/
```

**Before:**

```groovy
new ConfigSpec(
    name: 'myApiKey',
    valueType: 'pwd',
    defaultValue: 'none',
    groupName: 'MyApp'
)
```

**After** - add `import io.xh.hoist.config.AppConfig` to the app's `BootStrap.groovy`:

```groovy
new ConfigSpec(
    name: 'myApiKey',
    valueType: 'pwd',
    defaultValue: AppConfig.NONE,
    groupName: 'MyApp'
)
```

Then check the read side, where the new `getStringIfSet` / `getPwdIfSet` getters replace the
comparison entirely:

```bash
grep -rn "== 'none'" grails-app/ src/
```

**Before:**

```groovy
String apiKey = configService.getPwd('myApiKey')
if (!apiKey || apiKey == 'none') throw new RuntimeException('myApiKey not configured')
```

**After:**

```groovy
String apiKey = configService.getPwdIfSet('myApiKey')
if (!apiKey) throw new RuntimeException('myApiKey not configured')
```

See [`configuration.md`](../configuration.md) for the full convention.

### 4. Optional - resolve directory groups from Entra ID

Apps whose corporate directory lives in Entra ID can enable the new `EntraIdService` and
retire their LDAP connectivity. This requires an Entra ID app registration with
admin-consented Graph application permissions, plus the new `xhEntraIdConfig`,
`xhEntraTenantId`, `xhEntraClientId`, and `xhEntraClientSecret` configs. Note that roles store
provider-specific group identifiers - a switch from LDAP requires re-entry of assigned
directory groups as Entra ID object IDs.

See [`directory-services.md`](../directory-services.md) for configuration details, provider
selection via `xhRoleModuleConfig.directoryGroupProvider`, and common pitfalls.

## Verification Checklist

After completing all steps:

- [ ] `./gradlew compileGroovy` succeeds
- [ ] Application starts without errors
- [ ] Users receive their expected roles, including directory-group-based memberships
- [ ] The Roles tab in the Admin Console loads and shows effective members
- [ ] For apps with a form-based login backup: `login()` still authenticates
- [ ] No untyped results remain: `grep -r "instanceof Set" grails-app/` (in role-related code)

## Reference

- [`directory-services.md`](../directory-services.md) - full directory services documentation
- [`authorization.md`](../authorization.md) - role management and directory group integration
- [Toolbox on GitHub](https://github.com/xh/toolbox) - canonical example of a Hoist app
