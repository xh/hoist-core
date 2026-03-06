package io.xh.hoist.mcp.tools

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.JsonSchema
import io.modelcontextprotocol.spec.McpSchema.Tool
import io.xh.hoist.mcp.data.DocRegistry

/**
 * Creates documentation tool specifications for the MCP server:
 *  - hoist-core-search-docs
 *  - hoist-core-list-docs
 *  - hoist-core-ping
 */
class DocTools {

    static List<SyncToolSpecification> create(DocRegistry registry) {
        return [
            createSearchDocs(registry),
            createListDocs(registry),
            createPing()
        ]
    }

    //------------------------------------------------------------------
    // hoist-core-search-docs
    //------------------------------------------------------------------
    private static SyncToolSpecification createSearchDocs(DocRegistry registry) {
        def categoryIds = registry.mcpCategories.collect { it.id as String } + ['all']

        def tool = Tool.builder()
            .name('hoist-core-search-docs')
            .title('Search hoist-core docs')
            .description('Search across all hoist-core documentation by keyword. Returns matching documents with context snippets showing where terms appear. Use this to find relevant docs when you do not know the exact document name.')
            .inputSchema(new JsonSchema(
                'object',
                [
                    query: [
                        type: 'string',
                        description: 'Search keywords (e.g. "BaseService lifecycle", "authentication OAuth")'
                    ],
                    category: [
                        type: 'string',
                        description: 'Filter by category. Default: all',
                        enum: categoryIds
                    ],
                    limit: [
                        type: 'number',
                        description: 'Maximum number of results. Default: 10',
                        minimum: 1,
                        maximum: 20
                    ]
                ],
                ['query'],
                null, null, null
            ))
            .build()

        return new SyncToolSpecification(tool, { exchange, args ->
            String query = args?.query ?: ''
            String category = args?.category
            int limit = (args?.limit as Integer) ?: 10

            def results = registry.searchDocs(query, category, limit)

            String text
            if (results.empty) {
                text = "No documents matched your search for \"${query}\"."
            } else {
                def lines = ["Found ${results.size()} result${results.size() > 1 ? 's' : ''} for \"${query}\":\n"]
                results.eachWithIndex { result, i ->
                    lines << "${i + 1}. [${result.entry.title}] (id: ${result.entry.id}, category: ${result.entry.mcpCategory})"
                    lines << "   ${result.entry.description}"
                    lines << "   Matches: ${result.matchCount} | Snippets:"
                    for (snippet in result.snippets) {
                        lines << "   - L${snippet.lineNumber}: ${snippet.text}"
                    }
                    lines << ''
                }
                text = lines.join('\n')
            }

            return new CallToolResult(text, false)
        })
    }

    //------------------------------------------------------------------
    // hoist-core-list-docs
    //------------------------------------------------------------------
    private static SyncToolSpecification createListDocs(DocRegistry registry) {
        def categoryIds = registry.mcpCategories.collect { it.id as String } + ['all']

        def tool = Tool.builder()
            .name('hoist-core-list-docs')
            .title('List hoist-core docs')
            .description('List all available hoist-core documentation with descriptions and categories. Use this to discover what docs are available before reading specific ones.')
            .inputSchema(new JsonSchema(
                'object',
                [
                    category: [
                        type: 'string',
                        description: 'Filter by category. Default: all',
                        enum: categoryIds
                    ]
                ],
                [],
                null, null, null
            ))
            .build()

        return new SyncToolSpecification(tool, { exchange, args ->
            String category = args?.category

            def filtered = registry.listDocs(category)
            def lines = ["Hoist Core Documentation (${filtered.size()} documents):\n"]

            for (catMeta in registry.mcpCategories) {
                def catEntries = filtered.findAll { it.mcpCategory == catMeta.id }
                if (!catEntries) continue

                lines << "## ${catMeta.title} (${catEntries.size()} doc${catEntries.size() > 1 ? 's' : ''})"
                for (entry in catEntries) {
                    lines << "- ${entry.id}: ${entry.description}"
                }
                lines << ''
            }

            lines << 'Use hoist-core-search-docs with keywords to find specific content within documents.'

            return new CallToolResult(lines.join('\n'), false)
        })
    }

    //------------------------------------------------------------------
    // hoist-core-ping
    //------------------------------------------------------------------
    private static SyncToolSpecification createPing() {
        def tool = Tool.builder()
            .name('hoist-core-ping')
            .title('Ping hoist-core MCP')
            .description('Verify the hoist-core MCP server is running and responsive')
            .inputSchema(new JsonSchema('object', [:], [], null, null, null))
            .build()

        return new SyncToolSpecification(tool, { exchange, args ->
            return new CallToolResult('hoist-core MCP server is running.', false)
        })
    }
}
