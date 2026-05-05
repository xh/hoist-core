package io.xh.hoist.mcp.data

import io.xh.hoist.mcp.ContentSource
import io.xh.hoist.mcp.util.McpLog
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.ConstructorNode
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
 *
 * Both .groovy and .java sources are parsed via Groovy's CompilationUnit at
 * CompilePhase.CONVERSION — this surfaces ClassNodes, methods, fields, and
 * Javadoc/Groovydoc uniformly without a separate Java parser dependency.
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
        def normalizedQuery = simpleSymbolName(query)
        def queryLower = normalizedQuery.toLowerCase()

        def results = symbolIndex.values().flatten().findAll { SymbolEntry entry ->
            if (kind && entry.kind != kind) return false
            return entry.name.toLowerCase().contains(queryLower)
        }

        // Sort: exact matches first, then by name
        results.sort { a, b ->
            def aExact = a.name.equalsIgnoreCase(normalizedQuery) ? 0 : 1
            def bExact = b.name.equalsIgnoreCase(normalizedQuery) ? 0 : 1
            if (aExact != bExact) return aExact <=> bExact
            return a.name <=> b.name
        }

        return results.take(Math.min(limit, 50))
    }

    List<MemberIndexEntry> searchMembers(String query, int limit = 15) {
        ensureInitialized()
        def normalizedQuery = simpleSymbolName(query)
        def queryLower = normalizedQuery.toLowerCase()

        def results = memberIndex.values().flatten().findAll { MemberIndexEntry entry ->
            entry.name.toLowerCase().contains(queryLower)
        }

        results.sort { a, b ->
            def aExact = a.name.equalsIgnoreCase(normalizedQuery) ? 0 : 1
            def bExact = b.name.equalsIgnoreCase(normalizedQuery) ? 0 : 1
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
        def lookup = simpleSymbolName(name)
        def entries = symbolIndex[lookup.toLowerCase()] ?: []
        if (entries.empty) return null

        def entry = filePath
            ? entries.find { it.filePath == filePath }
            : entries[0]
        if (!entry) return null

        def classNode = findClassInFile(entry.filePath, entry.name)
        if (!classNode) return null

        return buildSymbolDetail(classNode, entry)
    }

    List<MemberInfo> getMembers(String name, String filePath = null) {
        ensureInitialized()
        def lookup = simpleSymbolName(name)
        def entries = symbolIndex[lookup.toLowerCase()] ?: []
        if (entries.empty) return null

        def entry = filePath
            ? entries.find { it.filePath == filePath }
            : entries[0]
        if (!entry) return null

        def classNode = findClassInFile(entry.filePath, entry.name)
        if (!classNode) return null

        return extractMembers(classNode)
    }

    private ClassNode findClassInFile(String relPath, String simpleName) {
        return parseFileClasses(relPath).find { extractSimpleName(it).equalsIgnoreCase(simpleName) }
    }

    /**
     * Look up indexed members by exact name (case-insensitive) — used as a fallback when
     * `get-symbol <name>` doesn't match a class. An agent searching for e.g. `createCache`
     * gets a useful pointer instead of "not found", since that name is a method on
     * `BaseService`.
     */
    List<MemberIndexEntry> findMembersByName(String name) {
        ensureInitialized()
        def lookup = simpleSymbolName(name)
        def hits = memberIndex[lookup.toLowerCase()] ?: []
        return hits.findAll { it.name.equalsIgnoreCase(lookup) }
    }

    /**
     * Strip package and outer-class prefixes from a symbol name so that agents can paste
     * fully-qualified or nested references and still get a hit. Examples:
     *   io.xh.hoist.util.Timer            -> Timer
     *   io.xh.hoist.ldap.LdapConfig$Inner -> Inner
     *   BaseService.createTimer           -> createTimer
     *   Timer                             -> Timer
     */
    static String simpleSymbolName(String input) {
        if (!input) return input
        def afterDot = input.contains('.') ? input.substring(input.lastIndexOf('.') + 1) : input
        return afterDot.contains('$') ? afterDot.substring(afterDot.lastIndexOf('$') + 1) : afterDot
    }

    //------------------------------------------------------------------
    // Index building
    //------------------------------------------------------------------
    private void buildIndex() {
        McpLog.info('Building Groovy symbol index...')
        int fileCount = 0, symbolCount = 0, memberCount = 0

        for (sourceDir in SOURCE_DIRS) {
            def files = contentSource.findFiles(sourceDir.dir, '.groovy') +
                contentSource.findFiles(sourceDir.dir, '.java')
            for (relPath in files) {
                try {
                    def classes = parseFileClasses(relPath)
                    if (classes.empty) continue

                    fileCount++
                    for (ClassNode classNode in classes) {
                        def simpleName = extractSimpleName(classNode)
                        def entry = new SymbolEntry(
                            name: simpleName,
                            kind: classKind(classNode),
                            filePath: relPath,
                            sourceCategory: sourceDir.category,
                            packageName: classNode.packageName ?: '',
                            isAbstract: classNode.isAbstract()
                        )

                        symbolIndex.computeIfAbsent(simpleName.toLowerCase()) { [] } << entry
                        symbolCount++

                        // Index members of curated classes
                        if (simpleName in MEMBER_INDEXED_CLASSES) {
                            def members = extractMembers(classNode)
                            for (member in members) {
                                // Skip constructors: their name == class name, so a search hit
                                // would duplicate the class symbol result.
                                if (member.kind == 'constructor') continue
                                def memberKey = member.name.toLowerCase()
                                memberIndex.computeIfAbsent(memberKey) { [] } << new MemberIndexEntry(
                                    name: member.name,
                                    memberKind: member.kind,
                                    ownerName: simpleName,
                                    filePath: relPath,
                                    sourceCategory: sourceDir.category,
                                    isStatic: member.isStatic,
                                    type: member.type,
                                    groovydoc: member.groovydoc,
                                    annotations: member.annotations,
                                    parameters: member.parameters
                                )
                            }
                            memberCount += members.size()
                        }
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
    /**
     * Parse a Groovy file and return ALL non-script classes — top-level + nested.
     * Inner classes appear in `unit.AST.classes` alongside their outer class with
     * `name` like `Outer$Inner`; we keep them so they can be indexed and looked up
     * under their bare simple names.
     */
    private List<ClassNode> parseFileClasses(String relPath) {
        def content = contentSource.readFile(relPath)
        if (!content) return []

        try {
            def config = new CompilerConfiguration()
            config.optimizationOptions = [groovydoc: true]

            def unit = new CompilationUnit(config)
            unit.addSource(relPath, content)
            unit.compile(CompilePhase.CONVERSION.phaseNumber)

            return (unit.AST?.classes ?: []).findAll { !it.isScript() }
        } catch (Exception e) {
            return []
        }
    }

    /** Bare class name without package or `Outer$` prefix (e.g. `LdapServerOptions`). */
    private static String extractSimpleName(ClassNode c) {
        return (c.nameWithoutPackage ?: c.name ?: '').tokenize('$').last()
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

        // Constructors — surfaced as a distinct member kind so DTO-style classes
        // (POGOs, exception types, TypedConfigMap subclasses) advertise their entry points.
        for (ConstructorNode ctor in classNode.declaredConstructors) {
            if (ctor.isSynthetic()) continue
            if (java.lang.reflect.Modifier.isPrivate(ctor.modifiers)) continue
            def ctorName = extractSimpleName(classNode)
            members << new MemberInfo(
                name: ctorName,
                kind: 'constructor',
                type: ctorName,
                visibility: visibilityStr(ctor.modifiers),
                isStatic: false,
                isAbstract: false,
                groovydoc: extractGroovydoc(ctor),
                annotations: annotationNames(ctor.annotations),
                parameters: ctor.parameters.collect { Parameter p ->
                    new ParameterInfo(name: p.name, type: typeName(p.type))
                }
            )
        }

        // Methods
        for (MethodNode method in classNode.methods) {
            if (isPrivate(method.name)) continue
            if (method.isSynthetic()) continue
            if (method.name.startsWith('$')) continue
            // Skip JVM-synthesized initializer methods (<clinit>, <init>) that surface
            // when parsing Java sources via Groovy's CompilationUnit.
            if (method.name.startsWith('<')) continue
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
        String s = raw

        // 1. Strip Groovydoc comment framing.
        s = s.replaceAll(/(?m)^\s*\/\*+/, '')         // /** at line start
        s = s.replaceAll(/(?m)\*+\/\s*$/, '')         // */ at line end (line-only or inline-trailing)
        s = s.replaceAll(/(?m)^\s*\*+\s?/, '')        // line-leading * continuation markers

        // 2. Render <pre>{@code ...}</pre> and bare <pre>...</pre> blocks as fenced code blocks.
        // Non-greedy with the `}\s*</pre>` anchor handles examples that contain inner braces.
        s = s.replaceAll(/(?s)<pre>\s*\{@code\s*(.*?)\s*\}\s*<\/pre>/, '\n```groovy\n$1\n```\n')
        s = s.replaceAll(/(?s)<pre>\s*(.*?)\s*<\/pre>/, '\n```\n$1\n```\n')

        // 3. Render inline Groovydoc tags. {@link X}, {@link X#y}, {@link X label} → backtick.
        s = s.replaceAll(/\{@code\s+([^}]+)\}/, '`$1`')
        s = s.replaceAll(/\{@literal\s+([^}]+)\}/, '$1')
        s = renderLinkTags(s)

        // 4. Strip block tags (@param, @return, @throws, @since, ...) — line-anchored only,
        //    so mid-prose `{@link}` references aren't eaten.
        s = s.replaceAll(/(?m)^\s*@\w+\b.*$/, '')

        // 5. Strip remaining stray HTML tags that don't carry semantic value.
        s = s.replaceAll(/<\/?(p|em|i|b|strong|br|ul|ol|li|h\d)\s*\/?>/, '')

        // 6. Right-trim each line, collapse 3+ blank-line runs to 2, and outer-trim.
        return s
            .split('\n')
            .collect { it.replaceAll(/\s+$/, '') }
            .join('\n')
            .replaceAll(/\n{3,}/, '\n\n')
            .trim()
    }

    private static String renderLinkTags(String s) {
        return s.replaceAll(/\{@link\s+([^}]+)\}/) { match, content ->
            def trimmed = content.trim()
            def spaceIdx = trimmed.indexOf(' ')
            if (spaceIdx >= 0) {
                // Explicit label: {@link Foo bar baz} → `bar baz`
                return "`${trimmed.substring(spaceIdx + 1).trim()}`"
            }
            // Strip package qualifier: io.xh.foo.Bar#baz → Bar#baz
            def simple = trimmed.contains('.') ? trimmed.substring(trimmed.lastIndexOf('.') + 1) : trimmed
            // Member-ref form: Class#member → Class.member; bare #member → member
            simple = simple.replace('#', '.')
            if (simple.startsWith('.')) simple = simple.substring(1)
            return "`${simple}`"
        }
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
        List<ParameterInfo> parameters = []
    }

    static class ParameterInfo {
        String name, type
    }
}
