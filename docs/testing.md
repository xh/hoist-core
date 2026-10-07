# Testing

## Overview

Hoist Core uses [Spock](https://spockframework.org/) (2.3, Groovy 4) on the JUnit Platform for unit
tests, with JaCoCo for coverage. The same framework is published for applications as
`io.xh:hoist-core-test`, a test-support library that lets app code built on `BaseService`,
`ConfigService`, identity and logging be unit tested without starting a Grails application.

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

### Why a harness is needed

Hoist services can't simply be instantiated in a plain Spock spec:

- Every `logInfo` / `logWarn` call resolves the current user via `Utils.identityService`, which
  requires a running Spring `ApplicationContext` (registered with Grails `Holders`).
- `createCache`, `createCachedValue` and `createTimer` consult `ClusterService`, whose cluster
  config requires an app code, and `Utils.appEnvironment` requires a resolvable Hoist environment.
- Static `Utils` accessors are called from `@CompileStatic` code, so metaclass stubbing can't
  intercept them.

`hoist-core-test` solves these through public APIs only:

- `HoistSpockGlobalExtension` (auto-registered) calls `HoistTestEnvironment.ensureInitialized()`
  before any spec runs. It sets the `io.xh.hoist.environment` (`Test`),
  `io.xh.hoist.instanceConfigFile` (a temp file with `multiInstanceEnabled: 'false'`),
  `info.xh.appCode` (`hoist-test`) and `info.app.version` system properties - each only if not
  already set. Caches and timers are therefore always local, and a developer's
  `/etc/hoist/conf` file is never read.
- `HoistTestContext` installs a lightweight Spring context with test implementations of the core
  services, registered with `Holders` so the static `Utils` accessors work.

### Writing a service spec

Extend `HoistSpec` (or annotate any `Specification` with `@HoistTest`). A fresh `HoistTestContext`
is installed before each feature and closed after it, so features are isolated.

```groovy
import io.xh.hoist.test.HoistSpec
import io.xh.hoist.test.TestUser

class WeatherServiceSpec extends HoistSpec {

    def 'builds the request URL from soft config'() {
        given:
        hoist.configService.set('weatherApiKey', 'abc')
        def svc = createService(WeatherService)

        expect:
        svc.buildUrl('NYC').contains('key=abc')
    }

    def 'admin-only action is rejected for a regular user'() {
        given:
        def svc = createService(WeatherService)
        loginAs(new TestUser('bob'))

        when:
        svc.resetAll()

        then:
        thrown(NotAuthorizedException)
    }
}
```

`createService(Class, props)` instantiates the service, injects beans by name (e.g. a
`ConfigService configService` property gets the `TestConfigService`), applies any extra `props`
(useful for passing Spock mocks of app collaborators), registers the service as a bean for later
services, and destroys it when the context closes. Field-initialized `createCache(...)` calls work.
Call `svc.init()` directly if the code under test sets up resources there.

### Harness API

All classes are in package `io.xh.hoist.test`.

| Class | Purpose |
|---|---|
| `HoistSpec` / `@HoistTest` | Install and tear down a `HoistTestContext` around each feature. `HoistSpec` adds `hoist`, `createService` and `loginAs` shortcuts. |
| `HoistTestContext` | The installed context: `configService`, `userService`, `identityService`, `clusterService`; `registerBean`, `getBean`, `autowire`, `createService`; identity via `loginAs`, `impersonate`, `logout`, `withUser`; `setAppTimeZone`. |
| `TestConfigService` | Map-backed `ConfigService`. Seed with `set` / `setAll`; all typed getters (`getString`, `getInt`, `getMap`, ...) follow `ConfigService` semantics, including throwing for a missing config without a default. `registerTypedConfig` supports `getObject` for `TypedConfigMap` subclasses. |
| `TestUser` | `HoistUser` with an explicit role set - no role service required. |
| `TestUserService` | In-memory `BaseUserService`: `add`, `find`, `list`, `clear`. |
| `TestClusterService` | `ClusterService` reporting `isPrimary` from `primaryInstance` (default `true`), so `primaryOnly` timers run. |
| `HoistJson` | Serialize and round-trip with Hoist's own Jackson `JSONSerializer` / `JSONParser`, to assert the real wire format of `JSONFormat` objects. |
| `HoistAssertions` | `httpStatusFor(Throwable)` and `isRoutine(Throwable)` mirror `ExceptionHandler`; `findUnsecuredActions` / `assertAllActionsSecured` check that every controller action has a Hoist access annotation. |

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

- The context and thread identity are static, so features using the harness must not run in
  parallel (Spock runs sequentially by default).
- No Hazelcast instance is started. `createIMap`, `createReplicatedMap`, `getTopic` and
  `subscribeToTopic` are not available; `replicate: true` caches and cached values behave as local.
- Async work run via Grails `task {}` does not inherit the test identity.
- Environment variables named `APP_<APPCODE>_*` still take precedence over instance config.
- GORM-backed code needs Grails `DataTest` / `DomainUnitTest` (included) or integration tests;
  `HoistTestContext` does not provide a datastore.

## Testing hoist-core

### Layout and conventions

- Specs live in `src/test/groovy`, in the same package as the class under test, named
  `<ClassName>Spec`. Specs for the harness itself live in `hoist-core-test/src/test/groovy`.
- Several **main** classes already end in `Spec` (`ConfigSpec`, `PreferenceSpec`, `MonitorSpec`,
  `RoleSpec`, `CounterSpec`, `TimerSpec`). Where the conventional spec name would collide, use
  `<ClassName>UnitSpec`. The `checkTestClassShadowing` task, part of `check`, fails the build if a
  test class has the same fully-qualified name as a main class.
- hoist-core's own tests depend on `hoist-core-test`, so every harness feature is exercised by the
  library's own build. Extend `HoistSpec` for code that logs at WARN or above, or reaches `Utils`
  service accessors.
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
(and the same under `hoist-core-test/build`).

### CI

The CI workflow runs `./gradlew build` on a JDK 17 and JDK 25 matrix, passing `-PtestJavaVersion`
so each row really runs tests on that JDK - the 17 row guards the published bytecode target. Each
row publishes a test summary and failure annotations to the job summary, the JDK 25 row adds a
coverage summary, and test reports are uploaded as an artifact on failure. Coverage is report-only
for now - no minimum threshold is enforced. See [`build-and-publish.md`](./build-and-publish.md).
