# Hoist Core v41 Upgrade Notes

> **From:** v40.x → v41.0 | **Released:** TBD | **Difficulty:** 🟢 LOW for most apps; 🟡 MEDIUM
> if you store local user passwords (~5 LoC change per User domain class) or write `pwd`-typed
> `AppConfig` values (one new instance config to set)

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

Three surfaces are affected:

1. **App-facing — `User` domain classes (if present):** Apps that store local user passwords
   historically imported `org.jasypt.util.password.BasicPasswordEncryptor` directly in their
   `User` (or `AppUser`) domain class — this worked because hoist-core re-exported jasypt via
   `api` scope. That transitive dependency is gone. Apps must switch to the new
   `io.xh.hoist.security.HoistPasswordEncoder` (~5 LoC change, see below).
2. **App-facing — `pwd`-typed `AppConfig` values (if used):** Pre-v41 `pwd` values were
   obfuscated under a hardcoded key checked into the open-source hoist-core repo. v41 fixes
   that. Apps that write `pwd` configs must now supply their own high-entropy encryption key
   via instance config `appConfigCryptoKey` (typically an env var or YAML entry sourced from a
   secrets manager). Pre-v41 values continue to decrypt for read via a one-release migration
   shim; writes fail closed until the key is configured. No DB migration is required.
3. **Internal:** `AppConfig`'s admin Config Diff digest moved from jasypt's salted MD5 to a
   pure-JDK deterministic SHA-256 of plaintext. Stable across environments, gated to
   `HOIST_ADMIN_READER` (same trust boundary as the plaintext read path).

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

### 4. Configure `appConfigCryptoKey` (required if you use `pwd`-typed `AppConfig` values)

Pre-v41 hoist-core obfuscated `pwd` config values at rest using a hardcoded key checked into
its own open-source source tree — adequate to mask values in the admin UI and DB-dump output,
but not real confidentiality. v41 fixes this: `pwd` writes now use AES-256-GCM under an
**app-supplied** key sourced from instance config. The hardcoded key from prior releases
remains in source for one role only — letting the new `LegacyJasyptDecrypter` read pre-v41
`pwd` values during the upgrade window.

**Generate a key** (any high-entropy string — 32+ random characters is ample):

```bash
# example: 48 random Base64 characters
openssl rand -base64 36
```

Store it in your secrets manager (Vault, AWS Secrets Manager, 1Password, etc.) and inject it
into every app instance via the standard hoist instance-config channels:

- **Environment variable:** `APP_<APPCODE>_APP_CONFIG_CRYPTO_KEY=<your-key>` (recommended for
  most deployments). The `<APPCODE>` segment matches your `appCode` in `application.groovy`,
  upper-snake-cased.
- **YAML:** add `appConfigCryptoKey: <your-key>` to `/etc/hoist/conf/<appCode>.yml` or
  whichever path your `-Dio.xh.hoist.instanceConfigFile` JavaOpt points at.

If `appConfigCryptoKey` is not configured:

- **Reads of pre-v41 `pwd` values continue to work** (via the legacy shim) — no boot-time
  failure, no impact on apps that don't use `pwd` configs.
- **Writes of `pwd` values fail closed** with an `IllegalStateException` naming the missing
  instance config. The admin save attempt surfaces this back to the operator.

**Migrating pre-v41 values to the new key:** existing `pwd` rows stay in the legacy format
until something rewrites them. Re-saving a `pwd` config from the admin UI triggers
re-encryption under the new key; for larger config sets a one-off Grails script that reads
each `pwd` `AppConfig` via `configService` and immediately re-saves it is straightforward.

> ⚠️ **Do not change `appConfigCryptoKey` once `pwd` values have been written under it.**
> Doing so will leave any v41+-format rows unreadable — the new key cannot decrypt content
> written under the old key. If you must rotate, decrypt all `pwd` configs to plaintext under
> the current key, change the env var, restart, and re-save each one (or run a migration
> script that performs the decrypt-with-old / encrypt-with-new sequence in a single pass before
> the restart).

### 5. Verify and ship

After steps 1–2, your build should compile cleanly with no remaining `import org.jasypt.*`
references in app code. After step 4, `appConfigCryptoKey` is available on every running
instance (if you use `pwd` configs). Boot the app — startup that previously failed on
`BootStrap` insertion of users / `pwd`-typed configs (the symptom that originally surfaced
this bug) should now succeed. Existing local-user logins continue to work via the legacy
verification path; new user records and (re-saved) `pwd` configs are written in the new
formats.

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

This release also closes a long-standing weakness in the `pwd`-typed `AppConfig` story. Prior
versions encrypted those values under a fixed key hardcoded into hoist-core's own open-source
source — effective at masking values in the admin UI but trivially reversible by anyone with
access to the public source tree. v41 moves the encryption key out of source and into
operator-controlled instance config (`appConfigCryptoKey`), restoring real confidentiality at
rest for apps that supply a high-entropy key. The old hardcoded key remains in source for one
role only: powering the `LegacyJasyptDecrypter` read path that lets pre-v41 ciphertexts be
loaded one final time and re-saved under the new key.
