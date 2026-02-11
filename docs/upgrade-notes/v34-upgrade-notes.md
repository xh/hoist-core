# Hoist Core v34 Upgrade Notes

> **From:** v33.x → v34.0.1 | **Released:** 2025-11-24 | **Difficulty:** MEDIUM

## Overview

Hoist Core v34 is a major framework upgrade. The underlying stack moves to Grails 7.0,
Spring Boot 3.5, Groovy 4, Gradle 8.14, and Tomcat 10.1. With this release, Grails is officially
part of the Apache Software Foundation, which changes several Maven coordinates and repository URLs.

The most significant app-level impacts are:

- **Build system restructuring** — Gradle 8 + Apache Grails BOM replaces individually versioned
  plugin dependencies.
- **Logging migration** — Logback's Groovy DSL has been removed; apps now use a class-based
  configuration extending `io.xh.hoist.LogbackConfig`.
- **Jakarta EE namespace** — Tomcat 10.1 requires `jakarta.servlet` imports (was `javax.servlet`).
- **`request.JSON` removal** — Use `parseRequestJSON()` / `parseRequestJSONArray()` instead.

## Prerequisites

Before starting, ensure:

- [ ] **Java 17 or 21** is installed (Java 11 is no longer supported by Grails 7)

## Upgrade Steps

### 1. Update Docker Base Image

Update your Dockerfile to use the Tomcat 10 base image with Jakarta EE support.

**File:** `docker/tomcat/Dockerfile`

Before:
```dockerfile
FROM xhio/xh-tomcat:*-jdk17
```

After:
```dockerfile
FROM xhio/xh-tomcat:next-tc10-jdk17
```

The `tc10` tag indicates Tomcat 10.1, which uses the `jakarta.servlet` namespace required by
Spring Boot 3.5 / Grails 7.

> **Custom base images:** If your Dockerfile uses a custom base image (not `xhio/xh-tomcat`), you
> will need a new Tomcat 10-based version of that image before proceeding. The existing Tomcat 9
> image will **not** work with Grails 7. Coordinate with your infrastructure team to ensure a
> compatible image is available.

### 2. Update Gradle Wrapper

Grails 7 requires Gradle 8.x. Update your wrapper properties directly.

**File:** `gradle/wrapper/gradle-wrapper.properties`

Before:
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-7.6.4-bin.zip
```

After:
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.3-bin.zip
networkTimeout=10000
validateDistributionUrl=true
```

Then regenerate the wrapper jar:
```bash
./gradlew wrapper
```

### 3. Update gradle.properties

Several version properties are now managed by the Grails BOM and should be **removed**. Others
need updating.

**File:** `gradle.properties`

**Remove** these properties (now managed by the Grails BOM):
```properties
groovyVersion=...
grailsGradlePluginVersion=...
grailsHibernatePluginVersion=...
gormVersion=...
logback.version=...
```

**Update** these properties:
```properties
grailsVersion=7.0.5
hoistCoreVersion=34.0.1
hazelcast.version=5.6.0
```

**Add** (if not already present — a different pre-existing value is fine, `2G` is a reasonable default):
```properties
localDevXmx=2G
```

Your `gradle.properties` should look similar to this when done (app-specific properties will vary):

```properties
xhAppCode=myapp
xhAppName=MyApp
xhAppPackage=com.example.myapp
xhAppVersion=X.Y-SNAPSHOT

grailsVersion=7.0.5
hoistCoreVersion=34.0.1
dotEnvGradlePluginVersion=4.0.0
hazelcast.version=5.6.0

runHoistInline=false
enableHotSwap=false
localDevXmx=2G

org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.jvmargs=-Dfile.encoding=UTF-8 -Xmx1024M
```

### 4. Restructure build.gradle

This is the largest file change. The key differences are:

1. **Buildscript dependencies** use `org.apache.grails` coordinates (was `org.grails`)
2. **BOM-based version management** replaces individual version declarations
3. **Repository URL** changes to `repo.grails.org/grails/restricted`
4. **Plugin application** uses the new `org.apache.grails.gradle.grails-web` ID
5. **Hot swap setup** uses `detachedConfiguration` (was resolving from `developmentOnly`)
6. **Task configuration** uses `configureEach` pattern for both `bootRun` and `console`

#### 4a. Buildscript Block

Before:
```groovy
buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()                                       // or: maven {url 'https://plugins.gradle.org/m2/'}
        maven {url 'https://repo.grails.org/grails/core'}
    }
    dependencies {
        classpath "org.grails:grails-gradle-plugin:$grailsGradlePluginVersion"
        classpath "org.grails.plugins:hibernate5:$grailsHibernatePluginVersion"
        classpath "co.uzzu.dotenv:gradle:$dotEnvGradlePluginVersion"   // moves to plugins {} block
    }
}
```

After:
```groovy
buildscript {
    repositories {
        mavenCentral()
        maven {url = 'https://repo.grails.org/grails/restricted'}
    }
    dependencies { // Not Published to Gradle Plugin Portal
        classpath platform("org.apache.grails:grails-bom:$grailsVersion")
        classpath "org.apache.grails:grails-data-hibernate5"
        classpath "org.apache.grails:grails-gradle-plugins"
    }
}
```

Note: the `co.uzzu.dotenv:gradle` classpath dependency moves to the `plugins {}` block (see 4b).
The `gradlePluginPortal()` repository is no longer needed in `buildscript`.

#### 4b. Plugins Block and Project Setup

Before:
```groovy
apply plugin:'idea'
apply plugin:'war'
apply plugin:'org.grails.grails-web'

// If you had this wrapper for composite builds, remove it entirely:
gradle.rootProject {
    apply plugin:'co.uzzu.dotenv.gradle'
}

version xhAppVersion
group xhAppPackage
```

After:
```groovy
plugins {
    id 'idea'
    id 'war'
    id 'co.uzzu.dotenv.gradle' version "$dotEnvGradlePluginVersion"
}

// Not Published to Gradle Plugin Portal
apply plugin: "org.apache.grails.gradle.grails-web"

version = xhAppVersion
group = xhAppPackage
```

Notes:
- The Grails web plugin must still use `apply plugin:` because it is not published to the Gradle
  Plugin Portal. Other plugins move to the declarative `plugins {}` block.
- The `gradle.rootProject { apply plugin: ... }` wrapper for dotenv is no longer needed — the
  `plugins {}` block handles composite build scenarios correctly.
- Update `version` and `group` to use assignment syntax (`=`) per Gradle 8 conventions.

#### 4c. Repositories

Before:
```groovy
repositories {
    mavenCentral()
    maven {url 'https://repo.grails.org/grails/core'}
    maven {url 'https://repo.xh.io/content/groups/public/'}
}
```

After:
```groovy
repositories {
    mavenCentral()
    maven {url = 'https://repo.grails.org/grails/restricted'}
    maven {url = 'https://repo.xh.io/content/groups/public/'}
}
```

#### 4d. Dependencies

Add the Grails console dependency, update any Jakarta-incompatible libraries, and remove the
old conditional `groovyReset` dependency (it moves to the JVM args section in Step 4e):

```groovy
dependencies {
    implementation "io.xh:hoist-core:$hoistCoreVersion"

    // ... your existing database drivers and other dependencies (unchanged) ...

    // Dev tooling
    developmentOnly "io.methvin:directory-watcher:0.19.1"
    console "org.apache.grails:grails-console"            // NEW - required for console command

    // REMOVE: groovyReset is no longer declared here — it is resolved via
    // detachedConfiguration in the JVM args section (see Step 4e)
}
```

Notes:
- Keep your existing database drivers as-is (MySQL, SQL Server, H2, etc.) — they don't depend on
  the servlet API and do not need to change for the Grails 7 upgrade.
- Remove any `if (parseBoolean(enableHotSwap)) { developmentOnly "io.xh:groovyReset:1.0" }`
  block from dependencies — this is now handled differently (see Step 4e).

#### 4d-2. Remove Obsolete Blocks

Remove these blocks if present in your `build.gradle`:

```groovy
// REMOVE - no longer needed in Gradle 8
grails {
    pathingJar = true
}
```

#### 4e. JVM Args and Hot Swap

Before:
```groovy
def allJvmArgs = [
    '-Dspring.output.ansi.enabled=always',
    '-XX:TieredStopAtLevel=1',
    '-Xmx3G',
    // ... --add-modules, --add-opens ...
]

if (parseBoolean(enableHotSwap)) {
    def groovyReset = configurations.developmentOnly.resolve().find {it.name.contains("groovyReset")}
    allJvmArgs += [
        '-XX:HotswapAgent=fatjar',
        '-XX:+AllowEnhancedClassRedefinition',
        '-javaagent:' + groovyReset.absolutePath
    ]
}

bootRun {
    ignoreExitValue true
    systemProperties System.properties
    jvmArgs(allJvmArgs)
    sourceResources sourceSets.main
    systemProperties hoistMetaData
    environment env.allVariables()
}
```

After:
```groovy
def allJvmArgs = [
    '-Dspring.output.ansi.enabled=always',
    '-XX:+TieredCompilation',
    '-XX:TieredStopAtLevel=1',
    '-XX:CICompilerCount=3',
    '-Xmx' + localDevXmx,
    // ... --add-modules, --add-opens (unchanged) ...
]

if (parseBoolean(enableHotSwap)) {
    def groovyReset = configurations.detachedConfiguration(
        dependencies.create("io.xh:groovyReset:1.0")
    )
    allJvmArgs += [
        '-XX:HotswapAgent=fatjar',
        '-XX:+AllowEnhancedClassRedefinition',
        "-Dspring.devtools.restart.enabled=false",
        "-Dspring.devtools.livereload.enabled=false",
        "-javaagent:" + groovyReset.singleFile.absolutePath
    ]
}

def execTasks = tasks.matching {it.name == 'bootRun' || it.name == 'console'}
execTasks.configureEach {
    ignoreExitValue = true
    systemProperties System.properties
    jvmArgs(allJvmArgs)
    systemProperties hoistMetaData
    environment env.allVariables()
}

tasks.withType(GroovyCompile) {
    configure(groovyOptions) {
        forkOptions.jvmArgs = allJvmArgs
    }
}
```

Key changes:
- Memory uses `localDevXmx` property (from `gradle.properties`) instead of a hardcoded value
- Added `-XX:+TieredCompilation` and `-XX:CICompilerCount=3` for faster compilation
- `groovyReset` resolved via `detachedConfiguration` (Gradle 8 compatible) — the old
  `configurations.developmentOnly.resolve()` pattern no longer works
- Spring DevTools restart/livereload explicitly disabled when using hot swap
- Task config applies to both `bootRun` and `console` via `configureEach` pattern
- **Remove** `sourceResources sourceSets.main` from `bootRun` — no longer needed
- Added `GroovyCompile` JVM args to ensure consistent compilation behavior

If your `build.gradle` has a `doFirst` block on `bootRun` (e.g. for env-variable validation),
update it to apply to the shared `execTasks` set:

Before:
```groovy
tasks.bootRun.doFirst {
    def missingEnvVars = env.allVariablesOrNull().findAll {it.value == null}.collect {it.key}
    if (missingEnvVars) {
        throw new GradleException("Missing env vars: ${missingEnvVars}")
    }
}
```

After:
```groovy
execTasks.all { task ->
    task.doFirst {
        def missingEnvVars = env.allVariablesOrNull().findAll {it.value == null}.collect {it.key}
        if (missingEnvVars) {
            throw new GradleException("Missing env vars: ${missingEnvVars}")
        }
    }
}
```

#### 4f. Blocks to Preserve Unchanged

The following `build.gradle` blocks typically require **no changes** — retain them as-is:

- `configurations { }` (resolution strategy)
- `springBoot { }` (main class)
- `Map hoistMetaData = [...]` (build info)
- `idea { }` (IntelliJ settings)
- `tasks.war.doFirst { }` (build info injection)

#### 4g. Reference

See the [Toolbox build.gradle](https://github.com/xh/toolbox/blob/develop/build.gradle) for a
complete, canonical example.

### 5. Update settings.gradle

If your `settings.gradle` doesn't already support composite builds, update it. If it does, ensure
the Grails web plugin reference uses the new name.

**File:** `settings.gradle`

```groovy
import static java.lang.Boolean.parseBoolean

rootProject.name = 'myapp'

// Composite build setup for compiling hoist-core locally
if (parseBoolean(runHoistInline)) {
    println "${xhAppName}: running with Hoist Core INLINE...."
    includeBuild '../hoist-core'
} else {
    println "${xhAppName}: running with Hoist Core PACKAGED at v${hoistCoreVersion}...."
}
```

### 6. Migrate Logging Configuration

Logback removed its Groovy DSL in newer versions. Hoist replaces it with a class-based approach.

#### 6a. Delete the old config

```bash
rm grails-app/conf/logback.groovy
```

#### 6b. Create a new LogbackConfig class

**Create:** `grails-app/init/<your-package>/LogbackConfig.groovy`

**Important:** The class **must** be named exactly `LogbackConfig` and placed in your app's root
package (e.g. `com.example.myapp.LogbackConfig`). Hoist discovers it by convention using
`Utils.appPackage + '.LogbackConfig'`. A different name or sub-package will silently fall back to
the base Hoist defaults.

**Minimal version** (inherits all defaults from Hoist):
```groovy
package com.example.myapp

class LogbackConfig extends io.xh.hoist.LogbackConfig {}
```

**Customized version** (if your old `logback.groovy` had custom configuration):
```groovy
package com.example.myapp

import static ch.qos.logback.classic.Level.INFO

class LogbackConfig extends io.xh.hoist.LogbackConfig {

    // Override layout properties to change log format
    String getMonitorLayout() {
        '%d{HH:mm:ss.SSS} | %customMsg%n'
    }

    // Override configureLogging() for custom appenders, loggers, or conversion rules
    void configureLogging() {
        // Call super first to set up all Hoist defaults
        super.configureLogging()

        // Then add your customizations
        logger('com.example.myapp.chatty', INFO)
        monthlyLog('order-tracking')
        logger('com.example.myapp.orders', INFO, ['order-tracking'])
    }
}
```

#### 6c. Available overrides in `io.xh.hoist.LogbackConfig`

| Method | Purpose |
|--------|---------|
| `configureLogging()` | Main template method. Override to add custom appenders and loggers. |
| `getStdoutLayout()` | Layout pattern for console output. |
| `getDailyLayout()` | Layout pattern for the daily rolling app log. |
| `getMonthlyLayout()` | Layout pattern for monthly rolling logs. |
| `getMonitorLayout()` | Layout pattern for the dedicated monitor log. |
| `getTrackLayout()` | Layout pattern for the activity tracking log. |

Helper methods available within `configureLogging()`:

| Method | Purpose |
|--------|---------|
| `consoleAppender(name, layout?)` | Create a console appender. |
| `dailyLog(name, layout?, subdir?)` | Create a daily rolling file appender. |
| `monthlyLog(name, layout?, subdir?)` | Create a monthly rolling file appender. |
| `logger(name, level, appenders?, additivity?)` | Configure a logger. |
| `root(level, appenders?)` | Configure the root logger. |
| `conversionRule(word, converterClass)` | Register a custom conversion pattern. |

### 7. Update javax to jakarta Imports

Tomcat 10.1 uses the Jakarta EE namespace. Find and update any `javax.servlet` imports in your
application code.

**Find affected files:**
```bash
grep -r "javax.servlet" grails-app/ src/
```

**Before:**
```groovy
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
```

**After:**
```groovy
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
```

This typically affects only your `AuthenticationService` and any custom servlet filters or
controllers that directly reference servlet types. Hoist Core handles its own internal migration.

### 8. Replace request.JSON Usage

The deprecated `request.JSON` property is no longer available in Grails 7. Use the `BaseController`
methods instead.

**Find affected files:**
```bash
grep -r "request\.JSON" grails-app/controllers/
```

**Before (Map body):**
```groovy
def create() {
    def data = request.JSON
    renderJSON(service.create(data))
}
```

**After:**
```groovy
def create() {
    def data = parseRequestJSON()
    renderJSON(service.create(data))
}
```

**Before (List body):**
```groovy
def bulkCreate() {
    def items = request.JSON
    renderJSON(service.bulkCreate(items))
}
```

**After:**
```groovy
def bulkCreate() {
    def items = parseRequestJSONArray()
    renderJSON(service.bulkCreate(items))
}
```

Both methods are defined on `BaseController` and use the Hoist-standard Jackson JSON parsing
configuration. `parseRequestJSON()` returns a `Map`; `parseRequestJSONArray()` returns a `List`.

### 9. Clean Up application.groovy (if needed)

If your `application.groovy` contains any of these obsolete Grails 6 properties, remove them:

```groovy
// REMOVE if present - no longer recognized in Grails 7
grails.project.groupId = ...
grails.resources.pattern = ...
grails.profile = 'rest-api'
grails.controllers.defaultScope = ...
grails.urlmapping.cache.maxsize = ...
grails.converters.encoding = ...
grails.enable.native2ascii = ...
grails.exceptionresolver.params.exclude = ...
grails.gorm.reactor.events = ...
```

Most Hoist apps use `ApplicationConfig.defaultConfig(this)` and won't have these. Check anyway.

## Verification Checklist

After completing all steps:

- [ ] `./gradlew compileGroovy` succeeds
- [ ] Application starts without errors (via wrapper app or `bootRun`)
- [ ] Log files are created in the expected directory
- [ ] Admin Console loads and is functional
- [ ] Authentication works (login/logout)
- [ ] WebSocket connections establish (if enabled)
- [ ] No `javax.servlet` references remain: `grep -r "javax.servlet" grails-app/ src/`
- [ ] No `request.JSON` references remain: `grep -r "request\.JSON" grails-app/`

## Reference

- [Grails 7 Upgrade Guide](https://docs.grails.org/7.0.2/guide/upgrading.html#upgrading60x)
- [Toolbox on GitHub](https://github.com/xh/toolbox) — canonical example of a Hoist app on v34+
