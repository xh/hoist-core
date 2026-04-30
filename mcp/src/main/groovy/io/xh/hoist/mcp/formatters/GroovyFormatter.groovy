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
                if (m.groovydoc) lines << "    ${m.groovydoc}"
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
        def instanceProps = members.findAll { !it.isStatic && it.kind in ['property', 'field'] }
        def instanceMethods = members.findAll { !it.isStatic && it.kind == 'method' }
        def staticProps = members.findAll { it.isStatic && it.kind in ['property', 'field'] }
        def staticMethods = members.findAll { it.isStatic && it.kind == 'method' }

        def lines = ["# ${name} Members", '']

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
