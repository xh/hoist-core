# Hoist Core v41 Upgrade Notes

> **From:** v40.x → v41.0 | **Released:** TBD | **Difficulty:** 🟢 LOW (unless your app stores
> local user passwords — then 🟡 MEDIUM, ~5 LoC change per User domain class)

## Overview

Hoist Core v41 removes the `org.jasypt:jasypt:1.9.3` dependency. Jasypt 1.9.3 was the last
release of the library (published 2014) and it breaks at runtime on JDK 21+ under Spring Boot's
launcher classloader: any call into `BasicPasswordEncryptor.encryptPassword` or
`BasicTextEncryptor.encrypt` throws
`org.jasypt.exceptions.EncryptionInitializationException: Could not perform a valid UNICODE
normalization` — a reflection failure inside its `Normalizer` wrapper around the JDK's
`java.text.Normalizer`. The standard `--add-opens` JVM flags do not resolve it (the bug is in
jasypt's reflective code path, not the JDK module's accessibility), making the library
effectively unusable on modern JDKs.

Two surfaces are affected:

1. **Internal:** `AppConfig` used jasypt for both symmetric encryption of `pwd`-typed config
   values at rest and a one-way digest used by the admin UI's config-differ. These have moved to
   pure-JDK implementations (AES-256-GCM + PBKDF2 and deterministic SHA-256, respectively) — no app
   action required, and **no DB migration needed**: existing encrypted `pwd` values continue to
   decrypt transparently via a one-release `LegacyJasyptDecrypter` shim.
2. **App-facing:** Apps that store local user passwords historically imported
   `org.jasypt.util.password.BasicPasswordEncryptor` directly in their `User` (or `AppUser`)
   domain class — this worked because hoist-core re-exported jasypt via `api` scope. That
   transitive dependency is gone. Apps must switch to the new
   `io.xh.hoist.security.HoistPasswordEncoder` (~5 LoC change, see below).

There are no database schema changes in this release. Existing user passwords stored under the
legacy jasypt-default format continue to authenticate without a forced reset — the new encoder's
`matches()` transparently verifies both new BCrypt hashes and legacy jasypt-format hashes.

## Prerequisites

Before starting, ensure:

- [ ] Running hoist-core v40.x (no special intermediate version needed)
- [ ] Your build can resolve `org.springframework.security:spring-security-crypto` (version
      managed by the Spring Boot 3.5.x BOM your app already inherits via hoist-core — should
      resolve automatically once hoist-core v41 is on your classpath)

## Upgrade Steps

### 1. Bump `hoistCoreVersion` in `gradle.properties`

```properties
hoistCoreVersion=41.0
```

Run `./gradlew assemble` (or your app's equivalent) to pull the new dependency graph.
`org.jasypt:jasypt:1.9.3` is no longer on hoist-core's `api` classpath — any direct imports of
`org.jasypt.*` from your app code will fail to compile after this step. The next two steps
handle the common case.

### 2. Update your `User` (or `AppUser`) domain class to use `HoistPasswordEncoder`

If your app has a domain class that stores hashed local-user passwords, find it under
`grails-app/domain/.../user/` (commonly named `User.groovy` or `AppUser.groovy`). It typically
contains a static jasypt `BasicPasswordEncryptor` instance:

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

`HoistPasswordEncoder.matches()` transparently verifies both new BCrypt hashes (written by
`HoistPasswordEncoder.encode`) and legacy jasypt-format hashes (written by older versions of
your app), so existing user records continue to authenticate without intervention.

### 3. (Optional, recommended) Migrate-on-login for legacy user hashes

Existing users will keep authenticating via the legacy verification path indefinitely. To
gradually re-hash them with BCrypt as they log in, add a post-authentication hook in your
`AuthenticationService` (or wherever `User.checkPassword` is called):

```groovy
class AuthenticationService extends BaseAuthenticationService {

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
}
```

This is purely housekeeping — the only behavioural difference is that once a user has logged in
post-upgrade, their stored hash transitions from MD5+8-byte-salt (jasypt default) to BCrypt
(industry standard). Adding this hook is encouraged but not required for the upgrade itself.

### 4. (Internal, no action) `pwd` config values

`AppConfig` continues to read and write `pwd`-type values transparently. Values written before
this upgrade (Base64-encoded PBEWithMD5AndDES under jasypt's `BasicTextEncryptor`) decrypt
through a `LegacyJasyptDecrypter` shim; values written after the upgrade use AES-256-GCM with a
`$hoist-aes1$` marker prefix. Re-saving any `pwd` config from the admin UI upgrades that record
to the new format. No mass migration is needed; the shim can be removed in a future major
version once all known clients have rolled forward.

The hard-coded `AppConfig` obfuscation key (`CONFIG_VALUE_OBFUSCATION_KEY` in source) is
unchanged from prior releases, so existing `pwd` ciphertexts decrypt without action. As
before, this key is at-rest obfuscation for low-sensitivity admin-UI display, not a
confidentiality boundary — anyone with source access can decrypt `pwd` values from a DB dump.
Real secrets belong in instance config / env vars / a dedicated secrets manager.

### 5. Verify and ship

After steps 1–2, your build should compile cleanly with no remaining `import org.jasypt.*`
references in app code. Boot the app — startup that previously failed on `BootStrap` insertion
of users / `pwd`-typed configs (the symptom that originally surfaced this bug) should now
succeed. Existing local-user logins continue to work via the legacy verification path; new
user records and `pwd` configs are written in the new formats.

## Background — why this change

The Unicode-normalization failure originates in `org.jasypt.normalization.Normalizer.normalizeWithJavaNormalizer`
(jasypt 1.9.3), which reflectively invokes `java.text.Normalizer.normalize`. Under the
`LaunchedURLClassLoader` Spring Boot uses for bootRun (and any packaged Spring Boot app), the
reflective invocation throws an `InaccessibleObjectException` / `IllegalAccessException` on
JDK 21+. The same call from a standalone JVM (`java -cp jasypt.jar`) on the same JDK works
fine — making this specifically a Spring Boot + JDK 21+ + jasypt 1.9.3 triple-point failure.
Because jasypt has had no release in over a decade and `jasypt-spring-boot` (an unrelated
community shim) still depends on the same broken `jasypt-1.9.3` artifact, the only durable fix
is to remove the dependency.

The replacements (`HoistPasswordEncoder` / `AesTextCipher` / `ConfigValueDigester`) prefer
algorithms that are JDK-bundled (PBKDF2, SHA-256, AES-GCM) or Spring-supported (BCrypt) and have
clear migration paths for legacy data.
