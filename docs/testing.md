# Testing

## Overview

Hoist Core uses [Spock](https://spockframework.org/) (2.3, Groovy 4) on the JUnit Platform for unit
tests, with JaCoCo for coverage. The same framework is published for applications as
`io.xh:hoist-core-test`, a test-support library that builds on the standard Grails unit-test
traits (`ServiceUnitTest`, `ControllerUnitTest`) so that app code built on `BaseService`,
`ConfigService`, identity and logging can be unit tested.

There are two audiences for this doc:

- **App developers** writing specs for services and controllers in a Hoist app - start with
  [Testing Hoist Applications](#testing-hoist-applications).
- **Library developers** adding specs to hoist-core itself - see
  [Testing hoist-core](#testing-hoist-core).

## Testing Hoist Applications

### Setup

Add a single test dependency, at the same version as `hoist-core`, and run tests on the JUnit
Platform:

```groovy
dependencies {
    testImplementation "io.xh:hoist-core-test:$hoistCoreVersion"
}

tasks.withType(Test).configureEach {
    useJUnitPlatform()
}
```

`hoist-core-test` brings in `spock-core`, Grails testing support (`grails-testing-support-web`
and `-datamapping`, for `ControllerUnitTest`, `ServiceUnitTest` and `DataTest`), and the
byte-buddy and objenesis libraries Spock needs to mock concrete classes. Versions are aligned with
the Grails BOM used by hoist-core.

Add a `src/test/resources/logback-test.xml` to keep test output quiet - hoist-core-test
deliberately ships no logging config, so it never overrides an app's own.

### Why Hoist needs more than the Grails test context

Hoist apps test with the standard Grails testing traits - `ServiceUnitTest`, `ControllerUnitTest`,
`DataTest` and so on - which build a lightweight Grails application and Spring context per spec
and register it with Grails `Holders`. Hoist code needs two things on top of that:

- **Framework beans.** Every `logInfo` / `logWarn` call resolves the current user via
  `Utils.identityService`, and services read `configService` and `clusterService`. These are
  static `Utils` lookups against the Spring context, called from `@CompileStatic` code, so they
  can't be stubbed with metaclass tricks - the beans must exist.
- **Hoist environment settings.** `createCache`, `createCachedValue` and `createTimer` consult
  `ClusterService`, whose cluster config requires an app code, and `Utils.appEnvironment` requires
  a resolvable Hoist environment.

`hoist-core-test` provides both:

- `HoistSpockGlobalExtension` (auto-registered) calls `HoistTestEnvironment.ensureInitialized()`
  before any spec runs. It sets the `io.xh.hoist.environment` (`Test`),
  `io.xh.hoist.instanceConfigFile` (a temp file with `multiInstanceEnabled: 'false'`),
  `info.xh.appCode` (`hoist-test`) and `info.app.version` system properties - each only if not
  already set. Caches and timers are therefore always local, and a developer's
  `/etc/hoist/conf` file is never read.
- The `HoistUnitTest` trait extends Grails' `GrailsUnitTest`. Before each feature it registers
  test implementations of the framework services in the Grails test context, autowired by name,
  and resets their state.

### Writing a service or controller spec

Implement the standard Grails trait for the artefact under test, plus `HoistUnitTest`:

```groovy
import grails.testing.services.ServiceUnitTest
import io.xh.hoist.test.HoistUnitTest
import io.xh.hoist.test.TestUser
import spock.lang.Specification

class WeatherServiceSpec extends Specification implements ServiceUnitTest<WeatherService>, HoistUnitTest {

    def 'builds the request URL from soft config'() {
        given:
        testConfigService.set('weatherApiKey', 'abc')

        expect:
        service.buildUrl('NYC').contains('key=abc')
    }

    def 'admin-only action is rejected for a regular user'() {
        given:
        loginAs(new TestUser('bob'))

        when:
        service.resetAll()

        then:
        thrown(NotAuthorizedException)
    }
}
```

Controllers work the same way, with `ControllerUnitTest<MyController>` and its `controller`,
`request` and `response`. Use `HoistJson.parse(response.text)` to check output from
`renderJSON`, since it uses Hoist's own serializer.

`service` is created per feature by Grails, with field-initialized `createCache(...)` calls run and
beans injected by name. Call `service.init()` directly if the code under test sets up resources
there. For collaborators, use `defineService(OtherService)`, or the standard Grails `defineBeans`
/ `doWithSpring()` - e.g. `defineBeans { otherService(InstanceFactoryBean, Mock(OtherService),
OtherService) }` for a Spock mock (`org.grails.spring.beans.factory.InstanceFactoryBean`).

For code that is not a single Grails artefact - utilities or value objects that log or read soft
config - extend `HoistSpec`, which is shorthand for `extends Specification implements
HoistUnitTest`.

### Harness API

All classes are in package `io.xh.hoist.test`.

| Class | Purpose |
|---|---|
| `HoistUnitTest` | Grails unit-test trait. Registers the beans below before each feature unless the spec defined its own in `doWithSpring()`; resets config, users and primary status before each feature and clears identity after. Adds `testConfigService`, `testUserService`, `testClusterService`, `identityService`, `defineService`, `loginAs`, `impersonate`, `logout`, `withUser` and `useAppTimeZone`. |
| `HoistSpec` | Abstract `Specification` implementing `HoistUnitTest`. |
| `TestConfigService` | `configService` bean. Map-backed `ConfigService` - seed with `set` / `setAll`. All typed getters (`getString`, `getInt`, `getMap`, ...) follow `ConfigService` semantics, including throwing for a missing config without a default. `registerTypedConfig` supports `getObject` for `TypedConfigMap` subclasses. |
| `TestUserService` | `userService` bean. In-memory `BaseUserService`: `add`, `find`, `list`, `clear`. |
| `TestClusterService` | `clusterService` bean. Reports `isPrimary` from `primaryInstance` (default `true`), so `primaryOnly` timers run. |
| `TestUser` | `HoistUser` with an explicit role set - no role service required. |
| `HoistJson` | Serialize and round-trip with Hoist's own Jackson `JSONSerializer` / `JSONParser`, to assert the real wire format of `JSONFormat` objects. |
| `HoistAssertions` | `httpStatusFor(Throwable)` and `isRoutine(Throwable)` mirror `ExceptionHandler`; `findUnsecuredActions` / `assertAllActionsSecured` check that every controller action has a Hoist access annotation. |

The real `IdentityService` is registered as `identityService`, and an `ExceptionHandler` as
`xhExceptionHandler`. To substitute any of these beans - e.g. an app-specific config service -
define a bean of the same name in `doWithSpring()`.

A useful one-line spec for any app:

```groovy
def 'all #controller.simpleName actions are secured'() {
    expect:
    HoistAssertions.findUnsecuredActions(controller).isEmpty()

    where:
    controller << [MyController, ReportController]
}
```

### Limitations

- The Grails test context is built once per spec class and shared by its features. `HoistUnitTest`
  resets its own beans before each feature, but beans a feature defines itself (e.g. via
  `defineBeans`) persist into later features - remove them in `cleanup()` if needed.
- Thread identity is per thread, so features must not run in parallel within a JVM (Spock runs
  sequentially by default), and async work run via Grails `task {}` does not inherit it.
- No Hazelcast instance is started. `createIMap`, `createReplicatedMap`, `getTopic` and
  `subscribeToTopic` are not available; `replicate: true` caches and cached values behave as local.
- Environment variables named `APP_<APPCODE>_*` still take precedence over instance config.

## Testing hoist-core

### Layout and conventions

- Specs live in `src/test/groovy`, in the same package as the class under test, named
  `<ClassName>Spec`. Specs for the harness itself live in `testing/src/test/groovy`.
- Several **main** classes already end in `Spec` (`ConfigSpec`, `PreferenceSpec`, `MonitorSpec`,
  `RoleSpec`, `CounterSpec`, `TimerSpec`). Where the conventional spec name would collide, use
  `<ClassName>UnitSpec`. The `checkTestClassShadowing` task, part of `check`, fails the build if a
  test class has the same fully-qualified name as a main class.
- hoist-core's own tests depend on `hoist-core-test`, so every harness feature is exercised by the
  library's own build. Use `HoistUnitTest` (or `HoistSpec`) for code that logs at WARN or above,
  or reaches `Utils` service accessors.
- Use data-driven `where:` tables for edge cases, `thrown()` for exceptions, and
  `PollingConditions` (never `Thread.sleep`) for asynchronous behavior. Restore any global state in
  `cleanup()`. Don't call `JSONSerializer.registerModules()` - it mutates global, append-only state.
- If a spec uncovers a suspected bug, assert the *correct* behavior and annotate the feature with
  `@PendingFeature(reason = '...')`. The build stays green, the issue is documented, and Spock will
  fail the feature once the bug is fixed - prompting removal of the annotation.

### Running

```bash
./gradlew test                          # root project specs (+ JaCoCo report)
./gradlew check                         # all projects, incl. shadowing guard
./gradlew :test --tests 'io.xh.hoist.data.filter.*'
./gradlew test -PtestJavaVersion=17     # run tests on JDK 17 (compile stays on the JDK 25 toolchain)
```

Reports: `build/reports/tests/test/index.html` and `build/reports/jacoco/test/html/index.html`
(and the same under `testing/build`).

### CI

The CI workflow runs `./gradlew build` on a JDK 17 and JDK 25 matrix, passing `-PtestJavaVersion`
so each row really runs tests on that JDK - the 17 row guards the published bytecode target. Each
row publishes a test summary and failure annotations to the job summary, the JDK 25 row adds a
coverage summary, and test reports are uploaded as an artifact on failure. Coverage is report-only
for now - no minimum threshold is enforced. See [`build-and-publish.md`](./build-and-publish.md).
