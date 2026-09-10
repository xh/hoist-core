# Build and Publish

Hoist Core is built with Gradle and published to Maven Central through the Sonatype Central Portal
as two artifacts under the `io.xh` group:

- `hoist-core` — the Grails plugin consumed by Hoist applications.
- `hoist-core-mcp` — a self-contained JAR providing the MCP server and CLI developer tools. See
  [`mcp/README.md`](../mcp/README.md).

GitHub Actions handles CI and publishing. Workflow definitions live in `.github/workflows/`. Steps
shared across XH repositories, such as version validation, tagging, and GitHub release creation,
come from composite actions in [xh/hoist-dev-utils](https://github.com/xh/hoist-dev-utils).

## Versioning

The `xhReleaseVersion` property in `gradle.properties` sets the version for both artifacts.

- On `develop`, the property always carries the `-SNAPSHOT` version of the next release line.
  Snapshot builds publish it as-is. Maven snapshot repositories handle mutable versions, so no
  timestamp is appended.
- Release builds pass the release version to Gradle at build time. It is never committed, so a
  release tag points at a commit whose `gradle.properties` still reads `-SNAPSHOT`. This is
  deliberate: it avoids paired set/reset commits on every release.
- After a release, bump `xhReleaseVersion` on `develop` to the next `-SNAPSHOT` so subsequent
  snapshots publish under the new line.

## Workflows

### CI (`ci.yml`)

Runs on pushes and pull requests to `develop`.

- **build** runs `./gradlew build` across a JDK matrix: the toolchain used for publishing, and the
  oldest runtime the published JAR supports. The older row catches accidental use of newer Java
  APIs. See the `java` block in `build.gradle` and
  [`application-structure.md`](./application-structure.md#jdk-choice) for the current toolchain
  and bytecode target.
- **dependency-submission** submits the Gradle dependency graph to GitHub, enabling Dependabot
  alerts for project dependencies.

CI uses no configured secrets.

### Deploy Snapshot (`deploySnapshot.yml`)

Runs on every push to `develop`, or manually via `workflow_dispatch` with an optional `version`
input that overrides `gradle.properties`. The `-SNAPSHOT` suffix is appended if missing.

1. Runs `publishToSonatype`, uploading both artifacts directly to the Sonatype snapshot
   repository. Snapshots are not signed and do not pass through staging.
2. Fires a `repository_dispatch` (`hoist-core-snapshot`) to `xh/toolbox`, which rebuilds and
   redeploys Toolbox against the new snapshot. The dispatch authenticates as the org-owned
   **XH Build Bot** GitHub App via a short-lived installation token minted by
   `actions/create-github-app-token`, scoped to the toolbox repo with Contents: write. Toolbox
   runs triggered this way show `xh-build-bot[bot]` as the actor.

Runs are debounced per branch: a newer push cancels an in-progress snapshot build.

Snapshot publishing must be enabled for the `io.xh` namespace on the Sonatype Central Portal.

### Deploy Release (`deployRelease.yml`)

Manually triggered via `workflow_dispatch` with two inputs:

- `version` — the release version, e.g. `41.0.0`.
- `is-hotfix` — check when releasing a fix to a line other than the latest.

A job-level guard requires standard releases to run from `master`, and hotfixes to run from a
branch other than `master` or `develop`, such as a maintenance branch for an older major version.

1. **Validate** — `version` must be semver with no leading zeros, must not already be tagged, and
   must be exactly one major, minor, or patch increment from the latest tag. For hotfixes, the
   base is the latest tag before the proposed version, which must not itself be the latest
   release.
2. **Publish** — runs `publishToSonatype closeAndReleaseSonatypeStagingRepository` with the
   release version, signing both artifacts and releasing the staging repository to Maven Central.
3. **Tag** — creates and pushes `vX.Y.Z`.
4. **Release** — creates a GitHub release with notes generated from merged PRs since the previous
   tag. Hotfix releases are not marked latest.

Release runs are serialized and never cancelled by one another.

## Performing a Release

1. Confirm CI is green on `develop` and the changelog is finalized.
2. Merge `develop` into `master`.
3. In GitHub, open **Actions → Deploy Release → Run workflow**. Select `master`, enter the
   version, and leave `is-hotfix` unchecked.
4. Confirm the artifacts appear on [Maven Central](https://central.sonatype.com/namespace/io.xh)
   and the release on the repository's [Releases](https://github.com/xh/hoist-core/releases) page.
5. Bump `xhReleaseVersion` on `develop` to the next `-SNAPSHOT`.

For a hotfix, run the workflow from the maintenance branch with `is-hotfix` checked.

## Build Configuration

`build.gradle` is the reference for exact plugin versions, repository URLs, and publication
metadata. In outline:

- **Publications** — the root project defines the `hoistCore` publication: compiled classes,
  sources, POM, and the Grails plugin descriptor as a `plugin`-classified artifact. The `mcp`
  subproject defines `mcpServer`, publishing its shadow JAR as `hoist-core-mcp`.
- **Signing** — both publications sign with in-memory PGP keys, so no keyring is written to disk.
  Signing is required for release versions and skipped for snapshots. Key material resolves from
  the `signingKey` and `signingPassword` Gradle properties, falling back to the `SIGNING_KEY` and
  `SIGNING_PASSWORD` environment variables.
- **Sonatype** — the `nexusPublishing` block in the root project targets the Central Portal's
  staging API for releases and its snapshot repository for snapshots. Credentials resolve from the
  `sonatypeUsername` and `sonatypePassword` Gradle properties, falling back to `SONATYPE_USERNAME`
  and `SONATYPE_PASSWORD`.
- **Artifact name** — `settings.gradle` pins `rootProject.name` so the artifact id does not depend
  on the checkout directory name.

## Required Secrets and Variables

Configured in the repository's GitHub settings.

| Name | Type | Used By | Purpose |
|------|------|---------|---------|
| `SONATYPE_USERNAME` | Secret | Snapshot, Release | Sonatype Central Portal credentials |
| `SONATYPE_PASSWORD` | Secret | Snapshot, Release | Sonatype Central Portal credentials |
| `SIGNING_KEY` | Secret | Release | ASCII-armored GPG private key for artifact signing |
| `SIGNING_PASSWORD` | Secret | Release | Passphrase for the signing key |
| `XH_BUILD_BOT_PRIVATE_KEY` | Secret | Snapshot | Private key for the XH Build Bot GitHub App |
| `XH_BUILD_BOT_CLIENT_ID` | Variable | Snapshot | Client ID of the XH Build Bot GitHub App |

The XH Build Bot app is registered under the xh GitHub org and installed org-wide. Its credentials
are kept in the team 1Password vault under "GitHub App: XH Build Bot". To rotate, generate a new
private key on the app's settings page, update the 1Password item and the
`XH_BUILD_BOT_PRIVATE_KEY` secret here and in hoist-react, then revoke the old key.

The release workflow also uses the automatic `GITHUB_TOKEN` to push the tag and create the release.

## Consuming the Artifacts

Applications declare the dependency in `build.gradle`:

```groovy
dependencies {
    implementation 'io.xh:hoist-core:<version>'
}
```

Releases resolve from Maven Central. Snapshot builds also require the Sonatype snapshot repository:

```groovy
repositories {
    mavenCentral()
    maven { url = 'https://central.sonatype.com/repository/maven-snapshots/' }
}
```

See [`mcp/README.md`](../mcp/README.md#app-side-distribution) for pulling `hoist-core-mcp` into an
application.

### Legacy releases

Releases up to and including `36.2.0` predate the move to Maven Central and are served from a
static, read-only archive:

```groovy
repositories {
    maven { url = 'https://maven-archive.xh.io/' }
}
```

The archive holds only `io.xh:hoist-core` release artifacts and their metadata. Add it only when
resolving those historical versions.
