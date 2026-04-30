package io.xh.hoist.mcp

import io.xh.hoist.mcp.util.McpLog

import java.util.jar.JarFile

/**
 * ContentSource backed by hoist-core docs and source files bundled into the
 * fat JAR at build time under {@code hoist-core-content/}.
 *
 * This makes the JAR self-contained: an app developer pulling
 * {@code io.xh:hoist-core-mcp:<version>:all} from Maven Central / their
 * internal Artifactory mirror has everything the CLI needs without further
 * downloads. Content is version-locked to the JAR.
 */
class BundledContentSource implements ContentSource {

    private static final String CONTENT_PREFIX = 'hoist-core-content/'

    private final ClassLoader cl = BundledContentSource.classLoader
    private final List<String> allPaths

    BundledContentSource() {
        this.allPaths = scanContent()
        if (allPaths.isEmpty()) {
            throw new IllegalStateException(
                'No bundled content found on the classpath under hoist-core-content/. ' +
                'The JAR may have been built without the bundled-content task, or this is ' +
                'running from an unbundled context. Use --source local --root <repoRoot> instead.'
            )
        }
        McpLog.info("Using bundled content source: ${allPaths.size()} files")
    }

    @Override
    String readFile(String relativePath) {
        if (relativePath?.contains('..')) return null
        def stream = cl.getResourceAsStream(CONTENT_PREFIX + relativePath)
        return stream?.getText('UTF-8')
    }

    @Override
    boolean fileExists(String relativePath) {
        if (relativePath?.contains('..')) return false
        return cl.getResource(CONTENT_PREFIX + relativePath) != null
    }

    @Override
    List<String> findFiles(String baseDir, String extension) {
        def base = baseDir.endsWith('/') ? baseDir : baseDir + '/'
        return allPaths.findAll { it.startsWith(base) && it.endsWith(extension) }
    }

    @Override
    String getRootDescription() {
        return "bundled JAR resources (${CONTENT_PREFIX})"
    }

    //------------------------------------------------------------------
    // Scan the classpath for bundled file paths once at construction.
    //------------------------------------------------------------------
    private List<String> scanContent() {
        def location = BundledContentSource.protectionDomain?.codeSource?.location
        if (!location) return []

        File file
        try {
            file = new File(location.toURI())
        } catch (Exception ignored) {
            return []
        }

        if (file.isFile() && (file.name.endsWith('.jar') || file.name.endsWith('.zip'))) {
            return scanJarFile(file)
        }
        if (file.isDirectory()) {
            return scanDirectory(new File(file, 'hoist-core-content'))
        }
        return []
    }

    private static List<String> scanJarFile(File jarFile) {
        def result = []
        new JarFile(jarFile).withCloseable { jar ->
            for (entry in jar.entries()) {
                if (!entry.isDirectory() && entry.name.startsWith(CONTENT_PREFIX)) {
                    result << entry.name.substring(CONTENT_PREFIX.length())
                }
            }
        }
        return result.sort()
    }

    private static List<String> scanDirectory(File baseDir) {
        if (!baseDir?.isDirectory()) return []
        def result = []
        baseDir.eachFileRecurse { f ->
            if (f.isFile()) {
                result << baseDir.toPath().relativize(f.toPath()).toString().replace('\\', '/')
            }
        }
        return result.sort()
    }
}
