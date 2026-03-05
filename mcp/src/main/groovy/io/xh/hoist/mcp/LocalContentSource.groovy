package io.xh.hoist.mcp

import io.xh.hoist.mcp.util.McpLog

/**
 * ContentSource backed by a local filesystem checkout of hoist-core.
 */
class LocalContentSource implements ContentSource {

    final File root

    LocalContentSource(String rootPath) {
        this.root = new File(rootPath).canonicalFile
        if (!root.isDirectory()) {
            throw new IllegalArgumentException("Root directory does not exist: $root")
        }
        McpLog.info("Using local content source: $root")
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
        return root.absolutePath
    }

    private File resolveFile(String relativePath) {
        if (relativePath.contains('..')) return null
        def file = new File(root, relativePath).canonicalFile
        // Verify the resolved file is within the root
        if (!file.absolutePath.startsWith(root.absolutePath)) return null
        return file
    }
}
