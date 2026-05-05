package io.xh.hoist.mcp.tools

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.JsonSchema
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.Tool
import io.xh.hoist.mcp.data.DocRegistry
import io.xh.hoist.mcp.formatters.DocFormatter

/**
 * Creates documentation tool specifications for the MCP server:
 *  - hoist-core-search-docs
 *  - hoist-core-list-docs
 *  - hoist-core-read-doc
 *  - hoist-core-ping
 *
 * Each tool is a thin adapter: parse args, delegate to {@link DocFormatter},
 * append the appropriate MCP-side hint, and wrap as a {@link CallToolResult}.
 */
class DocTools {

    static List<SyncToolSpecification> create(DocRegistry registry) {
        return [
            createSearchDocs(registry),
            createListDocs(registry),
            createReadDoc(registry),
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

        return new SyncToolSpecification(tool, { exchange, request ->
            def args = request?.arguments() ?: [:]
            String query = args?.query ?: ''
            String category = args?.category
            int limit = (args?.limit as Integer) ?: 10

            def results = registry.searchDocs(query, category, limit)
            return textResult(DocFormatter.formatSearchDocs(query, results))
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

        return new SyncToolSpecification(tool, { exchange, request ->
            def args = request?.arguments() ?: [:]
            String category = args?.category

            def filtered = registry.listDocs(category)
            def text = DocFormatter.formatListDocs(filtered, registry.mcpCategories)
            text += '\n\nUse hoist-core-search-docs with keywords to find specific content within documents.'
            return textResult(text)
        })
    }

    //------------------------------------------------------------------
    // hoist-core-read-doc
    //------------------------------------------------------------------
    private static SyncToolSpecification createReadDoc(DocRegistry registry) {
        def tool = Tool.builder()
            .name('hoist-core-read-doc')
            .title('Read a hoist-core doc')
            .description('Read the full content of a hoist-core documentation file by id. Use hoist-core-search-docs or hoist-core-list-docs first to discover ids.')
            .inputSchema(new JsonSchema(
                'object',
                [
                    id: [
                        type: 'string',
                        description: 'Document id (e.g. "docs/base-classes.md", "docs/coding-conventions.md")'
                    ]
                ],
                ['id'],
                null, null, null
            ))
            .build()

        return new SyncToolSpecification(tool, { exchange, request ->
            def args = request?.arguments() ?: [:]
            String id = args?.id ?: ''

            def res = registry.resolve(id)
            if (res.ambiguous) {
                return textResult(DocFormatter.formatDocAmbiguous(id, res.candidates))
            }
            if (!res.found) {
                return textResult(DocFormatter.formatDocNotFound(id, registry.entries))
            }
            def content = registry.loadContent(res.entry.id)
            if (content == null) {
                return textResult("Document file not readable: \"${res.entry.id}\".")
            }
            return textResult(DocFormatter.formatReadDoc(res.entry, content))
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

        return new SyncToolSpecification(tool, { exchange, request ->
            return textResult('hoist-core MCP server is running.')
        })
    }

    //------------------------------------------------------------------
    // helpers
    //------------------------------------------------------------------
    private static CallToolResult textResult(String text) {
        return CallToolResult
            .builder()
            .content([new TextContent(text)])
            .isError(false)
            .build()
    }
}
