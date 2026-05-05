package io.xh.hoist.mcp.tools

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.JsonSchema
import io.modelcontextprotocol.spec.McpSchema.TextContent
import io.modelcontextprotocol.spec.McpSchema.Tool
import io.xh.hoist.mcp.data.GroovyRegistry
import io.xh.hoist.mcp.formatters.GroovyFormatter

/**
 * Creates Groovy symbol exploration tool specifications:
 *  - hoist-core-search-symbols
 *  - hoist-core-get-symbol
 *  - hoist-core-get-members
 *
 * Each tool is a thin adapter: parse args, delegate to {@link GroovyFormatter},
 * append the appropriate MCP-side hint, and wrap as a {@link CallToolResult}.
 */
class GroovyTools {

    static List<SyncToolSpecification> create(GroovyRegistry registry) {
        return [
            createSearchSymbols(registry),
            createGetSymbol(registry),
            createGetMembers(registry)
        ]
    }

    //------------------------------------------------------------------
    // hoist-core-search-symbols
    //------------------------------------------------------------------
    private static SyncToolSpecification createSearchSymbols(GroovyRegistry registry) {
        def tool = Tool.builder()
            .name('hoist-core-search-symbols')
            .title('Search hoist-core symbols')
            .description('Search for Groovy/Java classes, interfaces, traits, and enums across the hoist-core framework by name. Also searches public members (properties, methods, fields) of key framework classes like BaseService, Cache, RestController, and others. Returns matching symbols and members with their kind, source, and context.')
            .inputSchema(new JsonSchema(
                'object',
                [
                    query: [
                        type: 'string',
                        description: 'Symbol or member name to search for (e.g. "BaseService", "Cache", "createTimer", "renderJSON")'
                    ],
                    kind: [
                        type: 'string',
                        description: 'Filter by symbol kind. Default: all kinds',
                        enum: ['class', 'interface', 'trait', 'enum']
                    ],
                    limit: [
                        type: 'number',
                        description: 'Maximum results. Default: 20',
                        minimum: 1,
                        maximum: 50
                    ]
                ],
                ['query'],
                null, null, null
            ))
            .build()

        return new SyncToolSpecification(tool, { exchange, request ->
            def args = request?.arguments() ?: [:]
            String query = args?.query ?: ''
            String kind = args?.kind
            int symbolLimit = (args?.limit as Integer) ?: 20

            def symbolResults = registry.searchSymbols(query, kind, symbolLimit)
            int memberLimit = symbolResults.empty ? symbolLimit : 15
            def memberResults = registry.searchMembers(query, memberLimit)

            return textResult(GroovyFormatter.formatSearchSymbols(query, symbolResults, memberResults))
        })
    }

    //------------------------------------------------------------------
    // hoist-core-get-symbol
    //------------------------------------------------------------------
    private static SyncToolSpecification createGetSymbol(GroovyRegistry registry) {
        def tool = Tool.builder()
            .name('hoist-core-get-symbol')
            .title('Get hoist-core symbol details')
            .description('Get detailed type information for a specific Groovy/Java symbol including its signature, Groovydoc, inheritance, and source location. Use hoist-core-search-symbols first to find the symbol name.')
            .inputSchema(new JsonSchema(
                'object',
                [
                    name: [
                        type: 'string',
                        description: 'Exact symbol name (e.g. "BaseService", "Cache", "HoistUser")'
                    ],
                    filePath: [
                        type: 'string',
                        description: 'Source file path to disambiguate if multiple symbols share the same name'
                    ]
                ],
                ['name'],
                null, null, null
            ))
            .build()

        return new SyncToolSpecification(tool, { exchange, request ->
            def args = request?.arguments() ?: [:]
            String name = args?.name ?: ''
            String filePath = args?.filePath

            def detail = registry.getSymbolDetail(name, filePath)
            if (detail) {
                return textResult(GroovyFormatter.formatSymbolDetail(detail))
            }
            // Fallback: try the indexed-member surface so `get-symbol createCache` resolves to
            // the method on BaseService rather than dead-ending at "not found".
            def memberMatches = registry.findMembersByName(name)
            if (memberMatches) {
                return textResult(GroovyFormatter.formatMemberAsSymbol(name, memberMatches))
            }
            return textResult(GroovyFormatter.formatSymbolNotFound(name) + ' Use hoist-core-search-symbols to find available symbols.')
        })
    }

    //------------------------------------------------------------------
    // hoist-core-get-members
    //------------------------------------------------------------------
    private static SyncToolSpecification createGetMembers(GroovyRegistry registry) {
        def tool = Tool.builder()
            .name('hoist-core-get-members')
            .title('Get hoist-core class members')
            .description('List all properties and methods of a class or interface with their types, annotations, and documentation. Use hoist-core-search-symbols or hoist-core-get-symbol first to identify the target symbol.')
            .inputSchema(new JsonSchema(
                'object',
                [
                    name: [
                        type: 'string',
                        description: 'Class or interface name (e.g. "BaseService", "Cache")'
                    ],
                    filePath: [
                        type: 'string',
                        description: 'Source file path to disambiguate if multiple symbols share the same name'
                    ]
                ],
                ['name'],
                null, null, null
            ))
            .build()

        return new SyncToolSpecification(tool, { exchange, request ->
            def args = request?.arguments() ?: [:]
            String name = args?.name ?: ''
            String filePath = args?.filePath

            def members = registry.getMembers(name, filePath)
            if (members == null) {
                return textResult(GroovyFormatter.formatMembersNotFound(name) + ' Use hoist-core-search-symbols to find the correct symbol name.')
            }
            return textResult(GroovyFormatter.formatMembers(name, members))
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
