package io.xh.hoist.mcp.data

import io.xh.hoist.mcp.ContentSource
import io.xh.hoist.mcp.util.McpLog
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.FieldNode
import org.codehaus.groovy.ast.MethodNode
import org.codehaus.groovy.ast.Parameter
import org.codehaus.groovy.ast.PropertyNode
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilePhase
import org.codehaus.groovy.control.CompilerConfiguration

import java.util.concurrent.CountDownLatch

/**
 * AST-based symbol extraction from hoist-core Groovy/Java source files.
 * Provides indexed search over classes, methods, and properties.
 */
class GroovyRegistry {

    final ContentSource contentSource

    /** Source directories to scan, relative to repo root. */
    static final SOURCE_DIRS = [
        [dir: 'grails-app/controllers', category: 'controllers'],
        [dir: 'grails-app/domain',      category: 'domain'],
        [dir: 'grails-app/services',    category: 'services'],
        [dir: 'grails-app/init',        category: 'init'],
        [dir: 'src/main/groovy',        category: 'core'],
    ]

    /** Classes whose members are indexed for search. */
    static final MEMBER_INDEXED_CLASSES = [
        'BaseService', 'BaseController', 'RestController',
        'HoistUser', 'Cache', 'CachedValue', 'Timer',
        'Filter', 'FieldFilter', 'CompoundFilter',
        'JSONClient', 'ClusterService', 'ConfigService',
        'MonitorResult', 'LogSupport', 'HttpException', 'IdentitySupport',
    ] as Set

    // Indexed data
    private Map<String, List<SymbolEntry>> symbolIndex = [:]
    private Map<String, List<MemberIndexEntry>> memberIndex = [:]

    // Initialization control
    private CountDownLatch initLatch = new CountDownLatch(1)
    private volatile boolean initialized = false

    GroovyRegistry(ContentSource contentSource) {
        this.contentSource = contentSource
    }

    /** Start background indexing. */
    void beginInitialization() {
        Thread.start('groovy-registry-init') {
            try {
                buildIndex()
            } catch (Exception e) {
                McpLog.error("Groovy registry init failed: ${e.message}")
            } finally {
                initialized = true
                initLatch.countDown()
            }
        }
    }

    /** Block until initialization is complete. */
    void ensureInitialized() {
        if (!initialized) {
            McpLog.info('Waiting for Groovy registry initialization...')
            initLatch.await()
        }
    }

    //------------------------------------------------------------------
    // Search
    //------------------------------------------------------------------
    List<SymbolEntry> searchSymbols(String query, String kind = null, int limit = 20) {
        ensureInitialized()
        def queryLower = query.toLowerCase()

        def results = symbolIndex.values().flatten().findAll { SymbolEntry entry ->
            if (kind && entry.kind != kind) return false
            return entry.name.toLowerCase().contains(queryLower)
        }

        // Sort: exact matches first, then by name
        results.sort { a, b ->
            def aExact = a.name.equalsIgnoreCase(query) ? 0 : 1
            def bExact = b.name.equalsIgnoreCase(query) ? 0 : 1
            if (aExact != bExact) return aExact <=> bExact
            return a.name <=> b.name
        }

        return results.take(Math.min(limit, 50))
    }

    List<MemberIndexEntry> searchMembers(String query, int limit = 15) {
        ensureInitialized()
        def queryLower = query.toLowerCase()

        def results = memberIndex.values().flatten().findAll { MemberIndexEntry entry ->
            entry.name.toLowerCase().contains(queryLower)
        }

        results.sort { a, b ->
            def aExact = a.name.equalsIgnoreCase(query) ? 0 : 1
            def bExact = b.name.equalsIgnoreCase(query) ? 0 : 1
            if (aExact != bExact) return aExact <=> bExact
            if (a.name != b.name) return a.name <=> b.name
            return a.ownerName <=> b.ownerName
        }

        return results.take(Math.min(limit, 20))
    }

    //------------------------------------------------------------------
    // Detail retrieval (re-parses single file)
    //------------------------------------------------------------------
    SymbolDetail getSymbolDetail(String name, String filePath = null) {
        ensureInitialized()
        def entries = symbolIndex[name.toLowerCase()] ?: []
        if (entries.empty) return null

        def entry = filePath
            ? entries.find { it.filePath == filePath }
            : entries[0]
        if (!entry) return null

        def classNode = parseFile(entry.filePath)
        if (!classNode) return null

        return buildSymbolDetail(classNode, entry)
    }

    List<MemberInfo> getMembers(String name, String filePath = null) {
        ensureInitialized()
        def entries = symbolIndex[name.toLowerCase()] ?: []
        if (entries.empty) return null

        def entry = filePath
            ? entries.find { it.filePath == filePath }
            : entries[0]
        if (!entry) return null

        def classNode = parseFile(entry.filePath)
        if (!classNode) return null

        return extractMembers(classNode)
    }

    //------------------------------------------------------------------
    // Index building
    //------------------------------------------------------------------
    private void buildIndex() {
        McpLog.info('Building Groovy symbol index...')
        int fileCount = 0, symbolCount = 0, memberCount = 0

        for (sourceDir in SOURCE_DIRS) {
            def files = contentSource.findFiles(sourceDir.dir, '.groovy')
            for (relPath in files) {
                try {
                    def classNode = parseFile(relPath)
                    if (!classNode) continue

                    fileCount++
                    def entry = new SymbolEntry(
                        name: classNode.nameWithoutPackage,
                        kind: classKind(classNode),
                        filePath: relPath,
                        sourceCategory: sourceDir.category,
                        packageName: classNode.packageName ?: '',
                        isAbstract: classNode.isAbstract()
                    )

                    def key = entry.name.toLowerCase()
                    symbolIndex.computeIfAbsent(key) { [] } << entry
                    symbolCount++

                    // Index members of curated classes
                    if (entry.name in MEMBER_INDEXED_CLASSES) {
                        def members = extractMembers(classNode)
                        for (member in members) {
                            def memberKey = member.name.toLowerCase()
                            memberIndex.computeIfAbsent(memberKey) { [] } << new MemberIndexEntry(
                                name: member.name,
                                memberKind: member.kind,
                                ownerName: entry.name,
                                filePath: relPath,
                                sourceCategory: sourceDir.category,
                                isStatic: member.isStatic,
                                type: member.type,
                                groovydoc: member.groovydoc,
                                annotations: member.annotations
                            )
                        }
                        memberCount += members.size()
                    }
                } catch (Exception e) {
                    McpLog.warn("Failed to parse ${relPath}: ${e.message}")
                }
            }
        }

        McpLog.info("Groovy index complete: ${fileCount} files, ${symbolCount} symbols, ${memberCount} indexed members")
    }

    //------------------------------------------------------------------
    // AST parsing
    //------------------------------------------------------------------
    private ClassNode parseFile(String relPath) {
        def content = contentSource.readFile(relPath)
        if (!content) return null

        try {
            def config = new CompilerConfiguration()
            config.optimizationOptions = [groovydoc: true]

            def unit = new CompilationUnit(config)
            unit.addSource(relPath, content)
            unit.compile(CompilePhase.CONVERSION.phaseNumber)

            // Get the first class from the compilation
            def classes = unit.AST?.classes
            if (!classes) return null

            // Return the primary class (skip script class)
            return classes.find { !it.isScript() } ?: classes[0]
        } catch (Exception e) {
            // CONVERSION phase should rarely fail, but guard against syntax errors
            return null
        }
    }

    private static String classKind(ClassNode node) {
        if (node.isInterface()) return 'interface'
        if (node.isEnum()) return 'enum'
        // Groovy traits are interfaces with a $Trait$Helper inner class at the AST level
        if (node.isInterface() && node.name.contains('$Trait$')) return 'trait'
        // Check annotations for @Trait (Groovy 4+)
        if (node.annotations?.any { it.classNode?.name?.endsWith('Trait') }) return 'trait'
        return 'class'
    }

    //------------------------------------------------------------------
    // Member extraction
    //------------------------------------------------------------------
    private List<MemberInfo> extractMembers(ClassNode classNode) {
        def members = []

        // Properties (Groovy properties)
        for (PropertyNode prop in classNode.properties) {
            if (isPrivate(prop.name)) continue
            members << new MemberInfo(
                name: prop.name,
                kind: 'property',
                type: typeName(prop.type),
                visibility: visibilityStr(prop.modifiers),
                isStatic: prop.isStatic(),
                isAbstract: false,
                groovydoc: extractGroovydoc(prop),
                annotations: annotationNames(prop.annotations)
            )
        }

        // Fields (explicit Java-style fields, skip backing fields for properties)
        def propertyNames = classNode.properties*.name as Set
        for (FieldNode field in classNode.fields) {
            if (isPrivate(field.name)) continue
            if (field.name in propertyNames) continue  // Skip property backing fields
            if (field.isSynthetic()) continue
            members << new MemberInfo(
                name: field.name,
                kind: 'field',
                type: typeName(field.type),
                visibility: visibilityStr(field.modifiers),
                isStatic: field.isStatic(),
                isAbstract: false,
                groovydoc: extractGroovydoc(field),
                annotations: annotationNames(field.annotations)
            )
        }

        // Methods
        for (MethodNode method in classNode.methods) {
            if (isPrivate(method.name)) continue
            if (method.isSynthetic()) continue
            if (method.name.startsWith('$')) continue
            // Skip property getters/setters
            if (isPropertyAccessor(method, propertyNames)) continue

            members << new MemberInfo(
                name: method.name,
                kind: 'method',
                type: typeName(method.returnType),
                visibility: visibilityStr(method.modifiers),
                isStatic: method.isStatic(),
                isAbstract: method.isAbstract(),
                groovydoc: extractGroovydoc(method),
                annotations: annotationNames(method.annotations),
                parameters: method.parameters.collect { Parameter p ->
                    new ParameterInfo(name: p.name, type: typeName(p.type))
                }
            )
        }

        return members
    }

    private static boolean isPrivate(String name) {
        return name.startsWith('_') || name.startsWith('$')
    }

    private static boolean isPropertyAccessor(MethodNode method, Set<String> propertyNames) {
        def name = method.name
        if (name.length() > 3 && (name.startsWith('get') || name.startsWith('set'))) {
            def propName = name[3].toLowerCase() + name.substring(4)
            return propName in propertyNames
        }
        if (name.length() > 2 && name.startsWith('is')) {
            def propName = name[2].toLowerCase() + name.substring(3)
            return propName in propertyNames
        }
        return false
    }

    private static String typeName(ClassNode type) {
        if (!type) return 'Object'
        def name = type.nameWithoutPackage ?: type.name
        def generics = type.genericsTypes
        if (generics) {
            def genericStr = generics.collect { g ->
                g.type?.nameWithoutPackage ?: g.name ?: '?'
            }.join(', ')
            return "${name}<${genericStr}>"
        }
        return name
    }

    private static String visibilityStr(int modifiers) {
        if (java.lang.reflect.Modifier.isPublic(modifiers)) return 'public'
        if (java.lang.reflect.Modifier.isProtected(modifiers)) return 'protected'
        if (java.lang.reflect.Modifier.isPrivate(modifiers)) return 'private'
        return 'package-private'
    }

    private static List<String> annotationNames(List annotations) {
        return annotations?.collect { it.classNode?.nameWithoutPackage ?: it.classNode?.name }?.findAll { it } ?: []
    }

    private static String extractGroovydoc(node) {
        // Groovy 4 stores Groovydoc in node metadata when enabled
        try {
            def groovydoc = node.groovydoc
            if (groovydoc && groovydoc.content) {
                return cleanDocComment(groovydoc.content)
            }
        } catch (Exception ignored) {}
        return ''
    }

    private static String cleanDocComment(String raw) {
        if (!raw) return ''
        return raw
            .replaceAll(/(?m)^\s*\/?\*+\/?/, '')  // Remove /** */ * markers
            .replaceAll(/@\w+.*/, '')                // Remove @param etc tags
            .trim()
            .split('\n')
            .collect { it.trim() }
            .findAll { it }
            .take(3)                                 // First 3 meaningful lines
            .join(' ')
    }

    //------------------------------------------------------------------
    // Symbol detail building
    //------------------------------------------------------------------
    private static SymbolDetail buildSymbolDetail(ClassNode classNode, SymbolEntry entry) {
        def detail = new SymbolDetail()
        detail.name = entry.name
        detail.kind = entry.kind
        detail.filePath = entry.filePath
        detail.sourceCategory = entry.sourceCategory
        detail.packageName = entry.packageName
        detail.isAbstract = entry.isAbstract

        // Signature
        def sig = new StringBuilder()
        if (classNode.isAbstract() && !classNode.isInterface()) sig.append('abstract ')
        sig.append(entry.kind).append(' ').append(entry.name)
        def generics = classNode.genericsTypes
        if (generics) {
            sig.append('<')
            sig.append(generics.collect { g -> g.name ?: '?' }.join(', '))
            sig.append('>')
        }
        detail.signature = sig.toString()

        // Extends
        def superClass = classNode.superClass
        if (superClass && superClass.name != 'java.lang.Object' && superClass.name != 'groovy.lang.Script') {
            detail.extendsClass = superClass.nameWithoutPackage
        }

        // Implements
        detail.implementsList = classNode.interfaces?.collect { it.nameWithoutPackage }?.findAll { it != 'GroovyObject' } ?: []

        // Annotations
        detail.annotations = annotationNames(classNode.annotations)

        // Groovydoc
        detail.groovydoc = extractGroovydoc(classNode)

        return detail
    }

    //------------------------------------------------------------------
    // Data classes
    //------------------------------------------------------------------
    static class SymbolEntry {
        String name, kind, filePath, sourceCategory, packageName
        boolean isAbstract
    }

    static class SymbolDetail extends SymbolEntry {
        String signature, groovydoc, extendsClass
        List<String> implementsList = [], annotations = []
    }

    static class MemberInfo {
        String name, kind, type, visibility, groovydoc
        boolean isStatic, isAbstract
        List<String> annotations = []
        List<ParameterInfo> parameters = []
    }

    static class MemberIndexEntry {
        String name, memberKind, ownerName, filePath, sourceCategory, type, groovydoc
        boolean isStatic
        List<String> annotations = []
    }

    static class ParameterInfo {
        String name, type
    }
}
