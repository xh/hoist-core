/*
 * This file belongs to Hoist, an application development toolkit
 * developed by Extremely Heavy Industries (www.xh.io | info@xh.io)
 *
 * Copyright © 2021 Extremely Heavy Industries Inc.
 */

package io.xh.hoist.admin

import io.xh.hoist.BaseController
import io.xh.hoist.configuration.LogbackConfig
import groovy.io.FileType
import io.xh.hoist.security.Access

@Access(['HOIST_ADMIN'])
class LogViewerAdminController extends BaseController {

    def logArchiveService,
        logReaderService

    def listFiles() {
        def baseDir = new File(LogbackConfig.logRootPath),
            basePath = baseDir.toPath(),
            files = []

        baseDir.eachFileRecurse FileType.FILES, {
            def matches = it.name ==~ /.*\.log/
            if (matches) files << basePath.relativize(it.toPath())
        }

        def ret = files.collect { [filename: it.toString()] }
        renderJSON(success:true, files:ret)
    }

    def getFile(String filename, Integer startLine, Integer maxLines, String pattern) {
        // Catch any exceptions and render clean failure - the admin client auto-polls for log file
        // updates, and we don't want to spam the logs with a repeated stacktrace.
        try {
            def content = logReaderService.readFile(filename, startLine, maxLines, pattern)
            renderJSON(success: true, filename: filename, content: content)
        } catch (Exception e) {
            renderJSON(success: false, filename: filename, content: [], exception: e.message)
        }
    }

    def download(String filename) {
        def file = logReaderService.get(filename)
        render(
                file: file,
                fileName: filename,
                contentType: 'application/octet-stream'
        )
    }

    /**
     * Deletes one or more files from the log directory.
     * @param filenames - (required)
     */
    def deleteFiles() {
        def filenames = params.list('filenames')

        filenames.each {String filename ->
            def fileToDelete = new File(LogbackConfig.logRootPath, filename),
                fileDeleted = fileToDelete.delete()

            if (!fileDeleted) {
                logWarn("Failed to delete log: '$filename'.  User may not have permissions.")
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
