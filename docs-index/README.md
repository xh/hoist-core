# Docs Index Builder

Builds the index file that the [Hoist Docs](https://github.com/xh/hoist-docs) service serves. Hoist
Docs is a Hoist application that gives AI coding agents structured access to Hoist documentation
and API reference over MCP, and gives people a docs viewer for the same content.

This subproject is development tooling. It is not published, and apps never run it.

## What the index holds

One JSON file per hoist-core version, in the schema described in the hoist-docs repo's
`docs/index-schema.md`:

- every doc registered in [`docs/doc-registry.json`](../docs/doc-registry.json), with its markdown
- every Groovy and Java class, interface, trait and enum under `grails-app/` and `src/main/`,
  with its signature, Groovydoc, inheritance, annotations and members
- the member search index for the key framework classes listed in `GroovyRegistry`

The service splits docs into sections, ranks search results, and formats tool output. Nothing here
runs at query time.

## Building

```bash
./gradlew :docs-index:buildIndex   # writes docs-index/build/docs-index/core-<version>.json
```

Symbols are read with Groovy's `CompilationUnit` at the conversion phase, which surfaces classes,
members and Groovydoc for both `.groovy` and `.java` sources without a separate Java parser.

## Using the MCP tools

Agents connect to a Hoist Docs server over HTTP. This repo's [`.mcp.json`](../.mcp.json) points
at `HOIST_DOCS_MCP_URL`, defaulting to a local server on port 8080. See the hoist-docs README for
running one locally against indexes built here.

## Layout

| Path | Purpose |
| --- | --- |
| `index/IndexBuilder.groovy` | Entry point for `buildIndex`. Assembles and writes the index. |
| `data/DocRegistry.groovy` | Loads `docs/doc-registry.json`. |
| `data/GroovyRegistry.groovy` | AST-based symbol and member extraction. |
| `ContentSource.groovy`, `LocalContentSource.groovy` | Read files from the checkout. |

## Maintaining

- A change to the shape of the index must bump `SCHEMA_VERSION` in `IndexBuilder` and be matched
  in the hoist-docs service, which refuses schema versions it does not know.
- To index more classes' members, add them to `MEMBER_INDEXED_CLASSES` in `GroovyRegistry`.
