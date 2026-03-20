package io.xh.hoist.mcp

import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.McpSyncServer
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities
import io.xh.hoist.mcp.data.DocRegistry
import io.xh.hoist.mcp.data.GroovyRegistry
import io.xh.hoist.mcp.tools.DocTools
import io.xh.hoist.mcp.tools.GroovyTools
import io.xh.hoist.mcp.util.McpLog

/**
 * Entry point for the hoist-core MCP server.
 *
 * Provides Claude Code with structured access to hoist-core documentation and
 * Groovy/Java symbol information via the Model Context Protocol.
 */
class HoistCoreMcpServer {

    static void main(String[] args) {
        try {
            def config = parseArgs(args)
            def contentSource = createContentSource(config)
            startServer(contentSource)
        } catch (Exception e) {
            McpLog.error("Failed to start: ${e.message}")
            System.exit(1)
        }
    }

    private static Map parseArgs(String[] args) {
        def config = [source: 'local', root: null]
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case '--source':
                    if (i + 1 < args.length) config.source = args[++i]
                    break
                case '--root':
                    if (i + 1 < args.length) config.root = args[++i]
                    break
            }
        }
        return config
    }

    private static ContentSource createContentSource(Map config) {
        String source = config.source
        if (source == 'local') {
            String root = config.root ?: resolveRepoRoot()
            return new LocalContentSource(root)
        } else if (source.startsWith('github:')) {
            String ref = source.substring('github:'.length())
            return new GitHubContentSource(ref)
        } else {
            throw new IllegalArgumentException("Unknown source: $source")
        }
    }

    private static void startServer(ContentSource contentSource) {
        def docRegistry = new DocRegistry(contentSource)
        def groovyRegistry = new GroovyRegistry(contentSource)

        def toolSpecs = DocTools.create(docRegistry) + GroovyTools.create(groovyRegistry)

        def transportProvider = new StdioServerTransportProvider(McpJsonDefaults.getMapper())

        McpSyncServer server = McpServer.sync(transportProvider)
            .serverInfo('hoist-core', this.class.package.implementationVersion ?: 'dev')
            .instructions('Hoist Core MCP server — provides access to hoist-core framework documentation and Groovy/Java symbol information.')
            .capabilities(ServerCapabilities.builder()
                .tools(true)
                .build()
            )
            .tools(toolSpecs)
            .build()

        // Warm the Groovy symbol index in the background so the first tool call doesn't
        // pay the full initialization cost.
        groovyRegistry.beginInitialization()

        McpLog.info('Server started, awaiting MCP client connection via stdio')
    }

    /**
     * Resolve the hoist-core repo root by walking up from the JAR location.
     * Falls back to the current working directory.
     */
    private static String resolveRepoRoot() {
        // Try to find repo root from JAR location (mcp/build/libs/mcp-all.jar -> repo root)
        def jarPath = HoistCoreMcpServer.class.protectionDomain?.codeSource?.location?.toURI()
        if (jarPath) {
            def jarFile = new File(jarPath)
            // JAR is at mcp/build/libs/mcp-all.jar, repo root is 3 levels up
            def candidate = jarFile.parentFile?.parentFile?.parentFile?.parentFile
            if (candidate && new File(candidate, 'docs/README.md').exists()) {
                return candidate.canonicalPath
            }
        }

        // Fall back to current working directory
        def cwd = new File('.').canonicalFile
        if (new File(cwd, 'docs/README.md').exists()) {
            return cwd.canonicalPath
        }

        throw new IllegalStateException(
            'Cannot determine hoist-core repo root. Use --root <path> to specify.'
        )
    }
}
