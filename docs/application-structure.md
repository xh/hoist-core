# Application Structure

This document describes the standard directory layout of a Hoist application repository. A Hoist
app combines a Grails server and a React/TypeScript client in a single repository. XH-built apps
all use this structure, so it is the map for any Hoist codebase.

This document covers the server side in full and the client side as layout only. Client-side
tooling and framework facts live in their own repos. See [Client Side](#client-side-client-app)
for where to find them.

## Overview

A Hoist application is a **single Git repository** that holds both server and client code. The
server is a Grails application that includes hoist-core as a plugin dependency. The client is a
React/TypeScript application that consumes hoist-react as an npm package. The two halves build,
test, and deploy together. They run as separate processes in development and as separate
containers in production.

The server runs on Tomcat via Grails and Spring Boot. It serves a REST API under a configurable
path, typically `/api/`. The client is an SPA bundled with Rsbuild and served by Nginx, which also
reverse-proxies API requests to Tomcat. In local development, the Rsbuild dev server proxies API
calls to the Grails `bootRun` process.

## Root Directory

Every Hoist app repository has the same top-level shape:

```
my-app/
├── grails-app/              # Server-side Grails application code
├── src/main/groovy/         # Additional server-side source (non-artifact classes)
├── client-app/              # Client-side React/TypeScript application
│   ├── package.json         # Dependencies and scripts (@xh/hoist, React, ag-Grid, etc.)
│   ├── pnpm-lock.yaml       # Dependency lock file
│   └── ...
├── docker/                  # Docker build files (Nginx + Tomcat)
│   ├── nginx/
│   └── tomcat/
├── gradle/                  # Gradle wrapper distribution
├── build.gradle             # Gradle build configuration
├── settings.gradle          # Project name + optional composite build for inline hoist-core
├── gradle.properties        # App metadata, framework versions, dev flags
├── .env.template            # Required/optional environment variables (template)
├── .env                     # Local environment values (git-ignored)
├── gradlew / gradlew.bat    # Gradle wrapper scripts
├── CHANGELOG.md             # Version history
└── README.md                # Project documentation
```

Some apps also include:

- `helm/` - Kubernetes Helm charts, for k8s deployments
- `infra/` - Infrastructure-as-code, for example AWS CDK
- `docs/` - Additional project documentation
- `bin/` - Utility scripts

## Build Configuration

### `gradle.properties`

Defines app identity and framework versions. Every app declares the same set of core properties.
Read the app's own copy for the versions it uses:

```properties
xhAppCode=myApp
xhAppName=My Application
xhAppPackage=com.example.myapp
xhAppVersion=3.0-SNAPSHOT
majorJavaVersion=<version>

grailsVersion=<version>
hoistCoreVersion=<version>
dotEnvGradlePluginVersion=<version>

runHoistInline=false
enableHotSwap=false
localDevXmx=2G

org.gradle.daemon=true
org.gradle.parallel=true
org.gradle.jvmargs=-Dfile.encoding=UTF-8 -Xmx1024M
```

Key properties:

| Property | Purpose |
|----------|---------|
| `xhAppCode` | Short identifier used in instance config env vars, log file names, and framework internals |
| `xhAppPackage` | Root Java/Groovy package for all server-side code |
| `majorJavaVersion` | JDK version for the compile toolchain in `build.gradle` - see [JDK Choice](#jdk-choice) |
| `hoistCoreVersion` | The published hoist-core version to use (ignored when `runHoistInline=true`) |
| `runHoistInline` | When `true`, builds hoist-core from a local checkout via Gradle composite build - see [Inline Hoist Development](#inline-hoist-development) |
| `enableHotSwap` | Enables the JVM HotSwap agent for faster server-side dev iteration |
| `localDevXmx` | JVM max heap for local `bootRun` |

### `settings.gradle`

Sets the project name. In inline mode, it also adds a local hoist-core checkout as a Gradle
composite build:

```groovy
import static java.lang.Boolean.parseBoolean

rootProject.name = 'my-app'

if (parseBoolean(runHoistInline)) {
    def hoistCorePath = System.getenv('XH_HOIST_CORE_PATH') ?: '../hoist-core'
    println "${xhAppName}: running with Hoist Core INLINE from ${hoistCorePath}...."
    includeBuild hoistCorePath
} else {
    println "${xhAppName}: running with Hoist Core PACKAGED at v${hoistCoreVersion}...."
}
```

### `build.gradle`

Configures the Grails web plugin and declares the hoist-core dependency and database drivers. It
also sets the compile toolchain and the JVM arguments for `bootRun`, and extends
`grails.build.info` with Hoist metadata. It fails `bootRun` early if `.env` does not set a
variable that `.env.template` lists.

This file is much the same across apps. The main differences are the database drivers and
app-specific dependencies, for example JWT libraries or cloud SDKs.

### JDK Choice

hoist-core compiles with a newer JDK toolchain than the bytecode level it targets. The `java`
block in hoist-core's `build.gradle` sets both. An app can build and run on any JDK at or above
that bytecode level, if the app's Grails version supports it. The app does not have to match the
toolchain JDK that hoist-core builds with.

Apps pin the compile JDK with a toolchain, whatever JVM the Gradle daemon runs on. The
`majorJavaVersion` property in `gradle.properties` sets the version:

```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(Integer.parseInt(majorJavaVersion))
    }
}
```

For Docker images, use the xh-tomcat base image tag that matches your runtime JDK. The tags name
the Tomcat and JDK versions.

### `.env.template` and `.env`

Instance configuration comes from environment variables or from a YAML file. In local
development, apps use environment variables. The
[`co.uzzu.dotenv.gradle`](https://github.com/uzzu/dotenv-gradle) plugin loads them from `.env`
into `bootRun`. The `.env.template` file lives in source control and lists all required and
optional variables. Developers copy it to `.env`, which git ignores, and fill in local values.

Server code reads a value with `InstanceConfigUtils.getInstanceConfig(key)`. For the key `dbHost`,
it first checks the env var `APP_{APP_CODE}_DB_HOST`, with both the app code and the key in upper
snake case. For an app code of `myApp`, that is `APP_MY_APP_DB_HOST`. If the env var is not set,
it falls back to the YAML file. The YAML file defaults to `/etc/hoist/conf/{appCode}.yml`, and the
`io.xh.hoist.instanceConfigFile` system property overrides that path.

Common variables include database connection details, the environment name, OAuth credentials,
SMTP settings, and bootstrap admin user credentials.

## Server Side (`grails-app/`)

The server follows standard Grails conventions. All application code lives under the app's root
package, for example `com.example.myapp`.

```
grails-app/
├── conf/
│   ├── application.groovy    # Grails config, delegates to Hoist defaults
│   ├── runtime.groovy        # Runtime config: datasource, mail
│   └── ehcache.xml           # Hibernate cache config (if needed)
├── controllers/{package}/
│   ├── BaseController.groovy # App-specific base controller
│   ├── UrlMappings.groovy    # Custom URL mappings (if needed)
│   └── ...                   # Feature-specific controllers
├── domain/{package}/
│   └── ...                   # GORM domain classes
├── init/{package}/
│   ├── Application.groovy    # Spring Boot entry point (boilerplate)
│   ├── BootStrap.groovy      # Startup initialization
│   ├── ClusterConfig.groovy  # Hazelcast network configuration
│   └── LogbackConfig.groovy  # Logging configuration
├── services/{package}/
│   └── ...                   # Grails services (business logic)
└── i18n/                     # Internationalization resources
```

### Init Files

Every Hoist app provides exactly four files in `grails-app/init/`:

**`Application.groovy`** - Boilerplate Spring Boot entry point, the same in every app:

```groovy
@CompileStatic
class Application extends GrailsAutoConfiguration {
    static void main(String[] args) {
        GrailsApp.run(Application, args)
    }
}
```

**`BootStrap.groovy`** - Startup initialization. It should implement `LogSupport` if it logs. It
has an `init` closure that:
1. Optionally logs a startup message
2. Calls `configService.ensureRequiredConfigsCreated()` to register all app-specific soft configs
3. Calls `prefService.ensureRequiredPrefsCreated()` to register all app-specific preferences
4. Calls `roleService.ensureRequiredRolesCreated()` to register app-specific roles. This step is
   optional but recommended for apps that manage roles with `DefaultRoleService`.
5. Optionally creates a bootstrap admin user for local development
6. Calls `parallelInit()` on app-specific services to initialize them concurrently

**`ClusterConfig.groovy`** - Extends `io.xh.hoist.ClusterConfig` to configure Hazelcast. Hoist
leaves the Hazelcast default of multicast discovery in place. Multi-instance apps typically
override `createNetworkConfig()` to set discovery for their environment, for example TCP/IP on
localhost in development and AWS discovery in production. Single-instance apps turn off
multi-instance mode with the `multiInstanceEnabled` instance config or by overriding
`getMultiInstanceEnabled()`. Apps can also add Hazelcast settings here, such as cache sizes.

**`LogbackConfig.groovy`** - Extends `io.xh.hoist.LogbackConfig` to inherit Hoist's default
logging configuration. Apps that need custom log formats override methods here. Apps that do not
must still include this class, with an empty body, to inherit the base configuration.

### Configuration Files

**`application.groovy`** - Delegates to Hoist's default configuration, then adds app-specific
overrides:

```groovy
import io.xh.hoist.configuration.ApplicationConfig

ApplicationConfig.defaultConfig(this)

hibernate {
    show_sql = false
}
```

Apps can also add other Grails and Hibernate settings here, for example CORS origins, upload size
limits, or a Hibernate default schema.

**`runtime.groovy`** - Configures the datasource, mail, and other runtime settings. It calls
Hoist's `RuntimeConfig.defaultConfig(this)` for the baseline config. Then it adds the app's
database connection, read from instance configs, and optional SMTP configuration. Some apps also
set CORS options here.

Many apps move database configuration into a separate `DBConfig` class in `src/main/groovy/` for
clarity. This is a common pattern, not a requirement.

### Required App-Provided Services

Every Hoist app must provide implementations of three abstract services. See
[`authentication.md`](./authentication.md) and [`authorization.md`](./authorization.md) for
details.

| Service | Base Class | Purpose |
|---------|-----------|---------|
| `AuthenticationService` | `BaseAuthenticationService` | Defines the authentication scheme (OAuth, SSO, form-based) |
| `UserService` | `BaseUserService` | User lookup, creates `HoistUser` instances |
| `RoleService` | `BaseRoleService` | Role assignment (or extend the built-in `DefaultRoleService`) |

Apps that use monitors must also provide:

| Service | Base Class | Purpose |
|---------|-----------|---------|
| `MonitorDefinitionService` | `BaseService` (or extend the built-in `DefaultMonitorDefinitionService`) | Defines application health checks - see [`monitoring.md`](./monitoring.md) |

### Controllers

Apps define an abstract base controller, usually named `BaseController`, that extends
`io.xh.hoist.BaseController`. This base is typically minimal, sometimes empty. It gives a hook for
app-wide controller behavior, for example casting `getUser()` to an app-specific user type.

Feature controllers extend this base, or `RestController` for CRUD endpoints. They carry access
annotations such as `@AccessRequiresRole` and `@AccessAll`. They delegate business logic to
services and return responses with `renderJSON()`.

An app that needs URL routing beyond the default `/$controller/$action?/$id?` pattern can define a
`UrlMappings.groovy` file. Apps that use only the standard patterns do not need one, because the
default mappings from hoist-core are sufficient.

### Services

App services extend `BaseService` from hoist-core. Many do so through an app-specific
intermediate base class, for example `BaseMyAppService`, that adds shared helpers. Apps organize
services by feature area within the app package.

Common service subpackages include:
- `security/` - `AuthenticationService`, `UserService`, `RoleService`, OAuth token services
- Feature-specific packages that match the app's domain

### Domain Classes

GORM domain classes for app-specific persistent data. The hoist-core plugin provides Hoist's own
domain classes, such as AppConfig, Preference, and TrackLog. Apps do not need to redeclare them.

### `src/main/groovy/`

Classes that are not Grails artifacts: POGOs, enums, utility classes, and helper code that does not
need to be a Grails service, controller, or domain class. Common examples include:

- `DBConfig.groovy` - Database configuration helper used by `runtime.groovy`
- Data transfer objects and query result wrappers
- Enum types
- Utility classes

## Client Side (`client-app/`)

The client app is a React/TypeScript application. Its primary dependency is `@xh/hoist`
(hoist-react), and its build tooling comes from `@xh/hoist-dev-utils`.

This section covers only the layout of `client-app/` and the conventions apps share. hoist-core
does not own client-side facts. Details copied here would go stale. Before you state a
client-side fact or change client-side code, check the source:

- **Build, scripts, lint, tests, and inline mode** - the hoist-dev-utils README. The installed copy
  at `client-app/node_modules/@xh/hoist-dev-utils/README.md` matches the version the app uses.
  The [latest README](https://github.com/xh/hoist-dev-utils#readme) is on GitHub.
- **Client framework** - the hoist-react docs. They cover components, state management with MobX,
  grids, and the Admin Console. Use the hoist-react MCP server or CLI tools if the app has them.
  Otherwise, read `client-app/node_modules/@xh/hoist/docs/` or the
  [latest docs](https://github.com/xh/hoist-react/tree/develop/docs) on GitHub.
- **Production builds** - the hoist-react
  [build and deploy guide](https://github.com/xh/hoist-react/blob/develop/docs/build-and-deploy-app.md).

If this document and those sources disagree, trust the sources.

```
client-app/
├── package.json              # Dependencies and scripts
├── rsbuild.config.mjs        # Build configuration, via @xh/hoist-dev-utils
├── tsconfig.json             # TypeScript compiler configuration
├── eslint.config.js          # ESLint configuration
├── pnpm-lock.yaml            # Dependency lock file
├── .nvmrc                    # Node version
├── public/                   # Static assets (favicons, error pages, images)
└── src/
    ├── Bootstrap.ts          # Library initialization and service declarations
    ├── apps/                 # Entry points (one per client app)
    │   ├── app.ts            # Main application entry
    │   └── admin.ts          # Hoist Admin Console entry
    ├── core/                 # Shared infrastructure (services, types, columns, icons)
    │   ├── svc/              # Client-side services
    │   └── ...
    ├── app/ or desktop/      # Main application UI, for the apps/app.ts entry point
    │   ├── AppModel.ts       # Root application model
    │   ├── AppComponent.ts   # Root UI component (sometimes App.ts)
    │   ├── App.scss          # Global application styles
    │   └── ...               # Feature-specific view directories
    ├── admin/                # Admin Console customizations, if any
    └── mobile/               # Mobile app UI, if applicable (matches apps/mobile.ts)
```

### Entry Points (`apps/`)

Each file in `apps/` is a separate entry point: a self-contained client application that shares
the codebase. Every Hoist app has at least:

- **`app.ts`** - The main application. It calls `XH.renderApp()` with the app's root component,
  model, and authentication configuration. By convention, nearly every Hoist app names its primary
  entry point `app.ts`. Its UI code lives in an `app/` or `desktop/` directory.
- **`admin.ts`** - The Hoist Admin Console. It calls `XH.renderAdminApp()` with the framework's
  built-in admin UI. An app that adds its own admin tabs puts that code, such as an `AppModel`
  subclass, in an `admin/` directory.

Some apps define more entry points, for example `mobile.ts` or `clientAdmin.ts`, each with a
matching directory for its UI code.

A typical entry point:

```typescript
import '../Bootstrap';
import {XH} from '@xh/hoist/core';
import {AppContainer} from '@xh/hoist/desktop/appcontainer';
import {AppComponent} from '../app/AppComponent';
import {AppModel} from '../app/AppModel';
import {AuthModel} from '../core/AuthModel';

XH.renderApp({
    clientAppCode: 'app',
    componentClass: AppComponent,
    modelClass: AppModel,
    containerClass: AppContainer,
    authModelClass: AuthModel,
    isMobileApp: false,
    enableLogout: true,
    checkAccess: 'APP_READER'
});
```

### `Bootstrap.ts`

Runs before any app entry point. It handles:

1. **Service declarations** - Imports app-specific client-side services and declares them on the
   `XHApi` interface through TypeScript module augmentation. This makes them available as
   `XH.myService`.
2. **ag-Grid setup** - Registers the required ag-Grid modules, community and enterprise, calls
   `installAgGrid()`, and installs the license key, often from server-side config.
3. **Highcharts setup** - Imports Highcharts modules and installs them with `installHighcharts()`.
4. **HoistUser augmentation** - Declares any app-specific user properties.

### Application UI

Each entry point in `apps/` has a matching directory under `src/` for its UI code. The directory
name often matches the entry point filename, for example `apps/mobile.ts` and `mobile/`. The main
app is the common exception, with its code in either `app/` or `desktop/`. The main application
directory contains:

- **`AppModel.ts`** - Extends `HoistAppModel`, directly or through an app-specific base class.
  Defines the application's top-level state, tab navigation, and overall data loading, and can
  hold app-wide models such as ViewManager instances. This is the model class that the
  entry point passes to `XH.renderApp()`.
- **`AppComponent.ts`** - The root UI component, created with `hoistCmp()`. Some apps name it
  `App.ts`. Renders the app's
  chrome, such as the app bar and tab container, from the `AppModel`.

Feature-specific views live in subdirectories. Each typically holds a model file and one or more
component files.

## Docker / Deployment (`docker/`)

Every app includes a `docker/` directory for two containers:

```
docker/
├── nginx/
│   ├── Dockerfile            # FROM xhio/xh-nginx:<version>
│   └── app.conf              # Nginx site configuration
└── tomcat/
    ├── Dockerfile            # FROM xhio/xh-tomcat:<tag>
    └── setenv.sh             # JVM options (heap size, instance config path)
```

### Nginx Container

Serves the built client assets and reverse-proxies API requests to Tomcat. The site config file,
usually `app.conf`, configures:

- **Security headers** - HSTS, CSP, X-Frame-Options, Permissions-Policy, X-Content-Type-Options
- **SPA routing** - `try_files` fallback to `index.html` for each client app entry point
- **API proxy** - `/api/` (or similar) proxied to `http://localhost:8080/` (Tomcat)
- **Cache policy** - Static JS/CSS cached indefinitely and cache-busted by content hashes,
  `index.html` always re-fetched
- **Root redirect** - `/` redirects to `/app/` (optionally with mobile detection)

### Tomcat Container

Runs the Grails WAR file. The `setenv.sh` script sets the JVM heap size and can set the instance
config file path. The heap defaults to a configured amount, and the `JAVA_XMX` env var overrides
it.

### Deployment Model

In production, both containers run together on the same host or task, for example an AWS ECS
Fargate task. Nginx listens on port 80/443 externally. Tomcat listens on port 8080, internally
only. This sidecar pattern keeps API requests local, with no network hop between the web server
and the app server.

## Local Development

### Starting the Server

```bash
./gradlew bootRun
```

Starts the Grails application on `localhost:8080`. The `.env` file provides instance
configuration, such as database credentials and the environment.

If the `xhExpectedServerTimeZone` soft config names a zone, the JVM time zone must match it, or
startup fails. To match, pass the zone that the error message names, for example
`-Duser.timezone=Etc/UTC`. The default value `*` skips the check.

### Starting the Client

```bash
cd client-app && pnpm start
```

Starts the Rsbuild dev server with hot module replacement. The dev server proxies API requests to
the Grails backend.

### Server-Side Hot Reload

Set `enableHotSwap=true` in `gradle.properties` to reload changed server classes without a full
restart. This uses [HotswapAgent](http://hotswapagent.org/), which needs more local setup:

- A HotswapAgent-enabled JVM. See the
  [HotswapAgent project](https://github.com/HotswapProjects/HotswapAgent) for supported JDKs and
  installation.
- The Groovy Reset agent (`io.xh:groovyReset`). Toolbox resolves it as a Gradle dependency.
- These JVM arguments on startup: `-XX:HotswapAgent=fatjar`,
  `-XX:+AllowEnhancedClassRedefinition`, and `-javaagent:<absolute-path-to-groovyReset.jar>`.

Toolbox has a working example of this setup.

### Inline Hoist Development

To develop hoist-core or hoist-react alongside an app, check out the framework repos as siblings
of the app and turn on inline mode:

- **hoist-core**: Set `runHoistInline=true` in `gradle.properties` or
  `~/.gradle/gradle.properties`, or pass `-PrunHoistInline=true` to Gradle.
- **hoist-react**: Run the `startWithHoist` script instead of `start`.

To use a checkout at another path, such as a git worktree, set these env vars to absolute paths:

- `XH_HOIST_CORE_PATH` - read by `settings.gradle`. Defaults to `../hoist-core`.
- `XH_HOIST_REACT_PATH` - the client-side equivalent, read by `@xh/hoist-dev-utils` 16.3+. The
  app's `startWithHoist` and `testWithHoist` scripts must also use it, so check them before you
  rely on it. The hoist-dev-utils README gives the required script form.

Both apply only in inline mode. They let an app worktree build against framework worktrees on
matching feature branches. If the hoist-core path does not exist, Gradle fails with a clear error.

Hot reload does not apply to a Grails plugin built inline. Changes to hoist-core source still need
a server restart.

## Conventions Summary

### Package and Naming

| Convention | Pattern | Example |
|-----------|---------|---------|
| Server package root | `xhAppPackage` from `gradle.properties` | `com.example.myapp` |
| Client app name | `name` field in `client-app/package.json` | `my-app` |
| Instance config env vars | `APP_{APPCODE}_{KEY}` | `APP_MYAPP_DB_HOST` |
| Log file names | `{appCode}-{instanceName}-app.log` | `myApp-inst1-app.log` |

### Common File Patterns

Every Hoist app has these files, beyond the root-level build files:

| File | Location | Purpose |
|------|----------|---------|
| `Application.groovy` | `grails-app/init/` | Spring Boot entry point (boilerplate) |
| `BootStrap.groovy` | `grails-app/init/` | Config/pref registration, service init |
| `ClusterConfig.groovy` | `grails-app/init/` | Hazelcast networking |
| `LogbackConfig.groovy` | `grails-app/init/` | Logging config inheritance |
| `application.groovy` | `grails-app/conf/` | Grails config (delegates to Hoist) |
| `runtime.groovy` | `grails-app/conf/` | Datasource, mail, runtime config |
| `BaseController.groovy` | `grails-app/controllers/` | App-specific controller base |
| `AuthenticationService.groovy` | `grails-app/services/` | Auth implementation |
| `UserService.groovy` | `grails-app/services/` | User lookup implementation |
| `RoleService.groovy` | `grails-app/services/` | Role assignment implementation |
| `MonitorDefinitionService.groovy` | `grails-app/services/` | Health check definitions |
| `Bootstrap.ts` | `client-app/src/` | Library init, service declarations |
| `app.ts` | `client-app/src/apps/` | Main app entry point |
| `admin.ts` | `client-app/src/apps/` | Admin Console entry point |
| `AppModel.ts` | `client-app/src/app/` or `desktop/` | Root application model |
| `AppComponent.ts` | `client-app/src/app/` or `desktop/` | Root UI component |

### Server/Client Boundary

The server and client communicate with JSON over HTTP, through REST endpoints, and over
WebSocket. The server provides:

- **`/xh/*`** - Hoist framework endpoints for auth, config, prefs, tracking, and more, served by
  hoist-core's `XhController`
- **`/$controller/$action`** - App-specific endpoints defined by app controllers
- **`/rest/$controller/$id?`** - REST CRUD endpoints for `RestController` subclasses

The client calls these endpoints through hoist-react's `FetchService` and app-specific service
classes.
