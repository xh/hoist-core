# Build and Publish

> **Status: DRAFT** — This document is awaiting review. It may contain inaccuracies or gaps.

## Overview

Hoist-core is built with Gradle and published as the `io.xh:hoist-core` artifact. The project uses
**GitHub Actions** for continuous integration and automated publishing, with **Maven Central** as
the primary public artifact repository (via the Sonatype Central Portal).

The build pipeline supports three workflows:

- **CI** — Compiles and tests on every push and PR to `develop`
- **Snapshot publishing** — Automatically publishes `-SNAPSHOT` builds to Sonatype on every push to
  `develop`
- **Release publishing** — Manually triggered workflow that builds, signs, and publishes a release
  version to Maven Central

A legacy `repo.xh.io` publishing path is also retained for internal use.

## Source Files

| File | Role |
|------|------|
| `build.gradle` | Gradle build config — plugins, dependencies, publishing, and signing |
| `settings.gradle` | Sets `rootProject.name = 'hoist-core'` (required for correct artifact naming in CI) |
| `gradle.properties` | Default version (`xhReleaseVersion`), Grails version, Gradle JVM args |
| `.github/workflows/gradle.yml` | CI workflow — build + dependency submission |
| `.github/workflows/deploySnapshot.yml` | Snapshot publishing workflow |
| `.github/workflows/deployRelease.yml` | Release publishing workflow |

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

## GitHub Actions Workflows

All three workflows run on `ubuntu-latest` with **JDK 17 (Zulu)** and use the
`gradle/actions/setup-gradle` action for Gradle caching and setup.

### CI (`gradle.yml`)

**Trigger:** Push or PR to `develop`

Runs two jobs:

1. **build** — Compiles and runs `./gradlew build` (compiles, runs tests/checks)
2. **dependency-submission** — Generates and submits a dependency graph to GitHub, enabling
   **Dependabot Alerts** for all project dependencies

This workflow requires no secrets — it only builds and reports.

### Snapshot Publishing (`deploySnapshot.yml`)

**Trigger:** Push to `develop` (runs after every merge)

Publishes a snapshot build to the Sonatype Maven Central snapshot repository:

```
./gradlew publishToSonatype --no-daemon
```

Snapshots are published directly — they do **not** go through Sonatype's staging/release process
and are not signed. They are available immediately at:

```
https://central.sonatype.com/repository/maven-snapshots/
```

**Required secrets:** `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`

> **Note:** Snapshot publishing must be enabled on the Sonatype Central Portal namespace
> (Namespaces → dropdown → "Enable SNAPSHOTs") for this workflow to succeed.

### Release Publishing (`deployRelease.yml`)

**Trigger:** Manual (`workflow_dispatch`) — the operator enters the release version string

Builds, signs, and publishes a release to Maven Central via Sonatype's staging API:

```
./gradlew -PxhReleaseVersion="$XH_RELEASE_VERSION" publishToSonatype closeAndReleaseSonatypeStagingRepository --no-daemon
```

This command:

1. **Builds** the project with the specified release version (overriding `gradle.properties`)
2. **Publishes** the artifacts to a Sonatype staging repository
3. **Closes and releases** the staging repository, which triggers Maven Central sync

Once released, the artifact is available on Maven Central as `io.xh:hoist-core:<version>`.

**Required secrets:** `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `SIGNING_KEY`, `SIGNING_PASSWORD`

## Gradle Build Configuration

### Plugins

The build uses three plugins for publishing:

| Plugin | Purpose |
|--------|---------|
| `maven-publish` | Gradle's built-in Maven publishing support — defines publications and repositories |
| `signing` | Gradle's built-in artifact signing — GPG signs JARs for Maven Central |
| `io.github.gradle-nexus.publish-plugin` (v2.0.0) | Sonatype Nexus integration — staging, close, and release via the Central Portal API |

### Publication: `hoistCore`

The `hoistCore` Maven publication is configured in the `publishing` block and includes:

- **Coordinates:** `io.xh:hoist-core:<version>`
- **Components:** The `java` component (compiled classes, sources JAR, Javadoc JAR)
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
| `hoist-core-<version>-javadoc.jar` | Javadoc (Maven Central best practice) |
| `hoist-core-<version>-plugin.xml` | Grails plugin descriptor |
| `hoist-core-<version>.pom` | Maven POM with dependency metadata |

For release builds, each artifact also gets a `.asc` GPG signature file.

### Artifact Signing

Release artifacts are signed with GPG to satisfy Maven Central requirements. The signing
configuration uses **in-memory PGP keys** — no keyring file is written to disk:

```groovy
signing {
    required { isReleaseVersion && gradle.taskGraph.hasTask("publish") }
    useInMemoryPgpKeys(signingKey, signingPassword)
    sign publishing.publications.hoistCore
}
```

- Signing is **required** for release versions and **skipped** for snapshots
- The signing key and password are read from environment variables (`SIGNING_KEY`,
  `SIGNING_PASSWORD`) or Gradle properties (`signingKey`, `signingPassword`)
- `SIGNING_KEY` must be the ASCII-armored GPG private key

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
- **Snapshots** go directly to the snapshot repository — no staging or signing required
- Credentials are read from environment variables (`SONATYPE_USERNAME`, `SONATYPE_PASSWORD`) or
  Gradle properties (`sonatypeUsername`, `sonatypePassword`)

## Required GitHub Secrets

The following secrets must be configured in the GitHub repository settings:

| Secret | Used By | Description |
|--------|---------|-------------|
| `SONATYPE_USERNAME` | Snapshot + Release | Sonatype Central Portal username |
| `SONATYPE_PASSWORD` | Snapshot + Release | Sonatype Central Portal password/token |
| `SIGNING_KEY` | Release only | ASCII-armored GPG private key for artifact signing |
| `SIGNING_PASSWORD` | Release only | Passphrase for the GPG signing key |

## Legacy Publishing (`repo.xh.io`)

The build retains a legacy publishing path to the XH internal Maven repository:

```bash
./gradlew publishHoistCore       # Publishes to repo.xh.io
```

This uses the `xhRepo` repository configured in `build.gradle`, with credentials supplied via
Gradle properties (`xhRepoDeployUser`, `xhRepoDeployPassword`) — typically set in a developer's
`~/.gradle/gradle.properties`. Snapshots publish to the `snapshots` endpoint and releases to
`releases`, based on the version suffix.

## How to Perform a Release

1. Ensure the `develop` branch is in a releasable state (CI passing, changelog updated)
2. Navigate to **Actions → Deploy Release** in the GitHub repository
3. Click **Run workflow**
4. Enter the release version (e.g. `37.0.0`) — this must **not** end in `-SNAPSHOT`
5. The workflow builds, signs, stages, and auto-releases to Maven Central
6. Verify the artifact appears on [Maven Central](https://central.sonatype.com/artifact/io.xh/hoist-core)
7. After the release, update `xhReleaseVersion` in `gradle.properties` to the next snapshot
   version (e.g. `38.0-SNAPSHOT`)

## `settings.gradle`

The `settings.gradle` file sets `rootProject.name = 'hoist-core'`. This is required because
GitHub Actions runs the build in a directory named after the repository clone, not necessarily
`hoist-core`. Without this file, the project name would default to the directory name, producing
an incorrectly named artifact.
