package io.xh.hoist.log

import groovy.transform.CompileStatic
import io.xh.hoist.BaseService
import io.xh.hoist.config.ConfigService
import io.xh.hoist.exception.RoutineRuntimeException
import org.apache.commons.io.input.ReversedLinesFileReader
import static java.lang.System.currentTimeMillis

@CompileStatic
class LogReaderService extends BaseService {

    ConfigService configService

    /**
     * Fetch the (selected) contents of a log file for viewing in the admin console.
     * @param filename - (required) Filename to be read
     * @param startLine - (optional) line number of file to start at; if null or zero or negative, will return tail of file
     * @param maxLines - (optional) number of lines to return
     * @param pattern - (optional) only lines matching pattern will be returned
     * @return - List of elements of the form [linenumber, text] for the requested lines
     */
    List readFile(String filename, Integer startLine, Integer maxLines, String pattern) {
        if (!configService.getBool('xhEnableLogViewer')) {
            throw new RuntimeException("Log Viewer disabled. See 'xhEnableLogViewer' config.")
        }

        return (List) withDebug('Reading log file ' + filename + '|' + startLine + '|' + maxLines + '|' + pattern) {
            doRead(filename, startLine, maxLines, pattern)
        }
    }

    //------------------------
    // Implementation
    //------------------------
    private List doRead(String filename, Integer startLine, Integer maxLines, String pattern) {
        maxLines = maxLines ?: 10000

        def tail = !startLine || startLine <= 0,
            ret = new ArrayList(maxLines),
            file = new File(LogUtils.logRootPath, filename)

        if (!file.exists()) throw new FileNotFoundException()

        long maxEndTime = currentTimeMillis() + configService.getLong('xhLogSearchTimeoutMs', 5000)

        Closeable closeable
        try {
            if (tail) {
                ReversedLinesFileReader reader = closeable = new ReversedLinesFileReader(file)

                long lineNumber = getFileLength(file, maxEndTime)
                for (String line = reader.readLine(); line != null && ret.size() < maxLines; line = reader.readLine()) {
                    throwOnTimeout(maxEndTime)
                    if (!pattern || line.toLowerCase() =~ pattern.toLowerCase()) {
                        ret << [lineNumber, line]
                    }
                    lineNumber--
                }

            } else {
                BufferedReader reader = closeable = new BufferedReader(new FileReader(file))

                // Skip lines as needed
                for (def i = 1; i < startLine; i++) {
                    def throwAway = reader.readLine();
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
            long ret = 0;
            while (reader.readLine() != null) {
                throwOnTimeout(maxEndTime)
                ret++;
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
}

