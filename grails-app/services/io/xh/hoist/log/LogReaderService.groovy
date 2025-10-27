/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2025 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.log

import groovy.io.FileType
import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.LogbackConfig
import io.xh.hoist.exception.RoutineRuntimeException
import org.apache.commons.io.input.ReversedLinesFileReader
import java.nio.file.Paths
import static io.xh.hoist.LogbackConfig.getLogRootPath

import static java.lang.System.currentTimeMillis
import java.util.regex.Pattern

@CompileStatic
class LogReaderService extends BaseService {

    ConfigService configService


    /**
     * Return meta data about available log files for client.
     */
    Map listFiles() {
        def logRootPath = logDir.absolutePath,
            files = availableFiles.collect {
                [
                    filename    : it.key,
                    size        : it.value.size(),
                    lastModified: it.value.lastModified()
                ]
            }
        return [files: files, logRootPath: logRootPath]
    }

    /**
     * Fetch the (selected) contents of a log file for viewing in the admin console.
     * @param filename - (required) Filename to be read
     * @param startLine - (optional) line number of file to start at; if null or zero or negative, will return tail of file
     * @param maxLines - (optional) number of lines to return
     * @param pattern - (optional) only lines matching pattern will be returned
     */
    Map getFile(String filename, Integer startLine, Integer maxLines, String pattern, Boolean caseSensitive) {
        if (!availableFiles[filename]) throwUnavailable(filename)
        def content = withDebug([
            _msg         : 'Reading log file',
            _filename    : filename,
            startLine    : startLine,
            maxLines     : maxLines,
            pattern      : pattern,
            caseSensitive: caseSensitive
        ]) {
            doRead(filename, startLine, maxLines, pattern, caseSensitive)
        }

        return [filename: filename, content: content]
    }


    /**
     * Fetch the raw contents of a log file for direct download.
     */
    File get(String filename) {
        if (!availableFiles[filename]) throwUnavailable(filename)
        def ret = new File(logRootPath, filename)
        if (!ret.exists()) throwUnavailable(filename)
        return ret
    }

    File getLogDir() {
        return new File(Paths.get(logRootPath).toString())
    }

    void deleteFiles(List<String> filenames) {
        def available = availableFiles

        filenames.each { filename ->
            def toDelete = available[filename]
            if (!toDelete) throwUnavailable(filename)

            def deleted = toDelete.delete()
            if (!deleted) logWarn("Failed to delete log: '$filename'.")
        }
    }


    //------------------------
    // Implementation
    //------------------------
    private List doRead(String filename, Integer startLine, Integer maxLines, String pattern, Boolean caseSensitive) {
        maxLines = maxLines ?: 10000

        def tail = !startLine || startLine <= 0,
            ret = new ArrayList(maxLines),
            file = new File(logRootPath, filename)

        if (!file.exists()) throwUnavailable(filename)

        long maxEndTime = currentTimeMillis() + configService.getLong('xhLogSearchTimeoutMs', 5000)

        def compiledPattern = caseSensitive ? Pattern.compile(pattern) : Pattern.compile(pattern, Pattern.CASE_INSENSITIVE)

        Closeable closeable
        try {
            if (tail) {
                ReversedLinesFileReader reader = closeable = new ReversedLinesFileReader(file)

                long lineNumber = getFileLength(file, maxEndTime)
                for (String line = reader.readLine(); line != null && ret.size() < maxLines; line = reader.readLine()) {
                    throwOnTimeout(maxEndTime)
                    if (!pattern || line =~ compiledPattern) {
                        ret << [lineNumber, line]
                    }
                    lineNumber--
                }

            } else {
                BufferedReader reader = closeable = new BufferedReader(new FileReader(file))

                // Skip lines as needed
                for (def i = 1; i < startLine; i++) {
                    def throwAway = reader.readLine()
                    if (throwAway == null) return []
                }

                long lineNumber = startLine
                for (String line = reader.readLine(); line != null && ret.size() < maxLines; line = reader.readLine()) {
                    throwOnTimeout(maxEndTime)
                    if (!pattern || line.toLowerCase() =~ pattern.toLowerCase()) {
                        ret << [lineNumber, line]
                    }
                    lineNumber++
                }
            }

            return ret

        } finally {
            if (closeable) closeable.close()
        }
    }

    private long getFileLength(File file, long maxEndTime) {
        BufferedReader reader
        try {
            reader = new BufferedReader(new FileReader(file))
            long ret = 0
            while (reader.readLine() != null) {
                throwOnTimeout(maxEndTime)
                ret++
            }
            return ret
        } finally {
            if (reader) reader.close()
        }
    }

    private void throwOnTimeout(long maxEndTime) {
        if (currentTimeMillis() > maxEndTime) {
            throw new RoutineRuntimeException('Query took too long. Log search aborted.')
        }
    }

    private Map<String, File> getAvailableFiles() {
        def baseDir = new File(LogbackConfig.logRootPath),
            basePath = baseDir.toPath()

        List<File> files = []
        baseDir.eachFileRecurse(FileType.FILES) {
            def matches = it.name ==~ /.*\.log/
            if (matches) files << it
        }

        files.collectEntries { File f ->
            [basePath.relativize(f.toPath()).toString(), f]
        }
    }

    private void throwUnavailable(String filename) {
        throw new RoutineRuntimeException("Filename not valid or available: $filename")
    }


    Map getAdminStats() {[
        config: configForAdminStats('xhEnableLogViewer', 'xhLogSearchTimeoutMs')
    ]}
}
