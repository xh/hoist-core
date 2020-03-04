package io.xh.hoist.log

import io.xh.hoist.BaseService
import org.apache.commons.io.input.ReversedLinesFileReader

class LogReaderService extends BaseService {

    def records = [:]

    /**
     * Fetch the (selected) contents of a log file for viewing in the admin console.
     * @param file - (required) File to be read
     * @param startLine - (optional) line number of file to start at; if null or zero or negative, will return tail of file
     * @param maxLines - (optional) number of lines to return
     * @param pattern - (optional) only lines matching pattern will be returned
     * @return - Multi-dimensional array of [linenumber, text] for the requested lines
     */
    synchronized readFile(File file, Integer startLine, Integer maxLines, String pattern, Integer maxSearch = 1000000) {
        def tail = !startLine || startLine < 0,
            ret = [],
            lineNumber,
            lineIncrement,
            reader,
            numSearched = 0

        if(!file.exists()) {
            records.remove(file.getAbsolutePath())
            throw new FileNotFoundException()
        }

        try {
            if (tail) {
                lineNumber = getLength(file)
                reader = new ReversedLinesFileReader(file)
                lineIncrement = -1
            } else {
                lineNumber = startLine
                reader = new BufferedReader(new FileReader(file))
                (1..<lineNumber).each { reader.readLine() }
                lineIncrement = 1
            }

            for (def line = reader.readLine();
                 line != null && ret.size() < maxLines;
                 line = reader.readLine()) {
                numSearched++
                if(numSearched > maxSearch) {
                    ret << [lineNumber, 'Search aborted due to ']
                }
                if (!pattern || line.toLowerCase() =~ pattern.toLowerCase()) {
                    ret << [lineNumber, line]
                    lineNumber += lineIncrement
                }
            }

            return ret
        } finally {
            if(reader) {
                reader.close()
            }
        }
    }

    long getLength(File file) {
        def path = file.getAbsolutePath()
        if(!records.containsKey(path)) {
            records[path] = new LengthRecord(file)
        }
        return records[path].getLength()
    }

    @Override
    void destroy() {
        records.values().each {it.destroy()}
    }

    class LengthRecord {
        File file
        BufferedReader reader
        long length = 0
        long bytes = 0;

        LengthRecord(File file) {
            this.file = file
            reader = new BufferedReader(new FileReader(file))
        }

        long getLength() {
            update()
            return length
        }

        long update() {
            if(file.size() < bytes) {
                log.info('Log file was deleted or truncated, re-reading...')
                length = 0;
                bytes = 0
                reader.close()
                reader = new BufferedReader(new FileReader(file))
            }

            bytes = file.size()

            while(reader.readLine() != null) length++
            return length
        }

        void destroy() {
            reader.close()
        }
    }
}
