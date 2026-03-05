package io.xh.hoist.mcp

/**
 * Abstraction for reading hoist-core content (docs + source files).
 * Implementations handle local filesystem vs. GitHub archive access.
 */
interface ContentSource {

    /** Read file content as a string. Returns null if file does not exist. */
    String readFile(String relativePath)

    /** Check if a file exists at the given relative path. */
    boolean fileExists(String relativePath)

    /** Find all files under a base directory with a given extension. */
    List<String> findFiles(String baseDir, String extension)

    /** Root path description for logging/display. */
    String getRootDescription()
}
