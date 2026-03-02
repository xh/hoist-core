# JSON Handling

## Overview

Hoist provides a custom Jackson-based JSON serialization and parsing system that replaces Grails'
default JSON converters. This system is used throughout the framework — in controller
request/response handling, domain object serialization, and inter-service communication.

The key components are:
- **`renderJSON()` / `parseRequestJSON()`** — Controller methods for sending and receiving JSON over
  HTTP — the primary interface to the JSON system in application code
- **`JSONFormat`** — An interface that domain classes and POGOs implement to control their JSON
  representation
- **`JSONSerializer`** — Serializes objects to JSON strings, with built-in support for `JSONFormat`
  and Hoist-specific type handling
- **`JSONParser`** — Parses JSON strings and streams into Maps and Lists

## Source Files

| File | Location | Role |
|------|----------|------|
| `JSONSerializer.java` | `src/main/groovy/io/xh/hoist/json/` | Jackson-based serializer (Java) |
| `JSONParser.java` | `src/main/groovy/io/xh/hoist/json/` | Jackson-based parser (Java) |
| `JSONFormat.java` | `src/main/groovy/io/xh/hoist/json/` | Interface for custom JSON representation (Java) |
| `JSONFormatCached.java` | `src/main/groovy/io/xh/hoist/json/` | Performance-optimized cached serialization (Java) |
| `BaseController` | `grails-app/controllers/io/xh/hoist/` | `renderJSON()`, `parseRequestJSON()` |
| Custom serializers | `src/main/groovy/io/xh/hoist/json/serializer/` | Built-in type serializers (Java, except `ThrowableSerializer.groovy`) |

## Architecture

### Why Not Grails JSON Converters?

Hoist uses its own Jackson-based serialization instead of Grails' built-in JSON converters for
several reasons:

- **Consistency** — One serialization path for all JSON output (controller responses, tracking
  data, cached values, etc.)
- **Customizability** — The `JSONFormat` interface gives domain classes and POGOs fine-grained
  control over their JSON representation
- **Performance** — `JSONFormatCached` pre-serializes objects that are rendered repeatedly
- **Extensibility** — Applications can register custom Jackson modules via
  `JSONSerializer.registerModules()`

### Serialization Flow

```
Controller renders response
    │
    └── renderJSON(object)
            │
            └── JSONSerializer.serialize(object)
                    │
                    ├── object implements JSONFormat?
                    │       └── Call formatForJSON() → serialize the result
                    │
                    ├── object extends JSONFormatCached?
                    │       └── Return pre-serialized JSON string
                    │
                    ├── Map, List, String, Number, etc.?
                    │       └── Standard Jackson serialization
                    │
                    └── Custom serializer registered?
                            └── Use registered serializer
```

## Key Classes

### JSONSerializer

A static utility class wrapping a Jackson `ObjectMapper` configured with Hoist-specific serializers.

#### Methods

| Method | Description |
|--------|-------------|
| `serialize(Object)` | Serialize an object to a JSON string |
| `serializePretty(Object)` | Serialize with pretty-printing (indented) |
| `registerModules(Module...)` | Register custom Jackson modules |

#### Built-in Serializers

`JSONSerializer` registers the following custom serializers:

| Serializer | Type | Behavior |
|------------|------|----------|
| `JSONFormatSerializer` | `JSONFormat` | Calls `formatForJSON()` and serializes the result |
| `JSONFormatCachedSerializer` | `JSONFormatCached` | Writes pre-cached JSON string directly |
| `GStringSerializer` | `GString` | Serializes Groovy GStrings as plain strings |
| `DoubleSerializer` | `Double` | Writes `NaN` and `Infinity` as `null` |
| `FloatSerializer` | `Float` | Writes `NaN` and `Infinity` as `null` |
| `LocalDateSerializer` | `LocalDate` | Formats as ISO date string (e.g., `"2024-01-15"`) |
| `ThrowableSerializer` | `Throwable` | Serializes exceptions as `{name, message, cause, isRoutine}` maps (null/false values filtered out). If the `Throwable` implements `JSONFormat`, delegates to `formatForJSON()` instead. (Groovy) |

The `JavaTimeModule` (JSR 310) is also registered for Java 8+ date/time types, with nanosecond
timestamps disabled.

#### Registering Custom Modules

Applications can extend the serializer with custom Jackson modules:

```groovy
// In BootStrap.groovy or a service init()
SimpleModule appModule = new SimpleModule()
appModule.addSerializer(Money.class, new MoneySerializer())
appModule.addSerializer(Currency.class, new CurrencySerializer())
JSONSerializer.registerModules(appModule)
```

This recreates the internal `ObjectMapper` with all previously registered modules plus the new ones.

### JSONParser

A static utility class for parsing JSON strings or input streams into Java/Groovy objects.

| Method | Input | Output | Description |
|--------|-------|--------|-------------|
| `parseObject(String)` | JSON string | `Map<String, Object>` | Parse JSON object |
| `parseObject(InputStream)` | Input stream | `Map<String, Object>` | Parse JSON object from stream |
| `parseArray(String)` | JSON string | `List` | Parse JSON array |
| `parseArray(InputStream)` | Input stream | `List` | Parse JSON array from stream |
| `parseObjectOrArray(String)` | JSON string | `Map` or `List` | Auto-detect and parse |
| `validate(String)` | JSON string | `boolean` | Check if string is valid JSON |

String-based parse methods return `null` for `null` or empty input. InputStream-based overloads
(`parseObject(InputStream)`, `parseArray(InputStream)`) return `null` for `null` input only — they
do not check for an empty stream.

### JSONFormat

A Java interface that classes implement to control their JSON serialization. When `JSONSerializer`
encounters an object implementing `JSONFormat`, it calls `formatForJSON()` and serializes the
returned object (typically a `Map`) instead of the original object.

```groovy
class Position implements JSONFormat {
    Long id
    String ticker
    Double quantity
    Double price
    Date lastUpdated

    Object formatForJSON() {
        return [
            id: id,
            ticker: ticker,
            quantity: quantity,
            marketValue: quantity * price,   // computed field
            lastUpdated: lastUpdated
        ]
    }
}
```

This pattern is widely used across hoist-core domain classes (`AppConfig`, `TrackLog`, `Role`,
`HoistUser`, etc.) and is the recommended approach for application domain classes and POGOs.

#### Key Benefits

- **Control** — Choose exactly which fields to include (avoid exposing internal state)
- **Computed fields** — Include derived values that don't exist as properties
- **Flattening** — Simplify nested object graphs for the client
- **Consistency** — Same JSON representation wherever the object is serialized

### JSONFormatCached

An abstract class that, like `JSONFormat`, uses a `formatForJSON()` method to define an object's
JSON representation. Unlike `JSONFormat`, the result is cached after the first serialization —
subsequent serializations write the cached string directly, avoiding repeated work. A class should
extend `JSONFormatCached` or implement `JSONFormat`, but not both.

```groovy
class LargeDataPoint extends JSONFormatCached {
    // ... many fields ...

    protected Object formatForJSON() {
        return [/* ... large map ... */]
    }
}
```

The first time a `JSONFormatCached` object is serialized, its `formatForJSON()` is called and the
resulting JSON string is cached. Subsequent serializations write the cached string directly,
avoiding repeated map creation and serialization.

**Use this when:**
- Objects are serialized in bulk (e.g., large lists rendered to the client)
- The object's JSON representation doesn't change after construction
- Serialization performance is a concern

**Avoid when:**
- The object is mutable (the cache won't reflect changes)
- The object is serialized only once (caching adds overhead for one-time use)

### Controller Methods

`BaseController` provides the primary interface between HTTP and the JSON system:

#### `renderJSON(Object o)`

Serializes an object via `JSONSerializer.serialize()` and writes it to the HTTP response with
`application/json` content type:

```groovy
class PositionController extends BaseController {

    @AccessRequiresRole('APP_USER')
    def list() {
        def positions = positionService.list()
        renderJSON(data: positions)    // positions serialized via JSONFormat
    }
}
```

**Always use `renderJSON()`** instead of Grails' `render` to ensure consistent serialization
through Jackson with support for `JSONFormat`, custom serializers, and proper content type headers.

#### `parseRequestJSON(Map options)`

Parses the HTTP request body as a JSON object (returns a `Map`):

```groovy
def update() {
    Map body = parseRequestJSON()
    // body.data, body.id, etc.
}

// With OWASP encoding for user-submitted content
def submit() {
    Map body = parseRequestJSON(safeEncode: true)
}
```

The `safeEncode: true` option runs the input through OWASP HTML content encoding before parsing,
escaping `&`, `<`, and `>` characters.

#### `parseRequestJSONArray(Map options)`

Same as `parseRequestJSON()` but expects a JSON array (returns a `List`).

#### `renderSuccess()`

Renders an empty 204 No Content response — used for void operations (e.g., delete).

#### `renderClusterJSON(ClusterResult)`

Renders the result of a cluster-delegated operation. If the result's value is already a JSON string,
it writes it directly (avoiding double-serialization).

## Common Patterns

### Implementing JSONFormat

The standard pattern for domain classes, POGOs, and DTOs:

```groovy
class Fund implements JSONFormat {
    String id
    String name
    String manager
    boolean active
    Date lastUpdated

    // Internal fields not exposed to client
    String internalCode
    String dbConnectionString

    Object formatForJSON() {
        return [
            id: id,
            name: name,
            manager: manager,
            active: active,
            lastUpdated: lastUpdated
            // internalCode and dbConnectionString excluded
        ]
    }
}
```

### Custom Serializer for External Types

For third-party classes you can't modify:

```groovy
class MoneySerializer extends StdSerializer<Money> {

    MoneySerializer() { super(Money) }

    void serialize(Money value, JsonGenerator jgen, SerializerProvider provider) {
        jgen.writeStartObject()
        jgen.writeNumberField('amount', value.amount)
        jgen.writeStringField('currency', value.currency.code)
        jgen.writeEndObject()
    }
}

// Register in BootStrap
SimpleModule module = new SimpleModule()
module.addSerializer(Money, new MoneySerializer())
JSONSerializer.registerModules(module)
```

## Client Integration

The JSON system forms the serialization contract between hoist-core and hoist-react. All data
exchanged between client and server passes through `renderJSON()` / `parseRequestJSON()` and the
corresponding client-side `FetchService`.

The `JSONFormat.formatForJSON()` output directly determines what the hoist-react client receives.
When designing `formatForJSON()` implementations, consider what fields the client needs and in what
format — this is the API surface between server and client.

## Common Pitfalls

### Using Grails' `render` instead of `renderJSON()`

Grails' built-in `render` method uses a different JSON converter that doesn't respect `JSONFormat`,
custom serializers, or Hoist's type handling:

```groovy
// ✅ Do: Use renderJSON
renderJSON(data: myObject)

// ❌ Don't: Use Grails render
render myObject as JSON
```

### Using Grails' `request.getJSON()` instead of `parseRequestJSON()`

Similarly, always use Hoist's parsing methods:

```groovy
// ✅ Do: Use parseRequestJSON
Map body = parseRequestJSON()

// ❌ Don't: Use Grails request.JSON
def body = request.JSON
```

### Non-serializable objects in formatForJSON()

`formatForJSON()` should return objects that Jackson can serialize — Maps, Lists, Strings, Numbers,
Dates, and other `JSONFormat` implementers. Avoid returning Closures, Iterators, or other
non-serializable types.

### Mutable objects with JSONFormatCached

`JSONFormatCached` caches the JSON string after the first serialization. If the object's state
changes after that, the cached JSON becomes stale. Only use `JSONFormatCached` for immutable or
effectively-immutable objects.

### NaN and Infinity in JSON

The `DoubleSerializer` and `FloatSerializer` convert `NaN` and `Infinity` to `null` in JSON output,
since these values are not valid in the JSON specification. If your application produces `NaN`
values, be aware they will be `null` on the client side.

### Forgetting to register custom modules early

`JSONSerializer.registerModules()` recreates the internal `ObjectMapper`. Register custom modules
in `BootStrap.init()` or early in a service's `init()` to ensure they are available before any
JSON serialization occurs.
