# Dependency Locking

## Overview

Gradle dependency locking records the exact version of every resolved dependency in a committed
lockfile. Builds then resolve the same graph on every machine and in CI. Snapshot dependencies are
the one exception - see [Snapshots](#snapshots). XH recommends locking for all Hoist applications.

Hoist developers know this idea from `pnpm-lock.yaml` on the client. Gradle lockfiles do the same
job, but the workflow differs in one important way: **Gradle does not update a lockfile when you
edit a version.** You must regenerate it yourself. If you forget, some changes fail the build and
others pass and silently resolve the old version. [When to Regenerate](#when-to-regenerate) lists
which is which.

Hoist-core does not lock an application's dependencies. Gradle resolves each app's graph afresh
from the published hoist-core metadata, so each app must turn on locking in its own build.

### The App's Hoist Dependency

An app gets hoist-core in one of two ways:

- **Directly.** `build.gradle` declares `io.xh:hoist-core`, with its version set by
  `hoistCoreVersion` in `gradle.properties`.
- **Through a plugin.** The app depends on a client-specific Hoist plugin, and that plugin declares
  hoist-core. The app has no `hoistCoreVersion`. The plugin's version decides which hoist-core the
  app gets.

This doc calls whichever one the app declares its *Hoist dependency*. The rules below apply to it
the same way in both cases. A plugin version change can move hoist-core and its transitive graph
too.

### Source Files

| File | Location | Role |
|------|----------|------|
| `build.gradle` | App root | Turns on locking with a `dependencyLocking` block |
| `gradle.properties` | App root | Holds `grailsVersion`, the version of the app's Hoist dependency, and other version properties that feed the graph |
| `gradle.lockfile` | App root | Lock state for all project configurations. Commit it |
| `buildscript-gradle.lockfile` | App root | Lock state for the build script classpath, which holds the Gradle plugins from the `buildscript` and `plugins` blocks. Commit it |

## Enabling Locking in an App

Add a `dependencyLocking` block to `build.gradle` twice: once inside the `buildscript` block and
once at the project level. Use `STRICT` mode, which also fails a build whose lockfile is missing
instead of resolving it unlocked.

Most Hoist apps support [inline hoist-core development](./application-structure.md#inline-hoist-development)
through the `runHoistInline` flag. An inline build takes its dependency graph from the local
hoist-core checkout, which a committed lockfile cannot track. Lock the project configurations for
packaged builds only:

```groovy
import static java.lang.Boolean.parseBoolean

buildscript {
    // ...repositories and classpath dependencies...
    dependencyLocking { lockAllConfigurations(); lockMode = LockMode.STRICT }
}

// After changing a dependency, run: ./gradlew dependencies --write-locks -PrunHoistInline=false
// Locking applies to packaged builds only. An inline build takes its transitive graph from the
// local hoist-core checkout, which the lock state cannot track.
if (!parseBoolean(runHoistInline)) {
    dependencyLocking { lockAllConfigurations(); lockMode = LockMode.STRICT }
}
```

An app without `runHoistInline` can drop the `if` guard and the `-PrunHoistInline=false` flag. An
app that builds a different plugin inline should guard on that plugin's flag instead.

To create the lockfiles:

1. Add the blocks above to `build.gradle`.
2. Run `./gradlew dependencies --write-locks -PrunHoistInline=false`.
3. Run `./gradlew build` to confirm that the build passes against the new locks.
4. Commit `gradle.lockfile` and `buildscript-gradle.lockfile` with the `build.gradle` change.

Keep `runHoistInline=false` in the committed `gradle.properties`. CI, Dependabot and production
builds then all resolve the locked, packaged graph.

## How Locking Works

A lockfile is a sorted text file with one line per module. Each line lists the module, its locked
version, and the configurations that use it. A final `empty=` line lists the locked configurations
that have no dependencies:

```
io.xh:hoist-core:45.0.0=compileClasspath,productionRuntimeClasspath,runtimeClasspath,...
empty=annotationProcessor,testAnnotationProcessor
```

Gradle generates the file. Do not edit it by hand.

During a normal build, Gradle checks the graph against the lockfile with two rules:

- **Versions.** Gradle adds each locked version as a
  [strict constraint](https://docs.gradle.org/current/userguide/dependency_locking.html#sec:locking-mechanism).
  A declared version **above** the locked one conflicts with it, and the build fails. A declared
  version **below** the locked one loses to it. The build passes and resolves the locked, higher
  version.
- **Modules.** Gradle compares the set of resolved modules with the lock state. A module **added
  to or removed from** the graph fails the build.

A `-SNAPSHOT` version is locked by its version string only. New snapshot content under the same
string passes the version rule. It fails the module rule only if it adds or removes a dependency.

The `--write-locks` flag tells Gradle to resolve without the existing lock state and record the
result. It overwrites the lockfile with a fresh resolution, much like deleting `pnpm-lock.yaml`
and running `pnpm install`. For declared fixed versions and BOMs, the result is the same graph you
would get with no locking at all.

A lockfile pins versions only. It does not record checksums or the repository that served each
artifact, so it works unchanged behind a corporate mirror. To verify artifact checksums, use
Gradle [dependency verification](https://docs.gradle.org/current/userguide/dependency_verification.html),
which is a separate feature.

## When to Regenerate

Regenerate the locks after any change to the dependency graph, and commit the new lockfiles in the
same commit as the change. The silent cases are the ones to watch:

| Change | Without new locks |
|--------|-------------------|
| Raise the version of the Hoist dependency, `grailsVersion` or any other module | Build fails |
| Lower the version of the Hoist dependency, `grailsVersion` or any other module | Build passes and **ships the old, higher version** |
| Add or remove a dependency in `build.gradle` | Build fails |
| A new build of a snapshot Hoist dependency adds or removes a dependency | Build fails, with no change in the app |
| A new build of a snapshot Hoist dependency bumps a dependency | Build passes and **keeps the old transitive version** |
| Change a Gradle plugin in the `buildscript` or `plugins` block | Build fails or passes, by the same rules |

Version properties in `gradle.properties` count as dependency changes. `grailsVersion` selects the
Grails BOM, which sets the versions of many modules at once. A change to it moves most of the
graph.

### Snapshots

Most Hoist apps depend on released versions of their Hoist dependency, so the snapshot rows do not
apply to them. Some apps track a snapshot between releases, for example Toolbox, which tests each
hoist-core snapshot ahead of release.

Snapshot content changes without any change in the app. Gradle checks a `-SNAPSHOT` dependency for
new content every 24 hours by default, and on every build if the app sets
`cacheChangingModulesFor 0, 'seconds'`, as Toolbox does. When a new snapshot adds or removes a
transitive dependency, the next build of every locked app on that snapshot fails. When it only bumps
a version, the build passes and stays on the old locked version. If a plugin depends on a hoist-core
snapshot, new hoist-core snapshot content has the same effect, even when the plugin version does not
change.

Gradle's own docs advise against locking changing versions such as `-SNAPSHOT`. An app that tracks
a snapshot should lock anyway. Regenerate its locks when a build fails on lock state after a new
snapshot, and whenever the snapshot line changes its dependencies.

## Commands

| Task | pnpm | Gradle |
|------|------|--------|
| Install from the lockfile | `pnpm install --frozen-lockfile` | Any build. `STRICT` mode also fails if a lockfile is missing |
| Record the graph after an edit | `pnpm install` | `./gradlew dependencies --write-locks -PrunHoistInline=false` |
| Update one dependency, keep the rest | `pnpm update <pkg>` | `./gradlew dependencies --update-locks <group>:<module> -PrunHoistInline=false` |
| Explain why a version resolved | `pnpm why <pkg>` | `./gradlew dependencyInsight --dependency <module> --configuration runtimeClasspath` |
| Check the locked hoist-core version | `grep` the lockfile | `grep '^io.xh:hoist-core:' gradle.lockfile` |

The `--update-locks` flag implies `--write-locks`. It takes a comma-separated list and accepts
wildcards, for example `org.apache.poi:*`. It keeps the rest only when nothing else in the build
changed. Gradle also records any other raised version or added dependency, and the modules the
update itself moves. A lowered version stays at its lock.

Hoist apps are single-project builds, so `./gradlew dependencies` covers every configuration. A
multi-project build needs each project resolved. Hoist-core itself adds a `resolveAndLockAll` task
for this reason - see [Build Configuration](./build-and-publish.md#build-configuration).

## Common Patterns

### Upgrading the Hoist Dependency

1. Set the new version of the Hoist dependency. For a direct dependency, that is
   `hoistCoreVersion` in `gradle.properties`.
2. Run `./gradlew dependencies --write-locks -PrunHoistInline=false`.
3. Run `grep '^io.xh:hoist-core:' gradle.lockfile` and confirm the hoist-core version.
4. Commit the version change and both lockfiles together.

The lockfile records hoist-core in both cases, so step 3 also shows which hoist-core a plugin
brought in.

Follow the same steps to move from a snapshot to a release before a production build. Gradle
orders a release above its own snapshot, so `46.0-SNAPSHOT` to `46.0.0` is a raise, and the build
fails until you regenerate. A move back to an older release, for example from `46.0-SNAPSHOT` to
`45.1.0`, is a downgrade. If you skip step 2 there, the build passes and ships the snapshot.

A release workflow can guard against this. Toolbox's `buildRelease.yml` fails a release when
`gradle.lockfile` does not lock the hoist-core version that `hoistCoreVersion` declares. An app on
a plugin can check the plugin's line the same way.

### Reviewing a Lockfile Diff

Read the lockfile diff in a dependency PR. It shows every transitive version that moved, which the
`build.gradle` diff does not. A version that moved down, or a module that changed when you did not
expect it, deserves a look with `dependencyInsight`.

### Resolving a Merge Conflict

Do not merge a lockfile by hand. Resolve the conflict in `build.gradle` and `gradle.properties`
first. Gradle rejects a lockfile that contains conflict markers, even with `--write-locks`, so take
either side of each lockfile and then regenerate:

```bash
git checkout --theirs gradle.lockfile buildscript-gradle.lockfile
./gradlew dependencies --write-locks -PrunHoistInline=false
git add gradle.lockfile buildscript-gradle.lockfile
```

A lockfile can also merge with no conflict when both branches changed dependencies. A clean text
merge does not prove that the locks match the merged build. After such a merge, regenerate and
commit any diff.

### Dependabot

Dependabot regenerates Gradle lockfiles in its own PRs. Hoist-core's Dependabot PRs show this. It
runs the app's Gradle build with `--write-locks` and the committed `gradle.properties`, which is
another reason to keep `runHoistInline=false` there.

The lockfile update is best-effort. If Dependabot's Gradle run fails, the PR can arrive with the
old lockfiles, and a `STRICT` build then fails on it. One cause is a repository that Dependabot
cannot reach, for example the private repository of a client-specific plugin. Declare each private
repository under `registries` in `.github/dependabot.yml`.

Each app needs its own `gradle` entry in `.github/dependabot.yml`. Hoist-core's configuration
covers hoist-core only.

## Locking in Hoist-Core

Hoist-core locks its own build the same way, but without the inline guard. It has one lockfile per
project and a `resolveAndLockAll` task to regenerate them all. That setup affects hoist-core builds only. See
[Build Configuration](./build-and-publish.md#build-configuration).

## Common Pitfalls

### Changing a Version Without Regenerating

Gradle does not update a lockfile when you edit `build.gradle` or `gradle.properties`. A raised
version fails the build, but a lowered version passes and resolves the old, higher one. Regenerate
after every version change, in either direction.

### Assuming a Snapshot Stays Put

New snapshot content reaches the build with no commit in the app. A new snapshot that bumps a
transitive dependency builds clean against the old lockfile. The app then runs against an older
library than its Hoist dependency declares. One that adds or removes a dependency fails the next
build. See [Snapshots](#snapshots).

### Regenerating in Inline Mode

With `runHoistInline=true`, the project configurations are not locked. A `--write-locks` run in
that mode removes their lock state: `gradle.lockfile` loses every module line. The next packaged
build then fails with "is locked but does not have lock state". Always pass `-PrunHoistInline=false`
when you regenerate, even if the project's `gradle.properties` sets it to `false`. A developer can
turn inline mode on in `~/.gradle/gradle.properties`, which takes priority over the project file.
Only the command-line flag overrides both.

### Editing or Hand-Merging a Lockfile

Gradle generates the lockfile, and a hand edit can leave it out of step with the graph. Regenerate
it instead. See [Resolving a Merge Conflict](#resolving-a-merge-conflict).

### Writing Locks in CI

Do not pass `--write-locks` or `--update-locks` in a CI or release build. The build then records
whatever it resolves and never fails on drift, which defeats the lock. Regenerate locally and
commit the result.

### Committing Only Part of the Change

Commit the lockfiles in the same commit as the version change. A version change without its locks
fails the build or ships the wrong version.

## Related Documentation

- [Application Structure](./application-structure.md) - App repository layout and build files
- [Build and Publish](./build-and-publish.md) - Hoist-core's own build, CI and publishing
- [Gradle dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html) -
  Gradle's reference docs
