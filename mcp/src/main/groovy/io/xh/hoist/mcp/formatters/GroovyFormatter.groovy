package io.xh.hoist.mcp.formatters

import io.xh.hoist.mcp.data.GroovyRegistry.MemberIndexEntry
import io.xh.hoist.mcp.data.GroovyRegistry.MemberInfo
import io.xh.hoist.mcp.data.GroovyRegistry.SymbolDetail
import io.xh.hoist.mcp.data.GroovyRegistry.SymbolEntry

/**
 * Pure formatting functions for Groovy/Java symbol tool output. Shared between
 * the MCP tools in {@code tools/GroovyTools} and the CLI commands in
 * {@code cli/SymbolsCli} so that both produce byte-identical text and parallel
 * JSON shapes.
 *
 * Methods return body text without trailing "Tip: ..." hints — those are added
 * by the consumer (MCP tool or CLI) so the wording can reference the right
 * tool surface.
 */
class GroovyFormatter {

    static final int SCHEMA_VERSION = 1
    private static final int MAX_TYPE_LENGTH = 200

    //------------------------------------------------------------------
    // search-symbols
    //------------------------------------------------------------------
    static String formatSearchSymbols(String query, List<SymbolEntry> symbols, List<MemberIndexEntry> members) {
        if (!symbols && !members) {
            return "No symbols or members found matching '${query}'. Try a broader search term."
        }

        def lines = []
        if (symbols) {
            lines << "Symbols (${symbols.size()} matches):"
            lines << ''
            symbols.eachWithIndex { result, i ->
                def abstractTag = result.isAbstract ? ' (abstract)' : ''
                lines << "${i + 1}. [${result.kind}] ${result.name}${abstractTag} (category: ${result.sourceCategory}, file: ${result.filePath})"
            }
        }
        if (members) {
            if (lines) lines << ''
            lines << "Members of key classes (${members.size()} matches):"
            lines << ''
            members.eachWithIndex { m, i ->
                def staticPrefix = m.isStatic ? 'static ' : ''
                def typeStr = truncateType(m.type)
                lines << "${i + 1}. [${m.memberKind}] ${staticPrefix}${m.name}: ${typeStr} (on ${m.ownerName})"
                if (m.groovydoc) lines << "    ${docPreview(m.groovydoc)}"
            }
        }
        return lines.join('\n').stripTrailing()
    }

    static Map searchSymbolsAsMap(String query, List<SymbolEntry> symbols, List<MemberIndexEntry> members) {
        return [
            schemaVersion: SCHEMA_VERSION,
            query: query,
            symbols: symbols.collect { s ->
                [
                    name: s.name,
                    kind: s.kind,
                    filePath: s.filePath,
                    sourceCategory: s.sourceCategory,
                    packageName: s.packageName,
                    isAbstract: s.isAbstract
                ]
            },
            members: members.collect { m ->
                [
                    name: m.name,
                    memberKind: m.memberKind,
                    ownerName: m.ownerName,
                    filePath: m.filePath,
                    sourceCategory: m.sourceCategory,
                    type: m.type,
                    isStatic: m.isStatic,
                    annotations: m.annotations ?: [],
                    groovydoc: m.groovydoc ?: ''
                ]
            }
        ]
    }

    //------------------------------------------------------------------
    // get-symbol
    //------------------------------------------------------------------
    static String formatSymbolNotFound(String name) {
        return "Symbol '${name}' not found."
    }

    static String formatSymbolDetail(SymbolDetail detail) {
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
        return lines.join('\n').stripTrailing()
    }

    /**
     * Member-as-symbol fallback view, used when `get-symbol <name>` matches an indexed
     * member rather than a class (e.g. `createCache` → method on `BaseService`).
     * Renders enough signature + Groovydoc to be useful, and points at the owning class
     * so the agent can pivot to `get-members` if they want full context.
     */
    static String formatMemberAsSymbol(String name, List<MemberIndexEntry> matches) {
        if (!matches) return formatSymbolNotFound(name)

        def displayName = io.xh.hoist.mcp.data.GroovyRegistry.simpleSymbolName(name)
        def lines = []
        if (matches.size() == 1) {
            lines << "# ${displayName} (${matches[0].memberKind}, member of ${matches[0].ownerName})"
        } else {
            lines << "# ${displayName} — ${matches.size()} member matches"
        }
        lines << ''
        matches.eachWithIndex { m, i ->
            if (matches.size() > 1) {
                lines << "## ${i + 1}. on ${m.ownerName}"
            }
            lines << "Owner: ${m.ownerName}"
            lines << "File: ${m.filePath}"
            lines << "Kind: ${m.memberKind}${m.isStatic ? ' (static)' : ''}"
            lines << ''
            lines << '## Signature'
            lines << memberSignature(m)
            if (m.groovydoc) {
                lines << ''
                lines << '## Documentation'
                lines << m.groovydoc
            }
            if (i < matches.size() - 1) lines << ''
        }
        return lines.join('\n').stripTrailing()
    }

    static Map getMemberAsSymbolAsMap(String name, List<MemberIndexEntry> matches) {
        return [
            schemaVersion: SCHEMA_VERSION,
            name: name,
            found: !matches.empty,
            resolution: 'member',
            matches: matches.collect { m ->
                [
                    name: m.name,
                    memberKind: m.memberKind,
                    ownerName: m.ownerName,
                    filePath: m.filePath,
                    sourceCategory: m.sourceCategory,
                    type: m.type,
                    isStatic: m.isStatic,
                    annotations: m.annotations ?: [],
                    parameters: m.parameters?.collect { [name: it.name, type: it.type] } ?: [],
                    groovydoc: m.groovydoc ?: ''
                ]
            }
        ]
    }

    private static String memberSignature(MemberIndexEntry m) {
        def staticPrefix = m.isStatic ? 'static ' : ''
        def annotationPrefix = m.annotations ? m.annotations.collect { "@${it}" }.join(' ') + ' ' : ''
        if (m.memberKind == 'method') {
            def params = m.parameters?.collect { "${it.name}: ${truncateType(it.type)}" }?.join(', ') ?: ''
            def ret = truncateType(m.type ?: 'void')
            return "${staticPrefix}${annotationPrefix}${m.name}(${params}): ${ret}"
        }
        return "${staticPrefix}${annotationPrefix}${m.name}: ${truncateType(m.type)}"
    }

    static Map getSymbolAsMap(String name, SymbolDetail detail) {
        if (!detail) {
            return [schemaVersion: SCHEMA_VERSION, name: name, found: false, symbol: null]
        }
        return [
            schemaVersion: SCHEMA_VERSION,
            name: name,
            found: true,
            symbol: [
                name: detail.name,
                kind: detail.kind,
                packageName: detail.packageName,
                filePath: detail.filePath,
                sourceCategory: detail.sourceCategory,
                isAbstract: detail.isAbstract,
                signature: detail.signature,
                extendsClass: detail.extendsClass,
                implementsList: detail.implementsList ?: [],
                annotations: detail.annotations ?: [],
                groovydoc: detail.groovydoc ?: ''
            ]
        ]
    }

    //------------------------------------------------------------------
    // get-members
    //------------------------------------------------------------------
    static String formatMembersNotFound(String name) {
        return "Symbol '${name}' not found or is not a class/interface."
    }

    static String formatMembers(String name, List<MemberInfo> members) {
        def constructors = members.findAll { it.kind == 'constructor' }
        def instanceProps = members.findAll { !it.isStatic && it.kind in ['property', 'field'] }
        def instanceMethods = members.findAll { !it.isStatic && it.kind == 'method' }
        def staticProps = members.findAll { it.isStatic && it.kind in ['property', 'field'] }
        def staticMethods = members.findAll { it.isStatic && it.kind == 'method' }

        def displayName = io.xh.hoist.mcp.data.GroovyRegistry.simpleSymbolName(name)
        def lines = ["# ${displayName} Members", '']

        if (constructors) {
            lines << "## Constructors (${constructors.size()})"
            constructors.each { lines << formatMemberLine(it) }
            lines << ''
        }
        if (instanceProps) {
            lines << "## Properties (${instanceProps.size()})"
            instanceProps.each { lines << formatMemberLine(it) }
            lines << ''
        }
        if (instanceMethods) {
            lines << "## Methods (${instanceMethods.size()})"
            instanceMethods.each { lines << formatMemberLine(it) }
            lines << ''
        }
        if (staticProps) {
            lines << "## Static Properties (${staticProps.size()})"
            staticProps.each { lines << formatMemberLine(it) }
            lines << ''
        }
        if (staticMethods) {
            lines << "## Static Methods (${staticMethods.size()})"
            staticMethods.each { lines << formatMemberLine(it) }
            lines << ''
        }
        if (members.empty) {
            lines << 'No members found.'
        }
        return lines.join('\n').stripTrailing()
    }

    static Map getMembersAsMap(String name, List<MemberInfo> members) {
        if (members == null) {
            return [schemaVersion: SCHEMA_VERSION, name: name, found: false, members: null]
        }
        return [
            schemaVersion: SCHEMA_VERSION,
            name: name,
            found: true,
            members: members.collect { m ->
                [
                    name: m.name,
                    kind: m.kind,
                    type: m.type,
                    visibility: m.visibility,
                    isStatic: m.isStatic,
                    isAbstract: m.isAbstract,
                    annotations: m.annotations ?: [],
                    groovydoc: m.groovydoc ?: '',
                    parameters: m.parameters?.collect { [name: it.name, type: it.type] } ?: []
                ]
            }
        ]
    }

    //------------------------------------------------------------------
    // helpers
    //------------------------------------------------------------------
    private static String formatMemberLine(MemberInfo member) {
        def lines = []
        def annotationPrefix = member.annotations ? member.annotations.collect { "@${it}" }.join(' ') + ' ' : ''
        if (member.kind == 'method') {
            def params = member.parameters?.collect { "${it.name}: ${truncateType(it.type)}" }?.join(', ') ?: ''
            def ret = truncateType(member.type ?: 'void')
            lines << "- ${annotationPrefix}${member.name}(${params}): ${ret}"
        } else if (member.kind == 'constructor') {
            def params = member.parameters?.collect { "${it.name}: ${truncateType(it.type)}" }?.join(', ') ?: ''
            lines << "- ${annotationPrefix}${member.name}(${params})"
        } else {
            lines << "- ${annotationPrefix}${member.name}: ${truncateType(member.type)}"
        }
        if (member.groovydoc) {
            lines << "    ${docPreview(member.groovydoc)}"
        }
        return lines.join('\n')
    }

    /**
     * One-line preview: first paragraph of the Groovydoc with soft line-wraps collapsed.
     * Source Groovydocs often wrap prose at ~80 chars; taking just the first physical
     * line would cut mid-clause.
     */
    private static String docPreview(String groovydoc) {
        def firstPara = groovydoc.split(/\n\s*\n/, 2)[0]
        return firstPara.split('\n').collect { it.trim() }.findAll { it }.join(' ')
    }

    private static String truncateType(String typeStr) {
        if (!typeStr) return 'Object'
        return typeStr.length() > MAX_TYPE_LENGTH ? typeStr.take(MAX_TYPE_LENGTH) + '...' : typeStr
    }
}
