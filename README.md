![Hoist](https://xh.io/wp-content/uploads/2018/04/hoist-web-350.png)

[TOC]: # "# Welcome to Hoist"

# Welcome to Hoist
- [Core Features - Overview](#core-features---overview)
- [Role of the Server](#role-of-the-server)
- [Application Structure and Deployment](#application-structure-and-deployment)
- [Custom plugins for enterprise deployments](#custom-plugins-for-enterprise-deployments)
- [Hoist usage, licensing, and support](#hoist-usage-licensing-and-support)
- [Core Features - Additional Details](#core-features---additional-details)
    - [User Authentication / Authorization](#user-authentication--authorization)
    - [Configuration](#configuration)
    - [Preferences](#preferences)
    - [Activity Tracking and Client Error Reporting](#activity-tracking-and-client-error-reporting)
    - [Emailing](#emailing)
    - [Status Monitoring](#status-monitoring)
    - [Readme TODOs](#readme-todos)

Hoist is a web application development toolkit developed by Extremely Heavy Industries.

Hoist is designed as a "full stack" UI development framework, meaning that it has both server and
client components that work together to provide an integrated set of tools and utilities for quickly
constructing sophisticated front-end interfaces - or entire applications - with a strong focus on
building for the enterprise.

The core technologies used are Java and its more dynamic cousin [Groovy](http://groovy-lang.org/) -
via the mature [Grails framework](https://grails.org/) - and JavaScript - via
[React](https://reactjs.org/) and associated libraries.

This repository is *hoist-core*, which is the server-side implementation of Hoist. It is designed to
be used with [hoist-react](https://github.com/exhi/hoist-react), our latest front-end toolkit.
Hoist's original front-end implementation - [hoist-sencha](https://github.com/exhi/hoist-sencha) -
is based on Sencha's ExtJS framework, but is being deprecated in favor of hoist-react. See those
repositories for detailed information on Hoist's client-side features and conventions.

## Core Features - Overview

Hoist grew out of our ongoing practice developing applications for enterprise clients - primarily in
finance - that required multiple interrelated yet distinct applications that were:

* Data-dense - able to load and visualize large amounts of data, with a focus on grids and charts
* Consistent - with shared UI controls, coding patterns, and styles
* User friendly - efficient and enjoyable to operate, even for demanding users
* Highly maintainable - stable, with strong tooling for deployment and operational support

While there are clearly many application development libraries and frameworks, we required a toolkit
that could pre-select a set of libraries and bring together higher-level services such as:

* Application configuration and administration
* Activity tracking and auditing
* User management and pluggable authentication
* User preferences
* Status monitoring and health checks
* Error reporting and feedback
* Customized / wrapped components, including grids, charts, and dashboards
* Shared and consistent formatters (dates/numbers) and styles

With Hoist, these features work together and build on each other. A simple utility method to make an
Ajax request to the server can automatically decode a JSON response, save a tracking record of who
made the call and how long it took, and clearly alert the user and/or app administrators if there
was an error. A customized grid component can offer a full-featured UI for column selection, support
filtering column choices based on user roles, and persist the user's choice of visible columns and
sorting options as a preference that is maintained across browsing sessions and workstations.

## Role of the Server

The primary focus of Hoist is on building user interfaces that sit in front of and display data from
a variety of back-end sources and services already deployed within an enterprise. As such, much of
the developer's interaction with Hoist is in the form of client-side JavaScript development.

Given this emphasis on the front-end, the role of the Grails server provided by hoist-core can be
limited almost exclusively to providing the built-in infrastructure expected by the client-side
toolkits - serving up and storing configuration, preference, tracking, and other related data. These
tasks require a server capable of persisting data to a database - Grails supports a wide variety -
and of securely processing requests and serializing data.

When it comes to fetching business data specific to an application, a Hoist JS app can talk to
independent back-end systems directly via CORS or proxy arrangements. Indeed once an initial project
setup is complete, a developer _might_ never touch Java / Groovy code.

That said, Grails does provide a feature rich server layer with the full power of the Java ecosystem
available for use. Roles played by the Hoist server commonly include:

* Authenticating users, via a username/password lookup or single-sign-on/NTLM auth.
* Serving as an intermediate layer between the JS client app and other back-end systems, parsing and
  validating queries, relaying them, and then potentially transforming, caching, or combining
  results.
* Directly proxying requests to other HTTP-based services, avoiding the need for CORS.
* Querying a SQL database or alternative data store such as Redis.
* Listening on or fetching data from a message queue such as Kafka or RabbitMQ.
* Sending email or instant message notifications.
* Managing its business objects directly, providing all services required for a full-stack app.

## Application Structure and Deployment

A Hoist app is structured primarily as a Grails 3.x application, with a file and directory layout
that follows the Grails conventions. The Grails project offers extensive and well maintained
[documentation](http://docs.grails.org/latest/) on the framework's features and configuration.
This library - hoist-core - is packaged as a Grails plugin, and should be specified as such within
the Grails app's build.gradle file, e.g.:

```
dependencies {
  ...  // standard Grails dependencies / plugins / app-specific libraries
  compile "io.xh:hoist-core:$hoistCoreVersion"`
}
```

This will add server-side support for Hoist's core back-end services, including a set of endpoints
expected by the client-side toolkit implementations. Versioned and snapshot builds of Hoist are
pushed to ExHI's maven repository, which must also be configured within build.gradle.

Grails applications are built via [Gradle](https://gradle.org/), a highly flexible and popular build
tool. The result is a single WAR file which can be deployed via
[Apache Tomcat](http://tomcat.apache.org/).

All client-side code is commonly maintained within the same repository, but within its own dedicated
folder tree. For hoist-react applications, JS apps are built independently by Webpack and deployed
via nginx. We recommend and provide standardized Docker containers to ship both sides of the
application and tie them together in an integrated whole, with very minimal infrastructure
requirements or dependencies. See the [hoist-dev-utils](https://github.com/exhi/hoist-dev-utils)
repository for related utilities, base Docker images, and additional details.

(Note that for legacy [hoist-sencha](https://github.com/exhi/hoist-sencha) applications, the client
assets _are_ built along with the server and included in the resulting WAR as a single deployment.)

## Custom plugins for enterprise deployments

While hoist-core and its associated client-side libraries provide a good deal of functionality on
their own, we realize that enterprise clients will have configurations, authentication requirements,
dependencies, data source definitions, and other reusable code constructs that are unique to their
environment.

To support these needs while still encouraging maximum consistency across multiple applications, we
can assist in creating and maintaining a custom plugin layer between Hoist and business
applications.

## Hoist usage, licensing, and support

While we maintain open access to the Hoist codebase via these public repositories, Hoist is intended
for use by clients of Extremely Heavy Industries who are working with us to develop custom
applications for their enterprise.

Extremely Heavy Industries reserves all rights to the code and intellectual property contained
within, does not offer public support or a license at this time, makes no warranties or guarantees
as to the functionality, correctness, or fitness of this software, and shall not be held liable for
its use outside the scope of a structured consulting engagement.

That said, please [contact us](https://xh.io/contact/) with any inquiries or further questions - we
are always interested in speaking with new potential clients, partners, and developers!

## Core Features - Additional Details

While this document does not aim to provide a definitive or complete guide to the components of the
Hoist framework or its usage, several key features are called out with additional details below.


### User Authentication / Authorization

|             Class/File             |                   Note                   | Repo |                                       Link                                       |
|------------------------------------|------------------------------------------|:----:|:--------------------------------------------------------------------------------:|
| `BaseAuthenticationService.groovy` | Abstract service each app must implement | 🏗️  | [🔗](grails-app/services/io/xh/hoist/security/BaseAuthenticationService.groovy) |
| `HoistUser.groovy`                 | Trait/interface for core user data       | 🏗️  |             [🔗](src/main/groovy/io/xh/hoist/user/HoistUser.groovy)             |
| `IdentityService.groovy`           | Source for current user info on server   | 🏗️  |        [🔗](grails-app/services/io/xh/hoist/user/IdentityService.groovy)        |
| `Access.groovy`                    | Annotation for endpoint security         | 🏗️  |            [🔗](src/main/groovy/io/xh/hoist/security/Access.groovy)             |
| `IdentityService.js`               | Source for current user info on client   | ⚛️  |  [🔗](https://github.com/exhi/hoist-react/blob/master/svc/IdentityService.js)   |

:couple: As organizations and applications will have a wide variety of requirements for
authenticating and authorizing users, Hoist has a deliberately minimal interface in this regard. A
primary requirement for applications is that they implement a Grails Service named
`AuthenticationService` that extends Hoist's `BaseAuthenticationService` and implement its
`completeAuthentication()` method.

Implementations of this method must lookup and/or create a User class which implements the
`HoistUser` trait/interface. This specifies the core information Hoist expects to access for any
logged in user. Applications can choose to enhance their own user class with any additional details,
managed via the app or sourced from systems such as Active Directory / LDAP.

While not included in Hoist directly, NTLM / SSO can be supported via integration with the
[Jespa library](https://www.ioplex.com/), commonly done via a
[custom plugin](#custom-plugins-for-enterprise-deployments).

Once authentication is complete, `IdentityService` is the primary server-side Service for getting a
reference to the current user. Hoist's client side code calls a dedicated endpoint to verify and
fetch core user info, making it easily available to the JS app via a corresponding JS service.

#### Roles and Access

:lock: A minimal structure is provided for application roles. The `HoistUser` trait defines an abstract
`getRoles()` method that returns a `Set<String>` of role names. It is up to the application to
determine how roles are resolved and what meaning they have in the context of the app, although
Hoist does have an expectation that a `"HOIST_ADMIN"` role will be assigned to any administrators of
the app. (This role is required to access the built-in Admin console and make calls to admin-only
endpoints.)

Server-side endpoints (Controllers) can be restricted to users with a given role or roles via
the `@Access` annotation, e.g. a controller that should be accessible only to users with an "EDITOR"
role could be decorated as such:

```
@Access(['EDITOR'])
class ReportController extends BaseController {
    def saveReport(params) { ... }
}
```

The `@AccessAll` annotation allows any user access. A controller endpoint must be decorated with one
or the other of these annotations or an exception will be thrown.

#### Impersonation

:sunglasses: Administrators have access to an impersonation mode where they can "act as" another user in
the context of the application. This process is managed by `IdentityService`, which exposes several
public methods for entering and exiting impersonation mode. When active, `IdentityService.getUser()`
will return the user being impersonated, while `IdentityService.getAuthUser()` will return the
actual admin.

The client toolkits provide built-in UIs for administrators to enter and exit impersonation mode.
Services such as activity tracking are aware of impersonation and will log activity done while
impersonation is active with both the impersonated and real user.


### Configuration

|       Class/File       |               Note               | Repo |                                    Link                                     |
|------------------------|----------------------------------|:----:|-----------------------------------------------------------------------------|
| `AppConfig.groovy`     | Domain object for config entries | 🏗  | [🔗](grails-app/domain/io/xh/hoist/config/AppConfig.groovy)                |
| `ConfigService.groovy` | Source for configs on server     | 🏗️  | [🔗](grails-app/services/io/xh/hoist/config/ConfigService.groovy)          |
| `ConfigService.js`     | Source for configs on client     | ⚛️  | [🔗](https://github.com/exhi/hoist-react/blob/master/svc/ConfigService.js) |

:wrench: The ability to store simple typed configuration values
(`string|int|long|double|bool|json|pwd`) and manage / adjust them in a running application has
proven to be an extremely useful core feature. `AppConfig` entries are stored in the UI server's
database, referenced via a simple string name, and can hold different values per environment.

Configs can also be made available to client applications (or not) via a dedicated flag, where they
can be referenced by JS code. The built-in Admin console provides a full UI for reviewing, updating,
and organizing these entries.

:see_no_evil: A special `pwd` type allows passwords and other sensitive info to be stored in an
encrypted form for use on the server, avoiding the need to save these common types of configuration
to the database in plaintext. Note however that any developer can deliberately print the output of
an encrypted config by logging the (unencrypted) output of `configService.getPwd()`.

#### Required Configs

Hoist requires certain configuration entries to be defined and present for the application to
initialize. Apps themselves might also have hard dependencies on configs. To help ensure these
entries are in place and to aid in the spinning up of a new app with an empty database, the
`ConfigService.ensureRequiredConfigsCreated()` method is available to verify and auto-create
required configs. See `Bootstrap.groovy` in hoist-core for configs required at the Hoist level.


### Preferences

|       Class/File        |                  Note                   | Repo |                                   Link                                    |
|-------------------------|-----------------------------------------|:----:|---------------------------------------------------------------------------|
| `Preference.groovy`     | Domain object for preference definition | 🏗  | [🔗](grails-app/domain/io/xh/hoist/pref/Preference.groovy)               |
| `UserPreference.groovy` | Domain object for user-specific value   | 🏗  | [🔗](grails-app/domain/io/xh/hoist/pref/UserPreference.groovy)           |
| `PrefService.groovy`    | Source for pref management on server    | 🏗️  | [🔗](grails-app/services/io/xh/hoist/pref/PrefService.groovy)            |
| `PrefService.js`        | Source for pref management on client    | ⚛️  | [🔗](https://github.com/exhi/hoist-react/blob/master/svc/PrefService.js) |

:star: Preferences provide a lightweight way to persist user-specific options and settings. Similar
to AppConfigs, preferences offer several predefined data types
(`string|int|long|double|boolean|json`) and are referenced by a string `name` property. Preferences
are assigned a default value that is returned if a user does not yet have a specific value set. When
a user preference is assigned via `PrefService.setPreference()` (or one of the typed setters) a
`UserPreference` object is created and saved. Both objects can be managed via the built-in Admin
console.

Preferences may generally be accessed and used on the server and client, although they are primarily
a client-side tool. Preferences with the `local` flag set to true, however, have their user-specific
values stored on the client (in local storage) and are not accessible on the server. This flag is
designed for preferences that store things like layout or sizing information that are most
appropriate to save in the context of a particular device or workstation.

Applications are encouraged to provide end-users with controls to reset their preferences should
they wish to restore their profile to a default state. Server and client APIs exist to do such a
thing - see `PrefService.clearPreferences()`.

#### Required Preferences

As with configs (above) the `PrefService.ensureRequiredPrefsCreated()` method is available to verify
and auto-create required preferences. See `Bootstrap.groovy` in hoist-core for prefs required at the
Hoist level.


### Activity Tracking and Client Error Reporting

|      Class/File       |              Note               | Repo |                                         Link                                         |
|-----------------------|---------------------------------|:----:|--------------------------------------------------------------------------------------|
| `TrackLog.groovy`     | Domain object for track entries | 🏗  | [🔗](grails-app/domain/io/xh/hoist/track/TrackLog.groovy)                           |
| `ClientError.groovy`  | Domain object for error reports | 🏗  | [🔗](grails-app/domain/io/xh/hoist/clienterror/ClientError.groovy)                  |
| `Feedback.groovy`     | Domain object for user feedback | 🏗  | [🔗](grails-app/domain/io/xh/hoist/feedback/Feedback.groovy)                        |
| `TrackService.groovy` | Server-side API to log activity | 🏗️  | [🔗](grails-app/services/io/xh/hoist/track/TrackService.groovy)                     |
| `TrackService.js`     | Client-side API to log activity | ⚛️  | [🔗](https://github.com/exhi/hoist-react/blob/master/svc/TrackService.js)           |
| `ExceptionHandler.js` | Client-side API to track errors | ⚛️  | [🔗](https://github.com/exhi/hoist-react/blob/master/exception/ExceptionHandler.js) |

:eyes: Knowing which users are visiting an app and tracking specific actions of interest is another
common need for apps. Hoist includes an API for easily tracking activity for the current user, and
the Admin console provides a UI for searching and reviewing activity. Hoist services track some
activities automatically (e.g. impersonation), but it is primarily up to app developers to determine
which activities are of interest for tracking.

In its simplest form, a tracking record is a string - e.g. "Viewed chart". Tracks can also be given
a category for easier organization in the UI and a JSON map of data for additional details (i.e. to
note query parameters. The `TrackLog` object stores this record along with a timestamp, the current
user, and browser/device info.

On the client-side, a `track()` method is added to the Promise prototype to provide convenient
tracking for asynchronous requests - e.g. tracking a particular API call. This method provides
built-in timing of the call and saves as 'TrackLog.elapsed'.

#### Client Errors

:boom: The `ClientError` object provides a special variation on tracking to handle exception reports
posted by the client applications. See `ExceptionHandler.js` for the hoist-react entry point to this
service. Note that the `ClientErrorService` on the server fires an `xhClientErrorReceived` event,
which is listened to be the related `ClientErrorEmailService` to automatically send error reports to
the configured `xhEmailSupport` email address. Custom services can also listen to these events to
e.g. send other notifications via instant message, or raise an issue in a ticketing system.

#### Feedback

:speech_balloon: A simple model is also included for collecting and storing feedback (in the form of
simple messages) submitted by end-users directly from the application. A `FeedbackService` fires a
similar event and is listened to by a built-in service that will email out report notifications.


### Emailing

|      Class/File       |               Note                | Repo |                               Link                               |
|-----------------------|-----------------------------------|:----:|------------------------------------------------------------------|
| `EmailService.groovy` | Managed service for sending email | 🏗️  | [🔗](grails-app/services/io/xh/hoist/email/EmailService.groovy) |

:email: Hoist core provides `EmailService` to send mail from the server. This relies on the
[Grails mail plugin](http://plugins.grails.org/plugin/grails/mail) which must be configured with a
suitable SMTP server within an app's `application.groovy` configuration file.

Several default AppConfigs are available to default the sender and provide filtering and override
options for email, especially useful in dev/test scenarios where careful control of what emails are
sent to which users is required.


### Status Monitoring

|         Class/File         |                 Note                  | Repo |                                  Link                                   |
|----------------------------|---------------------------------------|:----:|-------------------------------------------------------------------------|
| `Monitor.groovy`           | Domain object for monitor definitions | 🏗  | [🔗](grails-app/domain/io/xh/hoist/monitor/Monitor.groovy)             |
| `MonitorResult.groovy`     | In-memory object for monitor outcomes | 🏗  | [🔗](src/main/groovy/io/xh/hoist/monitor/MonitorResult.groovy)         |
| `MonitoringService.groovy` | Service that coordinates monitor runs | 🏗  | [🔗](grails-app/services/io/xh/hoist/monitor/MonitoringService.groovy) |

:+1: :-1: Hoist provides an API and services for runtime monitoring of the application, with a
deliberate focus on running application-specific checks that relate to the business logic and data
sources specific to the app (as opposed to e.g. system or OS level monitoring of metrics like CPU or
memory usage).

To use monitoring, applications must implement a `MonitorDefinitionService` (i.e. a standard Grails
service with that name) that implements one or more monitoring methods of the form:

```
def customCheckName(MonitorResult result) {
    // Run any custom business logic here, setting properties on MonitorResult to record outcomes
}
```

The names of these methods should match the `code` property of `Monitor` objects created and managed
via the Admin console. These definition objects hold data-driven parameters to determine how monitor
results are evaluated. The `MonitorResult.status` property is the outcome of a given monitor. While
the app's `MonitorDefinitionService` can set this status directly on results within its methods, a
more flexible and common pattern is to have the service set a `metric` instead - e.g. the number of
rows returned by a query, or the age in seconds of a particular result set.

This metric can then be evaluated against data-driven parameters on the `Monitor` object to
determine the status dynamically, allowing for runtime adjustments and tuning of the checks. The
Hoist monitor runner will time all checks (and enforce a timeout) and catch any exceptions that
might get thrown (marking the check as having failed and noting the exception on the result).

Monitor results can be viewed via the Admin console. The `xhMonitorConfig` and
`xhMonitorEmailRecipients` configs control option for email-based alerting on monitor failures,
including support for debouncing alerts. `MonitoringService` fires a server-side
`xhMonitorStatusReport` event that can be picked up by other custom services for additional
notifications.

:construction: Note an ExHI project is underway to provide a more general and cross-application
implementation of this monitoring API for both Hoist and non-Hoist based applications.

### Readme TODOs

- [ ] Environments (Grails vs. Hoist)
- [ ] Base/Super classes
- [ ] Additional deployment info
- [ ] Version checking
- [ ] Proxy Service
- [ ] Dashboards
- [ ] Logging

Contact: xh.io | info@xh.io
Copyright © 2018 Extremely Heavy Industries Inc.
