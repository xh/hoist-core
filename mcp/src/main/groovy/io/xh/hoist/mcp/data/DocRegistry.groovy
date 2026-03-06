package io.xh.hoist.mcp.data

import groovy.json.JsonSlurper
import io.xh.hoist.mcp.ContentSource
import io.xh.hoist.mcp.util.McpLog

/**
 * Registry of hoist-core documentation. Loads inventory from docs/doc-registry.json
 * with metadata for search and categorization.
 */
class DocRegistry {

    final ContentSource contentSource
    final List<DocEntry> entries
    final List<Map> mcpCategories

    DocRegistry(ContentSource contentSource) {
        this.contentSource = contentSource
        def json = loadFromJson()
        this.mcpCategories = json.mcpCategories ?: []
        this.entries = buildEntries(json)
    }

    //------------------------------------------------------------------
    // Search
    //------------------------------------------------------------------
    List<SearchResult> searchDocs(String query, String mcpCategory = null, int limit = 10) {
        def terms = query.toLowerCase().split(/\s+/).toList()
        def filtered = mcpCategory && mcpCategory != 'all'
            ? entries.findAll { it.mcpCategory == mcpCategory }
            : entries

        def results = filtered.collect { entry ->
            scoreEntry(entry, terms)
        }.findAll {
            it != null
        }.sort { a, b ->
            b.matchCount <=> a.matchCount
        }

        return results.take(Math.min(limit, 20))
    }

    //------------------------------------------------------------------
    // List
    //------------------------------------------------------------------
    List<DocEntry> listDocs(String mcpCategory = null) {
        if (mcpCategory && mcpCategory != 'all') {
            return entries.findAll { it.mcpCategory == mcpCategory }
        }
        return entries
    }

    //------------------------------------------------------------------
    // Load content
    //------------------------------------------------------------------
    String loadContent(String docId) {
        def entry = entries.find { it.id == docId }
        if (!entry) return null
        return contentSource.readFile(entry.id)
    }

    //------------------------------------------------------------------
    // JSON loading
    //------------------------------------------------------------------
    private Map loadFromJson() {
        def jsonText = contentSource.readFile('docs/doc-registry.json')
        if (!jsonText) {
            McpLog.warn('docs/doc-registry.json not found')
            return [mcpCategories: [], entries: []]
        }
        return new JsonSlurper().parseText(jsonText) as Map
    }

    private List<DocEntry> buildEntries(Map json) {
        def rawEntries = json.entries ?: []
        def inventory = rawEntries.collect { Map raw ->
            new DocEntry(
                id: raw.id,
                title: raw.title,
                mcpCategory: raw.mcpCategory,
                description: raw.description,
                keywords: raw.keywords ?: []
            )
        }

        // Validate file existence
        def validated = inventory.findAll { entry ->
            if (contentSource.fileExists(entry.id)) {
                return true
            } else {
                McpLog.warn("Doc file not found, skipping: ${entry.id}")
                return false
            }
        }

        McpLog.info("Doc registry loaded: ${validated.size()} of ${inventory.size()} entries available")
        return validated
    }

    //------------------------------------------------------------------
    // Search scoring
    //------------------------------------------------------------------
    private SearchResult scoreEntry(DocEntry entry, List<String> terms) {
        int matchCount = 0
        List<Snippet> snippets = []

        // Score metadata matches
        def metaText = "${entry.title} ${entry.description} ${entry.keywords.join(' ')}".toLowerCase()
        for (term in terms) {
            if (metaText.contains(term)) matchCount++
        }

        // Score content matches and extract snippets
        def content = contentSource.readFile(entry.id)
        if (content) {
            def lines = content.split('\n')
            for (int i = 0; i < lines.length; i++) {
                def lineLower = lines[i].toLowerCase()
                for (term in terms) {
                    if (lineLower.contains(term)) {
                        matchCount++
                        if (snippets.size() < 5) {
                            def text = lines[i].trim()
                            if (text.length() > 120) text = text.take(120) + '...'
                            snippets << new Snippet(lineNumber: i + 1, text: text)
                        }
                        break // Only count one match per line for snippets
                    }
                }
            }
        }

        return matchCount > 0 ? new SearchResult(entry: entry, snippets: snippets, matchCount: matchCount) : null
    }

    //------------------------------------------------------------------
    // Data classes
    //------------------------------------------------------------------
    static class DocEntry {
        /** Unique identifier AND relative file path (e.g. 'docs/base-classes.md'). */
        String id
        String title, mcpCategory, description
        List<String> keywords = []
    }

    static class SearchResult {
        DocEntry entry
        List<Snippet> snippets
        int matchCount
    }

    static class Snippet {
        int lineNumber
        String text
    }
}
