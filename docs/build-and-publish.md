# Build and Publish

> **Status: DRAFT** — This document is awaiting review. It may contain inaccuracies or gaps.

## Overview

Hoist-core is built with Gradle and published as the `io.xh:hoist-core` artifact. The project uses
**GitHub Actions** for continuous integration and automated publishing, with **Maven Central** as
the primary public artifact repository (via the Sonatype Central Portal).

This replaces a previous build pipeline that used **TeamCity** for CI and published to an internal
**repo.xh.io** Nexus repository. The legacy `repo.xh.io` publishing path is retained alongside the
new Maven Central workflow.

The build pipeline supports three GitHub Actions workflows:

- **CI** — Builds on every push and PR to `develop`
- **Snapshot publishing** — Automatically publishes `-SNAPSHOT` builds to Sonatype on every push to
  `develop`
- **Release publishing** — Manually triggered workflow that builds, signs, and publishes a release
  version to Maven Central from the `main` branch

> When code is pushed or merged to `develop`, both the CI and snapshot workflows run. CI validates
> the build; the snapshot workflow additionally publishes the artifact.

## Source Files

| File | Role |
|------|------|
| `build.gradle` | Gradle build config — plugins, dependencies, publishing, and signing |
| `settings.gradle` | Sets `rootProject.name = 'hoist-core'` — ensures the correct artifact name regardless of the checkout directory. Replaces a TeamCity `%projectName%` placeholder that was written as a literal string in GitHub Actions |
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

1. **build** — Runs `./gradlew build` (compilation and any configured checks)
2. **dependency-submission** — Generates and submits a dependency graph to GitHub, enabling
   **Dependabot Alerts** for all project dependencies

This workflow requires no secrets — it only builds and reports.

### Snapshot Publishing (`deploySnapshot.yml`)

**Trigger:** Push to `develop`

Publishes a snapshot build to the Sonatype Maven Central snapshot repository:

```
./gradlew publishToSonatype --no-daemon
```

Snapshots are published directly — they do **not** go through Sonatype's staging/release process
and are not signed. They are available immediately at the Sonatype snapshot repository
(`https://central.sonatype.com/repository/maven-snapshots/`).

**Required secrets:** `SONATYPE_USERNAME`, `SONATYPE_PASSWORD`

> **Note:** Snapshot publishing must be enabled on the Sonatype Central Portal namespace
> (Namespaces → dropdown → "Enable SNAPSHOTs") for this workflow to succeed.

### Release Publishing (`deployRelease.yml`)

**Trigger:** Manual (`workflow_dispatch`) — the operator selects a branch and enters the release
version string. Release builds are always run against the `main` branch.

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

The Maven Central publishing configuration was written using:
- [How to Publish a Grails Plugin to the Maven Central Repository](https://grails.apache.org/blog/2021-04-07-publish-grails-plugin-to-maven-central.html)
- [Sample Grails plugin configured for Maven Central](https://github.com/puneetbehl/myplugin/blob/main/build.gradle)

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
| `hoist-core-<version>-javadoc.jar` | Javadoc (Maven Central requirement) |
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
  (the `isReleaseVersion` flag is `true` when the version does not end in `SNAPSHOT`)
- The signing key and password are resolved from Gradle properties first (`signingKey`,
  `signingPassword`), falling back to environment variables (`SIGNING_KEY`, `SIGNING_PASSWORD`)
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
- **Snapshots** go directly to the snapshot repository — no staging or signing required
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

1. Merge `develop` into `main` when ready to release (CI passing, changelog updated)
2. Navigate to **Actions → Deploy Release** in the GitHub repository
3. Click **Run workflow**, select the `main` branch
4. Enter the release version (e.g. `37.0.0`) — this must **not** end in `-SNAPSHOT`
5. The workflow builds, signs, stages, and auto-releases to Maven Central
6. Verify the artifact appears on
   [Maven Central](https://central.sonatype.com/artifact/io.xh/hoist-core)
7. Update `xhReleaseVersion` in `gradle.properties` on `develop` to the next snapshot version
   (e.g. `38.0-SNAPSHOT`)
