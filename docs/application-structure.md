# Application Structure

> **Status: DRAFT** — This document is awaiting review and may contain inaccuracies.

This document describes the standard directory layout of a Hoist application repository. Hoist
apps follow a consistent structure across projects, combining a Grails 7 server-side backend with a
React/TypeScript client-side frontend in a single repository. Understanding this structure is
essential for navigating any Hoist codebase — the patterns described here are uniform across
XH-built applications.

## Overview

A Hoist application is a **single Git repository** containing both server and client code. The
server is a Grails 7 application that includes hoist-core as a plugin dependency. The client is a
React/TypeScript application that consumes hoist-react as an npm package. Both halves are built,
tested, and deployed together, but run as separate processes during development and as separate
containers in production.

The server runs on Tomcat (via Grails/Spring Boot), serving a REST API under a configurable path
(typically `/api/`). The client is a webpack-bundled SPA served by Nginx, which also reverse-proxies
API requests to Tomcat. In local development, webpack-dev-server proxies API calls to the Grails
`bootRun` process.

## Root Directory

Every Hoist app repository has the same top-level shape:

```
my-app/
├── grails-app/              # Server-side Grails application code
├── src/main/groovy/         # Additional server-side source (non-artifact classes)
├── client-app/              # Client-side React/TypeScript application
│   ├── package.json         # Dependencies and scripts (@xh/hoist, React, ag-Grid, etc.)
│   ├── yarn.lock            # Dependency lock file (or package-lock.json if using npm)
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
├── gradlew / gradlew.bat   # Gradle wrapper scripts
├── CHANGELOG.md             # Version history
└── README.md                # Project documentation
```

Some apps may also include:

- `helm/` — Kubernetes Helm charts (for k8s deployments)
- `infra/` — Infrastructure-as-code (e.g. AWS CDK)
- `docs/` — Additional project documentation
- `bin/` — Utility scripts

## Build Configuration

### `gradle.properties`

Defines app identity and framework versions. Every app declares the same set of core properties:

```properties
xhAppCode=myApp
xhAppName=My Application
xhAppPackage=com.example.myapp
xhAppVersion=3.0-SNAPSHOT

grailsVersion=7.0.5
hoistCoreVersion=36.1.0
dotEnvGradlePluginVersion=4.0.0
hazelcast.version=5.6.0

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
| `hoistCoreVersion` | The published hoist-core version to use (ignored when `runHoistInline=true`) |
| `runHoistInline` | When `true`, uses a sibling `../hoist-core` checkout via Gradle composite build |
| `enableHotSwap` | Enables JVM HotSwap agent for faster server-side dev iteration |
| `localDevXmx` | JVM max heap for local `bootRun` |

### `settings.gradle`

Minimal — sets the project name and optionally enables composite build for local hoist-core
development:

```groovy
rootProject.name = 'my-app'

if (parseBoolean(runHoistInline)) {
    println "${xhAppName}: running with Hoist Core INLINE...."
    includeBuild '../hoist-core'
}
```

### `build.gradle`

Configures the Grails web plugin, declares the hoist-core dependency and database drivers, sets up
JVM arguments for `bootRun`, and extends `grails.build.info` with Hoist metadata. The structure is
highly consistent across apps — the primary differences are database driver choices and
app-specific dependencies (e.g. JWT libraries, cloud SDKs).

### `.env.template` and `.env`

Instance configuration is provided via environment variables loaded by the
[`co.uzzu.dotenv.gradle`](https://github.com/uzzu/dotenv-gradle) plugin. The `.env.template` file
is checked into source control and enumerates all required and optional variables. Developers copy
it to `.env` (git-ignored) and fill in local values.

Variable names follow the pattern `APP_{APPCODE}_{KEY}` — for example, `APP_MYAPP_DB_HOST`. These
are accessible in server code via `InstanceConfigUtils.getInstanceConfig('dbHost')`, which strips
the prefix and converts to camelCase.

Common variables include database connection details, environment name, OAuth credentials, SMTP
settings, and bootstrap admin user credentials.

## Server Side (`grails-app/`)

The server follows standard Grails conventions, with all application code organized under the
app's root package (e.g. `com.example.myapp`).

```
grails-app/
├── conf/
│   ├── application.groovy    # Grails config — delegates to Hoist defaults
│   ├── runtime.groovy        # Runtime config — datasource, mail, CORS
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

**`Application.groovy`** — Boilerplate Spring Boot entry point. Identical across all apps:

```groovy
@CompileStatic
class Application extends GrailsAutoConfiguration {
    static void main(String[] args) {
        GrailsApp.run(Application, args)
    }
}
```

**`BootStrap.groovy`** — Startup initialization. Implements `LogSupport` and contains an `init`
closure that:
1. Logs an ASCII art startup banner (app name, version, build, environment)
2. Calls `configService.ensureRequiredConfigsCreated()` to register all app-specific soft configs
3. Calls `prefService.ensureRequiredPrefsCreated()` to register all app-specific preferences
4. Optionally creates a bootstrap admin user for local development
5. Calls `parallelInit()` on app-specific services to initialize them concurrently

**`ClusterConfig.groovy`** — Extends `io.xh.hoist.ClusterConfig` to configure Hazelcast networking.
Typically uses multicast discovery for local development and a cloud-specific strategy (AWS ECS,
etc.) for production. Single-instance apps can leave clustering disabled.

**`LogbackConfig.groovy`** — Extends `io.xh.hoist.LogbackConfig` to inherit Hoist's default
logging configuration. Apps that need custom log formats override methods here; apps that don't
still must include this class (with an empty body) to properly inherit the base configuration.

### Configuration Files

**`application.groovy`** — Delegates to Hoist's default configuration, then adds app-specific
overrides:

```groovy
import io.xh.hoist.configuration.ApplicationConfig

ApplicationConfig.defaultConfig(this)

hibernate {
    show_sql = false
}
```

Apps may also enable features like WebSockets (`hoist.enableWebSockets = true`) or configure
Hibernate schema names here.

**`runtime.groovy`** — Configures the datasource, mail, and other runtime settings. Delegates to
Hoist's `RuntimeConfig.defaultConfig(this)` for baseline config, then adds the app's database
connection (read from instance configs) and optional SMTP configuration.

Database configuration is often extracted to a separate `DBConfig` class in `src/main/groovy/` for
clarity — this is a common pattern but not required.

### Required App-Provided Services

Every Hoist app must provide implementations of three abstract services. See the
[`authentication.md`](./authentication.md) and [`authorization.md`](./authorization.md) docs for
full details.

| Service | Base Class | Purpose |
|---------|-----------|---------|
| `AuthenticationService` | `BaseAuthenticationService` | Defines the authentication scheme (OAuth, SSO, form-based) |
| `UserService` | `BaseUserService` | User lookup, creates `HoistUser` instances |
| `RoleService` | `BaseRoleService` | Role assignment (or use the built-in `DefaultRoleService`) |

Apps that use monitors must also provide:

| Service | Base Class | Purpose |
|---------|-----------|---------|
| `MonitorDefinitionService` | `BaseMonitorDefinitionService` | Defines application health checks |

### Controllers

Apps define an abstract `BaseController` extending `io.xh.hoist.BaseController`. This base is
typically minimal — sometimes empty — but provides a hook for app-wide controller behavior (e.g.
casting `getUser()` to an app-specific user type).

Feature controllers extend this base and are annotated with access annotations
(`@AccessRequiresRole`, `@AccessAll`, etc.). They delegate business logic to services and use
`renderJSON()` to return responses.

Apps that need custom URL routing beyond the default `/$controller/$action?/$id?` pattern can
define a `UrlMappings.groovy` file. Apps using only the standard patterns do not need one — the
default mappings provided by hoist-core are sufficient.

### Services

App services extend `BaseService` (from hoist-core), often through an app-specific intermediate
base class (e.g. `BaseMyAppService`) that adds shared helpers. Services are organized by feature
area within the app package.

Common service subpackages include:
- `security/` — `AuthenticationService`, `UserService`, `RoleService`, OAuth token services
- Feature-specific packages matching the app's domain

### Domain Classes

GORM domain classes for app-specific persistent data. Hoist's own domain classes (AppConfig,
Preference, TrackLog, etc.) are provided by the hoist-core plugin — apps do not need to redeclare
them.

### `src/main/groovy/`

Non-Grails-artifact classes: POGOs, enums, utility classes, and helper code that doesn't need to
be a Grails service, controller, or domain class. Common examples include:

- `DBConfig.groovy` — Database configuration helper used by `runtime.groovy`
- Data transfer objects and query result wrappers
- Enum types
- Utility classes

## Client Side (`client-app/`)

The client app is a React/TypeScript application built with webpack and consuming `@xh/hoist`
(hoist-react) as its primary dependency.

```
client-app/
├── package.json              # Dependencies and scripts
├── webpack.config.js         # Webpack build configuration
├── tsconfig.json             # TypeScript compiler configuration
├── eslint.config.js          # ESLint configuration
├── .prettierrc.json          # Prettier code formatting
├── .stylelintrc              # SCSS/CSS linting
├── .npmrc                    # npm registry configuration
├── .nvmrc                    # Node version specification
├── yarn.lock                 # Dependency lock file (or package-lock.json)
├── public/                   # Static assets (favicons, error pages, images)
└── src/
    ├── Bootstrap.ts          # Library initialization and service declarations
    ├── apps/                 # Webpack entry points (one per client app)
    │   ├── app.ts            # Main application entry
    │   └── admin.ts          # Hoist Admin Console entry
    ├── core/                 # Shared infrastructure (services, types, columns, icons)
    │   ├── svc/              # Client-side services
    │   └── ...
    ├── app/                  # Main application UI (matches apps/app.ts entry point)
    │   ├── AppModel.ts       # Root application model
    │   ├── AppComponent.ts   # Root UI component
    │   ├── App.scss          # Global application styles
    │   └── ...               # Feature-specific view directories
    ├── admin/                # Admin app UI (matches apps/admin.ts entry point)
    └── mobile/               # Mobile app UI, if applicable (matches apps/mobile.ts)
```

### Build Configuration

**`package.json`** — Declares `@xh/hoist` as the primary dependency along with React, ag-Grid,
Highcharts, and other UI libraries. Common scripts:

| Script | Purpose |
|--------|---------|
| `start` | Start webpack-dev-server for local development |
| `build` | Production webpack build |
| `startWithHoist` | Dev server using a local sibling `hoist-react` checkout |
| `lint` | Run ESLint and Stylelint |

**`webpack.config.js`** — Delegates to `@xh/hoist-dev-utils/configureWebpack` with app-specific
metadata:

```js
const configureWebpack = require('@xh/hoist-dev-utils/configureWebpack');

module.exports = (env = {}) => {
    return configureWebpack({
        appCode: 'myApp',
        appName: 'My Application',
        appVersion: '3.0-SNAPSHOT',
        favicon: './public/favicon.svg',
        devServerOpenPage: 'app/',
        sourceMaps: 'devOnly',
        ...env
    });
};
```

**`tsconfig.json`** — Targets ES2022 with React JSX support. Includes path aliases for inline
hoist-react development.

### Entry Points (`apps/`)

Each file in `apps/` is a separate webpack entry point — a self-contained client application
sharing the same codebase. Every Hoist app has at minimum:

- **`app.ts`** — The main application, calling `XH.renderApp()` with the app's root component,
  model, and authentication configuration. By convention, nearly every Hoist app names its primary
  entry point `app.ts`, with the corresponding UI code in a sibling `app/` directory.
- **`admin.ts`** — The Hoist Admin Console, calling `XH.renderAdminApp()` with the framework's
  built-in admin UI. The corresponding admin UI code lives in an `admin/` directory.

Some apps define additional entry points (e.g. `mobile.ts`, `clientAdmin.ts`), each with a
matching directory for its UI code.

A typical entry point:

```typescript
import '../Bootstrap';
import {XH} from '@xh/hoist/core';
import {AppContainer} from '@xh/hoist/desktop/appcontainer';
import {AppComponent} from '../app/AppComponent';
import {AppModel} from '../app/AppModel';

XH.renderApp({
    clientAppCode: 'app',
    componentClass: AppComponent,
    modelClass: AppModel,
    containerClass: AppContainer,
    isMobileApp: false,
    enableLogout: true,
    checkAccess: 'APP_READER'
});
```

### `Bootstrap.ts`

Executed before any app entry point. Responsible for:

1. **Service declarations** — Imports app-specific client-side services and declares them on the
   `XHApi` interface via TypeScript module augmentation, making them accessible as `XH.myService`
2. **ag-Grid setup** — Registers required ag-Grid modules (community and enterprise) and installs
   the license key from server-side config
3. **Highcharts setup** — Imports Highcharts modules and installs via `installHighcharts()`
4. **HoistUser augmentation** — Declares any app-specific user properties

### Application UI

Each entry point in `apps/` has a corresponding directory under `src/` containing its UI code. By
convention, the directory name matches the entry point filename — e.g. `apps/app.ts` imports from
`app/`, `apps/admin.ts` imports from `admin/`. The main application directory contains:

- **`AppModel.ts`** — Extends `HoistAppModel`. Defines the application's top-level state,
  tab navigation, ViewManager instances, and overall data loading. This is the model class
  referenced in the entry point's `XH.renderApp()` call.
- **`AppComponent.ts`** — The root UI component, created with `hoistCmp()`. Renders the app's
  chrome (app bar, tab container, etc.) using the `AppModel`.

Feature-specific views are organized into subdirectories, each typically containing a model file and
one or more component files.

## Docker / Deployment (`docker/`)

Every app includes a `docker/` directory with two containers:

```
docker/
├── nginx/
│   ├── Dockerfile            # FROM xhio/xh-nginx:<version>
│   └── app.conf              # Nginx site configuration
└── tomcat/
    ├── Dockerfile            # FROM xhio/xh-tomcat:next-tc10-jdk17
    └── setenv.sh             # JVM options (heap size, instance config path)
```

### Nginx Container

Serves the webpack-built client assets and reverse-proxies API requests to Tomcat. The
`app.conf` configures:

- **Security headers** — HSTS, CSP, X-Frame-Options, Permissions-Policy, X-Content-Type-Options
- **SPA routing** — `try_files` fallback to `index.html` for each client app entry point
- **API proxy** — `/api/` (or similar) proxied to `http://localhost:8080/` (Tomcat)
- **Cache policy** — Static JS/CSS cached indefinitely (cache-busted by webpack hashes); index.html
  always re-fetched
- **Root redirect** — `/` redirects to `/app/` (optionally with mobile detection)

### Tomcat Container

Runs the Grails WAR file. The `setenv.sh` sets JVM heap size (defaulting to a configured amount,
overridable via `JAVA_XMX` env var) and optionally specifies the instance config file path.

### Deployment Model

In production, both containers run together within the same host or task (e.g. an AWS ECS Fargate
task). Nginx listens on port 80/443 externally; Tomcat listens on port 8080 internally only.
This sidecar pattern ensures API requests stay local (no network hop between web server and app
server).

## Local Development

### Starting the Server

```bash
./gradlew bootRun -Duser.timezone=Etc/UTC
```

Starts the Grails application on `localhost:8080`. The `.env` file provides instance configuration
(database credentials, environment, etc.).

### Starting the Client

```bash
cd client-app && yarn start    # or: npm start
```

Starts webpack-dev-server with hot module replacement. API requests are proxied to the Grails
backend.

### Inline Hoist Development

For developing hoist-core or hoist-react alongside the app, check out the framework repos as
siblings and enable inline mode:

- **hoist-core**: Set `runHoistInline=true` in `gradle.properties` (or `~/.gradle/gradle.properties`)
- **hoist-react**: Run the `startWithHoist` script instead of `start`

## Conventions Summary

### Package and Naming

| Convention | Pattern | Example |
|-----------|---------|---------|
| Server package root | `xhAppPackage` from `gradle.properties` | `com.example.myapp` |
| Client app name | `name` field in `client-app/package.json` | `my-app` |
| Instance config env vars | `APP_{APPCODE}_{KEY}` | `APP_MYAPP_DB_HOST` |
| Log file names | `{appCode}-{instanceName}-app.log` | `myApp-inst1-app.log` |

### Common File Patterns

Every Hoist app will have these files (beyond the root-level build files):

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
| `AppModel.ts` | `client-app/src/app/` | Root application model |
| `AppComponent.ts` | `client-app/src/app/` | Root UI component |

### Server/Client Boundary

The server and client communicate via JSON over HTTP (REST endpoints) and WebSocket. The server
provides:

- **`/xh/*`** — Hoist framework endpoints (auth, config, prefs, tracking, etc.) — served by
  hoist-core's `XhController`
- **`/$controller/$action`** — App-specific endpoints defined by app controllers
- **`/rest/$controller/$id?`** — REST CRUD endpoints (for `RestController` subclasses)

The client consumes these via hoist-react's `FetchService` and app-specific service classes.

## Client Integration

See the [hoist-react documentation](https://github.com/xh/hoist-react/tree/develop/docs) for
detailed coverage of the client-side framework, including component architecture, state management
with MobX, the grid system, and the admin console.
