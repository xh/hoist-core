# Hoist Core v43 Upgrade Notes

> **From:** v42.x → v43.0.0 | **Released:** unreleased | **Difficulty:** 🟠 MEDIUM

## Overview

Hoist Core v43 is a major framework upgrade. The underlying stack moves to Grails 8.0, Spring
Boot 4.1, Spring Framework 7, Groovy 5, Gradle 9.8 and Tomcat 11, with Java 21 as the new minimum.
Hibernate stays at 5.6, so Hibernate-level mappings and queries are unaffected.

The most significant app-level impacts are:

- **Java 21 and Tomcat 11** - apps must build and run on Java 21 or later, and deploy on a
  Tomcat 11 image.
- **Gradle 9** - Grails 8 requires the Gradle 9 wrapper.
- **Groovy 5** - stricter closure resolution can break `constraints` blocks and `dataSource`
  config. These failures appear at startup, not at compile time.
- **Jackson 3** - `JSONSerializer` and `JSONParser` now use Jackson 3. JSON output is unchanged,
  but custom serializers need small API changes.

## Prerequisites

Before starting, ensure:

- [ ] **Java 21 or later** is installed for local builds and in CI - see step 3. Toolbox uses
  Java 25.
- [ ] **A Tomcat 11 base image** is available for deployment - see step 1.

## Upgrade Steps

### 1. Update Docker Base Image

Grails 8 (Spring Boot 4) requires Servlet 6.1, which means Tomcat 11. The WAR will not start on
the Tomcat 10 images.

**File:** `docker/tomcat/Dockerfile`

Before:
```dockerfile
FROM xhio/xh-tomcat:next-tc10-jdk21
```

After:
```dockerfile
FROM xhio/xh-tomcat:next-tc11-jdk21
```

The image's JDK must be at least the `majorJavaVersion` the app builds with (step 3) - an app
built on Java 25 needs `next-tc11-jdk25`, as Toolbox uses. Tomcat 11 images are published for
JDK 21 and 25 only. Use the `next-tc11-*` tags until an `xh-tomcat` release adds the pinned
`latest-tc11-*` and `<version>-tc11-*` tags.

> **Custom base images:** If your Dockerfile uses a custom base image, you will need a Tomcat 11
> version of that image. A Tomcat 10 image will **not** work with Grails 8.

### 2. Update Gradle Wrapper

Grails 8 requires Gradle 9. Regenerate the wrapper **before** the other build changes, while the
build still configures on Grails 7:

```bash
./gradlew wrapper --gradle-version 9.8.0
```

This updates the wrapper scripts and jar, and `gradle/wrapper/gradle-wrapper.properties`:

Before:
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.5-bin.zip
```

After:
```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-9.8.0-bin.zip
retries=0
retryBackOffMs=500
```

Gradle 9 removes APIs deprecated in Gradle 8. If custom build logic fails, see the
[Gradle 9 upgrade guide](https://docs.gradle.org/current/userguide/upgrading_major_version_9.html).

### 3. Update gradle.properties and CI JDK

**File:** `gradle.properties`

Before:
```properties
majorJavaVersion=17
grailsVersion=7.2.2
hoistCoreVersion=42.1.0
hazelcast.version=5.7.0
opentelemetry.version=1.62.0
```

After:
```properties
majorJavaVersion=21
grailsVersion=8.0.0
hoistCoreVersion=43.0.0
hazelcast.version=5.7.0
opentelemetry.version=1.65.0
```

Keep both version overrides. The Grails 8 BOM manages older versions (OpenTelemetry `1.62.0`,
Hazelcast `5.5.0`) than hoist-core is built against, and hoist-core does not publish these
versions itself. `opentelemetry.version` must move to hoist-core's `1.65.0`.

`majorJavaVersion` sets the Gradle Java toolchain in Toolbox-style builds - use `25` to build on
Java 25. Gradle needs a matching JDK installed locally and in CI. Update the JDK in your CI
workflows to match:

```bash
grep -rn "java-version" .github/workflows/
```

Before:
```yaml
java-version: '21'
```

After:
```yaml
java-version: '25'
```

### 4. Update build.gradle

#### 4a. Mail and Quartz coordinates

`grails-mail` and `grails-quartz` moved into grails-core and are now managed by the Grails BOM.
Hoist Core declares both, so most apps can remove their own declarations. If your app declares
them, update the coordinates and remove the versions.

**Find affected files:**
```bash
grep -n "grails-mail\|grails-quartz" build.gradle
```

Before:
```groovy
implementation "org.grails.plugins:grails-mail:5.0.3"
implementation "org.apache.grails:grails-quartz:4.0.0"
```

After:
```groovy
implementation "org.apache.grails:grails-mail"
implementation "org.apache.grails:grails-quartz"
```

#### 4b. hoist-core MCP install snippet

If your `build.gradle` contains the hoist-core MCP install snippet (marked
`hoist-ai-snippet: hoist-core-install/v1`), replace it with the current `v2` version. The `v1`
snippet resolves `runtimeClasspath` while Gradle configures the build, which fails under the
Grails 8 Gradle plugin.

**Find affected files:**
```bash
grep -n "hoist-core-install/v1" build.gradle
```

Before:
```groovy
// hoist-ai-snippet: hoist-core-install/v1 -- DO NOT REMOVE (drift marker, see hoist-core/mcp/README.md)
configurations {
    hoistCoreCli
}

dependencies {
    hoistCoreCli providers.provider {
        def hc = configurations.runtimeClasspath.incoming.resolutionResult.allComponents
            .find { it.moduleVersion?.group == 'io.xh' && it.moduleVersion?.name == 'hoist-core' }
        if (!hc) throw new GradleException(
            'hoist-core not found on the runtimeClasspath -- cannot install hoist-core tools.')
        "io.xh:hoist-core-mcp:${hc.moduleVersion.version}:all@jar"
    }
}
```

After:
```groovy
// hoist-ai-snippet: hoist-core-install/v2 -- DO NOT REMOVE (drift marker, see hoist-core/mcp/README.md)
configurations {
    hoistCoreCli
}

configurations.hoistCoreCli.withDependencies { deps ->
    def hc = configurations.runtimeClasspath.incoming.resolutionResult.allComponents
        .find { it.moduleVersion?.group == 'io.xh' && it.moduleVersion?.name == 'hoist-core' }
    if (!hc) throw new GradleException(
        'hoist-core not found on the runtimeClasspath -- cannot install hoist-core tools.')
    deps.add(project.dependencies.create("io.xh:hoist-core-mcp:${hc.moduleVersion.version}:all@jar"))
}
```

The `installHoistCoreTools` task that follows is unchanged. The full current snippet is in
[`mcp/README.md`](../../mcp/README.md).

#### 4c. dependencyManagement blocks

Grails 8 no longer applies Spring's dependency-management plugin. If your `build.gradle` has a
`dependencyManagement { }` block, migrate it as described under "Spring Dependency Management Plugin Replaced by Gradle Platforms"
in the [Grails 8 upgrade guide](https://grails.apache.org/docs/8.0.0/guide/upgrading.html).

### 5. Move Upload Limits in application.groovy

Grails 8 fails at startup if `grails.controllers.upload` is set. Hoist Core now sets its 20MB
defaults via the Spring Boot keys. Apps that override the limits must move them.

**Find affected files:**
```bash
grep -rn "controllers.upload\|upload {" grails-app/conf/
```

Before:
```groovy
grails {
    controllers {
        upload {
            maxFileSize = 52428800
            maxRequestSize = 52428800
        }
    }
}
```

After:
```groovy
spring {
    servlet.multipart.maxFileSize = '50MB'
    servlet.multipart.maxRequestSize = '50MB'
}
```

Set these after the `ApplicationConfig.defaultConfig(this)` call, so they override Hoist's
defaults.

### 6. Qualify Static References in Domain constraints

Groovy 5 no longer resolves a trait's static methods through the trait name, nor certain
unqualified static references inside a domain class's static `constraints` closure. Qualify them
with the domain class name. These fail at runtime, typically at startup or on first validation.

**Find affected files:**
```bash
grep -rn "validateUsername" grails-app/ src/
```

Before:
```groovy
class User implements HoistUser {
    static constraints = {
        email blank: false, unique: true, email: true, validator: {
            return validateUsername(it) ?: 'myapp.user.validation.fail.message'
        }
    }
}
```

After:
```groovy
class User implements HoistUser {
    static constraints = {
        email blank: false, unique: true, email: true, validator: {
            return User.validateUsername(it) ?: 'myapp.user.validation.fail.message'
        }
    }
}
```

`HoistUser.validateUsername(username)` must likewise become `User.validateUsername(username)`.

Apply the same qualification to a domain class's own static constants and methods referenced
within `constraints`. To find candidates, look for upper-case constants and validators near each
`constraints` block:

```bash
grep -rn -A25 "static constraints" grails-app/domain/ | grep -E "\b[A-Z][A-Z0-9_]{2,}\b|validator"
```


Before:
```groovy
static constraints = {
    msg(maxSize: MAX_MSG_LENGTH)
    name(validator: { val, obj -> isNameUnique(val, obj) ?: 'default.not.unique.message' })
}
```

After:
```groovy
static constraints = {
    msg(maxSize: TrackLog.MAX_MSG_LENGTH)
    name(validator: { val, obj -> JsonBlob.isNameUnique(val, obj) ?: 'default.not.unique.message' })
}
```

### 7. Update the dataSource properties Block

A nested `properties { }` block inside a `dataSource { }` closure fails at startup under Groovy 5
with `ReadOnlyPropertyException: Cannot set read-only property: properties`, as `properties` now
resolves to the config object's own getter. Move the pool settings out of the closure and set
them on `dataSource.properties` directly.

**Find affected files:**
```bash
grep -rn "properties {" grails-app/conf/ src/main/groovy/
```

Before (e.g. in `src/main/groovy/com/example/myapp/DBConfig.groovy`):
```groovy
dataSource {
    url = dbUrl
    username = dbUser
    password = dbPassword

    properties {
        initialSize = 5
        maxActive = 50
        validationQuery = "/* ping */ SELECT 1"

        dbProperties {
            useSSL = dbHost != 'localhost'
        }
    }
}
```

After:
```groovy
dataSource {
    url = dbUrl
    username = dbUser
    password = dbPassword
}

dataSource.properties.with {
    initialSize = 5
    maxActive = 50
    validationQuery = "/* ping */ SELECT 1"
}

dataSource.properties.dbProperties.with {
    useSSL = dbHost != 'localhost'
}
```

Place the new blocks after the `dataSource { }` block, in the same config scope. See Toolbox's
[`DBConfig.groovy`](https://github.com/xh/toolbox/blob/develop/src/main/groovy/io/xh/toolbox/DBConfig.groovy)
for a full example.

### 8. Update Custom Jackson Serializers

Hoist Core now uses Jackson 3 (`tools.jackson` packages) for `JSONSerializer` and `JSONParser`.
Apps that register custom serializers or modules via `JSONSerializer.registerModules()` must port
them. Jackson 2 remains on the classpath for third-party libraries, so stale imports may still
compile - check them explicitly.

**Find affected files:**
```bash
grep -rln "com.fasterxml.jackson" grails-app/ src/
```

Before:
```java
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;

public class MyTypeSerializer extends StdSerializer<MyType> {
    public MyTypeSerializer() { super(MyType.class); }

    @Override
    public void serialize(MyType value, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeString(value.toString());
    }
}
```

After:
```java
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

public class MyTypeSerializer extends StdSerializer<MyType> {
    public MyTypeSerializer() { super(MyType.class); }

    @Override
    public void serialize(MyType value, JsonGenerator jgen, SerializationContext context) {
        jgen.writeString(value.toString());
    }
}
```

`JSONParser` and `JSONSerializer` no longer throw checked exceptions. Review any `try`/`catch`
blocks around them that catch `IOException` or `JsonProcessingException`.

See Hoist's own serializers in `io.xh.hoist.json.serializer` and the
[Jackson 3 migration guide](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md)
for other API changes.

### 9. Review Default Security Headers

Grails 8 adds default security headers to every response it serves: `X-Content-Type-Options:
nosniff`, `X-Frame-Options: SAMEORIGIN`, `Referrer-Policy: strict-origin-when-cross-origin` and
`X-XSS-Protection: 0`. Headers already set on a response are left alone.

Hoist apps typically serve their client from nginx and proxy API calls to Tomcat, so these
headers appear only on proxied API responses, which are not framed. Where nginx also sets the same
headers (as the `xh-nginx` image does for `X-Frame-Options`), proxied responses carry duplicates
with identical values, which browsers accept. No change is needed in that setup.

Review this if your app serves HTML directly from Grails and must be framed by another origin, or
if your nginx config sets different values for these headers. Configure or disable the Grails
defaults via `grails.security.headers.*` - see "Default Security Response Headers" in the
[Grails 8 upgrade guide](https://grails.apache.org/docs/8.0.0/guide/upgrading.html).

### 10. Review Other Grails 8 Changes

The [Grails 8 upgrade guide](https://grails.apache.org/docs/8.0.0/guide/upgrading.html) lists
further changes. Most do not affect typical Hoist apps, but review it for features your app uses
directly - for example `@SpringBootTest` tests, GORM `sort`/`order` arguments taken from client
input, `count()` in `@CompileStatic` code, Quartz jobs, or custom Spring Boot configuration.

Grails 8 makes unconstrained domain properties nullable by default. Hoist Core restores the
Grails 7 default (`grails.gorm.default.nullable = false`), so no domain class changes are needed
for this.

To find renamed or removed Spring Boot configuration keys, temporarily add
`runtimeOnly 'org.springframework.boot:spring-boot-properties-migrator'` to `build.gradle` and
check the startup log for warnings.

## Verification Checklist

After completing all steps:

- [ ] `./gradlew compileGroovy` succeeds
- [ ] `./gradlew test` passes, if the app has server-side tests
- [ ] Application starts without errors, including domain class validation and the datasource pool
- [ ] Admin Console loads and is functional
- [ ] Authentication works (login/logout)
- [ ] Saving a domain object with a validator (e.g. a user with `validateUsername`) works
- [ ] File uploads and grid exports work, within the configured upload limits
- [ ] JSON responses are unchanged, including dates and any custom serializers
- [ ] The Docker image builds and starts on the Tomcat 11 base image
- [ ] No Jackson 2 imports remain in app code: `grep -rn "com.fasterxml.jackson" grails-app/ src/`
- [ ] No `grails.controllers.upload` config remains: `grep -rn "controllers.upload" grails-app/conf/`

## Reference

- [Grails 8 upgrade guide](https://grails.apache.org/docs/8.0.0/guide/upgrading.html)
- [Spring Boot 4.0 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- [Groovy 5 release notes](https://groovy-lang.org/releasenotes/groovy-5.0.html)
- [Jackson 3 migration guide](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md)
- [Gradle 9 upgrade guide](https://docs.gradle.org/current/userguide/upgrading_major_version_9.html)
- [Toolbox on GitHub](https://github.com/xh/toolbox) - canonical example of a Hoist app
