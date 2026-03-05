package io.xh.hoist.mcp.tools

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema.CallToolResult
import io.modelcontextprotocol.spec.McpSchema.JsonSchema
import io.modelcontextprotocol.spec.McpSchema.Tool
import io.xh.hoist.mcp.data.GroovyRegistry
import io.xh.hoist.mcp.data.GroovyRegistry.MemberInfo

/**
 * Creates Groovy symbol exploration tool specifications:
 *  - hoist-core-search-symbols
 *  - hoist-core-get-symbol
 *  - hoist-core-get-members
 */
class GroovyTools {

    private static final int MAX_TYPE_LENGTH = 200

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

        return new SyncToolSpecification(tool, { exchange, args ->
            String query = args?.query ?: ''
            String kind = args?.kind
            int symbolLimit = (args?.limit as Integer) ?: 20

            def symbolResults = registry.searchSymbols(query, kind, symbolLimit)

            // Search members with separate cap; if no symbols match, give members more room
            int memberLimit = symbolResults.empty ? symbolLimit : 15
            def memberResults = registry.searchMembers(query, memberLimit)

            def lines = []

            if (symbolResults) {
                lines << "Symbols (${symbolResults.size()} matches):\n"
                symbolResults.eachWithIndex { result, i ->
                    def abstractTag = result.isAbstract ? ' (abstract)' : ''
                    lines << "${i + 1}. [${result.kind}] ${result.name}${abstractTag} (category: ${result.sourceCategory}, file: ${result.filePath})"
                }
            }

            if (memberResults) {
                if (lines) lines << ''
                lines << "Members of key classes (${memberResults.size()} matches):\n"
                memberResults.eachWithIndex { m, i ->
                    def staticPrefix = m.isStatic ? 'static ' : ''
                    def typeStr = truncateType(m.type)
                    lines << "${i + 1}. [${m.memberKind}] ${staticPrefix}${m.name}: ${typeStr} (on ${m.ownerName})"
                    if (m.groovydoc) lines << "    ${m.groovydoc}"
                }
            }

            if (lines) {
                lines << ''
                lines << 'Tip: Use hoist-core-get-members to see all members of a specific class.'
            }

            def text = lines ? lines.join('\n') : "No symbols or members found matching '${query}'. Try a broader search term."

            return new CallToolResult(text, false)
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

        return new SyncToolSpecification(tool, { exchange, args ->
            String name = args?.name ?: ''
            String filePath = args?.filePath

            def detail = registry.getSymbolDetail(name, filePath)

            String text
            if (!detail) {
                text = "Symbol '${name}' not found. Use hoist-core-search-symbols to find available symbols."
            } else {
                def lines = [
                    "# ${detail.name} (${detail.kind})",
                    "Package: ${detail.packageName}",
                    "File: ${detail.filePath}",
                    "Category: ${detail.sourceCategory}"
                ]

                if (detail.extendsClass) lines << "Extends: ${detail.extendsClass}"
                if (detail.implementsList) lines << "Implements: ${detail.implementsList.join(', ')}"
                if (detail.annotations) lines << "Annotations: ${detail.annotations.collect { '@' + it }.join(', ')}"

                lines << ''
                lines << '## Signature'
                lines << detail.signature

                if (detail.groovydoc) {
                    lines << ''
                    lines << '## Documentation'
                    lines << detail.groovydoc
                }

                if (detail.kind in ['class', 'interface', 'trait']) {
                    lines << ''
                    lines << 'Use hoist-core-get-members to see all properties and methods.'
                }

                text = lines.join('\n')
            }

            return new CallToolResult(text, false)
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

        return new SyncToolSpecification(tool, { exchange, args ->
            String name = args?.name ?: ''
            String filePath = args?.filePath

            def members = registry.getMembers(name, filePath)

            String text
            if (members == null) {
                text = "Symbol '${name}' not found or is not a class/interface. Use hoist-core-search-symbols to find the correct symbol name."
            } else {
                def instanceProps = members.findAll { !it.isStatic && it.kind in ['property', 'field'] }
                def instanceMethods = members.findAll { !it.isStatic && it.kind == 'method' }
                def staticProps = members.findAll { it.isStatic && it.kind in ['property', 'field'] }
                def staticMethods = members.findAll { it.isStatic && it.kind == 'method' }

                def lines = ["# ${name} Members\n"]

                if (instanceProps) {
                    lines << "## Properties (${instanceProps.size()})"
                    instanceProps.each { lines << formatMember(it) }
                    lines << ''
                }

                if (instanceMethods) {
                    lines << "## Methods (${instanceMethods.size()})"
                    instanceMethods.each { lines << formatMember(it) }
                    lines << ''
                }

                if (staticProps) {
                    lines << "## Static Properties (${staticProps.size()})"
                    staticProps.each { lines << formatMember(it) }
                    lines << ''
                }

                if (staticMethods) {
                    lines << "## Static Methods (${staticMethods.size()})"
                    staticMethods.each { lines << formatMember(it) }
                    lines << ''
                }

                if (members.empty) {
                    lines << 'No members found.'
                }

                text = lines.join('\n')
            }

            return new CallToolResult(text, false)
        })
    }

    //------------------------------------------------------------------
    // Formatting helpers
    //------------------------------------------------------------------
    private static String formatMember(MemberInfo member) {
        def lines = []
        def annotationPrefix = member.annotations ? member.annotations.collect { "@${it}" }.join(' ') + ' ' : ''

        if (member.kind == 'method') {
            def params = member.parameters?.collect { "${it.name}: ${truncateType(it.type)}" }?.join(', ') ?: ''
            def ret = truncateType(member.type ?: 'void')
            lines << "- ${annotationPrefix}${member.name}(${params}): ${ret}"
        } else {
            lines << "- ${annotationPrefix}${member.name}: ${truncateType(member.type)}"
        }

        if (member.groovydoc) {
            lines << "    ${member.groovydoc.split('\n')[0]}"
        }

        return lines.join('\n')
    }

    private static String truncateType(String typeStr) {
        if (!typeStr) return 'Object'
        return typeStr.length() > MAX_TYPE_LENGTH ? typeStr.take(MAX_TYPE_LENGTH) + '...' : typeStr
    }
}
