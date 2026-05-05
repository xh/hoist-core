package io.xh.hoist.mcp.formatters

import io.xh.hoist.mcp.data.DocRegistry.DocEntry
import io.xh.hoist.mcp.data.DocRegistry.SearchResult

/**
 * Pure formatting functions for documentation tool output. Shared between the
 * MCP tools in {@code tools/DocTools} and the CLI commands in {@code cli/DocsCli}
 * so that both produce byte-identical text and parallel JSON shapes.
 *
 * Methods return body text without trailing "Tip: ..." hints — those are added
 * by the consumer (MCP tool or CLI) so the wording can reference the right
 * tool surface (MCP tool names vs. CLI subcommands).
 */
class DocFormatter {

    static final int SCHEMA_VERSION = 1

    //------------------------------------------------------------------
    // search-docs
    //------------------------------------------------------------------
    static String formatSearchDocs(String query, List<SearchResult> results) {
        if (!results) return "No documents matched your search for \"${query}\"."

        def lines = ["Found ${results.size()} result${results.size() > 1 ? 's' : ''} for \"${query}\":", '']
        results.eachWithIndex { result, i ->
            lines << "${i + 1}. [${result.entry.title}] (id: ${result.entry.id}, category: ${result.entry.mcpCategory})"
            lines << "   ${result.entry.description}"
            lines << "   Matches: ${result.matchCount} | Snippets:"
            for (snippet in result.snippets) {
                lines << "   - L${snippet.lineNumber}: ${snippet.text}"
            }
            lines << ''
        }
        return lines.join('\n').stripTrailing()
    }

    static Map searchDocsAsMap(String query, List<SearchResult> results) {
        return [
            schemaVersion: SCHEMA_VERSION,
            query: query,
            count: results.size(),
            results: results.collect { result ->
                [
                    id: result.entry.id,
                    title: result.entry.title,
                    category: result.entry.mcpCategory,
                    description: result.entry.description,
                    matchCount: result.matchCount,
                    snippets: result.snippets.collect { [lineNumber: it.lineNumber, text: it.text] }
                ]
            }
        ]
    }

    //------------------------------------------------------------------
    // list-docs
    //------------------------------------------------------------------
    static String formatListDocs(List<DocEntry> entries, List<Map> mcpCategories) {
        def lines = ["Hoist Core Documentation (${entries.size()} documents):", '']
        for (catMeta in mcpCategories) {
            def catEntries = entries.findAll { it.mcpCategory == catMeta.id }
            if (!catEntries) continue
            lines << "## ${catMeta.title} (${catEntries.size()} doc${catEntries.size() > 1 ? 's' : ''})"
            for (entry in catEntries) {
                lines << "- ${entry.id}: ${entry.description}"
            }
            lines << ''
        }
        return lines.join('\n').stripTrailing()
    }

    static Map listDocsAsMap(List<DocEntry> entries, List<Map> mcpCategories) {
        return [
            schemaVersion: SCHEMA_VERSION,
            count: entries.size(),
            categories: mcpCategories.collect { catMeta ->
                def catEntries = entries.findAll { it.mcpCategory == catMeta.id }
                [
                    id: catMeta.id,
                    title: catMeta.title,
                    count: catEntries.size(),
                    entries: catEntries.collect { entry ->
                        [
                            id: entry.id,
                            title: entry.title,
                            description: entry.description,
                            keywords: entry.keywords ?: []
                        ]
                    }
                ]
            }.findAll { it.count > 0 }
        ]
    }

    //------------------------------------------------------------------
    // read-doc
    //------------------------------------------------------------------
    static String formatReadDoc(DocEntry entry, String content) {
        if (!entry || content == null) return null
        return content
    }

    static Map readDocAsMap(DocEntry entry, String content) {
        return [
            schemaVersion: SCHEMA_VERSION,
            id: entry.id,
            title: entry.title,
            category: entry.mcpCategory,
            description: entry.description,
            content: content
        ]
    }

    /** Error text for an unknown doc id; lists available ids for recovery. */
    static String formatDocNotFound(String docId, List<DocEntry> available) {
        def ids = available*.id.sort().join(', ')
        return "Unknown document id: \"${docId}\". Available ids: ${ids}"
    }

    /** Error text when a doc id matches multiple entries by suffix; lists candidates. */
    static String formatDocAmbiguous(String docId, List<DocEntry> candidates) {
        def ids = candidates*.id.sort().join(', ')
        return "Ambiguous document id: \"${docId}\" matches multiple entries. Specify one of: ${ids}"
    }
}
