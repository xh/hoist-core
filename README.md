# Welcome to Hoist

Hoist is a full-stack web application development toolkit built by
[Extremely Heavy Industries](https://xh.io/). It supplies server and client components that work
together to build data-dense enterprise applications, from a single sophisticated interface up to a
complete app.

This repository is *hoist-core*, the server side of Hoist. It is a Grails plugin, published as
`io.xh:hoist-core`, and is designed for use with [hoist-react](https://github.com/xh/hoist-react),
our client-side toolkit. See that repository for client-side features and conventions.

The core technologies are Java and its more dynamic cousin [Groovy](http://groovy-lang.org/) on the
server, via the mature [Grails framework](https://grails.org/), and TypeScript on the client, via
[React](https://react.dev/) and associated libraries.

## Documentation

[`docs/README.md`](docs/README.md) is the primary catalog for hoist-core documentation. It indexes a
guide for every feature area, plus upgrade notes for each major version, the coding conventions, the
build and release process, and the standard layout of a Hoist application.

Start there for anything specific. This README covers the project as a whole - what Hoist is for,
what the server does, and how a Hoist app is built and deployed.

Hoist Core also ships an MCP server that gives AI coding agents structured access to these docs and
to Groovy/Java symbol information. See [`mcp/README.md`](mcp/README.md).

This README does not introduce Grails, Java, or the other core technologies. It assumes general
familiarity with enterprise web application development.

## Why Hoist

Hoist grew out of our ongoing practice developing applications for enterprise clients, primarily in
finance. Those clients needed multiple interrelated yet distinct applications that were:

- **Data-dense** - able to load and visualize large datasets, with a focus on grids and charts.
- **Consistent** - with shared UI controls, coding patterns, and styles.
- **User friendly** - efficient and enjoyable to operate, even for demanding users.
- **Highly maintainable** - stable, with strong tooling for deployment and operational support.

Many application development libraries and frameworks exist, but we wanted a toolkit that could
pre-select a set of libraries and add the higher-level services that every such application needs:

| Server-side feature                               | Guide                                                                  |
|---------------------------------------------------|------------------------------------------------------------------------|
| Application configuration and administration      | [`configuration.md`](docs/configuration.md)                            |
| User preferences                                  | [`preferences.md`](docs/preferences.md)                                |
| User management and pluggable authentication      | [`authentication.md`](docs/authentication.md)                          |
| Roles and access control                          | [`authorization.md`](docs/authorization.md)                            |
| LDAP / Active Directory / Entra ID integration    | [`directory-services.md`](docs/directory-services.md)                  |
| Activity tracking and auditing                    | [`activity-tracking.md`](docs/activity-tracking.md)                    |
| Error reporting and user feedback                 | [`activity-tracking.md`](docs/activity-tracking.md)                    |
| Status monitoring and health checks               | [`monitoring.md`](docs/monitoring.md)                                  |
| Metrics and distributed tracing                   | [`metrics.md`](docs/metrics.md), [`tracing.md`](docs/tracing.md)       |
| Distributed caching and multi-instance clustering | [`caching.md`](docs/caching.md), [`clustering.md`](docs/clustering.md) |
| Email notifications                               | [`email.md`](docs/email.md)                                            |
| Server push over WebSocket                        | [`websocket.md`](docs/websocket.md)                                    |
| HTTP client and request proxying                  | [`http-client.md`](docs/http-client.md)                                |

Hoist React adds the client-side counterparts, along with customized grids, charts, and dashboards,
and shared formatters and styles.

These features work together and build on each other. A simple utility method to make an Ajax
request to the server can automatically decode a JSON response, save a tracking record of who made
the call and how long it took, and alert the user and/or app administrators if there was an error. A
customized grid component can offer a full-featured UI for column selection, filter those column
choices by user role, and persist the user's choices as a preference that is maintained across
browsing sessions and workstations.

## Role of the Server

The primary focus of Hoist is on building user interfaces that sit in front of data from back-end
sources and services already deployed within an enterprise. Most of a developer's work with Hoist is
therefore client-side.

Given that emphasis, the role of the Grails server can be limited almost exclusively to the
infrastructure the client toolkit expects - serving and storing configuration, preference, tracking,
and related data. These tasks require a server that can persist data to a database (Grails supports
a wide variety) and process requests securely.

To fetch business data specific to an application, a Hoist client app can talk to independent
back-end systems directly, via CORS or a proxy arrangement. Once the initial project setup is
complete, a developer *might* never touch Java / Groovy code.

That said, Grails provides a feature-rich server layer with the full power of the Java ecosystem
behind it. Common roles for a Hoist server include:

- Authenticate users, via a username/password lookup or single sign-on.
- Sit between the client app and other back-end systems - parse and validate queries, relay them,
  then transform, cache, or combine the results.
- Proxy requests directly to other HTTP services, which avoids the need for CORS.
- Query a SQL database or an alternative data store such as Redis.
- Listen on or fetch data from a message queue such as Kafka or RabbitMQ.
- Send email or instant message notifications.
- Manage its own business objects, providing everything a full-stack app requires.

## Application Structure and Deployment

A Hoist app is structured as a Grails application and follows the Grails conventions for its file
and directory layout. Client-side code lives in the same repository, in its own dedicated folder
tree. See [`docs/application-structure.md`](docs/application-structure.md) for the full layout, and
the [Grails documentation](https://docs.grails.org/latest/) for the framework itself.

hoist-core is packaged as a Grails plugin, and is added to an app within its `build.gradle` file:

```groovy
dependencies {
    ...  // standard Grails dependencies / plugins / app-specific libraries
    implementation "io.xh:hoist-core:$hoistCoreVersion"
}
```

This adds server-side support for Hoist's core back-end services, including the set of endpoints
expected by the client-side toolkit. Releases are published to Maven Central.

Grails applications are built via [Gradle](https://gradle.org/) into a single WAR file, which is
deployed via [Apache Tomcat](http://tomcat.apache.org/). Client-side apps are built independently by
Webpack and served by nginx. We recommend and provide standardized Docker containers to ship both
sides of the application as an integrated whole, with minimal infrastructure requirements.

## Custom plugins for enterprise deployments

Hoist Core and its client-side libraries provide a good deal of functionality on their own. But
enterprise clients also have configurations, authentication requirements, dependencies, data source
definitions, and other reusable code constructs that are unique to their environment.

To support those needs while still encouraging maximum consistency across multiple applications, we
can assist in creating and maintaining a custom plugin layer between Hoist and business
applications. Authentication in particular - OAuth via libraries such as MSAL, for example - is
commonly delivered this way.

## Developing hoist-core

```bash
./gradlew clean assemble    # compile all sources and build the JAR
```

This project is a plugin, so `bootRun` is not supported. To run it locally, use a wrapper app that
includes hoist-core as a dependency. See
[`docs/application-structure.md`](docs/application-structure.md#inline-hoist-development) for inline
development against a local checkout, and
[`docs/build-and-publish.md`](docs/build-and-publish.md) for the build pipeline, the JDK contract,
CI workflows, and the release process.

Coding conventions are documented in
[`docs/coding-conventions.md`](docs/coding-conventions.md). AI coding assistants should also read
[`CLAUDE.md`](CLAUDE.md).

## Hoist usage, licensing, and support

Hoist is developed exclusively by Extremely Heavy and intended for use by XH and our client partners
to develop enterprise web applications with XH's guidance and direction. That said, we have released
the toolkit under the permissive and open Apache 2.0 license. This allows any developer, whether a
current XH client or not, to check out, use, modify, and otherwise explore Hoist and its source
code. See [this project's license file](LICENSE.md) for the full license.

We selected an open source license as part of our ongoing commitment to openness, transparency, and
ease-of-use, and to clarify the suitability of Hoist for use within a wide variety of enterprise
software projects. Note, however, that we cannot at this time commit to any particular support or
contribution model outside of our consulting work. If you are interested in Hoist and think it might
be helpful for a project, please do not hesitate to [contact us](https://xh.io)!

------------------------------------------

☎️ info@xh.io | <https://xh.io>
Copyright © 2026 Extremely Heavy Industries Inc.
