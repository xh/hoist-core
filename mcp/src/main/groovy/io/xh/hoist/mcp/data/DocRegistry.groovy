package io.xh.hoist.mcp.data

import io.xh.hoist.mcp.ContentSource
import io.xh.hoist.mcp.util.McpLog

/**
 * Registry of hoist-core documentation. Provides a hardcoded inventory of all known docs
 * with metadata for search and categorization, mirroring hoist-react's doc-registry.ts.
 */
class DocRegistry {

    final ContentSource contentSource
    final List<DocEntry> entries

    DocRegistry(ContentSource contentSource) {
        this.contentSource = contentSource
        this.entries = buildRegistry()
    }

    //------------------------------------------------------------------
    // Search
    //------------------------------------------------------------------
    List<SearchResult> searchDocs(String query, String category = null, int limit = 10) {
        def terms = query.toLowerCase().split(/\s+/).toList()
        def filtered = category && category != 'all'
            ? entries.findAll { it.category == category }
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
    List<DocEntry> listDocs(String category = null) {
        if (category && category != 'all') {
            return entries.findAll { it.category == category }
        }
        return entries
    }

    //------------------------------------------------------------------
    // Load content
    //------------------------------------------------------------------
    String loadContent(String docId) {
        def entry = entries.find { it.id == docId }
        if (!entry) return null
        return contentSource.readFile(entry.filePath)
    }

    //------------------------------------------------------------------
    // Registry inventory
    //------------------------------------------------------------------
    private List<DocEntry> buildRegistry() {
        def inventory = [
            // Core Framework
            new DocEntry(
                id: 'base-classes',
                title: 'Base Classes',
                filePath: 'docs/base-classes.md',
                category: 'core-framework',
                description: 'Base classes for services and controllers — lifecycle, resource factories, CRUD patterns.',
                keywords: ['BaseService', 'init', 'destroy', 'createCache', 'createCachedValue', 'createTimer', 'createIMap', 'BaseController', 'renderJSON', 'parseRequestJSON', 'RestController', 'doCreate', 'doList', 'doUpdate', 'doDelete']
            ),
            new DocEntry(
                id: 'request-flow',
                title: 'Request Flow',
                filePath: 'docs/request-flow.md',
                category: 'core-framework',
                description: 'How an HTTP request flows through the Hoist framework.',
                keywords: ['HoistCoreGrailsPlugin', 'HoistFilter', 'UrlMappings', 'AccessInterceptor', 'controller dispatch', 'JSON response']
            ),
            new DocEntry(
                id: 'authentication',
                title: 'Authentication',
                filePath: 'docs/authentication.md',
                category: 'core-framework',
                description: 'Authentication service contract and user identity.',
                keywords: ['BaseAuthenticationService', 'BaseUserService', 'HoistUser', 'IdentityService', 'impersonation']
            ),
            new DocEntry(
                id: 'authorization',
                title: 'Authorization',
                filePath: 'docs/authorization.md',
                category: 'core-framework',
                description: 'Role-based access control and controller security annotations.',
                keywords: ['BaseRoleService', 'DefaultRoleService', 'Role', 'RoleMember', 'AccessRequiresRole', 'AccessAll']
            ),

            // Core Features
            new DocEntry(
                id: 'configuration',
                title: 'Configuration',
                filePath: 'docs/configuration.md',
                category: 'core-features',
                description: 'Database-backed soft configuration with typed values.',
                keywords: ['AppConfig', 'ConfigService', 'clientVisible', 'pwd', 'encryption', 'xhConfigChanged']
            ),
            new DocEntry(
                id: 'preferences',
                title: 'Preferences',
                filePath: 'docs/preferences.md',
                category: 'core-features',
                description: 'User-specific settings and preference management.',
                keywords: ['Preference', 'UserPreference', 'PrefService', 'local']
            ),
            new DocEntry(
                id: 'clustering',
                title: 'Clustering',
                filePath: 'docs/clustering.md',
                category: 'core-features',
                description: 'Hazelcast-based multi-instance coordination and distributed data structures.',
                keywords: ['ClusterService', 'Cache', 'CachedValue', 'IMap', 'ReplicatedMap', 'Topic', 'primaryOnly']
            ),
            new DocEntry(
                id: 'activity-tracking',
                title: 'Activity Tracking',
                filePath: 'docs/activity-tracking.md',
                category: 'core-features',
                description: 'Usage and performance logging with email notifications.',
                keywords: ['TrackLog', 'TrackService', 'categories', 'elapsed', 'timing', 'client error', 'feedback']
            ),
            new DocEntry(
                id: 'json-handling',
                title: 'JSON Handling',
                filePath: 'docs/json-handling.md',
                category: 'core-features',
                description: 'Jackson-based JSON serialization and parsing.',
                keywords: ['JSONSerializer', 'JSONParser', 'JSONFormat', 'renderJSON', 'parseRequestJSON']
            ),

            // Infrastructure & Operations
            new DocEntry(
                id: 'monitoring',
                title: 'Monitoring',
                filePath: 'docs/monitoring.md',
                category: 'infrastructure',
                description: 'Application health monitoring with configurable checks and email alerting.',
                keywords: ['Monitor', 'MonitorResult', 'MonitoringService', 'MonitorDefinitionService', 'email alerts']
            ),
            new DocEntry(
                id: 'metrics',
                title: 'Metrics',
                filePath: 'docs/metrics.md',
                category: 'infrastructure',
                description: 'Micrometer-based observable metrics with Prometheus and OTLP export.',
                keywords: ['MetricsService', 'CompositeMeterRegistry', 'MonitorMetricsService', 'TrackMetricsService', 'Prometheus', 'OTLP', 'xhMetricsConfig']
            ),
            new DocEntry(
                id: 'websocket',
                title: 'WebSocket',
                filePath: 'docs/websocket.md',
                category: 'infrastructure',
                description: 'Cluster-aware server push to connected clients.',
                keywords: ['WebSocketService', 'HoistWebSocketHandler', 'HoistWebSocketChannel', 'channel']
            ),
            new DocEntry(
                id: 'http-client',
                title: 'HTTP Client',
                filePath: 'docs/http-client.md',
                category: 'infrastructure',
                description: 'HTTP client for external API calls and request proxying.',
                keywords: ['JSONClient', 'BaseProxyService', 'HttpUtils']
            ),
            new DocEntry(
                id: 'email',
                title: 'Email',
                filePath: 'docs/email.md',
                category: 'infrastructure',
                description: 'Email sending with config-driven filtering and overrides.',
                keywords: ['EmailService', 'xhEmailFilter', 'xhEmailOverride']
            ),
            new DocEntry(
                id: 'exception-handling',
                title: 'Exception Handling',
                filePath: 'docs/exception-handling.md',
                category: 'infrastructure',
                description: 'Exception hierarchy and error rendering.',
                keywords: ['HttpException', 'RoutineException', 'ExceptionHandler', 'HTTP status']
            ),
            new DocEntry(
                id: 'logging',
                title: 'Logging',
                filePath: 'docs/logging.md',
                category: 'infrastructure',
                description: 'Logging infrastructure with dynamic configuration.',
                keywords: ['LogSupport', 'logDebug', 'logInfo', 'logWarn', 'logError', 'LogLevelService', 'LogReaderService']
            ),

            // Application Development
            new DocEntry(
                id: 'application-structure',
                title: 'Application Structure',
                filePath: 'docs/application-structure.md',
                category: 'app-development',
                description: 'Standard Hoist application repository layout — server and client structure, build configuration, deployment.',
                keywords: ['build.gradle', 'gradle.properties', 'grails-app/init', 'client-app', 'Bootstrap.ts', 'AppModel', 'Docker', 'Nginx', 'Tomcat']
            ),

            // Grails Platform
            new DocEntry(
                id: 'gorm-domain-objects',
                title: 'GORM Domain Objects',
                filePath: 'docs/gorm-domain-objects.md',
                category: 'grails-platform',
                description: 'GORM domain classes, querying, transactions, caching, associations, and performance optimization.',
                keywords: ['Domain classes', 'Transactional', 'ReadOnly', 'second-level cache', 'N+1', 'fetch strategies', 'SQL logging']
            ),

            // Supporting Features
            new DocEntry(
                id: 'data-filtering',
                title: 'Data Filtering',
                filePath: 'docs/data-filtering.md',
                category: 'supporting',
                description: "Server-side filter system mirroring hoist-react's client-side filters.",
                keywords: ['Filter', 'FieldFilter', 'CompoundFilter', 'JSON roundtrip']
            ),
            new DocEntry(
                id: 'utilities',
                title: 'Utilities',
                filePath: 'docs/utilities.md',
                category: 'supporting',
                description: 'Timers, date/string utilities, and async helpers.',
                keywords: ['Timer', 'DateTimeUtils', 'StringUtils', 'Utils', 'InstanceConfigUtils', 'AsyncUtils']
            ),
            new DocEntry(
                id: 'jsonblob',
                title: 'JsonBlob',
                filePath: 'docs/jsonblob.md',
                category: 'supporting',
                description: 'Generic JSON storage backing ViewManager and other client state.',
                keywords: ['JsonBlob', 'JsonBlobService', 'token-based access']
            ),
            new DocEntry(
                id: 'ldap',
                title: 'LDAP',
                filePath: 'docs/ldap.md',
                category: 'supporting',
                description: 'LDAP / Active Directory integration for user and group lookups.',
                keywords: ['LdapService', 'LdapPerson', 'LdapGroup']
            ),
            new DocEntry(
                id: 'environment',
                title: 'Environment',
                filePath: 'docs/environment.md',
                category: 'supporting',
                description: 'Runtime environment detection and external configuration.',
                keywords: ['EnvironmentService', 'AppEnvironment', 'InstanceConfigUtils']
            ),
            new DocEntry(
                id: 'admin-endpoints',
                title: 'Admin Endpoints',
                filePath: 'docs/admin-endpoints.md',
                category: 'supporting',
                description: 'Admin console endpoints and supporting services.',
                keywords: ['XhController', 'admin controllers', 'AlertBannerService', 'ViewService', 'ServiceManagerService']
            ),

            // Build & Publishing
            new DocEntry(
                id: 'build-and-publish',
                title: 'Build & Publish',
                filePath: 'docs/build-and-publish.md',
                category: 'build',
                description: 'Gradle build, GitHub Actions CI, and Maven Central publishing.',
                keywords: ['GitHub Actions', 'deployRelease', 'deploySnapshot', 'Sonatype', 'GPG signing', 'nexus-publish-plugin', 'publishToSonatype']
            ),

            // Upgrade Notes
            new DocEntry(
                id: 'v36-upgrade-notes',
                title: 'v36 Upgrade Notes',
                filePath: 'docs/upgrade-notes/v36-upgrade-notes.md',
                category: 'upgrade',
                description: 'Cluster-aware WebSockets, new @AccessRequiresXXX annotations, @Access deprecated.',
                keywords: ['v36', 'upgrade', 'WebSocket', 'AccessRequiresRole', 'Access deprecated']
            ),
            new DocEntry(
                id: 'v35-upgrade-notes',
                title: 'v35 Upgrade Notes',
                filePath: 'docs/upgrade-notes/v35-upgrade-notes.md',
                category: 'upgrade',
                description: 'CacheEntry generic key type, TrackLog clientAppCode, POI 5.x.',
                keywords: ['v35', 'upgrade', 'CacheEntry', 'TrackLog', 'POI']
            ),
            new DocEntry(
                id: 'v34-upgrade-notes',
                title: 'v34 Upgrade Notes',
                filePath: 'docs/upgrade-notes/v34-upgrade-notes.md',
                category: 'upgrade',
                description: 'Grails 7, Gradle 8, Tomcat 10, Jakarta EE.',
                keywords: ['v34', 'upgrade', 'Grails 7', 'Gradle 8', 'Tomcat 10', 'Jakarta']
            ),

            // Doc index itself
            new DocEntry(
                id: 'doc-index',
                title: 'Documentation Index',
                filePath: 'docs/README.md',
                category: 'index',
                description: 'Primary catalog of all hoist-core documentation with quick reference tables.',
                keywords: ['index', 'catalog', 'quick reference']
            ),
        ]

        // Validate file existence
        def validated = inventory.findAll { entry ->
            if (contentSource.fileExists(entry.filePath)) {
                return true
            } else {
                McpLog.warn("Doc file not found, skipping: ${entry.filePath}")
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
        def content = contentSource.readFile(entry.filePath)
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
        String id, title, filePath, category, description
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

    /** Display order for categories. */
    static final CATEGORY_ORDER = [
        'core-framework', 'core-features', 'infrastructure', 'app-development',
        'grails-platform', 'supporting', 'build', 'upgrade', 'index'
    ]

    static final CATEGORY_LABELS = [
        'core-framework' : 'Core Framework',
        'core-features'  : 'Core Features',
        'infrastructure' : 'Infrastructure & Operations',
        'app-development': 'Application Development',
        'grails-platform': 'Grails Platform',
        'supporting'     : 'Supporting Features',
        'build'          : 'Build & Publishing',
        'upgrade'        : 'Upgrade Notes',
        'index'          : 'Index',
    ]
}
