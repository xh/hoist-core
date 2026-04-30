package io.xh.hoist.mcp.cli

import io.xh.hoist.mcp.BundledContentSource
import io.xh.hoist.mcp.ContentSource
import io.xh.hoist.mcp.GitHubContentSource
import io.xh.hoist.mcp.LocalContentSource
import io.xh.hoist.mcp.data.DocRegistry
import io.xh.hoist.mcp.data.GroovyRegistry

/**
 * Lazily-built shared state for the CLI: ContentSource, DocRegistry, and
 * GroovyRegistry. Doc commands skip GroovyRegistry initialization (which
 * parses ~150 Groovy files on the foreground thread) so they stay sub-second.
 *
 * Source selection mirrors {@code HoistCoreMcpServer}:
 *   --source bundled (default)  → JAR-embedded content
 *   --source local --root P      → local checkout
 *   --source github:REF          → downloaded GitHub tarball
 */
class CliContext {

    final String sourceSpec
    final String rootPath

    private ContentSource contentSource
    private DocRegistry docRegistry
    private GroovyRegistry groovyRegistry

    CliContext(String sourceSpec, String rootPath) {
        this.sourceSpec = sourceSpec ?: 'bundled'
        this.rootPath = rootPath
    }

    ContentSource getContentSource() {
        if (contentSource == null) contentSource = buildContentSource()
        return contentSource
    }

    DocRegistry getDocRegistry() {
        if (docRegistry == null) docRegistry = new DocRegistry(getContentSource())
        return docRegistry
    }

    GroovyRegistry getGroovyRegistry() {
        if (groovyRegistry == null) {
            groovyRegistry = new GroovyRegistry(getContentSource())
            // CLI is short-lived: build the index synchronously on this thread.
            groovyRegistry.beginInitialization()
            groovyRegistry.ensureInitialized()
        }
        return groovyRegistry
    }

    private ContentSource buildContentSource() {
        if (sourceSpec == 'bundled') return new BundledContentSource()
        if (sourceSpec == 'local') {
            String root = rootPath ?: resolveLocalRoot()
            return new LocalContentSource(root)
        }
        if (sourceSpec.startsWith('github:')) {
            return new GitHubContentSource(sourceSpec.substring('github:'.length()))
        }
        throw new IllegalArgumentException("Unknown --source value: ${sourceSpec}")
    }

    /** Walk up from CWD looking for docs/doc-registry.json. */
    private static String resolveLocalRoot() {
        def cwd = new File('.').canonicalFile
        def candidate = cwd
        while (candidate != null) {
            if (new File(candidate, 'docs/doc-registry.json').isFile()) {
                return candidate.canonicalPath
            }
            candidate = candidate.parentFile
        }
        throw new IllegalStateException(
            'Cannot find a hoist-core repo root from CWD. Pass --root <path> explicitly.'
        )
    }
}
