# Hoist Core v44 Upgrade Notes

> **From:** v43.x → v44.0 | **Released:** TBD | **Difficulty:** 🟢 LOW - no changes for most
> apps; a one-line import swap if your app stores local user passwords

## Overview

Hoist Core v44 removes the `org.jasypt:jasypt:1.9.3` dependency. Jasypt's last release was in
2014, there is no successor version, and its heavy use of reflection makes it a liability on each
new JDK. Hoist-core used it for two things, both now handled by JDK or Spring Security code:

1. **App-facing - `User` domain classes (if present):** Apps that store local user passwords
   historically imported `org.jasypt.util.password.BasicPasswordEncryptor` directly in their
   `User` (or `AppUser`) domain class, which worked because hoist-core re-exported jasypt via
   `api` scope. That transitive dependency is gone. Apps must switch to the new
   `io.xh.hoist.security.HoistPasswordEncryptor`, which has the same constructor and methods, so
   only the import changes (see below). Existing hashes continue to verify, so no user needs to
   reset a password.
2. **Internal - `pwd`-typed `AppConfig` values:** Now encrypted by hoist-core's own
   `HoistTextEncryptor`, which reads and writes the same format jasypt did under the same key.
   Existing values are unaffected, no configuration or DB migration is required, and apps that
   don't store local user passwords need no code changes at all.

There are no database schema changes in this release.

## Upgrade Steps

### 1. Bump `hoistCoreVersion` in `gradle.properties`

```properties
hoistCoreVersion=44.0
```

`org.jasypt:jasypt:1.9.3` is no longer on hoist-core's `api` classpath, so any direct imports of
`org.jasypt.*` in your app will fail to compile. Run `grep -r org.jasypt` over your app source to
find them. The next step covers the common case.

### 2. Swap the import in your `User` (or `AppUser`) domain class

If your app has a domain class that stores hashed local-user passwords, it typically holds a
static jasypt `BasicPasswordEncryptor` and calls `encryptPassword` and `checkPassword` on it.
`HoistPasswordEncryptor` has the same constructor and the same two methods, so replace the import
and leave the rest of the class as it is:

```groovy
// Before
import org.jasypt.util.password.BasicPasswordEncryptor
private static encryptor = new BasicPasswordEncryptor()

// After
import io.xh.hoist.security.HoistPasswordEncryptor
private static encryptor = new HoistPasswordEncryptor()
```

`checkPassword` verifies both new BCrypt hashes (written by `encryptPassword`) and existing
jasypt-format hashes (written by older versions of your app), so existing user records continue to
authenticate without intervention.

### 3. Verify

Your build should compile with no remaining `import org.jasypt.*` references. Existing local-user
logins continue to work, new user records are written as BCrypt hashes, and `pwd` configs read
and write exactly as before.
