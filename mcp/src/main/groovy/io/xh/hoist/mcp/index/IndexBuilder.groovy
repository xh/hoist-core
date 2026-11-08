package io.xh.hoist.mcp.index

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import io.xh.hoist.mcp.ContentSource
import io.xh.hoist.mcp.LocalContentSource
import io.xh.hoist.mcp.data.GroovyRegistry
import io.xh.hoist.mcp.data.GroovyRegistry.MemberIndexEntry
import io.xh.hoist.mcp.data.GroovyRegistry.MemberInfo
import io.xh.hoist.mcp.data.GroovyRegistry.SymbolEntry

import java.time.Instant
import java.util.zip.GZIPOutputStream

/**
 * Builds the Hoist Docs index file for a hoist-core checkout.
 *
 * Writes one JSON file (schema v1, see the hoist-docs repo's `docs/index-schema.md`) holding the
 * doc registry and markdown, every indexed symbol with its detail and members precomputed, and the
 * member search index. The Hoist Docs service reads this file; it never parses hoist-core source.
 *
 * Usage: `./gradlew :mcp:buildIndex` - or run this class with `--root <dir> --out <dir> [--gzip]`.
 */
class IndexBuilder {

    static final int SCHEMA_VERSION = 1

    final ContentSource source
    final String version

    IndexBuilder(ContentSource source, String version) {
        this.source = source
        this.version = version
    }

    Map build() {
        def registryJson = new JsonSlurper().parseText(source.readFile('docs/doc-registry.json')) as Map
        def docEntries = (registryJson.entries as List<Map>)
            .findAll { source.fileExists(it.id as String) }
            .collect { Map e ->
                [
                    id            : e.id,
                    title         : e.title,
                    mcpCategory   : e.mcpCategory,
                    viewerCategory: e.viewerCategory,
                    description   : e.description ?: '',
                    keywords      : e.keywords ?: [],
                    aliases       : e.aliases ?: [],
                    markdown      : source.readFile(e.id as String)
                ]
            }

        def registry = new GroovyRegistry(source)
        registry.beginInitialization()

        def symbols = registry.allSymbols().collect { SymbolEntry entry ->
            def detail = registry.getSymbolDetail(entry.name, entry.filePath)
            def members = registry.getMembers(entry.name, entry.filePath)
            compact([
                name         : entry.name,
                kind         : entry.kind,
                filePath     : entry.filePath,
                sourcePackage: entry.packageName,
                category     : entry.sourceCategory,
                isExported   : true,
                isAbstract   : entry.isAbstract ?: null,
                doc          : detail?.groovydoc ?: '',
                signature    : detail?.signature ?: "${entry.kind} ${entry.name}",
                extends      : detail?.extendsClass,
                implements   : detail?.implementsList ?: null,
                decorators   : detail?.annotations ?: null,
                members      : members?.collect { memberJson(it) }
            ])
        }

        def memberIndex = registry.allMemberIndexEntries().collect { MemberIndexEntry m ->
            compact([
                name         : m.name,
                memberKind   : m.memberKind,
                ownerName    : m.ownerName,
                filePath     : m.filePath,
                sourcePackage: m.sourceCategory,
                isStatic     : m.isStatic,
                type         : m.type,
                doc          : m.groovydoc ?: '',
                decorators   : m.annotations ?: [],
                parameters   : m.parameters ? m.parameters.collect { [name: it.name, type: it.type] } : null
            ])
        }

        return [
            schemaVersion: SCHEMA_VERSION,
            library      : compact([
                id         : 'core',
                name       : 'io.xh:hoist-core',
                version    : version,
                language   : 'groovy',
                gitSha     : gitSha(),
                builtAt    : Instant.now().toString(),
                description: 'Server half of the Hoist toolkit: a Grails plugin providing services, controllers, caching, clustering, config, prefs, roles and monitoring.'
            ]),
            docs         : [
                mcpCategories   : registryJson.mcpCategories ?: [],
                viewerCategories: registryJson.viewerCategories ?: [],
                entries         : docEntries
            ],
            symbols      : symbols,
            memberIndex  : memberIndex
        ]
    }

    private static Map memberJson(MemberInfo m) {
        compact([
            name       : m.name,
            kind       : m.kind,
            type       : m.type,
            isStatic   : m.isStatic,
            isAbstract : m.isAbstract ?: null,
            visibility : m.visibility,
            decorators : m.annotations ?: [],
            doc        : m.groovydoc ?: '',
            parameters : m.parameters ? m.parameters.collect { [name: it.name, type: it.type] } : null
        ])
    }

    /** Drop null-valued keys so optional fields are absent rather than null. */
    private static Map compact(Map m) {
        m.findAll { k, v -> v != null }
    }

    private String gitSha() {
        try {
            def root = new File(source.rootDescription)
            def proc = ['git', 'rev-parse', 'HEAD'].execute(null, root)
            proc.waitFor()
            return proc.exitValue() == 0 ? proc.text.trim() : null
        } catch (ignored) {
            return null
        }
    }

    //------------------------------------------------------------------
    // CLI entry point
    //------------------------------------------------------------------
    static void main(String[] args) {
        def argList = args as List<String>
        def opt = { String name, String dflt -> def i = argList.indexOf(name); i >= 0 ? argList[i + 1] : dflt }
        def root = opt('--root', '.'),
            outDir = new File(opt('--out', 'build/docs-index')),
            version = opt('--version', null) ?: readVersion(new File(root)),
            gzip = argList.contains('--gzip')

        long start = System.currentTimeMillis()
        def index = new IndexBuilder(new LocalContentSource(root), version).build()
        def json = JsonOutput.toJson(index)

        outDir.mkdirs()
        def out = new File(outDir, "core-${version}.json${gzip ? '.gz' : ''}")
        if (gzip) {
            out.withOutputStream { os -> new GZIPOutputStream(os).withWriter('UTF-8') { it << json } }
        } else {
            out.setText(json, 'UTF-8')
        }
        System.err.println("Wrote ${out}: ${index.docs.entries.size()} docs, ${index.symbols.size()} symbols, " +
            "${index.memberIndex.size()} member index entries in ${(System.currentTimeMillis() - start) / 1000}s")
        System.exit(0)
    }

    private static String readVersion(File root) {
        def props = new Properties()
        new File(root, 'gradle.properties').withInputStream { props.load(it) }
        return props.getProperty('xhReleaseVersion')
    }
}
