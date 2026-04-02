package io.xh.hoist.mcp

import io.xh.hoist.mcp.util.McpLog

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * ContentSource backed by a downloaded GitHub tarball, cached locally.
 * Downloads the hoist-core repo at a specific ref (tag, branch, or SHA)
 * and extracts it to ~/.cache/hoist-core-mcp/<ref>/.
 *
 * For branch refs (e.g. "develop"), the cache is invalidated after {@link #MAX_CACHE_AGE_MS}
 * to ensure documentation stays in sync with the latest source. Tag and SHA refs are
 * considered immutable and cached indefinitely.
 */
class GitHubContentSource implements ContentSource {

    private static final String GITHUB_API = 'https://api.github.com/repos/xh/hoist-core/tarball'

    /** Max age for branch caches before re-download. Tags/SHAs are cached indefinitely. */
    private static final long MAX_CACHE_AGE_MS = 24 * 60 * 60 * 1000  // 24 hours

    final String ref
    final File cacheDir
    final File root

    GitHubContentSource(String ref) {
        this.ref = ref
        this.cacheDir = new File(System.getProperty('user.home'), ".cache/hoist-core-mcp/${ref}")
        this.root = ensureDownloaded()
        McpLog.info("Using GitHub content source: ${ref} -> ${root}")
    }

    @Override
    String readFile(String relativePath) {
        def file = resolveFile(relativePath)
        return file?.exists() ? file.text : null
    }

    @Override
    boolean fileExists(String relativePath) {
        return resolveFile(relativePath)?.exists() ?: false
    }

    @Override
    List<String> findFiles(String baseDir, String extension) {
        def dir = new File(root, baseDir)
        if (!dir.isDirectory()) return []

        def results = []
        dir.eachFileRecurse { file ->
            if (file.isFile() && file.name.endsWith(extension)) {
                results << root.toPath().relativize(file.toPath()).toString()
            }
        }
        return results
    }

    @Override
    String getRootDescription() {
        return "github:${ref} (${root.absolutePath})"
    }

    private File resolveFile(String relativePath) {
        if (relativePath.contains('..')) return null
        def file = new File(root, relativePath).canonicalFile
        if (!file.absolutePath.startsWith(root.absolutePath)) return null
        return file
    }

    /**
     * Download and extract the tarball if not already cached (or if the cache is stale).
     * Returns the extracted directory root (the single top-level dir in the tarball).
     */
    private File ensureDownloaded() {
        if (cacheDir.isDirectory()) {
            if (isCacheStale()) {
                McpLog.info("Cache for '${ref}' is stale (>${MAX_CACHE_AGE_MS / 3600000}h old), re-downloading...")
                cacheDir.deleteDir()
            } else {
                def extracted = findExtractedRoot()
                if (extracted) return extracted
            }
        }

        McpLog.info("Downloading hoist-core archive for ref '${ref}'...")
        cacheDir.mkdirs()

        def url = "${GITHUB_API}/${ref}"
        def client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        def request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header('Accept', 'application/vnd.github+json')
            .GET()
            .build()

        def response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            throw new RuntimeException("GitHub API returned ${response.statusCode()} for ${url}")
        }

        // Extract the tarball
        def gzipStream = new GZIPInputStream(response.body())
        def tarStream = new TarArchiveInputStream(gzipStream)

        def entry = tarStream.nextEntry
        while (entry != null) {
            def targetPath = new File(cacheDir, entry.name)
            if (entry.isDirectory()) {
                targetPath.mkdirs()
            } else {
                targetPath.parentFile.mkdirs()
                targetPath.bytes = tarStream.readAllBytes()
            }
            entry = tarStream.nextEntry
        }
        tarStream.close()

        McpLog.info("Downloaded and extracted to ${cacheDir}")

        def extracted = findExtractedRoot()
        if (!extracted) {
            throw new RuntimeException("Failed to find extracted content in ${cacheDir}")
        }
        return extracted
    }

    /** GitHub tarballs contain a single top-level directory (e.g. xh-hoist-core-abc1234). */
    private File findExtractedRoot() {
        def children = cacheDir.listFiles()?.findAll { it.isDirectory() }
        if (children?.size() == 1) {
            def candidate = children[0]
            if (new File(candidate, 'docs/README.md').exists() ||
                new File(candidate, 'build.gradle').exists()) {
                return candidate
            }
        }
        return null
    }

    /**
     * Check if the cache is stale. Branch refs (e.g. "develop") are invalidated after
     * MAX_CACHE_AGE_MS. Tag refs (e.g. "v37.0.0") and SHA refs are immutable and never stale.
     */
    private boolean isCacheStale() {
        if (isImmutableRef()) return false
        def age = System.currentTimeMillis() - cacheDir.lastModified()
        return age > MAX_CACHE_AGE_MS
    }

    /** Tags (v1.2.3) and full SHAs (40 hex chars) point to immutable content. */
    private boolean isImmutableRef() {
        return ref.startsWith('v') || ref.matches('[0-9a-f]{40}')
    }
}
