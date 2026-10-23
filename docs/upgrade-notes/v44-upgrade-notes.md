# Hoist Core v44 Upgrade Notes

> **From:** v43.x → v44.0 | **Released:** TBD | **Difficulty:** 🟢 LOW - no changes for most
> apps; ~5 LoC per `User` domain class if your app stores local user passwords

## Overview

Hoist Core v44 removes the `org.jasypt:jasypt:1.9.3` dependency. Jasypt's last release was in
2014, there is no successor version, and its heavy use of reflection makes it a liability on each
new JDK. Hoist-core used it for two things, both now handled by JDK or Spring Security code:

1. **App-facing - `User` domain classes (if present):** Apps that store local user passwords
   historically imported `org.jasypt.util.password.BasicPasswordEncryptor` directly in their
   `User` (or `AppUser`) domain class, which worked because hoist-core re-exported jasypt via
   `api` scope. That transitive dependency is gone. Apps must switch to the new
   `io.xh.hoist.security.HoistPasswordEncoder` (see below). Existing hashes continue to verify, so
   no user needs to reset a password.
2. **Internal - `pwd`-typed `AppConfig` values:** Now encrypted with AES-256-GCM instead of
   jasypt's PBE-MD5-DES, under the same key as before. Pre-v44 values still decrypt and upgrade in
   place when re-saved. No configuration or DB migration is required, and apps that don't store
   local user passwords need no code changes at all.

There are no database schema changes in this release.

## Upgrade Steps

### 1. Bump `hoistCoreVersion` in `gradle.properties`

```properties
hoistCoreVersion=44.0
```

`org.jasypt:jasypt:1.9.3` is no longer on hoist-core's `api` classpath, so any direct imports of
`org.jasypt.*` in your app will fail to compile. Run `grep -r org.jasypt` over your app source to
find them. The next step covers the common case.

### 2. Update your `User` (or `AppUser`) domain class to use `HoistPasswordEncoder`

If your app has a domain class that stores hashed local-user passwords, it typically contains a
static jasypt `BasicPasswordEncryptor` instance:

**Before:**

```groovy
import org.jasypt.util.password.BasicPasswordEncryptor

class User {
    String username
    String password
    // ...

    private static encryptor = new BasicPasswordEncryptor()

    boolean checkPassword(String plain) {
        password ? encryptor.checkPassword(plain, password) : false
    }

    def beforeInsert() { encodePassword() }
    def beforeUpdate() { if (isDirty('password')) encodePassword() }

    private void encodePassword() {
        password = password ? encryptor.encryptPassword(password) : null
    }
}
```

**After:**

```groovy
import io.xh.hoist.security.HoistPasswordEncoder

class User {
    String username
    String password
    // ...

    boolean checkPassword(String plain) {
        HoistPasswordEncoder.matches(plain, password)
    }

    def beforeInsert() { encodePassword() }
    def beforeUpdate() { if (isDirty('password')) encodePassword() }

    private void encodePassword() {
        password = HoistPasswordEncoder.encode(password)
    }
}
```

`HoistPasswordEncoder.matches()` verifies both new BCrypt hashes (written by
`HoistPasswordEncoder.encode`) and legacy jasypt-format hashes (written by older versions of your
app), so existing user records continue to authenticate without intervention.

### 3. (Optional) Migrate legacy user hashes on login

Existing users will keep authenticating via the legacy verification path indefinitely. To
gradually re-hash them with BCrypt as they log in, re-encode after a successful check wherever
`User.checkPassword` is called:

```groovy
boolean authenticate(String username, String plain) {
    def user = User.findByUsername(username)
    if (!user?.checkPassword(plain)) return false

    // Opportunistically re-encode legacy hashes once we know the plaintext.
    if (HoistPasswordEncoder.isLegacyHash(user.password)) {
        user.password = plain  // beforeUpdate will hash via HoistPasswordEncoder
        user.save(flush: true)
    }
    return true
}
```

This moves stored hashes from jasypt's default MD5 with an 8-byte salt to BCrypt. It is
encouraged but not required for the upgrade.

### 4. Verify

Your build should compile with no remaining `import org.jasypt.*` references. Existing local-user
logins continue to work; new user records are written as BCrypt hashes, and `pwd` configs are
re-encrypted in the new format the next time they are saved from the Admin Console.
