# Build and Publish

> **Status: DRAFT** — This document is awaiting review. It may contain inaccuracies or gaps.

## Overview

Hoist-core is built with Gradle and published as the `io.xh:hoist-core` artifact. The project uses
**GitHub Actions** for continuous integration and automated publishing, with **Maven Central** as
the primary public artifact repository (via the Sonatype Central Portal).

The build pipeline supports three GitHub Actions workflows:

- **CI** — Builds on every push and PR to `develop`
- **Snapshot publishing** — Automatically publishes `-SNAPSHOT` builds to Sonatype on every push to
  `develop`. Can also be triggered manually with an optional version override.
- **Release publishing** — Manually triggered workflow that validates the version, builds, signs,
  and publishes a release to Maven Central from the `master` branch, then tags the commit and
  creates a GitHub release

> When code is pushed or merged to `develop`, both the CI and snapshot workflows run. CI validates
> the build; the snapshot workflow additionally publishes the artifact.

## How to Perform a Release

1. Update `xhReleaseVersion` in `gradle.properties` on `develop` to the next snapshot version
   (e.g. `38.0-SNAPSHOT`). Do this first so that subsequent snapshot builds can use this version.
2. Merge `develop` into `master` when ready to release (CI passing, changelog updated)
3. Navigate to **Actions → Deploy Release** in the GitHub repository
4. Click **Run workflow**
5. Enter the branch `master` and release version (e.g. `37.0.0`) — must be semver, must not duplicate an existing tag
6. The workflow validates the version, builds, signs, publishes to Maven Central, tags the commit
   (`vX.Y.Z`), and creates a GitHub release with auto-generated notes
7. Verify the artifact appears on
   [Maven Central](https://central.sonatype.com/artifact/io.xh/hoist-core) and the release
   appears on the repository's [Releases](../../releases) page

## Source Files

| File | Role |
|------|------|
| `build.gradle` | Gradle build config — plugins, dependencies, publishing, and signing |
| `settings.gradle` | Sets `rootProject.name = 'hoist-core'` — ensures the correct artifact name regardless of the checkout directory. Replaces a TeamCity `%projectName%` placeholder that was written as a literal string in GitHub Actions |
| `gradle.properties` | Default version (`xhReleaseVersion`), Grails version, Gradle JVM args |
| `.github/workflows/ci.yml` | CI workflow — build + dependency submission |
| `.github/workflows/deploySnapshot.yml` | Snapshot publishing workflow |
| `.github/workflows/deployRelease.yml` | Release publishing workflow |
| `.github/dependabot.yml` | Dependabot config — monitors workflow action versions weekly |

## Version Numbering

The project version is controlled by the `xhReleaseVersion` property in `gradle.properties`:

```properties
xhReleaseVersion=37.0-SNAPSHOT
```

- **Snapshot builds** use the version as-is from `gradle.properties` (e.g. `37.0-SNAPSHOT`).
  The `-SNAPSHOT` suffix tells Gradle and Sonatype to treat the artifact as a mutable development
  build.
- **Release builds** override this property at build time via the workflow's `xhReleaseVersion`
  input parameter (e.g. `37.0.0`). Release versions must **not** contain the `-SNAPSHOT` suffix.

## JDK Policy: Build on 25, Target 17

hoist-core is compiled with a **JDK 25 toolchain** but targets **Java 17 bytecode**
(`javac --release 17` on Java sources, `targetCompatibility = '17'` on Groovy sources). The
published JAR therefore runs on any JDK 17+ runtime — client apps still on Java 17 are not
forced to upgrade.

**Contributors must not use Java 18+ APIs in hoist-core source code.** The CI build enforces
this with a matrix that also runs the test suite on JDK 17, so accidental use of newer APIs
(e.g. `List.reversed()`, `SequencedCollection`) fails fast on the 17 matrix row.

Toolbox, and most client apps, are separate from this contract — as applications, they are free
to use the full JDK 25 API.

## GitHub Actions Workflows

All workflows run on `ubuntu-latest` with **JDK 25 (Zulu)** as the build toolchain, via the
`gradle/actions/setup-gradle` action for Gradle caching and setup. The CI workflow additionally
runs the build on **JDK 17** in a matrix row to guard the Java-17 bytecode contract.

### CI (`ci.yml`)

**Trigger:** Push or PR to `develop`

Runs two jobs:

1. **build** — Runs `./gradlew build` (compilation and any configured checks)
2. **dependency-submission** — Generates and submits a dependency graph to GitHub, enabling
   **Dependabot Alerts** for all project dependencies

This workflow requires no secrets — it only builds and reports.

### Snapshot Publishing (`deploySnapshot.yml`)

**Trigger:** Push to `develop`, or manual `workflow_dispatch`

The workflow can also be triggered manually via `workflow_dispatch` with an optional
`xhSnapshotVersion` input to override the version in `gradle.properties`. If provided, the
`-SNAPSHOT` suffix is automatically appended if not already present.

Publishes a snapshot build to the Sonatype Maven Central snapshot repository:

```
./gradlew publishToSonatype --no-daemon
```

Snapshots are published directly — they do **not** go through Sonatype's staging/release process
and are not signed. They are available immediately at the Sonatype snapshot repository
(`https://central.sonatype.com/repository/maven-snapshots/`).

> **Note:** Snapshot publishing must be enabled on the Sonatype Central Portal namespace
> (Namespaces → dropdown → "Enable SNAPSHOTs") for this workflow to succeed.

### Release Publishing (`deployRelease.yml`)

**Trigger:** Manual (`workflow_dispatch`) — the operator enters the release version string and
optionally checks the `isHotfix` boolean input to flag a hotfix release.

A job-level branch guard ensures standard releases run from `master`, while hotfix releases must
run from a branch other than `master` or `develop` (e.g. a maintenance branch for an older major
version).

The workflow performs the following steps in order:

1. **Validates** the version input — must be semver (`X.Y.Z`), must not duplicate an existing tag,
   and must be a reasonable increment from the latest release (catches fat-finger errors like
   `38.40.0` instead of `38.4.0`). When `isHotfix` is checked, the version is validated against
   the relevant older major version's tags instead.
2. **Builds and publishes** to Maven Central via Sonatype's staging API:
   ```
   ./gradlew -PxhReleaseVersion="$XH_RELEASE_VERSION" publishToSonatype closeAndReleaseSonatypeStagingRepository --no-daemon
   ```
3. **Tags** the commit as `vX.Y.Z` and pushes the tag to GitHub
4. **Creates a GitHub release** with auto-generated notes from merged PRs since the previous tag.
   Hotfix releases are created without the "latest" flag to avoid displacing the current release.

Once released, the artifact is available on Maven Central as `io.xh:hoist-core:<version>`.

## Gradle Build Configuration

### Plugins

The build uses three plugins for publishing:

| Plugin | Purpose |
|--------|---------|
| `maven-publish` | Gradle's built-in Maven publishing support — defines publications and repositories |
| `signing` | Gradle's built-in artifact signing — GPG signs JARs for Maven Central |
| `io.github.gradle-nexus.publish-plugin` (v2.0.0) | Sonatype Nexus integration — staging, close, and release via the Central Portal API |

The Maven Central publishing configuration was written using:
- [How to Publish a Grails Plugin to the Maven Central Repository](https://grails.apache.org/blog/2021-04-07-publish-grails-plugin-to-maven-central.html)
- [Sample Grails plugin configured for Maven Central](https://github.com/puneetbehl/myplugin/blob/main/build.gradle)

### Publication: `hoistCore`

The `hoistCore` Maven publication is configured in the `publishing` block and includes:

- **Coordinates:** `io.xh:hoist-core:<version>`
- **Components:** The `java` component (compiled classes, sources JAR)
- **Grails plugin descriptor:** An additional artifact (`grails-plugin.xml`) with classifier
  `plugin` — required for Grails plugin resolution
- **POM metadata:** Project name, description, Apache 2.0 license, organization, SCM URLs,
  issue tracker, and developer info — all required by Maven Central

### Published Artifacts

Each publication produces:

| Artifact | Description |
|----------|-------------|
| `hoist-core-<version>.jar` | Compiled classes |
| `hoist-core-<version>-sources.jar` | Source code |
| `hoist-core-<version>-plugin.xml` | Grails plugin descriptor |
| `hoist-core-<version>.pom` | Maven POM with dependency metadata |

For release builds, each artifact also gets a `.asc` GPG signature file.

### Artifact Signing

Release artifacts are signed with GPG to satisfy Maven Central requirements. The signing
configuration uses **in-memory PGP keys** — no keyring file is written to disk:

```groovy
ext.isReleaseVersion = !version.endsWith("SNAPSHOT")
afterEvaluate {
    signing {
        required { isReleaseVersion && gradle.taskGraph.hasTask("publish") }
        def signingKey = findProperty("signingKey") ?: System.getenv("SIGNING_KEY")
        def signingPassword = findProperty("signingPassword") ?: System.getenv("SIGNING_PASSWORD")
        if (signingKey) {
            useInMemoryPgpKeys(signingKey, signingPassword)
        }
        sign publishing.publications.hoistCore
    }
}
tasks.withType(Sign) {
    onlyIf { isReleaseVersion }
}
```

- Signing is **required** for release versions and **skipped** for snapshots
  (the `isReleaseVersion` flag is `true` when the version does not end in `SNAPSHOT`)
- The signing key and password are resolved from Gradle properties first (`signingKey`,
  `signingPassword`), falling back to environment variables (`SIGNING_KEY`, `SIGNING_PASSWORD`)
- The `if (signingKey)` guard prevents failures in environments where no signing key is available
  (e.g. local development or snapshot CI runs) — without a key, in-memory PGP signing is simply
  not configured
- The `tasks.withType(Sign) { onlyIf { isReleaseVersion } }` block provides an additional
  safeguard, ensuring sign tasks are skipped entirely for snapshot builds
- The entire signing block is wrapped in `afterEvaluate` to ensure the publication is fully
  configured before signing is applied
- `SIGNING_KEY` must be the full ASCII-armored GPG private key

### Nexus Publishing (Sonatype)

The `nexusPublishing` block configures the connection to Sonatype's Central Portal:

```groovy
nexusPublishing {
    repositories {
        sonatype {
            nexusUrl = uri "https://ossrh-staging-api.central.sonatype.com/service/local/"
            snapshotRepositoryUrl = uri "https://central.sonatype.com/repository/maven-snapshots/"
        }
    }
}
```

- **Releases** go through the OSSRH staging API — artifacts are uploaded to a staging repository,
  then closed and released to trigger Maven Central sync
- **Snapshots** go directly to the snapshot repository
- Credentials are resolved from Gradle properties first (`sonatypeUsername`, `sonatypePassword`),
  falling back to environment variables (`SONATYPE_USERNAME`, `SONATYPE_PASSWORD`)

## Required GitHub Secrets

The following secrets must be configured in the GitHub repository settings:

| Secret | Used By | Description |
|--------|---------|-------------|
| `SONATYPE_USERNAME` | Snapshot + Release | Sonatype Central Portal username |
| `SONATYPE_PASSWORD` | Snapshot + Release | Sonatype Central Portal password/token |
| `SIGNING_KEY` | Release only | ASCII-armored GPG private key for artifact signing |
| `SIGNING_PASSWORD` | Release only | Passphrase for the GPG signing key |

## Consuming the Artifact

Application projects that depend on hoist-core declare it in their `build.gradle`:

```groovy
dependencies {
    implementation 'io.xh:hoist-core:<version>'
}
```

To resolve the artifact, the app's `repositories` block must include Maven Central (for releases)
and/or the Sonatype snapshot repository (for snapshot builds):

```groovy
repositories {
    mavenCentral()
    // For snapshot builds:
    maven { url = 'https://central.sonatype.com/repository/maven-snapshots/' }
}
```

Apps that still resolve from the legacy repository use:

```groovy
repositories {
    maven { url = 'https://repo.xh.io/content/groups/public/' }
}
```

