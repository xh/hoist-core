/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2019 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.log.LogUtils
import groovy.io.FileType
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN'])
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
        try {
            def file = new File(LogUtils.logRootPath, filename),
                content = file.readLines(),
                size = content.size(),
                tail = !startLine || startLine<0,
                startIdx = tail ? size-1 : startLine-1 ,
                lastIdx = tail ? 0: size-1,
                idxIncrementer = tail ? -1 : 1,
                idxComparator = tail ? {return it >= lastIdx} : {return it <= lastIdx},
                lineCount = 0,
                ret = []

            maxLines = maxLines ?: 10000

            for (def i = startIdx; idxComparator(i) && lineCount < maxLines; i+=idxIncrementer) {
                def line = content[i]
                if (!pattern || line.toLowerCase() =~ pattern.toLowerCase()) {
                    lineCount++
                    ret << [i + 1, line]
                }
            }

            renderJSON(success: file.exists(), filename: filename, content: ret)
        } catch (Exception e) {
            renderJSON(success: false, filename: filename, content: [], exception: e.message)
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
