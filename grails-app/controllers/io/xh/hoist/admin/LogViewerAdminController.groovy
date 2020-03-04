/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2020 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import groovy.util.logging.Slf4j
import io.xh.hoist.BaseController
import io.xh.hoist.log.LogUtils
import groovy.io.FileType
import io.xh.hoist.security.Access
import org.apache.commons.io.input.ReversedLinesFileReader

@Access(['HOIST_ADMIN'])
@Slf4j
class LogViewerAdminController extends BaseController {

    def logArchiveService

    def listFiles() {
        def baseDir = new File(LogUtils.logRootPath),
            basePath = baseDir.toPath(),
            files = []

        baseDir.eachFileRecurse FileType.FILES, {
            def matches = it.name ==~ /.*\.log/
            if (matches) files << basePath.relativize(it.toPath())
        }

        def ret = files.collect { [filename: it.toString()] }
        renderJSON(success:true, files:ret)
    }

    /**
     * Fetch the (selected) contents of a log file for viewing in the admin console.
     * @param filename - (required) path to file from log root dir
     * @param startLine - (optional) line number of file to start at; if null or zero or negative, will return tail of file
     * @param maxLines - (optional) number of lines to return
     * @param pattern - (optional) only lines matching pattern will be returned
     * @return - JSON with content property containing multi-dim array of [lineNumber,text] for lines in file
     */
    def getFile(String filename, Integer startLine, Integer maxLines, String pattern) {
        // Catch any exceptions and render clean failure - the admin client auto-polls for log file
        // updates, and we don't want to spam the logs with a repeated stacktrace.
        long t1 = System.nanoTime()
        def reader, counter
        try {
            def file = new File(LogUtils.logRootPath, filename),
                tail = !startLine || startLine < 0,
                ret = [],
                lineNumber = 0,
                lineIncrement

            if (tail) {
                counter = new BufferedReader(new FileReader(file))
                // BufferedReader.lines() is a stream; we don't read the whole file into memory at once
                lineNumber = counter.lines().count()
                counter.close()

                reader = new ReversedLinesFileReader(file)
                lineIncrement = -1
            } else {
                lineNumber = startLine
                reader = new BufferedReader(new FileReader(file))
                reader.skip(lineNumber - 1)
                lineIncrement = 1
            }

            for(def line = reader.readLine(); line != null && ret.size() < maxLines; line = reader.readLine()) {
                if (!pattern || line.toLowerCase() =~ pattern.toLowerCase()) {
                    ret << [lineNumber, line]
                    lineNumber += lineIncrement
                }
            }

            renderJSON(success: file.exists(), filename: filename, content: ret)
        } catch (Exception e) {
            renderJSON(success: false, filename: filename, content: [], exception: e.message)
        } finally {
            if(reader) reader.close()
            if(counter) counter.close()
            long t2 = System.nanoTime()
            log.info('fetching logs took ' + ((t2 - t1) / 1e6) + ' ms')
        }
    }


    /**
     * Deletes one or more files from the log directory.
     * @param filenames - (required)
     */
    def deleteFiles() {
        def filenames = params.list('filenames')

        filenames.each {String filename ->
            def fileToDelete = new File(LogUtils.logRootPath, filename),
                fileDeleted = fileToDelete.delete()

            if (!fileDeleted) {
                log.warn("Failed to delete log: '$filename'.  User may not have permissions.")
            }
        }

        renderJSON(success:true)
    }

    /**
     * Run log archiving process immediately.
     * @param daysThreshold - (optional) min age in days of files to archive - null to use configured default.
     */
    def archiveLogs(Integer daysThreshold) {
        def ret = logArchiveService.archiveLogs(daysThreshold)
        renderJSON([archived: ret])
    }

}
