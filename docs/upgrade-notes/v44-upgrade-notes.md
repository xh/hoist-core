# Hoist Core v44 Upgrade Notes

> **From:** v43.x → v44.0.0 | **Released:** 2026-10-29 | **Difficulty:** 🟢 LOW

## Overview

Hoist Core v44 is a dependency cleanup release. It removes three dependencies that hoist-core no
longer uses, but which apps could have used through hoist-core's `api` scope. Most apps need only
the version bump. An app needs more changes only if it uses one of these dependencies directly.

The most significant app-level impacts are:

- **`jasypt` removed** - Hoist Core now uses its own JDK and Spring Security based encryptors. Apps
  that store local user passwords with jasypt's `BasicPasswordEncryptor` must switch to
  `io.xh.hoist.security.HoistPasswordEncryptor`. Existing password hashes continue to verify.
- **`grails-quartz` removed** - Apps that use the Grails Quartz plugin must declare it themselves.
- **Apache Directory API reduced to client-only artifacts** - The `api-all` bundle is replaced by
  `api-ldap-client-api`. Apps that use Directory API modules outside the LDAP client, for example
  DSMLv2, must declare those modules themselves.

Encrypted `pwd` soft configs need no change. Hoist Core now encrypts them with its own
`HoistTextEncryptor`, which reads and writes the same format as jasypt with the same key.

There are no database schema changes in this release.

## Prerequisites

Before starting, ensure:

- [ ] **The app is on hoist-core v43.x** - see the [v43 upgrade notes](./v43-upgrade-notes.md).

## Upgrade Steps

### 1. Bump `hoistCoreVersion`

**File:** `gradle.properties`

Before:
```properties
hoistCoreVersion=43.0.1
```

After:
```properties
hoistCoreVersion=44.0.0
```

Then find any direct use of the three removed dependencies. The steps below cover each one. If
these commands find nothing, the upgrade is complete after this step.

```bash
grep -rn "org.jasypt" grails-app/ src/
grep -rln "org.quartz\|grails.plugins.quartz" grails-app/ src/
grep -rn "org.apache.directory.api\|org.xmlpull" grails-app/ src/
```

### 2. Replace jasypt's `BasicPasswordEncryptor` with `HoistPasswordEncryptor`

An app that stores hashed local user passwords usually does this in a `User` (or `AppUser`)
domain class. The class holds a static jasypt `BasicPasswordEncryptor` and calls
`encryptPassword` and `checkPassword` on it. `HoistPasswordEncryptor` has the same constructor and
the same two methods, so change only the import and the type.

**File:** `grails-app/domain/{your/package}/User.groovy`

Before:
```groovy
import org.jasypt.util.password.BasicPasswordEncryptor

class User implements HoistUser {
    private static encryptor = new BasicPasswordEncryptor()
```

After:
```groovy
import io.xh.hoist.security.HoistPasswordEncryptor

class User implements HoistUser {
    private static encryptor = new HoistPasswordEncryptor()
```

`checkPassword` verifies both new BCrypt hashes and existing jasypt hashes, so users do not need to
reset their passwords. Toolbox made this change in its `User` domain class.

Two points to check:

- **Column width** - `encryptPassword` now writes BCrypt hashes, which are 60 characters long.
  The old jasypt hashes were 32 characters. If the app sets `maxSize` or a column length below 60
  on the `password` field, increase it. The Grails default (`varchar(255)`) is sufficient.
- **Rollback** - Users who set a password after the upgrade get a BCrypt hash. A pre-v44 build of
  the app cannot verify a BCrypt hash, so those users cannot log in if you roll the app back.

If the app uses other jasypt classes, for example a text encryptor, replace them with JDK or
Spring Security Crypto (`org.springframework.security.crypto`) code. Hoist Core now brings in
Spring Security Crypto. `io.xh.hoist.security.HoistTextEncryptor` is also available. It reads and
writes the jasypt `BasicTextEncryptor` format.

### 3. Declare `grails-quartz` if the app uses Quartz jobs

Hoist Core no longer brings in the Grails Quartz plugin. An app that has job classes in
`grails-app/jobs/`, or that imports `org.quartz.*` or `grails.plugins.quartz.*`, must declare the
plugin itself. The Grails BOM supplies the version.

**File:** `build.gradle`

```groovy
dependencies {
    implementation "org.apache.grails:grails-quartz"
}
```

Hoist Core never used Quartz itself. Timers made with `BaseService.createTimer()` are not affected.

### 4. Declare any Directory API modules beyond the LDAP client

Hoist Core now depends on `org.apache.directory.api:api-ldap-client-api`, not on the `api-all`
bundle. The LDAP client, the LDAP model (`org.apache.directory.api.ldap.model.*`), and the standard
codecs and schema data are all still on the classpath. Apps that use `LdapNetworkConnection` or
the other LDAP client classes directly need no change.

The removed parts are the DSMLv2 engine (`org.apache.directory.api.dsmlv2.*`), the other modules
outside the LDAP client, and the `xpp3` XML parser (`org.xmlpull.*`). If step 1 found imports
from these packages, declare the matching artifact at the version that hoist-core uses.

**File:** `build.gradle`

```groovy
dependencies {
    // Example - only if the app uses the DSMLv2 engine
    implementation "org.apache.directory.api:api-dsml-parser:2.1.9"
}
```

`dom4j` stays on the classpath, because Hibernate depends on it.

## Verification Checklist

After you complete all steps:

- [ ] `./gradlew compileGroovy` succeeds
- [ ] The application starts without errors
- [ ] The Admin Console loads and works
- [ ] Authentication works (login and logout)
- [ ] An existing local user can log in with their current password (if the app stores local
  passwords)
- [ ] A new or changed password is saved as a 60-character hash that starts with `$2`, and that
  user can log in
- [ ] A `pwd` soft config still decrypts correctly. For example, a feature that uses a stored
  credential still works.
- [ ] Quartz jobs still run on schedule (if the app uses Quartz)
- [ ] LDAP or Entra ID directory group lookups still resolve (if the app uses them)
- [ ] No jasypt references remain: `grep -rn "org.jasypt" grails-app/ src/`

## Reference

- [Toolbox on GitHub](https://github.com/xh/toolbox) - canonical example of a Hoist app
