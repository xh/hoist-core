> **Status: DRAFT** — This document is awaiting review. Content may be incomplete or subject to
> change. Do not remove this banner until the document has been interactively reviewed and approved.

# HTTP Client & Proxy Services

## Overview

Hoist-core provides a lightweight HTTP client layer and a request-proxying framework for
server-to-server communication. These components serve two distinct but related needs:

1. **Outbound API calls** — `JSONClient` wraps Apache HttpClient 5 to make HTTP requests to
   external services, automatically parsing JSON responses and normalizing error handling. This is
   the standard way for Hoist services to call REST APIs, webhooks, or any JSON-over-HTTP endpoint.

2. **Client request proxying** — `BaseProxyService` lets a Hoist application act as a transparent
   proxy, forwarding requests from the browser (hoist-react) to an external API. This avoids
   exposing third-party API credentials or endpoints directly to the client, and sidesteps CORS
   restrictions.

A small `HttpUtils` utility class provides response caching and host/port parsing helpers used
across the framework.

### Why a custom client wrapper?

Groovy and Grails offer many HTTP client options (Groovy's `HttpBuilder`, Spring's `RestTemplate`,
etc.). Hoist standardizes on a thin wrapper over Apache HttpClient 5 for several reasons:

- **Consistent JSON handling** — Uses Hoist's own `JSONParser` (Jackson-based) rather than mixing
  serialization strategies
- **Unified error model** — Non-2xx responses throw `ExternalHttpException` with the upstream
  status code preserved, integrating cleanly with Hoist's exception hierarchy
- **Minimal abstraction** — The wrapper is intentionally thin; callers still construct Apache
  `HttpGet`, `HttpPost`, etc. directly, retaining full control over headers, timeouts, and auth

## Source Files

| File | Location | Role |
|------|----------|------|
| `JSONClient` | `src/main/groovy/io/xh/hoist/http/` | Typed HTTP client — executes requests and parses JSON responses |
| `BaseProxyService` | `grails-app/services/io/xh/hoist/http/` | Abstract base for proxy services — forwards client requests to external APIs |
| `HttpUtils` | `src/main/groovy/io/xh/hoist/http/` | Static utility methods for response caching and host/port parsing |
| `ProxyImplController` | `grails-app/controllers/io/xh/hoist/impl/` | Internal controller that routes `/proxy/{name}/{url}` requests to proxy services |
| `ExternalHttpException` | `src/main/groovy/io/xh/hoist/exception/` | Exception thrown when an outbound HTTP call fails |

## Key Classes

### JSONClient

`JSONClient` is a `@CompileStatic` wrapper around Apache's `CloseableHttpClient`. It executes HTTP
requests and returns parsed results in one of four forms: `Map` (JSON object), `List` (JSON array),
`String` (raw text), or `Integer` (status code only).

#### Construction

```groovy
import io.xh.hoist.http.JSONClient
import org.apache.hc.client5.http.impl.classic.HttpClients

// Default client — suitable for most uses
def client = new JSONClient()

// Custom client — e.g. with timeouts, auth, SSL config
def customHttpClient = HttpClients.custom()
    .setDefaultRequestConfig(requestConfig)
    .build()
def client = new JSONClient(customHttpClient)
```

The constructor accepts an optional `CloseableHttpClient`. When omitted, it creates a default
client via `HttpClients.createDefault()`. For production use with external APIs, you will typically
want to configure timeouts, connection pooling, or authentication on a custom `HttpClients` builder.

#### Execute Methods

| Method | Return Type | Description |
|--------|-------------|-------------|
| `executeAsMap(method)` | `Map` | Parse response body as a JSON object |
| `executeAsList(method)` | `List` | Parse response body as a JSON array |
| `executeAsString(method)` | `String` | Return raw response body as a string |
| `executeAsStatusCode(method)` | `Integer` | Return the HTTP status code only |

All methods accept an `HttpUriRequestBase` (the Apache superclass for `HttpGet`, `HttpPost`, etc.).
`executeAsMap`, `executeAsList`, and `executeAsString` return `null` for HTTP 204 (No Content)
responses. `executeAsStatusCode` returns `204` as an `Integer` (it never returns `null` for a
successful response).

#### Automatic Behavior

- **Content-Type header** — Automatically sets `Content-Type: application/json` on POST, PUT, and
  PATCH requests if no `Content-Type` header is already present
- **Accept header** — Automatically sets `Accept: application/json` if no `Accept` header is
  already present
- **Error handling** — Any response with a status code outside the 200-204 range throws an
  `ExternalHttpException`. The exception preserves the upstream status code and attempts to parse
  an error message from the response body

#### Error Handling Details

When a non-success response is received, `JSONClient` attempts to extract a meaningful error
message using a three-tier strategy:

1. **Structured JSON** — If the response body is a JSON object with a `message` field, that
   message is used. If the JSON also contains a `className` field referencing a known `io.xh.hoist`
   or `java.lang` exception class, the exception is rehydrated as that type
2. **Raw string** — If the body is not valid JSON, it is used directly as the error message
   (truncated to 255 characters)
3. **Fallback** — If the body cannot be read at all, the exception carries only the status code

All errors are wrapped in `ExternalHttpException`, which extends `HttpException` and carries the
upstream `statusCode`.

#### Usage Examples

```groovy
import io.xh.hoist.http.JSONClient
import org.apache.hc.client5.http.classic.methods.HttpGet
import org.apache.hc.client5.http.classic.methods.HttpPost
import org.apache.hc.core5.http.io.entity.StringEntity
import io.xh.hoist.json.JSONSerializer

class WeatherService extends BaseService {

    private JSONClient _client = new JSONClient()

    /** Fetch current weather as a parsed Map. */
    Map getCurrentWeather(String city) {
        def get = new HttpGet("https://api.weather.example.com/current?city=${city}")
        get.setHeader('Authorization', "Bearer ${configService.getString('weatherApiKey')}")
        return _client.executeAsMap(get)
    }

    /** Post a payload and get a list of results. */
    List submitBatchRequest(List<Map> items) {
        def post = new HttpPost('https://api.example.com/batch')
        post.setEntity(new StringEntity(JSONSerializer.serialize(items)))
        return _client.executeAsList(post)
    }

    /** Check if a service is reachable (status code only). */
    boolean isServiceHealthy() {
        try {
            def status = _client.executeAsStatusCode(new HttpGet('https://api.example.com/health'))
            return status == 200
        } catch (Exception ignored) {
            return false
        }
    }
}
```

### BaseProxyService

`BaseProxyService` is an abstract Grails service that proxies incoming HTTP requests from the
browser to an external API server. It extends `BaseService`, giving it full access to Hoist's
service lifecycle, caching, and logging infrastructure.

The proxy system is designed for scenarios where the client (hoist-react) needs to communicate
with a third-party API but cannot do so directly due to:

- **Credentials** — API keys or tokens should not be exposed to the browser
- **CORS** — The external API does not allow cross-origin requests
- **Network access** — The external API is on an internal network not reachable from the browser

#### How Proxying Works

The request flow through the proxy system is:

1. The hoist-react client makes a request to `/proxy/{serviceName}/{path}` on the Hoist server
2. `UrlMappings` routes this to `ProxyImplController`, which looks up `{serviceName}Service` in
   the Spring context
3. `ProxyImplController` calls `handleRequest(url, request, response)` on the resolved service
4. `BaseProxyService.handleRequest()` constructs a matching Apache HTTP request to the external
   API (preserving method, query string, body, and selected headers)
5. The external response is streamed back to the client (status code, selected headers, body)

#### Abstract and Overridable Methods

Subclasses **must** implement:

| Method | Description |
|--------|-------------|
| `createSourceClient()` | Create and return a `CloseableHttpClient` configured with appropriate auth, timeouts, etc. |

Subclasses **may** override:

| Method | Default | Description |
|--------|---------|-------------|
| `getSourceRoot()` | `''` (empty) | Base URL of the external API (e.g. `'https://api.example.com/v2'`) |
| `getCacheSourceClient()` | `false` | If `true`, cache the `CloseableHttpClient` between requests instead of creating a new one each time |
| `proxyRequestHeaders()` | `[]` | List of header names to forward from the client request to the external API |
| `proxyResponseHeaders()` | `[]` | List of header names to forward from the external response back to the client |
| `installRequestHeaders(request, method)` | Copies matching headers | Customize how request headers are forwarded |
| `installResponseHeaders(response, sourceResponse)` | Copies matching headers | Customize how response headers are forwarded |
| `installParamsOnEntity(request, method)` | Copies body or form params | Customize how POST/PUT/PATCH body content is forwarded |

#### Request Body Handling

For POST, PUT, and PATCH requests, `BaseProxyService` inspects the `Content-Type` header:

- **Form-encoded** (`application/x-www-form-urlencoded`) — Parameters are extracted from the
  servlet request and re-encoded as `UrlEncodedFormEntity`
- **All other content types** — The raw request body is read as text and forwarded as a
  `StringEntity`

#### Error Handling

- **Client abort** — If the browser cancels the request mid-stream, the resulting
  `ClientAbortException` is caught and logged at DEBUG level (not treated as an error)
- **Streaming errors** — If an error occurs after the response has already started streaming
  (i.e. `response.isCommitted()`), the error is logged but cannot be sent to the client. If the
  response is not yet committed, it is reset and the exception is rethrown for standard Hoist
  error handling

#### Implementation Example

```groovy
import io.xh.hoist.http.BaseProxyService
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.config.RequestConfig

import java.util.concurrent.TimeUnit

/**
 * Proxies requests from the client to an internal analytics API.
 * Registered as 'analyticsProxyService' in Spring — client calls /proxy/analyticsProxy/...
 */
class AnalyticsProxyService extends BaseProxyService {

    protected CloseableHttpClient createSourceClient() {
        def config = RequestConfig.custom()
            .setResponseTimeout(30, TimeUnit.SECONDS)
            .build()

        return HttpClients.custom()
            .setDefaultRequestConfig(config)
            .build()
    }

    protected String getSourceRoot() {
        return configService.getString('analyticsApiBaseUrl')
    }

    protected boolean getCacheSourceClient() {
        // Reuse the HttpClient across requests for connection pooling
        return true
    }

    protected List<String> proxyRequestHeaders() {
        return ['Accept', 'Content-Type']
    }

    protected List<String> proxyResponseHeaders() {
        return ['Content-Type', 'Content-Disposition']
    }
}
```

**Note on naming:** The proxy URL path uses the service's Spring bean name minus the `Service`
suffix. A service class named `AnalyticsProxyService` is resolved via the bean name
`analyticsProxyService`, so the client calls `/proxy/analyticsProxy/some/endpoint`.

#### Cache Management

When `getCacheSourceClient()` returns `true`, the `CloseableHttpClient` is stored in the
`_sourceClient` field and reused across requests. Calling `clearCaches()` (inherited from
`BaseService`) sets this field to `null`, causing the next request to create a fresh client via
`createSourceClient()`. This is useful for picking up config changes (e.g. rotated API keys)
without restarting the server.

### HttpUtils

A small `@CompileStatic` utility class with two static methods:

| Method | Signature | Description |
|--------|-----------|-------------|
| `setResponseCache` | `(HttpServletResponse response, int minutes)` | Sets `Cache-Control` and `Expires` headers. Pass `0` for `no-cache` |
| `parseHostPort` | `(String str)` | Parses `"host:port"` into `[host, port]` or `"host"` into `[host, null]` |

#### Usage Examples

```groovy
import io.xh.hoist.http.HttpUtils

// Cache response for 15 minutes
HttpUtils.setResponseCache(response, 15)

// Disable caching
HttpUtils.setResponseCache(response, 0)

// Parse a host:port string from configuration
def (host, port) = HttpUtils.parseHostPort('api.example.com:8443')
// host = 'api.example.com', port = 8443

def (host2, port2) = HttpUtils.parseHostPort('api.example.com')
// host2 = 'api.example.com', port2 = null
```

### ExternalHttpException

Thrown by `JSONClient` when an outbound HTTP call returns a non-success status code or fails at the
network level. Extends `HttpException`, which carries a `statusCode` field.

```groovy
try {
    client.executeAsMap(new HttpGet('https://api.example.com/data'))
} catch (ExternalHttpException e) {
    log.error("API call failed with status ${e.statusCode}: ${e.message}")
}
```

## Configuration

The HTTP client and proxy classes themselves do not require any `xh`-prefixed AppConfigs. However,
application-level proxy services commonly use soft configuration to store:

- External API base URLs (via `getSourceRoot()`)
- API keys and credentials (stored as `pwd`-typed configs for encryption at rest)
- Timeout values

Example config pattern:

| Config Name | Type | Description |
|-------------|------|-------------|
| `myApiBaseUrl` | `string` | Base URL for the external API |
| `myApiKey` | `pwd` | API key (encrypted at rest) |
| `myApiTimeoutSecs` | `int` | Request timeout in seconds |

## Common Patterns

### Making a Simple GET Request

```groovy
def client = new JSONClient()
def result = client.executeAsMap(new HttpGet('https://api.example.com/users/42'))
String name = result.name
```

### POST with JSON Body

```groovy
import io.xh.hoist.json.JSONSerializer
import org.apache.hc.client5.http.classic.methods.HttpPost
import org.apache.hc.core5.http.io.entity.StringEntity

def client = new JSONClient()
def post = new HttpPost('https://api.example.com/users')
post.setEntity(new StringEntity(JSONSerializer.serialize([name: 'Jane', role: 'admin'])))
Map created = client.executeAsMap(post)
```

### Configuring a Client with Timeouts

```groovy
import org.apache.hc.client5.http.impl.classic.HttpClients
import org.apache.hc.client5.http.config.RequestConfig
import java.util.concurrent.TimeUnit

def config = RequestConfig.custom()
    .setConnectionRequestTimeout(5, TimeUnit.SECONDS)
    .setResponseTimeout(30, TimeUnit.SECONDS)
    .build()

def httpClient = HttpClients.custom()
    .setDefaultRequestConfig(config)
    .build()

def client = new JSONClient(httpClient)
```

### Reusable Client in a Service

```groovy
class ExternalDataService extends BaseService {

    private JSONClient _client

    void init() {
        _client = new JSONClient(buildHttpClient())
        super.init()
    }

    private CloseableHttpClient buildHttpClient() {
        def config = RequestConfig.custom()
            .setResponseTimeout(
                configService.getInt('externalApiTimeoutSecs'),
                TimeUnit.SECONDS
            )
            .build()

        return HttpClients.custom()
            .setDefaultRequestConfig(config)
            .build()
    }

    Map fetchReport(String reportId) {
        def get = new HttpGet("${configService.getString('externalApiUrl')}/reports/${reportId}")
        get.setHeader('X-API-Key', configService.getString('externalApiKey'))
        return _client.executeAsMap(get)
    }
}
```

### Minimal Proxy Service

```groovy
class ThirdPartyProxyService extends BaseProxyService {

    protected CloseableHttpClient createSourceClient() {
        return HttpClients.custom()
            .setDefaultHeaders([
                new BasicHeader('Authorization', "Bearer ${configService.getString('thirdPartyApiKey')}")
            ])
            .build()
    }

    protected String getSourceRoot() {
        return 'https://api.thirdparty.com/v1'
    }

    protected List<String> proxyResponseHeaders() {
        return ['Content-Type']
    }
}
// Client calls: /proxy/thirdPartyProxy/some/endpoint?param=value
```

## Client Integration

### hoist-react Proxy Pattern

On the client side, hoist-react provides `FetchService` for making HTTP requests. When a proxy
service is registered on the server, client code can call it via the `/proxy/` URL prefix:

```javascript
// In hoist-react client code
const result = await XH.fetchJson({
    url: 'proxy/analyticsProxy/reports/summary',
    params: {startDate: '2026-01-01'}
});
```

This request hits the Hoist server at `/proxy/analyticsProxy/reports/summary`, which is routed
by `UrlMappings` to `ProxyImplController`. The controller resolves `analyticsProxyService` from
the Spring context and delegates to its `handleRequest()` method, which forwards the request to
the configured external API.

### URL Mapping

The proxy URL mapping is defined in `UrlMappings.groovy`:

```groovy
"/proxy/$name/$url**" {
    controller = 'proxyImpl'
}
```

- `$name` — The service bean name without the `Service` suffix
- `$url**` — The remaining path, forwarded as the `endpoint` parameter to `handleRequest()`

### Security Note

`ProxyImplController` is annotated with `@AccessAll`, meaning any authenticated user can call any
registered proxy. If your proxy service provides access to sensitive data, implement authorization
checks in the service itself (e.g. check the current user's roles in `handleRequest()` before
proceeding).

## Common Pitfalls

### Not Closing Resources

`JSONClient`'s convenience methods (`executeAsMap`, `executeAsList`, etc.) handle response
closing internally via `try/finally` blocks. However, if you use `executeRaw()` or work with
the underlying Apache client directly, you must close responses yourself.

```groovy
// ✅ Do: Use the convenience methods — they handle resource cleanup
Map data = client.executeAsMap(new HttpGet('https://api.example.com/data'))

// ❌ Don't: Call the underlying client directly without closing the response
def response = httpClient.execute(new HttpGet('https://api.example.com/data'))
// response is never closed — connection leak!
```

### Ignoring ExternalHttpException Status Codes

`JSONClient` throws `ExternalHttpException` for any non-2xx response. The exception carries the
upstream `statusCode`, which is valuable for distinguishing between client errors (4xx) and server
errors (5xx).

```groovy
// ✅ Do: Inspect the status code for appropriate error handling
try {
    return client.executeAsMap(get)
} catch (ExternalHttpException e) {
    if (e.statusCode == 404) {
        return null  // Not found is expected
    }
    throw e  // Other errors are unexpected
}

// ❌ Don't: Catch all exceptions generically
try {
    return client.executeAsMap(get)
} catch (Exception e) {
    return null  // Silently swallows real errors
}
```

### Creating a New HttpClient Per Request

Creating `HttpClients.createDefault()` on every request is wasteful — it bypasses connection
pooling and incurs setup overhead. Either reuse a `JSONClient` instance or enable client caching
in proxy services.

```groovy
// ✅ Do: Reuse the client instance
class MyService extends BaseService {
    private JSONClient _client = new JSONClient()

    Map fetchData() {
        return _client.executeAsMap(new HttpGet('https://api.example.com/data'))
    }
}

// ❌ Don't: Create a new client per call
Map fetchData() {
    def client = new JSONClient()  // New client + new connection pool every time
    return client.executeAsMap(new HttpGet('https://api.example.com/data'))
}
```

For proxy services, set `getCacheSourceClient()` to return `true` to reuse the underlying
`CloseableHttpClient` across requests.

### Forgetting to Set Content-Type on Non-JSON Payloads

`JSONClient` auto-sets `Content-Type: application/json` on POST/PUT/PATCH requests when no
`Content-Type` header is present. If you are sending non-JSON content (e.g. XML, form data),
you must explicitly set the header — otherwise the external API will receive a misleading
content type.

```groovy
// ✅ Do: Set Content-Type explicitly for non-JSON payloads
def post = new HttpPost('https://api.example.com/upload')
post.setHeader('Content-Type', 'application/xml')
post.setEntity(new StringEntity('<data>value</data>'))
client.executeAsMap(post)

// ❌ Don't: Rely on the auto-set header when sending non-JSON
def post = new HttpPost('https://api.example.com/upload')
post.setEntity(new StringEntity('<data>value</data>'))
client.executeAsMap(post)  // Sends Content-Type: application/json with XML body
```

### Not Authorizing Proxy Access

`ProxyImplController` uses `@AccessAll`, so any authenticated user can reach any proxy service.
If a proxy provides access to sensitive or admin-only external APIs, the service itself must
enforce authorization.

```groovy
// ✅ Do: Check roles in your proxy service when needed
class AdminApiProxyService extends BaseProxyService {

    void handleRequest(String endpoint, HttpServletRequest request, HttpServletResponse response) {
        if (!authUser.hasRole('HOIST_ADMIN')) {
            throw new NotAuthorizedException()
        }
        super.handleRequest(endpoint, request, response)
    }

    // ...
}

// ❌ Don't: Assume the framework handles proxy authorization
class AdminApiProxyService extends BaseProxyService {
    // No access checks — any authenticated user can proxy to the admin API
}
```
